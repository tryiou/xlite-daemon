package io.xlite.daemon.app.net.electrumx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.xlite.daemon.app.util.AddressBalance;
import io.xlite.daemon.app.util.history.Transaction;

import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.Utils;
import org.bitcoinj.params.MainNetParams;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

class HistoryEngineTest {

    private static final byte[] HASH160 = new byte[20];

    static {
        HASH160[0] = 1;
    }

    private static final LegacyAddress ADDRESS =
            LegacyAddress.fromPubKeyHash(MainNetParams.get(), HASH160);
    private static final String SCRIPT = scriptHex();
    private static final String SCRIPTHASH = ScriptHashes.scripthash(ADDRESS);

    private final FakeApi api = new FakeApi();
    private final FakeSink sink = new FakeSink();

    private HistoryEngine engine() {
        return new HistoryEngine("TEST", MainNetParams.get(), api, sink, Function.identity());
    }

    @Test
    void initialSyncPublishesTheWalletSnapshot() throws Exception {
        api.batch("blockchain.scripthash.subscribe", args(SCRIPTHASH), "s1");
        api.batch("blockchain.scripthash.get_history", args(SCRIPTHASH), history("t1", 10));
        api.batch("blockchain.transaction.get", args("t1", true), receive("t1", 4.0));

        HistoryEngine engine = engine();
        engine.start();
        api.fireConnected();

        List<Transaction> published = sink.next();
        assertReady(engine);
        assertEquals(1, published.size());
        assertEquals("t1", published.get(0).getTxid());
        assertEquals(4.0, published.get(0).getValue());
        assertEquals(10, published.get(0).getBlockheight());
        assertEquals(100, sink.tip);
        engine.close();
    }

    @Test
    void theFirstFullSyncReportsReadyToTheCoin() throws Exception {
        api.batch("blockchain.scripthash.subscribe", args(SCRIPTHASH), "s1");
        api.batch("blockchain.scripthash.get_history", args(SCRIPTHASH), history("t1", 10));
        api.batch("blockchain.transaction.get", args("t1", true), receive("t1", 4.0));

        HistoryEngine engine = engine();
        engine.start();
        api.fireConnected();
        sink.next();
        assertReady(engine);
        assertEquals(1, sink.readyCalls());
        engine.close();
    }

    @Test
    void readinessDropsWhileTheSessionIsDown() throws Exception {
        api.batch("blockchain.scripthash.subscribe", args(SCRIPTHASH), "s1");
        api.batch("blockchain.scripthash.get_history", args(SCRIPTHASH), history("t1", 10));
        api.batch("blockchain.transaction.get", args("t1", true), receive("t1", 4.0));

        HistoryEngine engine = engine();
        engine.start();
        api.fireConnected();
        sink.next();
        assertReady(engine);

        api.fireDisconnected();

        assertFalse(engine.isReady());
        engine.close();
    }

    @Test
    void aDownSessionIsNotPolled() throws Exception {
        HistoryEngine engine = engine();
        engine.start();
        // The client reports a connection event, but the session is already gone again.
        engine.onConnected();
        Thread.sleep(100);
        assertEquals(0, api.count("blockchain.headers.subscribe"));
        assertFalse(engine.isReady());
        engine.close();
    }

    @Test
    void aStatusChangeRefreshesOnlyThatAddress() throws Exception {
        api.batch("blockchain.scripthash.subscribe", args(SCRIPTHASH), "s1");
        api.batch("blockchain.scripthash.get_history", args(SCRIPTHASH), history("t1", 10));
        api.batch("blockchain.transaction.get", args("t1", true), receive("t1", 4.0));

        HistoryEngine engine = engine();
        engine.start();
        api.fireConnected();
        sink.next();

        api.batch("blockchain.scripthash.get_history", args(SCRIPTHASH), history("t2", 11));
        api.batch("blockchain.transaction.get", args("t2", true), receive("t2", 5.0));
        api.fireNotification("blockchain.scripthash.subscribe", args(SCRIPTHASH, "s2"));

        List<Transaction> updated = sink.next();
        assertTrue(updated.stream().anyMatch(row -> "t2".equals(row.getTxid())));
        engine.close();
    }

