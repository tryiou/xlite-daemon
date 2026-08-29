package io.xlite.daemon.app.net.api.http.master;

import com.google.common.base.Preconditions;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.subgraph.orchid.encoders.Base64;
import io.xlite.daemon.app.Version;
import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.util.ConfigHelper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class HTTPServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private ConfigHelper configHelper;

    public HTTPServerHandler() {
        configHelper = new ConfigHelper("master");

        if (configHelper.getRpcUsername().isEmpty() && configHelper.getRpcPassword().isEmpty()) {
            configHelper.setRpcUsername(generateRandomString(12));
            configHelper.setRpcPassword(generateRandomString(32));

            configHelper.writeConfig();
        }
    }

    private String generateRandomString(int length) {
        SecureRandom secureRandom = new SecureRandom();

        byte[] token = new byte[length];
        secureRandom.nextBytes(token);

        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.warning("[http-master] Exception caught on channel: " + cause.getMessage());

        FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
        writeResponse(ctx, httpResponse, null);
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        ctx.close();
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        LOGGER.finer("[http-server-handler] DEBUG: Channel read complete. Flushing context.");
        super.channelReadComplete(ctx);
        ctx.flush();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        boolean successfulAuth = false;
        HttpResponseStatus status = HttpResponseStatus.OK;
        JsonObject response = new JsonObject();

        if (request != null) {
            HttpHeaders httpHeaders = request.headers();

            if (HttpUtil.is100ContinueExpected(request)) {
                send100Continue(ctx);
            }

            String headerUser;
            String headerPass;
            if (httpHeaders.contains("Authorization")) {
                String authorization = httpHeaders.get("Authorization");

                if (authorization != null && authorization.toLowerCase().startsWith("basic")) {
                    String base64Credentials = authorization.substring("Basic".length()).trim();
                    byte[] credDecoded = Base64.decode(base64Credentials);
                    String credentials = new String(credDecoded, StandardCharsets.UTF_8);
                    final String[] values = credentials.split(":", 2);
                    if (values.length < 2) {
                        LOGGER.warning("[http-server-handler] Malformed Basic Auth header");
                    } else {
                        headerUser = values[0];
                        headerPass = values[1];

                        if (headerUser.equals(configHelper.getRpcUsername()) && headerPass.equals(configHelper.getRpcPassword())) {
                            successfulAuth = true;
                            LOGGER.finer("[http-server-handler] Successful Auth");
                        }
                    }
                }
            }

            if (!request.uri().equals("/")) {
                JsonObject onlyServerRootJSON = new JsonObject();
                onlyServerRootJSON.addProperty("code", -1002);
                onlyServerRootJSON.addProperty("message", "Only the server root ('/') is being served.");

                response.add("error", onlyServerRootJSON);
                response.add("result", JsonNull.INSTANCE);
                status = HttpResponseStatus.BAD_REQUEST;
            }

            if (request.method() != HttpMethod.POST) {
                JsonObject onlyPostAllowedJSON = new JsonObject();

                onlyPostAllowedJSON.addProperty("code", -1003);
                onlyPostAllowedJSON.addProperty("message", "Only HTTP POST is accepted.");

                response.add("error", onlyPostAllowedJSON);
                response.add("result", JsonNull.INSTANCE);
                status = HttpResponseStatus.BAD_REQUEST;
            }

            if (!successfulAuth) {
                JsonObject onlyServerRootJSON = new JsonObject();
                onlyServerRootJSON.addProperty("code", -1111);
                onlyServerRootJSON.addProperty("message", "Unauthorized!");

                response.add("error", onlyServerRootJSON);
                response.add("result", JsonNull.INSTANCE);
                status = HttpResponseStatus.FORBIDDEN;
            }
        }

        if (status != HttpResponseStatus.OK) {
            ByteBuf responseContent = Unpooled.copiedBuffer(response.toString(), CharsetUtil.UTF_8);
            FullHttpResponse httpResponse = new DefaultFullHttpResponse(request.protocolVersion(), status, responseContent);

            writeResponse(ctx, httpResponse, request);
            ctx.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        if (request != null) {
            String content = request.content().toString(CharsetUtil.UTF_8);
            JsonObject jsonReq = null;

            try {
                jsonReq = JsonParser.parseString(content).getAsJsonObject();

                Preconditions.checkNotNull(jsonReq);

                if (!jsonReq.has("method") || !jsonReq.has("params")) {
                    ctx.close();
                    throw new IllegalArgumentException("Bad JSON-RPC request by client.");
                }
            } catch (Exception e) {
                LOGGER.info("Failed Content: " + content);
                LOGGER.warning("[http-master] Failed to parse JSON-RPC request" + e.getMessage());
                JsonObject errorParsingJSON = new JsonObject();
                errorParsingJSON.addProperty("code", -1001);
                errorParsingJSON.addProperty("message", "Error parsing JSON.");

                response.add("error", errorParsingJSON);
                response.add("result", JsonNull.INSTANCE);
                if (e instanceof IllegalArgumentException) {
                    LOGGER.finer("[http-server-handler] WARNING: Client sent valid JSON, but did not specify method and/or parameters!");
                } else {
                    LOGGER.finer("[http-server-handler] WARNING: Client sent invalid JSON!");
                }
                status = HttpResponseStatus.BAD_REQUEST;
            }

            if (status == HttpResponseStatus.OK) {
                Preconditions.checkNotNull(jsonReq);

                String method = jsonReq.get("method").getAsString();
                JsonArray params = jsonReq.get("params").getAsJsonArray();

                // Master surface handles wallet management — params may contain
                // passwords; never log them verbatim. Ping is high-frequency liveness.
                String methodLower = method == null ? null : method.toLowerCase(Locale.ROOT);
                if ("ping".equals(methodLower)) {
                    LOGGER.finer("[http-server-handler] RPC CALL: " + method + " PARAMS: <redacted>");
                } else {
                    LOGGER.info("[http-server-handler] RPC CALL: " + method + " PARAMS: <redacted>");
                }

                response = getResponse(method, params);
                LOGGER.finer(response.toString());
            } else {
                ByteBuf responseContent = Unpooled.copiedBuffer(response.toString(), CharsetUtil.UTF_8);
                FullHttpResponse httpResponse = new DefaultFullHttpResponse(request.protocolVersion(), status, responseContent);

                writeResponse(ctx, httpResponse, request);
                ctx.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            if (request instanceof LastHttpContent) {
                ByteBuf responseContent = Unpooled.copiedBuffer(response.toString(), CharsetUtil.UTF_8);

                FullHttpResponse httpResponse = new DefaultFullHttpResponse(request.protocolVersion(), status, responseContent);

                if (!writeResponse(ctx, httpResponse, request)) {
                    ctx.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                }
            }
        }
    }

    public JsonObject getResponse(String method, JsonArray params) {
        JsonObject response = new JsonObject();
        boolean shutdownRequested = false;

        String normalizedMethod = method == null ? "" : method.toLowerCase(Locale.ROOT);
        switch (normalizedMethod) {
            case "reloadconfig": {
                if (params.size() != 1) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Usage: reloadconfig <token>\n\ntoken (string, required)");

                    response.add("error", errorJSON);
                    break;
                }

                CoinTicker ticker = CoinTickerUtils.stringToTicker(params.get(0).getAsString());
                if (ticker == null) {
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Unknown ticker: " + params.get(0).getAsString());
                    response.add("error", errorJSON);
                    break;
                }

                CoinInstance instance = CoinInstance.getInstance(ticker);
                if (instance == null) {
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Coin instance not found for ticker: " + CoinTickerUtils.tickerToString(ticker));
                    response.add("error", errorJSON);
                    break;
                }

                Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(500);
                        instance.reloadConfig();
                    } catch (InterruptedException e) {
                        LOGGER.warning("[http-master] Interrupted during reloadconfig for " + ticker + ", " + e.getMessage());
                    }
                });
                t.setDaemon(true);
                t.start();

                response.addProperty("result", true);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "version": {
                response.addProperty("result", Version.CLIENT_VERSION);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
//			case "reloadconfigs": {
//				boolean success = true;
//				for (CoinInstance instance : CoinInstance.getCoinInstances()) {
//					if (!CoinTickerUtils.isActiveTicker(instance.getTicker()))
//						continue;
//
//					try {
//						LOGGER.info(instance.getTicker().toString());
//						instance.reloadConfig();
//					} catch (Exception e) {
//						success = false;
//						e.printStackTrace();
//					}
//				}
//
//				response.addProperty("result", success);
//				response.add("error", JsonNull.INSTANCE);
//				break;
//			}
            case "ping": {
                response.addProperty("result", 1);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "help": {
                String helpString = "Master JSON-RPC server\n"
                        + "This JSON-RPC server is served by " + CoinInstance.getVersionString() + "\n"
                        + "\n"
                        + "help - Display the help\n"
                        + "\n=====RPC Master=====\n"
                        + "ping - Lightweight liveness probe (result 1)\n"
                        + "stop - Shutdown the server\n"
                        + "reloadconfig <token> - Reload configuration for specified token\n"
                        + "getCoins - List coin configurations (alias listCoins)\n"
                        + "version - Get version\n";
//						+ "reloadconfigs - Reload all configuration files\n";

                response.addProperty("result", helpString);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "stop": {
                shutdownRequested = true;
                response.addProperty("result", "shutting down...");
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "getcoins":
            case "listcoins": {
                if (params == null || params.size() != 0) {
                    JsonObject err = new JsonObject();
                    err.addProperty("code", -1);
                    err.addProperty("message", "Usage: getCoins");
                    response.add("error", err);
                    response.add("result", JsonNull.INSTANCE);
                    break;
                }
                // Atomic snapshot — avoids isLoaded()/list() TOCTOU; empty check is the gate.
                java.util.Map<String, CoinConfig> snap = CoinConfigRegistry.list();
                if (snap.isEmpty()) {
                    JsonObject err = new JsonObject();
                    err.addProperty("code", -1);
                    err.addProperty("message", "Coin configs not loaded");
                    response.add("error", err);
                    response.add("result", JsonNull.INSTANCE);
                    break;
                }
                // Single DTO source — delegate to CoinConfig.toDtoMap() (authenticated via channelRead0)
                try {
                    JsonArray arr = new JsonArray();
                    snap.values().stream()
                            .sorted(java.util.Comparator.comparing(CoinConfig::getTicker))
                            .forEach(cfg -> {
                                java.util.Map<String, Object> dto = cfg.toDtoMap();
                                JsonObject o = new JsonObject();
                                o.addProperty("ticker", (String) dto.get("ticker"));
                                o.addProperty("blockchain", (String) dto.get("blockchain"));
                                o.addProperty("verId", (String) dto.get("verId"));
                                o.addProperty("addressPrefix", ((Number) dto.get("addressPrefix")).intValue());
                                o.addProperty("scriptPrefix", ((Number) dto.get("scriptPrefix")).intValue());
                                o.addProperty("secretPrefix", ((Number) dto.get("secretPrefix")).intValue());
                                o.addProperty("coin", ((Number) dto.get("coin")).longValue());
                                o.addProperty("feePerByte", ((Number) dto.get("feePerByte")).longValue());
                                o.addProperty("minTxFee", ((Number) dto.get("minTxFee")).longValue());
                                o.addProperty("port", ((Number) dto.get("port")).intValue());
                                Object dust = dto.get("dustAmount");
                                if (dust == null)
                                    o.add("dustAmount", JsonNull.INSTANCE);
                                else
                                    o.addProperty("dustAmount", ((Number) dust).longValue());
                                arr.add(o);
                            });
                    response.add("result", arr);
                    response.add("error", JsonNull.INSTANCE);
                } catch (IllegalStateException e) {
                    LOGGER.warning("[http-master] getCoins DTO error: " + e.getMessage());
                    JsonObject err = new JsonObject();
                    err.addProperty("code", -1);
                    err.addProperty("message", e.getMessage());
                    response.add("error", err);
                    response.add("result", JsonNull.INSTANCE);
                }
                break;
            }
            default: {
                JsonObject methodNotFound = new JsonObject();
                methodNotFound.addProperty("code", -32601);
                methodNotFound.addProperty("message", "Method not found.");
                response.add("error", methodNotFound);
                response.add("result", JsonNull.INSTANCE);
                break;
            }
        }

        if (shutdownRequested) {
            Thread t = new Thread(() -> System.exit(0));
            t.setDaemon(true);
            t.start();
        }
        return response;
    }

    private boolean writeResponse(ChannelHandlerContext ctx, FullHttpResponse httpResponse, FullHttpRequest request) {
        boolean keepAlive = false;
        if (request != null)
            keepAlive = HttpUtil.isKeepAlive(request);

        httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        httpResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, httpResponse.content().readableBytes());
        httpResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        httpResponse.headers().set(HttpHeaderNames.SERVER, CoinInstance.getVersionString());

        LOGGER.finer("[http-server-handler] Writing response to channel. Keep alive? " + keepAlive);
        LOGGER.finer("[http-server-handler] Response content: " + httpResponse.content().toString(CharsetUtil.UTF_8));
        ctx.write(httpResponse);

        return keepAlive;
    }

    private static void send100Continue(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE);
        ctx.write(response);
    }
}
