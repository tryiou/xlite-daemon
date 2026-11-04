package io.xlite.daemon.app.net.api.http.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.net.api.http.client.HTTPClient;
import io.xlite.daemon.app.util.ConfigHelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * The per-coin JSON-RPC endpoint: authenticates the request, parses the JSON-RPC envelope, and
 * dispatches to the method that implements it.
 *
 * <p>Methods are grouped into family classes under this package, each registered in a map keyed by
 * method name. This class owns only the transport concerns -- HTTP guards, the envelope, logging
 * and redaction -- so a method can be read, tested and changed without the framing around it.
 */
public final class HTTPServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    /** Methods whose params or responses contain key material — never logged. */
    private static final Set<String> SENSITIVE_METHODS =
            Set.of("importprivkey", "dumpprivkey", "signmessage");

    /**
     * Methods that answer from local wallet state and therefore keep working while the provider is
     * not serving this coin.
     *
     * <p>Everything else reads chain data and stays behind the {@code 503 -1112} gate: a wallet
     * whose indexer is down can still report who it is and what it is waiting for, but it must not
     * answer "no unspents" for a chain it cannot see.
     */
    private static final Set<String> LOCAL_METHODS =
            Set.of(
                    "help",
                    "version",
                    "ping",
                    "reloadconfig",
                    "getinfo",
                    "getnetworkinfo",
                    "getfees",
                    "getblockchaininfo",
                    "getblockcount",
                    "getaddressesbyaccount",
                    "getnewaddress",
                    "importprivkey",
                    "dumpprivkey",
                    "validateaddress",
                    "historystatus",
                    "signmessage",
                    "verifymessage");

    private final CoinInstance coin;
    private final ConfigHelper configHelper;
    private final HTTPClient httpClient;
    private final Map<String, RpcMethod> methods;

    HTTPServerHandler(CoinInstance coin) {
        this(coin, new HTTPClient());
    }

    /** The client is owned by the caller, which closes it with the channel. */
    HTTPServerHandler(CoinInstance coin, HTTPClient httpClient) {
        this.coin = coin;
        this.configHelper = coin.getConfigHelper();
        this.httpClient = httpClient;
        this.methods = dispatch(coin, httpClient);
    }

    private static Map<String, RpcMethod> dispatch(CoinInstance coin, HTTPClient httpClient) {
        WalletUtxos utxos = new WalletUtxos(coin, httpClient);
        NTimeSource nTimes = new NTimeSource(coin, httpClient);
        Map<String, RpcMethod> methods = new HashMap<>();
        register(methods, new ServerMethods(coin).methods());
        register(methods, new NetworkMethods(coin).methods());
        register(methods, new ChainMethods(coin, httpClient).methods());
        register(methods, new WalletMethods(coin).methods());
        register(methods, new UtxoMethods(coin, httpClient, utxos).methods());
        register(methods, new RawTransactionMethods(coin, httpClient, utxos, nTimes).methods());
        register(methods, new SendTransactionMethod(coin, httpClient, utxos, nTimes).methods());
        register(methods, new MessageMethods(coin).methods());
        return Map.copyOf(methods);
    }

    /** Merges one family, refusing a name another family already registered. */
    private static void register(Map<String, RpcMethod> into, Map<String, RpcMethod> family) {
        for (Map.Entry<String, RpcMethod> method : family.entrySet()) {
            if (into.put(method.getKey(), method.getValue()) != null) {
                throw new IllegalStateException("RPC method registered twice: " + method.getKey());
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        this.httpClient.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.warning(
                "[http-server-handler] Exception caught on channel for "
                        + CoinTickerUtils.tickerToString(coin.getTicker())
                        + ", "
                        + cause.getMessage());

        FullHttpResponse httpResponse =
                new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
        writeResponse(ctx, httpResponse, null);
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        ctx.close().addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        this.httpClient.close();
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
                    ctx,
                    request,
                    rejection.status(),
                    RpcResponse.error(rejection.code(), rejection.message()));
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

        JsonObject response;
        String method = jsonRequest.get("method").getAsString();
        String normalizedMethod = method.toLowerCase(Locale.ROOT);
        if (!coin.isInstanceRunning()
                && !LOCAL_METHODS.contains(normalizedMethod)
                && methods.containsKey(normalizedMethod)) {
            // Same response the gate always produced, now scoped to the methods that need chain
            // data. A local method must answer or the caller cannot even see that the coin exists.
            writeAndClose(
                    ctx,
                    request,
                    HttpResponseStatus.SERVICE_UNAVAILABLE,
                    RpcResponse.error(-1112, "This coin is temporarily unavailable."));
            return;
        }
        response = invoke(method, jsonRequest.get("params").getAsJsonArray());

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
     * The transport guards: authentication, then the HTTP shape.
     *
     * <p>Authentication is checked first: an unauthenticated caller learns nothing about the wallet
     * beyond the fact that it must authenticate, whatever else is wrong with the request. Whether
     * the coin can serve chain data is a per-method question, decided after the envelope is parsed,
     * so local wallet methods keep answering while the provider is down.
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
        byte[] decoded = Base64.decode(authorization.substring("Basic".length()).trim());
        String[] values = new String(decoded, StandardCharsets.UTF_8).split(":", 2);
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

    /**
     * Parses the JSON-RPC envelope, rejecting anything that is not an object with a string {@code
     * method} and an optional array {@code params}.
     */
    private static JsonObject parseRequest(String content) {
        JsonObject json = JsonParser.parseString(content).getAsJsonObject();
        if (!json.has("method")
                || !json.get("method").isJsonPrimitive()
                || !json.get("method").getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Bad JSON-RPC request by client.");
        }
        if (!json.has("params") || json.get("params").isJsonNull()) {
            json.add("params", new JsonArray());
        } else if (!json.get("params").isJsonArray()) {
            throw new IllegalArgumentException("Bad JSON-RPC request by client.");
        }
        return json;
    }

    private JsonObject parseFailure(String content, RuntimeException cause) {
        LOGGER.info("Failed Content: " + content);
        LOGGER.warning(
                "[http-server-handler] Failed to parse JSON-RPC request for "
                        + CoinTickerUtils.tickerToString(coin.getTicker())
                        + ", "
                        + cause.getMessage());
        if (cause instanceof IllegalArgumentException) {
            LOGGER.warning(
                    "[http-server-handler] WARNING: Client sent valid JSON, but did not specify"
                            + " method and/or parameters!");
        } else {
            LOGGER.warning("[http-server-handler] WARNING: Client sent invalid JSON!");
        }
        return RpcResponse.error(-1001, "Error parsing JSON.");
    }

    /**
     * Runs one method, turning any failure into the error envelope.
     *
     * <p>A method may throw on malformed arguments; that must surface as a response rather than as
     * a dropped connection, and the caller must not be shown key material in the reply log.
     */
    private JsonObject invoke(String method, JsonArray params) {
        String methodLower = method.toLowerCase(Locale.ROOT);
        boolean sensitive = SENSITIVE_METHODS.contains(methodLower);
        logCall(method, methodLower, params, sensitive);

        JsonObject response;
        try {
            RpcMethod rpc = methods.get(methodLower);
            response = rpc == null ? methodNotFound() : rpc.invoke(params);
        } catch (RuntimeException e) {
            LOGGER.warning(
                    "[http-server-handler] Invalid params for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + " "
                            + method
                            + ", "
                            + e.getMessage());
            response = RpcResponse.error(-1, "Invalid params: " + e.getMessage());
        }

        if (sensitive) {
            LOGGER.finer("[http-server-handler] response withheld (sensitive method)");
        } else {
            LOGGER.finer(response.toString());
        }
        return response;
    }

    private void logCall(String method, String methodLower, JsonArray params, boolean sensitive) {
        String renderedParams = params.toString().replace(",", ", ");
        if ("ping".equals(methodLower)) {
            // Liveness probes are frequent and their params carry nothing worth guarding.
            LOGGER.finer(
                    "[http-server-handler] RPC CALL: "
                            + coin.getTicker()
                            + " "
                            + method
                            + " PARAMS: "
                            + renderedParams);
            return;
        }
        LOGGER.info(
                "[http-server-handler] RPC CALL: "
                        + coin.getTicker()
                        + " "
                        + method
                        + " PARAMS: "
                        + (sensitive ? "<redacted>" : renderedParams));
    }

    private static JsonObject methodNotFound() {
        return RpcResponse.error(-32601, "Method not found.");
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

    /** Why a request was refused before its body was looked at. */
    private record Rejection(HttpResponseStatus status, int code, String message) {}
}
