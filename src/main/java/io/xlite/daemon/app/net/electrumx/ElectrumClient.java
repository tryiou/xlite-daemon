package io.xlite.daemon.app.net.electrumx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * One native ElectrumX connection for one coin.
 *
 * <p>The hub exposes the stock protocol over a WebSocket; this client speaks it directly. It owns
 * request correlation, the handshake, bounded request timeouts and reconnection with exponential
 * backoff. It knows nothing about wallets: notifications are handed to a listener, which decides
 * what a changed script hash means.
 *
 * <p>A connection is usable only after {@code server.version} and {@code server.features} have both
 * answered. A request issued while no session is up fails immediately with {@link
 * ElectrumUnavailableException}; it never parks until its own timeout. The client keeps dialling
 * with capped, jittered backoff for the life of the process, so a coin whose provider is down — or
 * that the provider starts serving later — comes online by itself. Recovery is event-driven: the
 * listener's {@code onConnected} runs the first reconcile, so nothing polls while the session is
 * down.
 */
public final class ElectrumClient implements ElectrumApi {

    /** Downstream callbacks. Called on the connection's reader thread: do not block in them. */
    public interface Listener {
        void onNotification(String method, JsonArray params);

        void onConnected();

        void onDisconnected();
    }

    private static final String CLIENT_NAME = "xlite-daemon";
    private static final String PROTOCOL = "1.4";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_BACKOFF_SECONDS = 30;
    private static final double BACKOFF_JITTER = 0.2;

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private final URI endpoint;
    private final HttpClient http;
    private final ScheduledExecutorService scheduler;
    private final Map<Long, CompletableFuture<JsonElement>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final Object sendLock = new Object();

    private volatile Listener listener;
    private volatile boolean closed;
    private volatile WebSocket socket;
    private volatile boolean handshaken;

    /** True once this outage has been reported at WARNING; repeats log at FINE. */
    private volatile boolean announcedDown;

    private CompletableFuture<WebSocket> sendTail = CompletableFuture.completedFuture(null);
    private int backoffSeconds = 1;

    public ElectrumClient(URI endpoint) {
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "electrum-" + endpoint.getPath());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Override
    public void setListener(Listener newListener) {
        this.listener = newListener;
    }

    private final AtomicBoolean started = new AtomicBoolean();

    @Override
    public void start() {
        if (closed || !started.compareAndSet(false, true)) {
            return;
        }
        scheduler.execute(this::connect);
    }

    @Override
    public boolean isConnected() {
        WebSocket current = socket;
        return current != null
                && handshaken
                && !current.isOutputClosed()
                && !current.isInputClosed();
    }

    /**
     * Send one request.
     *
     * <p>Fails at once when no session is up. A caller that must wait for the provider belongs in
     * the connect callback ({@code onConnected}), not in a queue behind a socket that may never
     * arrive: a coin the backend does not serve must not park a caller until its timeout.
     */
    public CompletableFuture<JsonElement> request(String method, JsonArray params) {
        if (closed) {
            return CompletableFuture.failedFuture(
                    new ElectrumUnavailableException("client is closed"));
        }
        WebSocket current = socket;
        if (current == null || !handshaken) {
            return CompletableFuture.failedFuture(
                    new ElectrumUnavailableException("not connected to " + endpoint));
        }
        return sendOn(current, method, params);
    }

    @Override
    public List<CompletableFuture<JsonElement>> batch(List<Call> calls) {
        if (calls.isEmpty()) {
            return List.of();
        }
        WebSocket current = socket;
        if (closed || current == null || !handshaken) {
            return failedBatch(
                    calls.size(),
                    new ElectrumUnavailableException(
                            closed ? "client is closed" : "not connected to " + endpoint));
        }
        JsonArray payloads = new JsonArray();
        List<CompletableFuture<JsonElement>> futures = new ArrayList<>();
        for (Call call : calls) {
            JsonObject payload = new JsonObject();
            futures.add(prepare(payload, call.method(), call.params()));
            payloads.add(payload);
        }
        enqueue(current, payloads.toString());
        return futures;
    }

