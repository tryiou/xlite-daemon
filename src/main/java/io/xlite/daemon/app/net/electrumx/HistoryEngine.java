package io.xlite.daemon.app.net.electrumx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.xlite.daemon.app.net.electrumx.ElectrumApi.Call;
import io.xlite.daemon.app.util.AddressBalance;
import io.xlite.daemon.app.util.history.Transaction;

import org.bitcoinj.core.NetworkParameters;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * The wallet's history, kept in memory and updated from the native subscription stream.
 *
 * <p>The indexer is the source of chain truth: which scripts changed and at which height. This
 * engine owns everything that is a wallet question: the address set, script ownership, row
 * classification, fees, confirmations, the immutable transaction cache and the snapshot served to
 * {@code listtransactions}.
 *
 * <p>Work runs on one worker thread per coin. Notifications only enqueue a script hash, so a burst
 * of changed addresses cannot run concurrently against the indexer; each hash is refreshed once and
 * the merged snapshot is republished. A transport failure leaves the last snapshot in place and the
 * next reconnect reconciles every subscription by status before serving again.
 */
public final class HistoryEngine implements AutoCloseable, ElectrumClient.Listener {

    /** Everything the engine needs from the coin, and everything it gives back. */
    public interface Sink {
        List<AddressBalance> addresses();

        void publish(List<Transaction> rows);

        void onTip(int height);

        /**
         * Replaces one address's unspent set with the provider's answer.
         *
         * @param address the wallet address the rows belong to
         * @param rows {@code blockchain.scripthash.listunspent} entries, possibly empty
         */
        void applyUnspents(String address, JsonArray rows);

        /**
         * The session is up and the first reconcile completed.
         *
         * <p>Provider-dependent startup work that must not run before the chain is reachable (a
         * wallet's address-count discovery) hangs off this callback, so it happens exactly when a
         * coin becomes usable — including one the backend starts serving minutes after launch.
         */
        default void onReady() {}
    }

    /**
     * Readiness, size and completeness, for the daemon's history status endpoint.
     *
     * <p>{@code ready} means the first reconcile finished; {@code complete} means every address's
     * history was read. They differ when the indexer refuses an address — a permanent condition for
     * that address, not a slow sync — and {@code failed} names each one so an operator can see why
     * a history is short instead of guessing.
     */
    public record Status(
            boolean ready,
            boolean complete,
            int addresses,
            int rows,
            int tip,
            List<Failure> failed) {
        public Status {
            failed = List.copyOf(failed);
        }
    }

    /** One address whose history the indexer refused, and what it said. */
    public record Failure(String address, String reason) {}

    /**
     * One item's answer from a batch: its value, or the error that stood in for it.
     *
     * <p>A JSON-RPC batch answers per item, so a refused address must not be allowed to speak for
     * its neighbours. Keeping each item's own outcome is what makes that possible without
     * re-asking.
     */
    private record Reply(JsonElement value, Throwable error) {
        private static Reply of(JsonElement value) {
            return new Reply(value, null);
        }

        private boolean ok() {
            return error == null;
        }

        /** A refusal is the indexer's own answer, not a transport failure. */
        private boolean refused() {
            return error instanceof ElectrumException;
        }
    }

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final int FETCH_BATCH = 64;
    private static final int BATCH_SIZE = 100;
    private static final int RETRY_SECONDS = 5;

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private final String name;
    private final ElectrumApi api;
    private final Sink sink;
    private final HistoryMaterialiser materialiser;
    private final ExecutorService worker;
    private final ScheduledExecutorService retry;
    private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private final Map<String, AddressState> states = new LinkedHashMap<>();
    private final Map<String, AddressState> byScripthash = new ConcurrentHashMap<>();
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final Map<String, JsonObject> transactions = new HashMap<>();
    private final AtomicBoolean retryScheduled = new AtomicBoolean();

    private volatile boolean running;
    private volatile boolean ready;
    private volatile int tip;
    private volatile int publishedRows;

