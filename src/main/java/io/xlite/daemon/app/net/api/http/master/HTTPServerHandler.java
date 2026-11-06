package io.xlite.daemon.app.net.api.http.master;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.subgraph.orchid.encoders.Base64;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.xlite.daemon.app.Version;
import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.util.ConfigHelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class HTTPServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private final ConfigHelper configHelper;

    public HTTPServerHandler() {
        configHelper = new ConfigHelper(ConfigHelper.MASTER_TICKER);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.warning("[http-master] Exception caught on channel: " + cause.getMessage());

        FullHttpResponse httpResponse =
                new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
        writeResponse(ctx, httpResponse, null);
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER)
                .addListener(ChannelFutureListener.CLOSE)
                .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        LOGGER.finer("[http-server-handler] DEBUG: Channel read complete. Flushing context.");
        super.channelReadComplete(ctx);
        ctx.flush();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (request == null) {
            return;
        }
        if (HttpUtil.is100ContinueExpected(request)) {
            send100Continue(ctx);
        }

        Rejection rejection = rejection(request);
        if (rejection != null) {
            writeAndClose(
                    ctx, request, rejection.status(), error(rejection.code(), rejection.message()));
            return;
        }

        JsonObject jsonRequest;
        try {
            jsonRequest = parseRequest(request.content().toString(CharsetUtil.UTF_8));
        } catch (RuntimeException e) {
            writeAndClose(
                    ctx,
                    request,
                    HttpResponseStatus.BAD_REQUEST,
                    parseFailure(request.content().toString(CharsetUtil.UTF_8), e));
            return;
        }

        String method = jsonRequest.get("method").getAsString();
        JsonArray params = jsonRequest.get("params").getAsJsonArray();
        // Master surface handles wallet management — params may contain passwords; never log
        // them verbatim. Ping is high-frequency liveness.
        if ("ping".equals(method.toLowerCase(Locale.ROOT))) {
            LOGGER.finer("[http-server-handler] RPC CALL: " + method + " PARAMS: <redacted>");
        } else {
            LOGGER.info("[http-server-handler] RPC CALL: " + method + " PARAMS: <redacted>");
        }
        JsonObject response = getResponse(method, params);
        LOGGER.finer(response.toString());

        FullHttpResponse httpResponse =
                new DefaultFullHttpResponse(
                        request.protocolVersion(),
                        HttpResponseStatus.OK,
                        Unpooled.copiedBuffer(response.toString(), CharsetUtil.UTF_8));
        if (!writeResponse(ctx, httpResponse, request)) {
            ctx.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * The first guard the request fails: authentication, then the HTTP shape. An unauthenticated
     * caller learns nothing about the wallet beyond the fact that it must authenticate.
     */
    private Rejection rejection(FullHttpRequest request) {
        if (!authorized(request)) {
            return new Rejection(HttpResponseStatus.FORBIDDEN, -1111, "Unauthorized!");
        }
        if (!HttpMethod.POST.equals(request.method())) {
            return new Rejection(
                    HttpResponseStatus.BAD_REQUEST, -1003, "Only HTTP POST is accepted.");
        }
        if (!request.uri().equals("/")) {
            return new Rejection(
                    HttpResponseStatus.BAD_REQUEST,
                    -1002,
                    "Only the server root ('/') is being served.");
        }
        return null;
    }

    private boolean authorized(FullHttpRequest request) {
        HttpHeaders headers = request.headers();
        if (!headers.contains("Authorization")) {
            return false;
        }
        String authorization = headers.get("Authorization");
        if (authorization == null || !authorization.toLowerCase(Locale.ROOT).startsWith("basic")) {
            return false;
        }
        String[] values =
                new String(
                                Base64.decode(authorization.substring("Basic".length()).trim()),
                                StandardCharsets.UTF_8)
                        .split(":", 2);
        if (values.length < 2) {
            LOGGER.warning("[http-server-handler] Malformed Basic Auth header");
            return false;
        }
        boolean authenticated =
                MessageDigest.isEqual(
                                values[0].getBytes(StandardCharsets.UTF_8),
                                configHelper.getRpcUsername().getBytes(StandardCharsets.UTF_8))
                        && MessageDigest.isEqual(
                                values[1].getBytes(StandardCharsets.UTF_8),
                                configHelper.getRpcPassword().getBytes(StandardCharsets.UTF_8));
        if (authenticated) {
            LOGGER.finer("[http-server-handler] Successful Auth");
        }
        return authenticated;
    }

    /** Parses the envelope, rejecting anything without a method and a parameter array. */
    private static JsonObject parseRequest(String content) {
        JsonObject json = JsonParser.parseString(content).getAsJsonObject();
        if (!json.has("method") || !json.has("params") || !json.get("params").isJsonArray()) {
            throw new IllegalArgumentException("Bad JSON-RPC request by client.");
        }
        return json;
    }

    private static JsonObject parseFailure(String content, RuntimeException cause) {
        // Same rule as the coin endpoint: a rejected body is never logged.
        LOGGER.info("[http-master] Failed to parse a " + content.length() + "-byte request body");
        LOGGER.warning("[http-master] Failed to parse JSON-RPC request" + cause.getMessage());
        if (cause instanceof IllegalArgumentException) {
            LOGGER.finer(
                    "[http-server-handler] WARNING: Client sent valid JSON, but did not specify"
                            + " method and/or parameters!");
        } else {
            LOGGER.finer("[http-server-handler] WARNING: Client sent invalid JSON!");
        }
        return error(-1001, "Error parsing JSON.");
    }

    public JsonObject getResponse(String method, JsonArray params) {
        String normalized = method == null ? "" : method.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "reloadconfig" -> reloadConfig(params);
            case "version" -> ok(new JsonPrimitive(Version.CLIENT_VERSION));
            case "ping" -> ok(new JsonPrimitive(1));
            case "help" -> help();
            case "stop" -> stop();
            case "getcoins", "listcoins" -> coinConfigs(params);
            default -> error(-32601, "Method not found.");
        };
    }

    private static JsonObject help() {
        return ok(
                new JsonPrimitive(
                        "Master JSON-RPC server\n"
                                + "This JSON-RPC server is served by "
                                + CoinInstance.getVersionString()
                                + "\n"
                                + "\n"
                                + "help - Display the help\n"
                                + "\n"
                                + "=====RPC Master=====\n"
                                + "ping - Lightweight liveness probe (result 1)\n"
                                + "stop - Shutdown the server\n"
                                + "reloadconfig <token> - Reload configuration for specified"
                                + " token\n"
                                + "getCoins - List coin configurations (alias listCoins)\n"
                                + "version - Get version\n"));
    }

    private JsonObject reloadConfig(JsonArray params) {
        if (params.size() != 1) {
            return error(-1, "Usage: reloadconfig <token>\n\ntoken (string, required)");
        }
        String requested = params.get(0).getAsString();
        CoinTicker ticker = CoinTickerUtils.stringToTicker(requested);
        if (ticker == null) {
            return error(-1, "Unknown ticker: " + requested);
        }
        CoinInstance instance = CoinInstance.getInstance(ticker);
        if (instance == null) {
            return error(
                    -1,
                    "Coin instance not found for ticker: "
                            + CoinTickerUtils.tickerToString(ticker));
        }
        Thread reloader =
                new Thread(() -> {
                    try {
                        Thread.sleep(500);
                        instance.reloadConfig();
                    } catch (InterruptedException e) {
                        LOGGER.warning(
                                "[http-master] Interrupted during reloadconfig for "
                                        + ticker
                                        + ", "
                                        + e.getMessage());
                        Thread.currentThread().interrupt();
                    }
                });
        reloader.setDaemon(true);
        reloader.start();
        return ok(new JsonPrimitive(true));
    }

    private static JsonObject stop() {
        Thread shutdown = new Thread(() -> System.exit(0));
        shutdown.setDaemon(true);
        shutdown.start();
        return ok(new JsonPrimitive("shutting down..."));
    }

    private static JsonObject coinConfigs(JsonArray params) {
        if (params == null || params.size() != 0) {
            return error(-1, "Usage: getCoins");
        }
        // Atomic snapshot — avoids isLoaded()/list() TOCTOU; empty check is the gate.
        Map<String, CoinConfig> snapshot = CoinConfigRegistry.list();
        if (snapshot.isEmpty()) {
            return error(-1, "Coin configs not loaded");
        }
        try {
            return ok(coinConfigs(snapshot));
        } catch (IllegalStateException e) {
            LOGGER.warning("[http-master] getCoins DTO error: " + e.getMessage());
            return error(-1, e.getMessage());
        }
    }

    /** Single DTO source: delegates to {@link CoinConfig#toDtoMap()}. */
    private static JsonArray coinConfigs(Map<String, CoinConfig> snapshot) {
        JsonArray coins = new JsonArray();
        snapshot.values().stream()
                .sorted(Comparator.comparing(CoinConfig::getTicker))
                .forEach(config -> coins.add(coinConfig(config)));
        return coins;
    }

    private static JsonObject coinConfig(CoinConfig config) {
        Map<String, Object> dto = config.toDtoMap();
        JsonObject coin = new JsonObject();
        coin.addProperty("ticker", (String) dto.get("ticker"));
        coin.addProperty("blockchain", (String) dto.get("blockchain"));
        coin.addProperty("verId", (String) dto.get("verId"));
        coin.addProperty("addressPrefix", ((Number) dto.get("addressPrefix")).intValue());
        coin.addProperty("scriptPrefix", ((Number) dto.get("scriptPrefix")).intValue());
        coin.addProperty("secretPrefix", ((Number) dto.get("secretPrefix")).intValue());
        coin.addProperty("coin", ((Number) dto.get("coin")).longValue());
        coin.addProperty("feePerByte", ((Number) dto.get("feePerByte")).longValue());
        coin.addProperty("minTxFee", ((Number) dto.get("minTxFee")).longValue());
        coin.addProperty("port", ((Number) dto.get("port")).intValue());
        coin.addProperty("dustAmount", ((Number) dto.get("dustAmount")).longValue());
        return coin;
    }

    private static JsonObject ok(JsonElement result) {
        JsonObject response = new JsonObject();
        response.add("result", result);
        response.add("error", JsonNull.INSTANCE);
        return response;
    }

    private static JsonObject error(int code, String message) {
        JsonObject response = new JsonObject();
        response.add("result", JsonNull.INSTANCE);
        JsonObject detail = new JsonObject();
        detail.addProperty("code", code);
        detail.addProperty("message", message);
        response.add("error", detail);
        return response;
    }

    private void writeAndClose(
            ChannelHandlerContext ctx,
            FullHttpRequest request,
            HttpResponseStatus status,
            JsonObject body) {
        ByteBuf content = Unpooled.copiedBuffer(body.toString(), CharsetUtil.UTF_8);
        FullHttpResponse response =
                new DefaultFullHttpResponse(request.protocolVersion(), status, content);
        writeResponse(ctx, response, request);
        ctx.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }

    /** Why a request was refused before its body was looked at. */
    private record Rejection(HttpResponseStatus status, int code, String message) {}

    private boolean writeResponse(
            ChannelHandlerContext ctx, FullHttpResponse httpResponse, FullHttpRequest request) {
        boolean keepAlive = false;
        if (request != null) {
            keepAlive = HttpUtil.isKeepAlive(request);
        }

        httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        httpResponse
                .headers()
                .setInt(HttpHeaderNames.CONTENT_LENGTH, httpResponse.content().readableBytes());
        httpResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        httpResponse.headers().set(HttpHeaderNames.SERVER, CoinInstance.getVersionString());

        LOGGER.finer("[http-server-handler] Writing response to channel. Keep alive? " + keepAlive);
        LOGGER.finer(
                "[http-server-handler] Response content: "
                        + httpResponse.content().toString(CharsetUtil.UTF_8));
        ctx.write(httpResponse).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);

        return keepAlive;
    }

    private static void send100Continue(ChannelHandlerContext ctx) {
        FullHttpResponse response =
                new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE);
        ctx.write(response).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }
}
