package io.xlite.daemon.app.net.api.http.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Builds the {@code result}/{@code error} envelope every RPC method returns.
 *
 * <p>Centralised because the shape is a contract: exactly one member is non-null, and an error
 * always carries an integer code and a message string. A method that composed its own envelope
 * could break either half without any test noticing until a client did.
 */
final class RpcResponse {

    private RpcResponse() {}

    /** A successful call. */
    static JsonObject ok(JsonElement result) {
        JsonObject response = new JsonObject();
        response.add("result", result);
        response.add("error", JsonNull.INSTANCE);
        return response;
    }

    static JsonObject ok(String result) {
        return ok(new JsonPrimitive(result));
    }

    static JsonObject ok(boolean result) {
        return ok(new JsonPrimitive(result));
    }

    static JsonObject ok(long result) {
        return ok(new JsonPrimitive(result));
    }

    /** A failed call. */
    static JsonObject error(int code, String message) {
        JsonObject response = new JsonObject();
        response.add("result", JsonNull.INSTANCE);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        return response;
    }

    /** A call rejected for its arguments; every such rejection uses {@code -1}. */
    static JsonObject usage(String message) {
        return error(-1, message);
    }

    /** Whether a provider envelope reports an error. */
    static boolean hasError(JsonObject envelope) {
        return envelope.has("error") && !envelope.get("error").isJsonNull();
    }

    /**
     * The failure described by a provider envelope, accepting both a legacy integer and a {@code
     * {code, message}} object, which is what the hosted backend and the plugin adapter return
     * respectively.
     */
    static ProviderError providerError(
            JsonObject envelope, int fallbackCode, String fallbackMessage) {
        if (envelope == null || !hasError(envelope)) {
            return new ProviderError(fallbackCode, fallbackMessage);
        }
        JsonElement error = envelope.get("error");
        if (error.isJsonPrimitive()) {
            return new ProviderError(error.getAsInt(), fallbackMessage);
        }
        JsonObject detail = error.getAsJsonObject();
        int code = detail.has("code") ? detail.get("code").getAsInt() : fallbackCode;
        String message =
                detail.has("message") ? detail.get("message").getAsString() : fallbackMessage;
        return new ProviderError(code, message);
    }

    /** The provider's payload: its {@code result} when it has one, the whole envelope otherwise. */
    static JsonObject fromEnvelope(JsonObject envelope) {
        return ok(envelope.has("result") ? envelope.get("result") : envelope);
    }

    /** An error an upstream provider reported. */
    record ProviderError(int code, String message) {}
}