    private static List<CompletableFuture<JsonElement>> failedBatch(
            int size, RuntimeException error) {
        List<CompletableFuture<JsonElement>> failed = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            failed.add(CompletableFuture.failedFuture(error));
        }
        return failed;
    }

    /** Blocking convenience for the engine's single worker thread. */
    public JsonElement requestBlocking(String method, JsonArray params, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        return request(method, params).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        WebSocket current = socket;
        socket = null;
        handshaken = false;
        if (current != null) {
            current.abort();
        }
        failPending(new ElectrumUnavailableException("client is closed"));
        http.close();
    }

    // --- connection lifecycle -------------------------------------------

    private void connect() {
        // A scheduled retry can fire while a current socket is still alive; opening a
        // second one would replace it without closing the first.
        if (closed || socket != null) {
            return;
        }
        if (!connecting.compareAndSet(false, true)) {
            return;
        }
        try {
            WebSocket webSocket =
                    http.newWebSocketBuilder()
                            .connectTimeout(CONNECT_TIMEOUT)
                            .buildAsync(endpoint, new Socket())
                            .get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            socket = webSocket;
            startHandshake(webSocket);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            announceDown("interrupted while connecting");
            scheduleReconnect();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            announceDown(cause(e));
            scheduleReconnect();
        } finally {
            connecting.set(false);
        }
    }

    private void startHandshake(WebSocket webSocket) {
        scheduler.execute(() -> {
            try {
                JsonElement version =
                        sendOn(webSocket, "server.version", args(CLIENT_NAME, PROTOCOL))
                                .get(HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (!version.isJsonArray() || version.getAsJsonArray().size() != 2) {
                    throw new IllegalStateException("malformed server.version " + version);
                }
                sendOn(webSocket, "server.features", new JsonArray())
                        .get(HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (closed) {
                    webSocket.abort();
                    return;
                }
                backoffSeconds = 1;
                handshaken = true;
                announcedDown = false;
                LOGGER.info(
                        "[electrum] connected to "
                                + endpoint
                                + " ("
                                + version.getAsJsonArray().get(0).getAsString()
                                + ", protocol "
                                + version.getAsJsonArray().get(1).getAsString()
                                + ")");
                Listener current = listener;
                if (current != null) {
                    current.onConnected();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // connect() admits one socket at a time, so this is that socket.
                socket = null;
                handshaken = false;
                webSocket.abort();
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                // connect() admits one socket at a time, so this is that socket.
                socket = null;
                handshaken = false;
                webSocket.abort();
                announceDown("handshake failed: " + cause(e));
                scheduleReconnect();
            }
        });
    }

    /**
     * Reports a failed dial once per outage, then at FINE.
     *
     * <p>Seven unserved coins retrying forever must not fill the log with one line per attempt; the
     * state that matters is "this coin is unavailable, here is why", repeated only when it changes.
     */
    private void announceDown(String detail) {
        String message = "[electrum] " + endpoint.getPath() + " unavailable: " + detail;
        if (announcedDown) {
            LOGGER.fine(message);
        } else {
            announcedDown = true;
            LOGGER.warning(message);
        }
    }

    private void scheduleReconnect() {
        if (closed) {
            return;
        }
        int seconds = backoffSeconds;
        backoffSeconds = Math.min(backoffSeconds * 2, MAX_BACKOFF_SECONDS);
        long millis = Math.round(seconds * 1000 * jitter());
        LOGGER.fine("[electrum] reconnecting to " + endpoint + " in " + millis + "ms");
        scheduler.schedule(this::connect, millis, TimeUnit.MILLISECONDS);
    }

    /** ±20 % so several coins that lost the same backend do not retry in lockstep. */
    private static double jitter() {
        return 1.0
                - BACKOFF_JITTER
                + (ThreadLocalRandom.current().nextDouble() * 2 * BACKOFF_JITTER);
    }

    private void disconnected(WebSocket webSocket) {
        if (socket != webSocket) {
            return;
        }
        socket = null;
        handshaken = false;
        failPending(new ElectrumUnavailableException("connection to " + endpoint + " lost"));
        if (closed) {
            return;
        }
        announceDown("connection lost");
        Listener current = listener;
        if (current != null) {
            current.onDisconnected();
        }
        scheduleReconnect();
    }

    private void failPending(RuntimeException error) {
        for (Map.Entry<Long, CompletableFuture<JsonElement>> entry : pending.entrySet()) {
            CompletableFuture<JsonElement> future = pending.remove(entry.getKey());
            if (future != null) {
                future.completeExceptionally(error);
            }
        }
    }

    // --- request plumbing -------------------------------------------------

    private CompletableFuture<JsonElement> sendOn(
            WebSocket webSocket, String method, JsonArray params) {
        JsonObject payload = new JsonObject();
        CompletableFuture<JsonElement> result = prepare(payload, method, params);
        enqueue(webSocket, payload.toString());
        return result;
    }

    /** Assign an id, register the future and fill the request object. */
    private CompletableFuture<JsonElement> prepare(
            JsonObject payload, String method, JsonArray params) {
        long id = nextId.incrementAndGet();
        payload.addProperty("jsonrpc", "2.0");
        payload.addProperty("id", id);
        payload.addProperty("method", method);
        payload.add("params", params);

        CompletableFuture<JsonElement> result = new CompletableFuture<>();
        pending.put(id, result);
        result.orTimeout(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((value, error) -> {
                    if (error != null) {
                        pending.remove(id);
                    }
                });
        return result;
    }

    private void enqueue(WebSocket webSocket, String text) {
        synchronized (sendLock) {
            sendTail =
                    sendTail.exceptionally(error -> null)
                            .thenCompose(ignored -> webSocket.sendText(text, true));
        }
    }

    private void handleMessage(String message) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(message);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "[electrum] malformed message from " + endpoint, e);
            return;
        }
        if (parsed.isJsonArray()) {
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    handleObject(element.getAsJsonObject());
                }
            }
            return;
        }
        if (!parsed.isJsonObject()) {
            return;
        }
        handleObject(parsed.getAsJsonObject());
    }

    private void handleObject(JsonObject object) {
        try {
            if (object.has("method")) {
                notify(object);
                return;
            }
            if (!object.has("id") || object.get("id").isJsonNull()) {
                return;
            }
            long id = object.get("id").getAsLong();
            CompletableFuture<JsonElement> future = pending.remove(id);
            if (future == null) {
                return;
            }
            JsonElement error = object.get("error");
            if (error != null && !error.isJsonNull()) {
                JsonObject details = error.getAsJsonObject();
                future.completeExceptionally(
                        new ElectrumException(
                                details.get("code").getAsInt(),
                                details.has("message")
                                        ? details.get("message").getAsString()
                                        : ""));
                return;
            }
            future.complete(object.get("result"));
        } catch (RuntimeException e) {
            // One malformed frame must not abort the reader and drop the whole session.
            LOGGER.log(Level.WARNING, "[electrum] malformed message from " + endpoint, e);
        }
    }

    private void notify(JsonObject object) {
        String method = object.get("method").getAsString();
        JsonElement params = object.get("params");
        Listener current = listener;
        if (current == null) {
            return;
        }
        try {
            current.onNotification(
                    method,
                    params != null && params.isJsonArray()
                            ? params.getAsJsonArray()
                            : new JsonArray());
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "[electrum] notification handler failed for " + method, e);
        }
    }

    private static JsonArray args(Object... values) {
        JsonArray array = new JsonArray();
        for (Object value : values) {
            if (value instanceof String text) {
                array.add(text);
            } else if (value instanceof Number number) {
                array.add(number);
            } else {
                throw new IllegalArgumentException("unsupported request argument " + value);
            }
        }
        return array;
    }

    private static String cause(Exception error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = cause.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        if (cause instanceof WebSocketHandshakeException handshake) {
            return "HTTP " + handshake.getResponse().statusCode();
        }
        return cause.getClass().getSimpleName();
    }

    /** Reader for one connection. A new instance is built for every reconnect. */
    private final class Socket implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String message = partial.toString();
                partial.setLength(0);
                handleMessage(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            disconnected(webSocket);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOGGER.log(Level.INFO, "[electrum] socket error: " + error.getMessage());
            disconnected(webSocket);
        }
    }
}