    @Test
    void anUnchangedStatusOnReconnectDoesNotRefetchHistory() throws Exception {
        api.batch("blockchain.scripthash.subscribe", args(SCRIPTHASH), "s1");
        api.batch("blockchain.scripthash.get_history", args(SCRIPTHASH), history("t1", 10));
        api.batch("blockchain.transaction.get", args("t1", true), receive("t1", 4.0));

        HistoryEngine engine = engine();
        engine.start();
        api.fireConnected();
        sink.next();

        int before = api.count("blockchain.scripthash.get_history");
        api.fireConnected();
        sink.next();
        assertEquals(before, api.count("blockchain.scripthash.get_history"));
        engine.close();
    }

    @Test
    void anEmptyWalletBecomesReadyWithoutTransactions() throws Exception {
        HistoryEngine engine = engine();
        sink.addresses.clear();
        engine.start();
        api.fireConnected();

        assertEquals(List.of(), sink.next());
        assertReady(engine);
        engine.close();
    }

    @Test
    void aNewAddressIsSubscribedAndPublishedWithoutAReconnect() throws Exception {
        api.batch("blockchain.scripthash.subscribe", args(SCRIPTHASH), "s1");
        api.batch("blockchain.scripthash.get_history", args(SCRIPTHASH), history("t1", 10));
        api.batch("blockchain.transaction.get", args("t1", true), receive("t1", 4.0));

        HistoryEngine engine = engine();
        engine.start();
        api.fireConnected();
        sink.next();

        LegacyAddress added = address(2);
        String scripthash = ScriptHashes.scripthash(added);
        api.batch("blockchain.scripthash.subscribe", args(scripthash), "s2");
        api.batch("blockchain.scripthash.get_history", args(scripthash), history("t2", 11));
        api.batch(
                "blockchain.transaction.get",
                args("t2", true),
                receive("t2", 5.0, added, scriptHex(added)));
        sink.addresses.add(new AddressBalance(added, null));

        engine.syncAddresses();

        List<Transaction> updated = sink.next();
        assertTrue(updated.stream().anyMatch(row -> "t2".equals(row.getTxid())));
        assertEquals(2, engine.status().addresses());
        assertTrue(engine.status().complete());
        assertTrue(engine.status().failed().isEmpty());
        engine.close();
    }

    @Test
    void aTransientTransactionFailureIsRetriedAndDoesNotMarkTheEngineReady() throws Exception {
        api.batch("blockchain.scripthash.subscribe", args(SCRIPTHASH), "s1");
        api.batch("blockchain.scripthash.get_history", args(SCRIPTHASH), history("t1", 10));
        api.failOnce("blockchain.transaction.get", args("t1", true));

        HistoryEngine engine = engine();
        engine.start();
        api.fireConnected();

        assertEquals(List.of(), sink.next());
        assertFalse(engine.isReady());

        api.batch("blockchain.transaction.get", args("t1", true), receive("t1", 4.0));
        api.fireConnected();

        List<Transaction> rows = sink.next();
        assertEquals(1, rows.size());
        assertReady(engine);
        engine.close();
    }

    @Test
    void notificationsBeforeAWalletAddressIsKnownAreIgnored() throws Exception {
        HistoryEngine engine = engine();
        engine.start();
        api.fireNotification("blockchain.scripthash.subscribe", args("unknown", "s"));

        assertFalse(engine.isReady());
        engine.close();
    }

    @Test
    void initialSyncFetchesTheUnspentSetSoTheBalanceIsKnown() throws Exception {
        assertFalse(sink.addresses.get(0).getBalanceProp() > 0);
        api.batch("blockchain.scripthash.subscribe", args(SCRIPTHASH), "s1");
        api.batch("blockchain.scripthash.get_history", args(SCRIPTHASH), history("t1", 10));
        api.batch("blockchain.transaction.get", args("t1", true), receive("t1", 4.0));
        api.batch("blockchain.scripthash.listunspent", args(SCRIPTHASH), unspents("t1", 0, 4.0));

        HistoryEngine engine = engine();
        engine.start();
        api.fireConnected();

        sink.next();
        assertReady(engine);
        assertEquals(1, api.count("blockchain.scripthash.listunspent"));
        assertEquals(1, sink.unspentsFor(ADDRESS.toBase58()));
        engine.close();
    }