    public HistoryEngine(
            String name,
            NetworkParameters parameters,
            ElectrumApi api,
            Sink sink,
            Function<String, String> displayAddress) {
        this.name = name;
        this.api = api;
        this.sink = sink;
        this.materialiser = new HistoryMaterialiser(parameters, transactions::get, displayAddress);
        this.worker =
                Executors.newSingleThreadExecutor(
                        runnable -> daemonThread(runnable, "history-" + name));
        this.retry =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> daemonThread(runnable, "history-retry-" + name));
    }

    public void start() {
        running = true;
        api.setListener(this);
        worker.execute(this::loop);
        api.start();
        // The session may already be connected by the time the engine attaches
        // (address discovery uses it first), in which case onConnected will not
        // fire again. Reconcile once here to cover that order.
        if (api.isConnected()) {
            submit(this::reconcileAll);
        }
    }

    public boolean isReady() {
        return ready;
    }

    /** Pick up addresses generated, imported or reloaded by the coin. */
    public void syncAddresses() {
        submit(this::reconcileNewAddresses);
    }

    public Status status() {
        List<AddressState> snapshot = snapshotStates();
        List<Failure> failed = new ArrayList<>();
        for (AddressState state : snapshot) {
            if (state.failure != null) {
                failed.add(new Failure(state.address, state.failure));
            }
        }
        return new Status(ready, failed.isEmpty(), snapshot.size(), publishedRows, tip, failed);
    }

    @Override
    public void close() {
        running = false;
        worker.shutdownNow();
        retry.shutdownNow();
        api.close();
    }

    // --- listener callbacks (connection reader thread) --------------------

    @Override
    public void onConnected() {
        submit(this::reconcileAll);
    }

    @Override
    public void onDisconnected() {
        // Readiness is "the chain is being served now", not "it was once synced": chain-data
        // methods must close again while the session is down, and the reconnect's reconcile
        // re-opens them. The last snapshot stays published either way.
        ready = false;
        LOGGER.info("[history-" + name + "] native connection lost; keeping the last snapshot");
    }

    @Override
    public void onNotification(String method, JsonArray params) {
        if ("blockchain.headers.subscribe".equals(method) && !params.isEmpty()) {
            JsonObject header = params.get(0).getAsJsonObject();
            if (header.has("height")) {
                tip = header.get("height").getAsInt();
                sink.onTip(tip);
            }
            return;
        }
        if ("blockchain.scripthash.subscribe".equals(method) && params.size() >= 2) {
            String scripthash = params.get(0).getAsString();
            JsonElement status = params.get(1);
            if (queued.add(scripthash)) {
                submit(() -> {
                    queued.remove(scripthash);
                    refreshScripthash(scripthash, status);
                });
            }
        }
    }

    // --- worker ------------------------------------------------------------

    private void loop() {
        while (running) {
            try {
                tasks.take().run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "[history-" + name + "] task failed", e);
            }
        }
    }

    private void submit(Runnable task) {
        if (running) {
            tasks.add(task);
        }
    }

    private void reconcileAll() {
        // Nothing to reconcile while the session is down: onConnected runs this again when it
        // comes up, so polling here would only be a timer that re-fails every few seconds.
        if (!api.isConnected()) {
            return;
        }
        syncAddressesOnWorker();
        if (!readTip()) {
            return;
        }
        List<AddressState> snapshot = snapshotStates();
        List<Pending> pending = changedStates(snapshot, awaitBatch(subscribeCalls(snapshot)));
        Applied applied = pending == null ? Applied.NOTHING_ANSWERED : applyChanged(pending);
        if (!applied.answered()) {
            scheduleRetry();
            return;
        }
        publish();
        if (applied.deferred()) {
            // A transport failure leaves addresses unread without saying so, so the first pass
            // stays open and the retry timer finishes it.
            scheduleRetry();
            return;
        }
        // A refusal is an answer, not an outage: the coin serves the addresses it could read and
        // reports the rest through Status.
        markReady();
    }

    /** One address whose history must be re-read, with the status it now reports. */
    private record Pending(AddressState state, JsonElement status) {}

    /**
     * Outcome of one reconcile pass.
     *
     * <p>{@code answered} means the provider spoke about at least one address. {@code deferred}
     * means some answer was a transport failure rather than the indexer's verdict, so the pass is
     * not finished and must be retried. A refused address is neither: it is the indexer's final
     * answer, recorded on the address and reported through {@link Status}.
     */
    private record Applied(boolean answered, boolean deferred) {
        private static final Applied NOTHING_ANSWERED = new Applied(false, true);
    }

    /**
     * Pairs each state with its status, keeping those that still need work.
     *
     * <p>Returns null when the provider could not be asked at all, which is the one case that
     * belongs to the retry timer rather than to the per-address outcome. An address the indexer
     * refuses is recorded here and left out: it is answered, not pending.
     */
    private List<Pending> changedStates(List<AddressState> states, List<Reply> statuses) {
        if (statuses == null || statuses.size() != states.size()) {
            return null;
        }
        List<Pending> changed = new ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            AddressState state = states.get(index);
            Reply reply = statuses.get(index);
            if (!reply.ok()) {
                if (reply.refused()) {
                    markRefused(state, reply.error());
                }
                continue;
            }
            JsonElement status = reply.value();
            // unspentsLoaded matters on its own: a failed unspent fetch leaves the
            // status unchanged, so retrying on status alone would never happen.
            if (!state.loaded || !state.unspentsLoaded || !Objects.equals(status, state.status)) {
                changed.add(new Pending(state, status));
            }
        }
        return changed;
    }

    /**
     * Re-reads history and unspents for the given addresses, applying both.
     *
     * <p>History and the unspent set are read from the same status change in one reconcile, so the
     * wallet's rows and its coin balance can never disagree about which tip they describe.
     */
    private Applied applyChanged(List<Pending> pending) {
        if (pending.isEmpty()) {
            // Nothing needed re-reading, which is a complete pass, not a failed one: an empty
            // wallet and an unchanged status on reconnect both land here.
            return new Applied(true, false);
        }
        List<AddressState> states = new ArrayList<>(pending.size());
        for (Pending entry : pending) {
            states.add(entry.state());
        }
        List<Reply> histories = awaitBatch(historyCalls(states));
        // Read both together, but let the wallet see the history rows it did get:
        // an unspent failure leaves the balance stale, not the transaction list empty.
        List<Reply> unspents = awaitBatch(unspentCalls(states));

        boolean answered = false;
        boolean deferred = false;
        for (int index = 0; index < states.size(); index++) {
            AddressState state = states.get(index);
            Reply history = histories.get(index);
            if (history.ok()) {
                answered = true;
                applyHistory(state, pending.get(index).status(), history.value());
            } else if (history.refused()) {
                // A refusal is the indexer's answer about this address, not a lost connection: the
                // rest of the wallet loads, and the snapshot says which address is missing.
                answered = true;
                markRefused(state, history.error());
            } else {
                deferred = true;
            }

            Reply unspent = unspents.get(index);
            if (unspent.ok() && unspent.value() != null && unspent.value().isJsonArray()) {
                state.unspentsLoaded = true;
                sink.applyUnspents(state.address, unspent.value().getAsJsonArray());
            } else {
                state.unspentsLoaded = false;
                if (unspent.refused()) {
                    markRefused(state, unspent.error());
                } else {
                    deferred = true;
                }
            }
        }
        return new Applied(answered, deferred);
    }

    /**
     * Records that this address's history cannot be read, and drops the rows it had.
     *
     * <p>Stale rows would be worse than none: they describe a history the indexer no longer vouches
     * for, and the caller is told the snapshot is incomplete either way.
     */
    private void markRefused(AddressState state, Throwable error) {
        String reason = error == null ? "refused" : error.getMessage();
        if (!reason.equals(state.failure)) {
            LOGGER.warning(
                    "[history-" + name + "] " + state.address + " cannot be read: " + reason);
        }
        state.failure = reason;
        state.rows = List.of();
        state.loaded = true;
    }

    private void markReady() {
        if (ready) {
            return;
        }
        Status snapshot = status();
        LOGGER.info(
                "[history-"
                        + name
                        + "] initial sync complete: "
                        + snapshot.addresses()
                        + " addresses, "
                        + snapshot.rows()
                        + " rows");
        // Tell the coin before publishing readiness: a caller that sees rpcready=true must not
        // race the provider-dependent work this callback starts.
        sink.onReady();
        ready = true;
    }

    /** Load addresses added after the initial sync, without touching the rest. */
    private void reconcileNewAddresses() {
        if (!api.isConnected()) {
            return;
        }
        List<AddressState> added = syncAddressesOnWorker();
        if (added.isEmpty()) {
            return;
        }
        List<Pending> pending = changedStates(added, awaitBatch(subscribeCalls(added)));
        Applied applied = pending == null ? Applied.NOTHING_ANSWERED : applyChanged(pending);
        if (!applied.answered()) {
            scheduleRetry();
            return;
        }
        publish();
        if (applied.deferred()) {
            scheduleRetry();
        }
    }

    private boolean readTip() {
        try {
            JsonElement header = request("blockchain.headers.subscribe", new JsonArray());
            if (header != null && header.isJsonObject() && header.getAsJsonObject().has("height")) {
                tip = header.getAsJsonObject().get("height").getAsInt();
                sink.onTip(tip);
            }
            return true;
        } catch (TransientFailure e) {
            // One line per failed pass at FINE: the client already reports the session state at
            // INFO when it transitions, and a retry while disconnected would only repeat it.
            LOGGER.fine("[history-" + name + "] cannot read the tip yet: " + e.getMessage());
            scheduleRetry();
            return false;
        }
    }

    private static List<Call> subscribeCalls(List<AddressState> states) {
        List<Call> calls = new ArrayList<>();
        for (AddressState state : states) {
            calls.add(new Call("blockchain.scripthash.subscribe", args(state.scripthash)));
        }
        return calls;
    }

    private static List<Call> historyCalls(List<AddressState> states) {
        List<Call> calls = new ArrayList<>();
        for (AddressState state : states) {
            calls.add(new Call("blockchain.scripthash.get_history", args(state.scripthash)));
        }
        return calls;
    }

    private static List<Call> unspentCalls(List<AddressState> states) {
        List<Call> calls = new ArrayList<>();
        for (AddressState state : states) {
            calls.add(new Call("blockchain.scripthash.listunspent", args(state.scripthash)));
        }
        return calls;
    }

    /**
     * Every request's own outcome, in order.
     *
     * <p>One item's failure does not speak for the others: a JSON-RPC batch reports each request
     * separately, so an indexer that refuses one address still answered the rest. Returning a
     * single failure for the chunk would throw those answers away and withhold up to {@value
     * #BATCH_SIZE} addresses over one refusal.
     */
    private List<Reply> awaitBatch(List<Call> calls) {
        List<Reply> replies = new ArrayList<>(calls.size());
        for (int start = 0; start < calls.size(); start += BATCH_SIZE) {
            List<Call> chunk = calls.subList(start, Math.min(start + BATCH_SIZE, calls.size()));
            replies.addAll(awaitChunk(chunk));
        }
        return replies;
    }

    private List<Reply> awaitChunk(List<Call> calls) {
        List<CompletableFuture<JsonElement>> futures = api.batch(calls);
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
                    .get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return timeoutReplies(calls.size(), "interrupted");
        } catch (TimeoutException e) {
            // No item's fate is known when the frame itself does not complete.
            LOGGER.log(Level.FINE, "[history-" + name + "] a batch timed out", e);
            return timeoutReplies(calls.size(), "no answer");
        } catch (ExecutionException e) {
            // Inspected per item below: one failing item must not hide the rest.
            LOGGER.log(Level.FINE, "[history-" + name + "] a batch item failed", e);
        }
        List<Reply> replies = new ArrayList<>(futures.size());
        for (CompletableFuture<JsonElement> future : futures) {
            replies.add(replyOf(future));
        }
        return replies;
    }

    private static Reply replyOf(CompletableFuture<JsonElement> future) {
        if (future.isCompletedExceptionally()) {
            Throwable error = failureOf(future);
            return new Reply(
                    null, error == null ? new ElectrumUnavailableException("no answer") : error);
        }
        if (!future.isDone()) {
            return new Reply(null, new ElectrumUnavailableException("no answer"));
        }
        return Reply.of(future.getNow(null));
    }

    /** Every item failed the same way, with nothing learned about any of them. */
    private static List<Reply> timeoutReplies(int count, String reason) {
        List<Reply> replies = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            replies.add(new Reply(null, new ElectrumUnavailableException(reason)));
        }
        return replies;
    }

    /** Every script hash the wallet owns, for whole-wallet output attribution. */
    private Set<String> walletHashX() {
        Set<String> owned = new HashSet<>();
        for (AddressState state : states.values()) {
            if (state.addressHashX != null) {
                owned.add(state.addressHashX);
            }
        }
        return owned;
    }

    /**
     * @return false when the address must be retried, true when it is fully applied
     */
    private boolean applyHistory(AddressState state, JsonElement status, JsonElement result) {
        try {
            List<TxRef> history = parseHistory(result, state.scripthash);
            warm(history);
            warmInputs(history);
            state.rows =
                    materialiser.rows(state.address, state.addressHashX, walletHashX(), history);
            state.status = status;
            state.loaded = true;
            state.failure = null;
            return true;
        } catch (TransientFailure e) {
            LOGGER.log(
                    Level.WARNING,
                    "[history-" + name + "] cannot load " + state.address + ": " + e.getMessage());
            return false;
        }
    }

    private void refreshScripthash(String scripthash, JsonElement status) {
        AddressState state = byScripthash.get(scripthash);
        if (state == null) {
            return;
        }
        try {
            refresh(state, status, true);
        } catch (TransientFailure e) {
            if (e.getCause() instanceof ElectrumException refused) {
                // The indexer answered for this address and refused it: permanent for that address,
                // so it is reported instead of retried.
                markRefused(state, refused);
                publish();
                return;
            }
            LOGGER.log(Level.WARNING, "[history-" + name + "] cannot refresh " + state.address, e);
            scheduleRetry();
        }
    }

    private void refresh(AddressState state, JsonElement status, boolean publishNow) {
        List<TxRef> history = history(state.scripthash);
        warm(history);
        warmInputs(history);
        state.rows = materialiser.rows(state.address, state.addressHashX, walletHashX(), history);
        state.status = status;
        state.loaded = true;
        state.failure = null;
        sink.applyUnspents(
                state.address,
                request("blockchain.scripthash.listunspent", args(state.scripthash))
                        .getAsJsonArray());
        if (publishNow) {
            publish();
        }
    }

    private List<TxRef> history(String scripthash) {
        return parseHistory(
                request("blockchain.scripthash.get_history", args(scripthash)), scripthash);
    }

    private static List<TxRef> parseHistory(JsonElement result, String scripthash) {
        if (result == null || !result.isJsonArray()) {
            throw new TransientFailure("malformed history for " + scripthash);
        }
        List<TxRef> history = new ArrayList<>();
        for (JsonElement element : result.getAsJsonArray()) {
            JsonObject entry = element.getAsJsonObject();
            history.add(
                    new TxRef(
                            entry.get("tx_hash").getAsString(),
                            entry.has("height") ? entry.get("height").getAsInt() : 0));
        }
        return history;
    }

    private void warm(List<TxRef> history) {
        List<String> missing = new ArrayList<>();
        for (TxRef reference : history) {
            if (needsTransaction(reference.txid())) {
                missing.add(reference.txid());
            }
        }
        fetch(missing);
    }

    private void warmInputs(List<TxRef> history) {
        Set<String> missing = new HashSet<>();
        for (TxRef reference : history) {
            JsonObject tx = transactions.get(reference.txid());
            if (tx == null) {
                continue;
            }
            JsonElement inputs = tx.get("vin");
            if (inputs == null || !inputs.isJsonArray()) {
                continue;
            }
            for (JsonElement element : inputs.getAsJsonArray()) {
                JsonObject input = element.getAsJsonObject();
                if (!input.has("txid")) {
                    continue;
                }
                String parent = input.get("txid").getAsString();
                if (needsTransaction(parent)) {
                    missing.add(parent);
                }
            }
        }
        fetch(new ArrayList<>(missing));
    }

    private boolean needsTransaction(String txid) {
        return !transactions.containsKey(txid);
    }

    private void fetch(List<String> txids) {
        for (int start = 0; start < txids.size(); start += FETCH_BATCH) {
            List<String> batch = txids.subList(start, Math.min(start + FETCH_BATCH, txids.size()));
            fetchBatch(batch);
        }
    }

    private void fetchBatch(List<String> txids) {
        List<Call> calls = new ArrayList<>();
        for (String txid : txids) {
            calls.add(new Call("blockchain.transaction.get", args(txid, true)));
        }
        List<CompletableFuture<JsonElement>> futures = api.batch(calls);
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
                    .get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientFailure("interrupted while fetching transactions");
        } catch (ExecutionException | TimeoutException e) {
            // A single missing transaction is not fatal; store() decides per item.
            LOGGER.log(Level.FINE, "[history-" + name + "] a transaction batch item failed", e);
        }
        for (int index = 0; index < txids.size(); index++) {
            store(txids.get(index), futures.get(index));
        }
    }

    private void store(String txid, CompletableFuture<JsonElement> future) {
        if (!future.isDone()) {
            throw new TransientFailure("transaction " + txid + " did not answer");
        }
        if (future.isCompletedExceptionally()) {
            Throwable error = failureOf(future);
            if (error instanceof ElectrumException) {
                LOGGER.warning("[history-" + name + "] transaction " + txid + " is unavailable");
                return;
            }
            throw new TransientFailure("cannot fetch " + txid + ": " + error);
        }
        JsonElement value = future.getNow(null);
        if (value != null && value.isJsonObject()) {
            transactions.put(txid, value.getAsJsonObject());
        }
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        try {
            future.get();
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return e;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private List<AddressState> syncAddressesOnWorker() {
        List<AddressState> added = new ArrayList<>();
        synchronized (states) {
            for (AddressBalance balance : sink.addresses()) {
                String text = balance.getAddress().toBase58();
                if (states.containsKey(text)) {
                    continue;
                }
                AddressState state =
                        new AddressState(
                                text,
                                ScriptHashes.scripthash(balance.getAddress()),
                                ScriptHashes.hashX(balance.getAddress()));
                states.put(text, state);
                byScripthash.put(state.scripthash, state);
                added.add(state);
            }
        }
        return added;
    }

    private void publish() {
        Map<String, Transaction> merged = new LinkedHashMap<>();
        synchronized (states) {
            for (AddressState state : states.values()) {
                for (Transaction row : state.rows) {
                    merged.put(row.uid(), row);
                }
            }
        }
        publishedRows = merged.size();
        sink.publish(new ArrayList<>(merged.values()));
    }

    private void scheduleRetry() {
        // A retry only makes sense while a session can answer it; recovery from a dropped
        // connection is the client's job and re-enters through onConnected.
        if (!running || !api.isConnected() || !retryScheduled.compareAndSet(false, true)) {
            return;
        }
        retry.schedule(
                () -> {
                    retryScheduled.set(false);
                    submit(this::reconcileAll);
                },
                RETRY_SECONDS,
                TimeUnit.SECONDS);
    }

    private List<AddressState> snapshotStates() {
        synchronized (states) {
            return new ArrayList<>(states.values());
        }
    }

    private JsonElement request(String method, JsonArray params) {
        try {
            return api.request(method, params)
                    .get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientFailure("interrupted during " + method);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ElectrumException electrum) {
                // The indexer refused this request and said why. Carried as the cause so the
                // caller can tell a permanent refusal from a transport failure.
                throw new TransientFailure(electrum.getMessage(), electrum);
            }
            throw new TransientFailure("request failed: " + method, cause);
        } catch (TimeoutException e) {
            throw new TransientFailure("request timed out: " + method);
        }
    }

    private static JsonArray args(Object... values) {
        JsonArray array = new JsonArray();
        for (Object value : values) {
            if (value instanceof String text) {
                array.add(text);
            } else if (value instanceof Number number) {
                array.add(number);
            } else if (value instanceof Boolean flag) {
                array.add(flag);
            } else {
                throw new IllegalArgumentException("unsupported request argument " + value);
            }
        }
        return array;
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static final class AddressState {
        private final String address;
        private final String scripthash;
        private final String addressHashX;
        private List<Transaction> rows = List.of();
        private JsonElement status;
        private boolean loaded;
        private boolean unspentsLoaded;

        /** Why the indexer would not answer for this address, or null when it did. */
        private String failure;

        private AddressState(String address, String scripthash, String addressHashX) {
            this.address = address;
            this.scripthash = scripthash;
            this.addressHashX = addressHashX;
        }
    }

    /** A failure that leaves the snapshot usable and is worth retrying. */
    private static final class TransientFailure extends RuntimeException {
        private TransientFailure(String message) {
            super(message);
        }

        private TransientFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
