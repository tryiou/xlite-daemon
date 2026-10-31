package io.xlite.daemon.app.net.api.http.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** One JSON-RPC method served by a coin endpoint. */
interface RpcMethod {

    /**
     * The envelope for one call: exactly one of {@code result} and {@code error} is non-null.
     *
     * <p>A malformed argument may throw; the dispatcher turns that into an invalid-params error, so
     * a method reports argument shapes it can describe with {@link RpcResponse} and lets the rest
     * propagate.
     */
    JsonObject invoke(JsonArray params);
}