    @Test
    void aStatusChangeRefreshesThatAddressesUnspentSet() throws Exception {
        api.batch("blockchain.scripthash.subscribe", args(SCRIPTHASH), "s1");
        api.batch("blockchain.scripthash.get_history", args(SCRIPTHASH), history("t1", 10));
        api.batch("blockchain.transaction.get", args("t1", true), receive("t1", 4.0));
        api.batch("blockchain.scripthash.listunspent", args(SCRIPTHASH), unspents("t1", 0, 4.0));

        HistoryEngine engine = engine();
        engine.start();
        api.fireConnected();
        sink.next();
        assertReady(engine);

        api.batch("blockchain.scripthash.get_history", args(SCRIPTHASH), history("t2", 11));
        api.batch("blockchain.transaction.get", args("t2", true), receive("t2", 5.0));
        api.batch("blockchain.scripthash.listunspent", args(SCRIPTHASH), unspents("t2", 0, 5.0));
        api.fireNotification("blockchain.scripthash.subscribe", args(SCRIPTHASH, "s2"));

        sink.next();
        assertEquals(2, api.count("blockchain.scripthash.listunspent"));
        assertEquals(1, sink.unspentsFor(ADDRESS.toBase58()));
        engine.close();
    }

    @Test
    void anUnspentFetchFailureKeepsTheRowsButDoesNotMarkTheEngineReady() throws Exception {
        api.batch("blockchain.scripthash.subscribe", args(SCRIPTHASH), "s1");
        api.batch("blockchain.scripthash.get_history", args(SCRIPTHASH), history("t1", 10));
        api.batch("blockchain.transaction.get", args("t1", true), receive("t1", 4.0));
        api.failOnce("blockchain.scripthash.listunspent", args(SCRIPTHASH));

        HistoryEngine engine = engine();
        engine.start();
        api.fireConnected();

        // History landed, so the row is published; only the balance is still unknown.
        assertEquals(1, sink.next().size());
        assertFalse(engine.isReady());
        assertEquals(-1, sink.unspentsFor(ADDRESS.toBase58()));

        api.batch("blockchain.scripthash.listunspent", args(SCRIPTHASH), unspents("t1", 0, 4.0));
        api.fireConnected();

        sink.next();
        assertReady(engine);
        assertEquals(1, sink.unspentsFor(ADDRESS.toBase58()));
        engine.close();
    }

    /**
     * A refused address must not speak for its neighbours.
     *
     * <p>The indexer answers each batch item separately; one address it will not serve is that
     * address's problem. The rest of the wallet loads, the pass finishes, and the status names the
     * one that did not.
     */
    @Test
    void aRefusedAddressIsReportedWithoutHoldingBackTheRest() throws Exception {
        LegacyAddress other = address(2);
        sink.addresses.add(new AddressBalance(other, null));
        String otherScripthash = ScriptHashes.scripthash(other);

        api.batch("blockchain.scripthash.subscribe", args(SCRIPTHASH), "s1");
        api.batch("blockchain.scripthash.subscribe", args(otherScripthash), "s2");
        api.batch("blockchain.scripthash.get_history", args(SCRIPTHASH), history("t1", 10));
        api.batch("blockchain.transaction.get", args("t1", true), receive("t1", 4.0));
        api.refuse("blockchain.scripthash.get_history", args(otherScripthash), "history too large");

        HistoryEngine engine = engine();
        engine.start();
        api.fireConnected();

        List<Transaction> published = sink.next();
        assertEquals(1, published.size());
        assertEquals("t1", published.get(0).getTxid());
        assertReady(engine);

        HistoryEngine.Status status = engine.status();
        assertEquals(2, status.addresses());
        assertFalse(status.complete());
        assertEquals(1, status.failed().size());
        assertEquals(other.toBase58(), status.failed().get(0).address());
        assertTrue(status.failed().get(0).reason().contains("history too large"));
        engine.close();
    }

