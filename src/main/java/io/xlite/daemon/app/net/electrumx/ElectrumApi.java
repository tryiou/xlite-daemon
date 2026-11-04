package io.xlite.daemon.app.net.electrumx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The native transport the history engine depends on.
 *
 * <p>Kept as an interface so the engine's state machine can be tested without a socket: the client
 * owns reconnection and framing, the engine owns what a changed script hash means.
 */
public interface ElectrumApi extends AutoCloseable {

    /** One request in a JSON-RPC batch. */
    record Call(String method, JsonArray params) {}

    void setListener(ElectrumClient.Listener listener);

    void start();

    /** Whether the session already completed its handshake. */
    boolean isConnected();

    CompletableFuture<JsonElement> request(String method, JsonArray params);

    /**
     * Send several requests in one frame. Each element completes (or fails) on its own, so one
     * missing result does not discard the rest.
     */
    List<CompletableFuture<JsonElement>> batch(List<Call> calls);

    @Override
    void close();
}