    /** A refusal that clears leaves the address readable and the snapshot whole again. */
    @Test
    void aRefusalThatClearsRestoresTheSnapshot() throws Exception {
        api.batch("blockchain.scripthash.subscribe", args(SCRIPTHASH), "s1");
        api.refuse("blockchain.scripthash.get_history", args(SCRIPTHASH), "history too large");

        HistoryEngine engine = engine();
        engine.start();
        api.fireConnected();
        sink.next();
        assertReady(engine);
        assertFalse(engine.status().complete());

        api.cleared();
        api.batch("blockchain.scripthash.get_history", args(SCRIPTHASH), history("t1", 10));
        api.batch("blockchain.transaction.get", args("t1", true), receive("t1", 4.0));
        api.fireNotification("blockchain.scripthash.subscribe", args(SCRIPTHASH, "s2"));

        List<Transaction> rows = sink.next();
        assertEquals(1, rows.size());
        assertTrue(engine.status().complete());
        assertTrue(engine.status().failed().isEmpty());
        engine.close();
    }

    /** The worker publishes and then marks ready; wait for the mark instead of racing it. */
    private static void assertReady(HistoryEngine engine) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (!engine.isReady() && System.currentTimeMillis() < deadline) {
            Thread.sleep(2);
        }
        assertTrue(engine.isReady());
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
            } else if (value instanceof JsonElement element) {
                array.add(element);
            }
        }
        return array;
    }

    private static JsonArray history(String txid, int height) {
        JsonObject entry = new JsonObject();
        entry.addProperty("tx_hash", txid);
        entry.addProperty("height", height);
        JsonArray array = new JsonArray();
        array.add(entry);
        return array;
    }

    /** One native {@code scripthash.listunspent} row, as stock ElectrumX states it. */
    private static JsonArray unspents(String txid, int vout, double amount) {
        JsonObject row = new JsonObject();
        row.addProperty("tx_hash", txid);
        row.addProperty("tx_pos", vout);
        row.addProperty("height", 10);
        row.addProperty("value", (long) (amount * 100_000_000));
        JsonArray array = new JsonArray();
        array.add(row);
        return array;
    }

    private static LegacyAddress address(int tag) {
        byte[] hash = new byte[20];
        hash[0] = (byte) tag;
        return LegacyAddress.fromPubKeyHash(MainNetParams.get(), hash);
    }

    private static JsonObject receive(String txid, double amount) {
        return receive(txid, amount, ADDRESS, SCRIPT);
    }

    private static JsonObject receive(
            String txid, double amount, LegacyAddress address, String scriptHex) {
        JsonObject script = new JsonObject();
        script.addProperty("type", "pubkeyhash");
        script.addProperty("hex", scriptHex);
        script.addProperty("address", address.toBase58());
        JsonObject output = new JsonObject();
        output.addProperty("value", amount);
        output.addProperty("n", 0);
        output.add("scriptPubKey", script);
        JsonArray vout = new JsonArray();
        vout.add(output);
        JsonObject tx = new JsonObject();
        tx.addProperty("txid", txid);
        tx.add("vin", new JsonArray());
        tx.add("vout", vout);
        tx.addProperty("blockhash", "b".repeat(64));
        tx.addProperty("blocktime", 1000);
        tx.addProperty("confirmations", 5);
        return tx;
    }

    private static String scriptHex() {
        return scriptHex(ADDRESS);
    }

    private static String scriptHex(LegacyAddress address) {
        byte[] raw = new byte[25];
        raw[0] = 0x76;
        raw[1] = (byte) 0xA9;
        raw[2] = 0x14;
        System.arraycopy(address.getHash(), 0, raw, 3, 20);
        raw[23] = (byte) 0x88;
        raw[24] = (byte) 0xAC;
        return Utils.HEX.encode(raw);
    }

    private static final class FakeSink implements HistoryEngine.Sink {
        private final BlockingQueue<List<Transaction>> published = new LinkedBlockingQueue<>();
        private final List<AddressBalance> addresses = new ArrayList<>();
        private final Map<String, Integer> unspentRows = new HashMap<>();
        private volatile int tip;

        private FakeSink() {
            addresses.add(new AddressBalance(ADDRESS, null));
        }

        @Override
        public List<AddressBalance> addresses() {
            return addresses;
        }

        @Override
        public void publish(List<Transaction> rows) {
            published.add(rows);
        }

        @Override
        public void onTip(int height) {
            tip = height;
        }

        @Override
        public void applyUnspents(String address, JsonArray rows) {
            unspentRows.put(address, rows.size());
            for (AddressBalance balance : addresses) {
                if (balance.getAddress().toBase58().equals(address)) {
                    balance.replaceUnspents(List.of());
                }
            }
        }

        private final java.util.concurrent.atomic.AtomicInteger readyCalls =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public void onReady() {
            readyCalls.incrementAndGet();
        }

        private int readyCalls() {
            return readyCalls.get();
        }

        private int unspentsFor(String address) {
            return unspentRows.getOrDefault(address, -1);
        }

        private List<Transaction> next() throws InterruptedException {
            List<Transaction> rows = published.poll(2, TimeUnit.SECONDS);
            assertTrue(rows != null, "no snapshot was published");
            return rows;
        }
    }

    private static final class FakeApi implements ElectrumApi {
        private final Map<String, Object> responses = new HashMap<>();
        private final Map<String, Integer> calls = new HashMap<>();
        private final Set<String> failOnce = new HashSet<>();
        private final Set<String> refused = new HashSet<>();
        private ElectrumClient.Listener listener;

        private void batch(String method, JsonArray params, Object response) {
            responses.put(method + params, response);
        }

        private void failOnce(String method, JsonArray params) {
            failOnce.add(method + params);
        }

        /** The indexer answered, and its answer is a refusal that will not change. */
        private void refuse(String method, JsonArray params, String reason) {
            refused.add(method + params + "|" + reason);
        }

        /** The refusal is gone: the indexer serves this address again. */
        private void cleared() {
            refused.clear();
        }

        private int count(String method) {
            return calls.getOrDefault(method, 0);
        }

        private boolean connected;

        private void fireConnected() {
            connected = true;
            listener.onConnected();
        }

        private void fireDisconnected() {
            connected = false;
            listener.onDisconnected();
        }

        private void fireNotification(String method, JsonArray params) {
            listener.onNotification(method, params);
        }

        @Override
        public void setListener(ElectrumClient.Listener newListener) {
            listener = newListener;
        }

        @Override
        public void start() {}

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public CompletableFuture<JsonElement> request(String method, JsonArray params) {
            calls.merge(method, 1, Integer::sum);
            if ("blockchain.headers.subscribe".equals(method)) {
                JsonObject header = new JsonObject();
                header.addProperty("height", 100);
                return CompletableFuture.completedFuture(header);
            }
            String key = method + params;
            if (failOnce.remove(key)) {
                return CompletableFuture.failedFuture(
                        new ElectrumUnavailableException("transport lost"));
            }
            for (String entry : refused) {
                if (entry.startsWith(key + "|")) {
                    return CompletableFuture.failedFuture(
                            new ElectrumException(1, entry.substring((key + "|").length())));
                }
            }
            Object response = responses.get(key);
            if (response == null) {
                // A real indexer always answers listunspent; an address with nothing
                // unspent reports an empty list rather than an error.
                if ("blockchain.scripthash.listunspent".equals(method)) {
                    return CompletableFuture.completedFuture(new JsonArray());
                }
                return CompletableFuture.failedFuture(
                        new ElectrumException(1, "missing " + method));
            }
            if (response instanceof JsonElement element) {
                return CompletableFuture.completedFuture(element);
            }
            return CompletableFuture.completedFuture(
                    com.google.gson.JsonParser.parseString(String.valueOf(response)));
        }

        @Override
        public List<CompletableFuture<JsonElement>> batch(
                List<io.xlite.daemon.app.net.electrumx.ElectrumApi.Call> calls) {
            List<CompletableFuture<JsonElement>> futures = new ArrayList<>();
            for (io.xlite.daemon.app.net.electrumx.ElectrumApi.Call call : calls) {
                futures.add(request(call.method(), call.params()));
            }
            return futures;
        }

        @Override
        public void close() {}
    }
}
