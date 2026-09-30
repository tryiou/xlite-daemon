package io.xlite.daemon.app.net.api.http.server;

import com.google.common.base.Preconditions;
import com.google.gson.*;
import com.subgraph.orchid.encoders.Base64;
import com.subgraph.orchid.encoders.Hex;
import io.xlite.daemon.app.Version;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.net.api.http.client.HTTPClient;
import io.xlite.daemon.app.net.protocols.blocknet.BlocknetPeer;
import io.xlite.daemon.app.util.AddressBalance;
import io.xlite.daemon.app.util.ConfigHelper;
import io.xlite.daemon.app.util.Sats;
import io.xlite.daemon.app.util.TxTimeField;
import io.xlite.daemon.app.util.TxTimeFieldSigner;
import io.xlite.daemon.app.util.UTXO;
import io.xlite.daemon.app.util.Utility;
import io.xlite.daemon.app.wallet.WalletHelper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.bitcoinj.core.*;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.LogManager;
import java.util.logging.Logger;

// Define a helper class for output entries
class OutputEntry {
    public String address;
    public double amount;

    public OutputEntry(String address, double amount) {
        this.address = address;
        this.amount = amount;
    }
}

public class HTTPServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    /**
     * How long a fetched unspent set stays usable before a caller refreshes it.
     * Shared so every path that reads unspent outputs ages the cache the same
     * way; a send that used a different window would either over-fetch or read
     * a staler set than the one listunspent just showed.
     */
    private static final int UTXO_FETCH_EXPIRY_MS = 30000;

    // RPC methods whose params/responses contain key material — never log them.
    private static final Set<String> SENSITIVE_METHODS = Set.of(
            "importprivkey", "dumpprivkey", "signmessage");

    /**
     * Serializes sends for this coin across every connection.
     *
     * <p>Inputs are marked spent only after the broadcast succeeds, so between
     * selecting them and marking them another request could select the same
     * outputs and build a second transaction spending them. Netty creates a
     * handler per accepted channel, so a field on this class would be a
     * different lock per connection and would never exclude anything; the lock
     * is therefore per coin, held on the coin's own state.
     *
     * <p>Held across the network round trip on purpose. Releasing before the
     * broadcast reopens exactly the window this closes, and a send is not
     * latency-critical enough to trade a double spend for.
     */
    private static final java.util.Map<CoinTicker, java.util.concurrent.locks.ReentrantLock> SEND_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static java.util.concurrent.locks.ReentrantLock sendLockFor(CoinTicker ticker) {
        return SEND_LOCKS.computeIfAbsent(ticker,
                k -> new java.util.concurrent.locks.ReentrantLock());
    }

    private HTTPClient httpClient;
    private CoinInstance coin;
    private ConfigHelper configHelper;

    HTTPServerHandler(CoinInstance coin) {
        this.coin = coin;
        this.configHelper = coin.getConfigHelper();
        this.httpClient = new HTTPClient(5);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        this.httpClient.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.warning("[http-server-handler] Exception caught on channel for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + cause.getMessage());

        FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
        writeResponse(ctx, httpResponse, null);
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        ctx.close();
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

                        if (MessageDigest.isEqual(headerUser.getBytes(StandardCharsets.UTF_8),
                                configHelper.getRpcUsername().getBytes(StandardCharsets.UTF_8))
                                && MessageDigest.isEqual(headerPass.getBytes(StandardCharsets.UTF_8),
                                configHelper.getRpcPassword().getBytes(StandardCharsets.UTF_8))) {
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

            if (!coin.isInstanceRunning()) {
                JsonObject instanceNotRunning = new JsonObject();

                instanceNotRunning.addProperty("code", -1112);
                instanceNotRunning.addProperty("message", "This coin is temporarily unavailable.");

                response.add("error", instanceNotRunning);
                response.add("result", JsonNull.INSTANCE);
                status = HttpResponseStatus.SERVICE_UNAVAILABLE;
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

                if (!jsonReq.has("method") || !jsonReq.get("method").isJsonPrimitive()
                        || !jsonReq.get("method").getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("Bad JSON-RPC request by client.");
                }
                if (!jsonReq.has("params") || jsonReq.get("params").isJsonNull()) {
                    jsonReq.add("params", new JsonArray());
                } else if (!jsonReq.get("params").isJsonArray()) {
                    throw new IllegalArgumentException("Bad JSON-RPC request by client.");
                }
            } catch (Exception e) {
                LOGGER.info("Failed Content: " + content);
                LOGGER.warning("[http-server-handler] Failed to parse JSON-RPC request for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());
                JsonObject errorParsingJSON = new JsonObject();
                errorParsingJSON.addProperty("code", -1001);
                errorParsingJSON.addProperty("message", "Error parsing JSON.");

                response.add("error", errorParsingJSON);
                response.add("result", JsonNull.INSTANCE);
                if (e instanceof IllegalArgumentException) {
                    LOGGER.warning("[http-server-handler] WARNING: Client sent valid JSON, but did not specify method and/or parameters!");
                } else {
                    LOGGER.warning("[http-server-handler] WARNING: Client sent invalid JSON!");
                }
                status = HttpResponseStatus.BAD_REQUEST;
            }

            if (status == HttpResponseStatus.OK) {
                Preconditions.checkNotNull(jsonReq);

                String method = jsonReq.get("method").getAsString();
                JsonArray params = jsonReq.get("params").getAsJsonArray();

                String methodLower = method == null ? null : method.toLowerCase(java.util.Locale.ROOT);
                boolean sensitiveMethod = methodLower != null && SENSITIVE_METHODS.contains(methodLower);
                boolean pingMethod = "ping".equals(methodLower);
                if (pingMethod) {
                    LOGGER.finer("[http-server-handler] RPC CALL: " + coin.getTicker() + " " + method
                            + " PARAMS: " + params.toString().replace(",", ", "));
                } else {
                    LOGGER.info("[http-server-handler] RPC CALL: " + coin.getTicker() + " " + method
                            + " PARAMS: " + (sensitiveMethod ? "<redacted>" : params.toString().replace(",", ", ")));
                }

                try {
                    response = getResponse(method, params);
                } catch (RuntimeException e) {
                    LOGGER.warning("[http-server-handler] Invalid params for " + CoinTickerUtils.tickerToString(coin.getTicker()) + " " + method + ", " + e.getMessage());
                    JsonObject invalidParams = new JsonObject();
                    invalidParams.addProperty("code", -1);
                    invalidParams.addProperty("message", "Invalid params: " + e.getMessage());
                    response.add("error", invalidParams);
                    response.add("result", JsonNull.INSTANCE);
                }
                if (sensitiveMethod)
                    LOGGER.finer("[http-server-handler] response withheld (sensitive method)");
                else
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

    private JsonObject getResponse(String method, JsonArray params) {
        JsonObject response = new JsonObject();

        String normalizedMethod = method == null ? "" : method.toLowerCase(java.util.Locale.ROOT);
        switch (normalizedMethod) {
            case "reloadconfig": {
                Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        LOGGER.warning("[http-server-handler] Interrupted during reloadconfig for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());
                    }

                    coin.reloadConfig();
                });
                t.setDaemon(true);
                t.start();

                response.addProperty("result", true);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "getinfo": {
                JsonObject infoJSON = new JsonObject();

                infoJSON.addProperty("protocolversion", coin.getNetworkParameters().getProtocolVersionNum(NetworkParameters.ProtocolVersion.CURRENT));
                infoJSON.addProperty("ticker", CoinTickerUtils.tickerToString(coin.getTicker()));
                infoJSON.addProperty("balance", coin.getAllBalances());
                infoJSON.addProperty("testnet", coin.isTestnet());
                infoJSON.addProperty("difficulty", 0.0);
                infoJSON.addProperty("connections", 1);  // We make this a static value for now, since we are deprecating P2P
                infoJSON.addProperty("blocks", CoinInstance.getBlockCountByTicker(coin.getTicker()));
                infoJSON.addProperty("keypoolsize", coin.getAddressKeyPairs().size());
                infoJSON.addProperty("keypoololdest", 0.0);

                long relayFeeSats = WalletHelper.getFeePerByte(coin.getNetworkParameters()) * 1000;
                long relayCoinUnit = CoinConfigRegistry.get(CoinTickerUtils.tickerToString(coin.getTicker())).coinFactor();
                BigDecimal relayFeeDecimal = new BigDecimal(relayFeeSats).divide(new BigDecimal(relayCoinUnit),
                        Sats.decimalPlaces(relayCoinUnit), RoundingMode.DOWN);
                infoJSON.addProperty("relayfee", relayFeeDecimal);

                infoJSON.addProperty("networkactive", true);
                infoJSON.addProperty("timeoffset", 0);
                infoJSON.addProperty("rpcready", coin.isInstanceRunning());

                response.add("result", infoJSON);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "getblockcount": {
                response.addProperty("result", CoinInstance.getBlockCountByTicker(coin.getTicker()));
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "getnetworkinfo": {
                JsonObject networkInfoJSON = new JsonObject();
                networkInfoJSON.addProperty("protocolversion", coin.getNetworkParameters().getProtocolVersionNum(NetworkParameters.ProtocolVersion.CURRENT));
                networkInfoJSON.addProperty("ticker", CoinTickerUtils.tickerToString(coin.getTicker()));
                networkInfoJSON.addProperty("subversion", CoinInstance.getVersionString());
                networkInfoJSON.addProperty("connections", 1);
                networkInfoJSON.addProperty("localservices", "0000000000000000");

                long relayFeeSats = WalletHelper.getFeePerByte(coin.getNetworkParameters()) * 1000;
                long networkCoinUnit = CoinConfigRegistry.get(CoinTickerUtils.tickerToString(coin.getTicker())).coinFactor();
                BigDecimal relayFeeDecimal = new BigDecimal(relayFeeSats).divide(new BigDecimal(networkCoinUnit),
                        Sats.decimalPlaces(networkCoinUnit), RoundingMode.DOWN);

                networkInfoJSON.addProperty("relayfee", relayFeeDecimal);

                response.add("result", networkInfoJSON);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "getfees": {
                JsonObject feesJSON = new JsonObject();
                feesJSON.addProperty("feeperbyte", WalletHelper.getFeePerByte(coin.getNetworkParameters()));
                feesJSON.addProperty("mintxfee", WalletHelper.getMinTxFee(coin.getNetworkParameters()));
                response.add("result", feesJSON);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "listunspent": {
                JsonArray unspent = httpClient.getUtxos(coin.getTicker(), UTXO_FETCH_EXPIRY_MS);
                if (unspent == null) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject err = new JsonObject();
                    err.addProperty("message", "listunspent not ready");
                    err.addProperty("code", 3002);
                    response.add("error", err);
                    break;
                }
                response.add("result", unspent);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "listtransactions": {
                int startTime = 0;
                int endTime = 0;
                if (params.size() == 2) {
                    startTime = params.get(0).getAsInt();
                    endTime = params.get(1).getAsInt();
                }
                JsonArray transactions = httpClient.getHistory(coin.getTicker(), startTime, endTime, 60 * 15);
                if (transactions == null) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject err = new JsonObject();
                    err.addProperty("message", "listtransactions not ready");
                    err.addProperty("code", 3002);
                    response.add("error", err);
                    break;
                }
                response.add("result", transactions);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "getblockchaininfo": {
                JsonObject blockchainInfoJSON = new JsonObject();

                blockchainInfoJSON.addProperty("chain", coin.getNetworkParameters().getPaymentProtocolId());
                blockchainInfoJSON.addProperty("blocks", CoinInstance.getBlockCountByTicker(coin.getTicker()));
                blockchainInfoJSON.addProperty("headers", CoinInstance.getBlockCountByTicker(coin.getTicker()));
                blockchainInfoJSON.addProperty("verificationprogress", 1.0);
                blockchainInfoJSON.addProperty("difficulty", 0.0);

                blockchainInfoJSON.addProperty("initialblockdownload", false);
                blockchainInfoJSON.addProperty("pruned", false);

                response.add("result", blockchainInfoJSON);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "getblockhash": {
                if (params.size() != 1) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Usage: getblockhash index\n\nindex (num, required)");

                    response.add("error", errorJSON);
                    break;
                }

                int blockIndex = params.get(0).getAsInt();
                JsonObject blockHash = httpClient.getBlockHash(coin.getTicker(), blockIndex);

                if (blockHash == null || blockHash.has("error") && !blockHash.get("error").isJsonNull()) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Error obtaining blockhash!");
                    response.add("error", errorJSON);

                    break;
                }

                if (blockHash.has("result")) {
                    response.add("result", blockHash.get("result"));
                    response.add("error", JsonNull.INSTANCE);

                    break;
                }

                response.add("result", blockHash);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "sendrawtransaction": {
                if (params.size() != 1) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Usage: sendrawtransaction hex-tx\n\nhex-tx (string, required) - Raw transaction, hex encoded");

                    response.add("error", errorJSON);
                    break;
                }

                String rawTx;
                Transaction transaction;
                try {
                    rawTx = params.get(0).getAsString();
                    transaction = parseForCoin(coin.getTicker(), coin.getNetworkParameters(),
                            Hex.decode(rawTx));
                } catch (RuntimeException e) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Error parsing transaction!");
                    response.add("error", errorJSON);

                    LOGGER.warning("[http-server-handler] Error parsing transaction in sendrawtransaction for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());
                    break;
                }

                JsonObject txid = httpClient.sendRawTransaction(coin.getTicker(), rawTx);
                if (txid == null || txid.has("error") && !txid.get("error").isJsonNull()) {
                    int code = -1;
                    String message = "Error sending transaction!";

                    // Upstream error may be a legacy int (hosted backend) or a
                    // structured {code,message} object (plugin-adapter) — accept both.
                    if (txid != null && txid.has("error") && !txid.get("error").isJsonNull()) {
                        JsonElement errElement = txid.get("error");
                        if (errElement.isJsonPrimitive()) {
                            code = errElement.getAsInt();
                        } else if (errElement.isJsonObject()) {
                            JsonObject errObj = errElement.getAsJsonObject();
                            if (errObj.has("code"))
                                code = errObj.get("code").getAsInt();
                            if (errObj.has("message"))
                                message = errObj.get("message").getAsString();
                        }
                    }

                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", code);
                    errorJSON.addProperty("message", message);
                    response.add("error", errorJSON);

                    break;
                }

                try {
                    WalletHelper.setAsSpent(coin.getTicker(), transaction, true);
                } catch (RuntimeException e) {
                    // The broadcast already happened, so this must not turn a
                    // successful send into an error response. Report it and carry
                    // on to the txid the caller actually asked for.
                    LOGGER.warning("[http-server-handler] post-broadcast bookkeeping failed for "
                            + CoinTickerUtils.tickerToString(coin.getTicker()) + ": " + e.getMessage());
                }

                if (txid.has("result")) {
                    response.add("result", txid.get("result"));
                    response.add("error", JsonNull.INSTANCE);

                    break;
                }

                response.add("result", txid);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "getrawtransaction": {
                if (params.size() < 1 || params.size() > 2) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Usage: getrawtransaction\n\ntxid (string, required) - TXID is required, verbose(optional)");

                    response.add("error", errorJSON);
                    break;
                }

                String txid = params.get(0).getAsString();
                boolean verbose = false;

                if (params.size() == 2) {
                    String v = params.get(1).toString();
                    if (v.equalsIgnoreCase("true") || v.equals("1"))
                        verbose = true;
                }

                JsonObject rawTransaction = httpClient.getRawTransaction(coin.getTicker(), txid, verbose);

                if (rawTransaction == null || rawTransaction.has("error") && !rawTransaction.get("error").isJsonNull()) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -5);
                    errorJSON.addProperty("message", "No information available about transaction");

                    response.add("error", errorJSON);

                    break;
                }

                if (rawTransaction.has("result")) {
                    response.add("result", rawTransaction.get("result"));
                    response.add("error", JsonNull.INSTANCE);

                    break;
                }

                response.add("result", rawTransaction);
                response.add("error", JsonNull.INSTANCE);

                break;
            }
            case "getrawmempool": {
                if (params.size() > 2) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Usage: getrawmempool\n\nverbose (int, optional) - VERBOSE is optional");

                    response.add("error", errorJSON);
                    break;
                }

                boolean verbose = false;

                if (params.size() == 2) {
                    String v = params.get(1).toString();
                    if (v.equalsIgnoreCase("true") || v.equals("1"))
                        verbose = true;
                }

                JsonObject rawMempool = httpClient.getRawMempool(coin.getTicker(), verbose);

                if (rawMempool == null || rawMempool.has("error") && !rawMempool.get("error").isJsonNull()) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "No information available");

                    response.add("error", errorJSON);

                    break;
                }

                if (rawMempool.has("result")) {
                    response.add("result", rawMempool.get("result"));
                    response.add("error", JsonNull.INSTANCE);

                    break;
                }

                response.add("result", rawMempool);
                response.add("error", JsonNull.INSTANCE);

                break;
            }
            case "getblock": {
                if (params.size() != 1) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Usage: getblock hash\n\nhash (string, required) - Hash of block to retrieve");

                    response.add("error", errorJSON);
                    break;
                }

                String hash;
                try {
                    hash = params.get(0).getAsString();
                    hash = hash.replaceAll("\"", "");
                } catch (JsonParseException e) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Error parsing JSON!");
                    response.add("error", errorJSON);

                    LOGGER.warning("[http-server-handler] Error parsing JSON in getblock for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());
                    break;
                }

                JsonObject block = httpClient.getBlock(coin.getTicker(), hash, true);

                if (block == null || block.has("error") && !block.get("error").isJsonNull()) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Error while retrieving block");
                    response.add("error", errorJSON);

                    break;
                }

                if (block.has("result")) {
                    response.add("result", block.get("result"));
                    response.add("error", JsonNull.INSTANCE);

                    break;
                }

                response.add("result", block);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "gettransaction": {
                if (params.size() != 1) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Usage: gettransaction txid\n\ntxid (string, required) - The transaction ID");

                    response.add("error", errorJSON);
                    break;
                }

                String txid = params.get(0).getAsString();

                JsonObject transaction = httpClient.getTransaction(coin.getTicker(), txid, true);

                if (transaction == null || transaction.has("error") && !transaction.get("error").isJsonNull()) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Error while obtaining transaction!");
                    response.add("error", errorJSON);

                    break;
                }

                if (transaction.has("result")) {
                    response.add("result", transaction.get("result"));
                    response.add("error", JsonNull.INSTANCE);

                    break;
                }

                response.add("result", transaction);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "getaddressesbyaccount": {
                if (params.size() != 1) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Usage: getaddressesbyaccount account\n\naccount (string, required) - The account from which to grab addresses. The only account available (for now) is 'main'.");

                    response.add("error", errorJSON);
                    break;
                }

                String account = params.get(0).getAsString();

                if (account.equals("main")) {
                    JsonArray addresses = new JsonArray();
                    for (AddressBalance addressBalance : coin.getAddressKeyPairs()) {
                        addresses.add(addressBalance.getAddress().toBase58());
                    }

                    response.add("result", addresses);
                    response.add("error", JsonNull.INSTANCE);
                } else {
                    response.add("result", new JsonArray());
                    response.add("error", JsonNull.INSTANCE);
                }
                break;
            }
            case "createrawtransaction": {
                if (params.size() < 2 || params.size() > 3) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message",
                            "Usage: createrawtransaction inputs outputs\n\ninputs (string, required) - Inputs in JSON format\nexample: [{\"txid\": \"id\", \"vout\": n}, ...]\n\noutputs (string, required) - Outputs in JSON format\nexample (legacy): {\"address1\": amount1, \"address2\": amount2, ...}\nexample (new): [{\"address\": \"address1\", \"amount\": amount1}, {\"address\": \"address1\", \"amount\": amount2}, ...]");
                    response.add("error", errorJSON);
                    break;
                }

                JsonArray inputs;
                // Instead of using a JsonObject for outputs, we’ll parse outputs into a list of
                // OutputEntry.
                List<OutputEntry> outputEntries = new ArrayList<>();
                long locktime = 0;

                if (params.size() >= 3)
                    locktime = params.get(2).getAsLong();

                try {
                    inputs = params.get(0).getAsJsonArray();
                    for (int i = 0; i < inputs.size(); i++) {
                        JsonObject input = inputs.get(i).getAsJsonObject();
                        if (!input.has("txid") || !input.has("vout"))
                            throw new JsonParseException("Bad transaction input.");
                    }

                    JsonElement outputsElem = params.get(1);
                    if (outputsElem.isJsonArray()) {
                        // New array format: allows duplicate outputs with same address.
                        JsonArray outputsArray = outputsElem.getAsJsonArray();
                        for (JsonElement elem : outputsArray) {
                            JsonObject obj = elem.getAsJsonObject();
                            if (!obj.has("address") || !obj.has("amount"))
                                throw new JsonParseException("Output entry must have 'address' and 'amount'");
                            String addr = obj.get("address").getAsString();
                            double amt = obj.get("amount").getAsDouble();
                            outputEntries.add(new OutputEntry(addr, amt));
                        }
                    } else if (outputsElem.isJsonObject()) {
                        // Legacy format: keys are addresses.
                        JsonObject outputsObject = outputsElem.getAsJsonObject();
                        for (String addr : outputsObject.keySet()) {
                            double amt = outputsObject.get(addr).getAsDouble();
                            outputEntries.add(new OutputEntry(addr, amt));
                        }
                    } else {
                        throw new JsonParseException("Invalid outputs format");
                    }
                } catch (JsonParseException e) {
                    LOGGER.warning(
                            "[http-server-handler] ERROR: Error while parsing JSON for createrawtransaction!");
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -2);
                    errorJSON.addProperty("message", "Error parsing JSON");
                    response.add("error", errorJSON);
                    break;
                }

                Transaction tx = new Transaction(coin.getNetworkParameters());
                if (locktime > 0 && !tx.isTimeLocked()) {
                    tx.setLockTime(locktime);
                }

                boolean inputSuccess = true;
                for (int i = 0; i < inputs.size(); i++) {
                    JsonObject input = inputs.get(i).getAsJsonObject();
                    try {
                        String txid = input.get("txid").getAsString();
                        int vout = input.get("vout").getAsInt();
                        tx.addInput(Sha256Hash.wrap(txid), vout, ScriptBuilder.createInputScript(null));
                    } catch (Exception e) {
                        LOGGER.warning(
                                "[http-server-handler] ERROR: Error while constructing transaction (input phase)!");
                        txConstructionError(response, e, "Error while constructing transaction (input phase)");
                        inputSuccess = false;
                        break;
                    }
                }
                if (!inputSuccess)
                    break;

                boolean outputSuccess = true;

                // First, add P2SH outputs.
                long outputCoinUnit = CoinConfigRegistry.get(CoinTickerUtils.tickerToString(coin.getTicker())).coinFactor();
                for (OutputEntry entry : outputEntries) {
                    try {
                        LegacyAddress address = LegacyAddress.fromBase58(coin.getNetworkParameters(), entry.address);
                        Coin outputValue = Coin.valueOf(Sats.fromWholeCoins(entry.amount, outputCoinUnit));
                        if (isP2SHAddress(entry.address)) {
                            LOGGER.fine("[http-server-handler] P2SH Address Found: " + entry.address);
                            Script p2shScript = ScriptBuilder.createP2SHOutputScript(address.getHash());
                            tx.addOutput(outputValue, p2shScript);
                        }
                    } catch (Exception e) {
                        LOGGER.warning(
                                "[http-server-handler] ERROR: Error while constructing transaction (output phase)!");
                        LOGGER.warning("[http-server-handler] Error in createrawtransaction P2SH output phase for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());
                        txConstructionError(response, e, "Error while constructing transaction (output phase)");
                        outputSuccess = false;
                    }
                }
                // Then, add non-P2SH outputs.
                for (OutputEntry entry : outputEntries) {
                    try {
                        LegacyAddress address = LegacyAddress.fromBase58(coin.getNetworkParameters(), entry.address);
                        Coin outputValue = Coin.valueOf(Sats.fromWholeCoins(entry.amount, outputCoinUnit));
                        if (!isP2SHAddress(entry.address)) {
                            tx.addOutput(outputValue, address);
                        }
                    } catch (Exception e) {
                        LOGGER.warning(
                                "[http-server-handler] ERROR: Error while constructing transaction (output phase)!");
                        LOGGER.warning("[http-server-handler] Error in createrawtransaction output phase for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());
                        txConstructionError(response, e, "Error while constructing transaction (output phase)");
                        outputSuccess = false;
                    }
                }

                if (!outputSuccess)
                    break;

                String hexTx;
                if (hasTxTimeField(coin.getTicker())) {
                    // An unsigned transaction handed to an external signer must
                    // still decode on a time-field chain, so the field is
                    // stamped here. It is wall-clock only, and sendrawtransaction
                    // forwards the bytes it is given unchanged, so a caller whose
                    // inputs carry a newer nTime must re-time and re-sign before
                    // broadcasting.
                    hexTx = new String(Hex.encode(TxTimeField.insertNTime(tx.bitcoinSerialize(),
                            System.currentTimeMillis() / 1000)));
                } else {
                    hexTx = new String(Hex.encode(tx.bitcoinSerialize()));
                }
                LOGGER.finer("[http-server-handler] DEBUG: Raw transaction = " + hexTx);
                response.addProperty("result", hexTx);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "decoderawtransaction": {
                if (params.size() != 1) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Usage: decoderawtransaction rawtx\n\nrawtx (string, required) - The raw transaction to decode, hex encoded.");

                    response.add("error", errorJSON);
                    break;
                }

                String rawTx;
                byte[] rawBytes;
                Transaction tx;

                try {
                    rawTx = params.get(0).getAsString();
                    rawBytes = Hex.decode(rawTx);
                    tx = parseForCoin(coin.getTicker(), coin.getNetworkParameters(), rawBytes);
                } catch (Exception e) {
                    LOGGER.warning("[http-server-handler] Error decoding raw transaction in decoderawtransaction for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());
                    getInvalidTxResponse(response, e);
                    break;
                }

                JsonObject txJSON = new JsonObject();

                txJSON.addProperty("txid", txidForDecoded(coin.getTicker(), rawBytes, tx));
                txJSON.addProperty("version", tx.getVersion());
                txJSON.addProperty("locktime", tx.getLockTime());
                JsonArray vin = new JsonArray();

                boolean inputParseSuccess = true;

                for (TransactionInput input : tx.getInputs()) {
                    try {
                        JsonObject thisVin = new JsonObject();
                        thisVin.addProperty("txid", input.getOutpoint().getHash().toString());
                        thisVin.addProperty("vout", input.getOutpoint().getIndex());

                        JsonObject scriptSig = new JsonObject();
                        scriptSig.addProperty("asm", canonicalizeASM(input.getScriptSig().toString()));
                        scriptSig.addProperty("hex", new String(Hex.encode(input.getScriptSig().getProgram())));

                        thisVin.add("scriptSig", scriptSig);
                        thisVin.addProperty("sequence", input.getSequenceNumber());

                        vin.add(thisVin);
                    } catch (Exception e) {
                        LOGGER.warning("[http-server-handler] ERROR: Error while parsing transaction inputs!");
                        LOGGER.warning("[http-server-handler] Error parsing transaction inputs for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());

                        response.add("result", JsonNull.INSTANCE);
                        JsonObject errorJSON = new JsonObject();
                        errorJSON.addProperty("code", -1008);
                        errorJSON.addProperty("message", "Error while parsing transaction inputs");

                        response.add("error", errorJSON);

                        inputParseSuccess = false;
                    }
                }

                if (!inputParseSuccess)
                    break;

                txJSON.add("vin", vin);
                JsonArray vout = new JsonArray();
                boolean outputParseFailed = false;

                for (TransactionOutput output : tx.getOutputs()) {
                    try {
                        Script.ScriptType type = output.getScriptPubKey().getScriptType();

                        JsonObject thisVout = new JsonObject();

                        thisVout.addProperty("value", Sats.toWholeCoins(output.getValue().value,
                                CoinConfigRegistry.get(CoinTickerUtils.tickerToString(coin.getTicker())).coinFactor()));
                        thisVout.addProperty("n", output.getIndex());

                        JsonObject scriptPubKey = new JsonObject();
                        scriptPubKey.addProperty("asm", canonicalizeASM(output.getScriptPubKey().toString()));
                        scriptPubKey.addProperty("hex", new String(Hex.encode(output.getScriptPubKey().getProgram())));

                        if (type == Script.ScriptType.P2SH) {
                            scriptPubKey.addProperty("reqSigs", 1);
                        } else {
                            scriptPubKey.addProperty("reqSigs", output.getScriptPubKey().getNumberOfSignaturesRequiredToSpend());
                        }

                        getScriptType(scriptPubKey, type);

                        JsonArray addresses = new JsonArray();
                        addresses.add(((LegacyAddress) output.getScriptPubKey().getToAddress(coin.getNetworkParameters())).toBase58());

                        scriptPubKey.add("addresses", addresses);
                        thisVout.add("scriptPubKey", scriptPubKey);

                        vout.add(thisVout);
                    } catch (Exception e) {
                        outputParseFailed = true;
                        LOGGER.warning("[http-server-handler] ERROR: Error while parsing transaction outputs!");
                        LOGGER.warning("[http-server-handler] Error parsing transaction outputs for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());

                        response.add("result", JsonNull.INSTANCE);
                        JsonObject errorJSON = new JsonObject();
                        errorJSON.addProperty("code", -1009);
                        errorJSON.addProperty("message", "Error while parsing transaction outputs");

                        response.add("error", errorJSON);
                    }
                }

                // A throwing vout must surface as an error, not be silently
                // dropped from a success-shaped response.
                if (outputParseFailed)
                    break;

                txJSON.add("vout", vout);

                response.add("result", txJSON);
                response.add("error", JsonNull.INSTANCE);

                break;
            }
            case "signrawtransaction": {
                if (params.size() < 1 || params.size() > 3) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Usage: signrawtransaction rawtx\n\nrawtx (string, required) - The raw transaction to sign, hex encoded.");

                    response.add("error", errorJSON);
                    break;
                }

                String rawTx = params.get(0).getAsString();
                Transaction tx;
                // Incoming nTime of time-field coins, preserved across re-signing.
                long preservedNTime = -1;
//				JsonArray prevtxs;
//				JsonArray privkeys;
//
//				if (params.size() == 3 && !(params.get(0).isJsonNull() || params.get(2).isJsonNull())) {
//					prevtxs = params.get(1).getAsJsonArray();
//					privkeys = params.get(2).getAsJsonArray();
//				}

                try {
                    byte[] rawBytes = Hex.decode(rawTx);
                    if (hasTxTimeField(coin.getTicker())) {
                        // Preserve the incoming nTime across re-signing.
                        preservedNTime = TxTimeField.parentNTime(rawBytes);
                    }
                    tx = parseForCoin(coin.getTicker(), coin.getNetworkParameters(), rawBytes);
                } catch (Exception e) {
                    LOGGER.warning("[http-server-handler] Error decoding raw tx in signrawtransaction for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());
                    getInvalidTxResponse(response, e);
                    break;
                }

                Transaction signedTx = new Transaction(coin.getNetworkParameters());

                boolean complete = true;
                boolean inputFailed = false;

                for (TransactionOutput output : tx.getOutputs()) {
                    signedTx.addOutput(output);
                }

                List<ECKey> signingKeys = new ArrayList<>();
                List<TransactionOutPoint> outPoints = new ArrayList<>();
                List<Script> spentScripts = new ArrayList<>();

                for (TransactionInput input : tx.getInputs()) {
                    Sha256Hash txid = input.getOutpoint().getHash();
                    long vout = input.getOutpoint().getIndex();

                    ECKey signingKey = getSigningKey(txid, vout);
                    UTXO utxo = getUtxo(txid, vout);

                    if (utxo == null || signingKey == null) {
                        getInvalidTxResponse(response, new Exception("Transaction contains an utxo/input which does not exist in our wallet."));
                        inputFailed = true;
                        break;
                    }

                    org.bitcoinj.core.UTXO bUtxo = utxo.createUTXO();
                    signingKeys.add(signingKey);
                    outPoints.add(new TransactionOutPoint(coin.getNetworkParameters(),
                            bUtxo.getIndex(), bUtxo.getHash()));
                    spentScripts.add(bUtxo.getScript());
//					utxo.setSpent(true);
                }

                if (inputFailed) {
                    // An error response is already set — never hand back a
                    // partially signed hex claiming complete:true.
                    LOGGER.warning("[http-server-handler] signrawtransaction aborted with unsigned inputs for " + CoinTickerUtils.tickerToString(coin.getTicker()));
                    break;
                }

                // All inputs are attached before any signature is computed:
                // the chain's preimage covers every input, so signing as we
                // went would invalidate each earlier signature.
                try {
                    if (preservedNTime >= 0) {
                        // The chain signs nTime, so the preserved value belongs
                        // in the preimage, not spliced in afterwards.
                        TxTimeFieldSigner.addSignedInputs(coin.getNetworkParameters(), signedTx,
                                signingKeys, outPoints, spentScripts, Transaction.SigHash.ALL,
                                preservedNTime);
                    } else {
                        for (int i = 0; i < signingKeys.size(); i++) {
                            signedTx.addSignedInput(outPoints.get(i), spentScripts.get(i),
                                    signingKeys.get(i), Transaction.SigHash.ALL, true);
                        }
                    }
                } catch (RuntimeException e) {
                    // Never let a signing refusal escape: this switch has no
                    // outer handler, so the caller would get no response.
                    getInvalidTxResponse(response, e);
                    LOGGER.warning("[http-server-handler] signrawtransaction could not sign inputs for "
                            + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());
                    break;
                }

                // Serialized once. A time-field chain signs an nTime-bearing
                // preimage, so what has to go on the wire is those bytes with
                // nTime reinserted; for every other chain the plain
                // serialization already is them.
                byte[] signedBytes = signedTx.bitcoinSerialize();
                if (hasTxTimeField(coin.getTicker()) && preservedNTime >= 0) {
                    signedBytes = TxTimeField.insertNTime(signedBytes, preservedNTime);
                }
                String signedTxHex = new String(Hex.encode(signedBytes));
                JsonObject resultJSON = new JsonObject();
                resultJSON.addProperty("hex", signedTxHex);
                resultJSON.addProperty("complete", complete);

                response.add("result", resultJSON);
                response.add("error", JsonNull.INSTANCE);

                LOGGER.finer("[DEBUG http-server-handler] Signed Raw Transaction: " + response.toString());
                break;
            }
            case "gettxout": {
                if (params.size() < 2 || params.size() > 3) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Usage: gettxout txid n [include_mempool]\\n" + //
                            "\\n" + //
                            "txid (string, required) - The transaction ID\\n" + //
                            "n (numeric, required) - The vout value\\n" + //
                            "include_mempool (boolean, optional, default=true) - Whether to include the mempool (WARNING: This can block execution for several seconds)");

                    response.add("error", errorJSON);
                    break;
                }

                String txid = params.get(0).getAsString();
                int n = params.get(1).getAsInt();
                boolean includeMempool = true;
                if (params.size() == 3) {
                    includeMempool = params.get(2).getAsBoolean();
                }

                // attempt to find UTXO in cache
                UTXO requested = this.getUtxo(Sha256Hash.wrap(txid), n);

                if (requested != null) {
                    LOGGER.fine("[http-server-handler] Using cached UTXO for gettxout");

                    org.bitcoinj.core.UTXO utxo = requested.createUTXO();
                    JsonObject resultJSON = new JsonObject();

                    resultJSON.addProperty("confirmations",
                            (CoinInstance.getBlockCountByTicker(coin.getTicker()) - utxo.getHeight()) + 1);
                    resultJSON.addProperty("value", Sats.toWholeCoins(utxo.getValue().value,
                            CoinConfigRegistry.get(CoinTickerUtils.tickerToString(coin.getTicker())).coinFactor()));

                    JsonObject scriptPubKey = new JsonObject();
                    scriptPubKey.addProperty("asm", utxo.getScript().toString());
                    scriptPubKey.addProperty("hex", new String(Hex.encode(utxo.getScript().getProgram())));
                    scriptPubKey.addProperty("reqSigs", utxo.getScript().getNumberOfSignaturesRequiredToSpend());

                    Script.ScriptType type = utxo.getScript().getScriptType();
                    getScriptType(scriptPubKey, type);

                    JsonArray addresses = new JsonArray();
                    addresses.add(utxo.getAddress());
                    scriptPubKey.add("addresses", addresses);

                    resultJSON.add("scriptPubKey", scriptPubKey);
                    resultJSON.addProperty("coinbase", utxo.isCoinbase());

                    response.add("result", resultJSON);
                    response.add("error", JsonNull.INSTANCE);
                    break;
                }

                if (!includeMempool) {
                    LOGGER.warning("[http-server-handler] WARNING: Client requested UTXO that is not ours!");
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();

                    errorJSON.addProperty("code", -5);
                    errorJSON.addProperty("message", "Invalid or non-wallet transaction ID (not ours)");
                    response.add("error", errorJSON);
                    break;
                }

                // considering mempool, find/wait for the transaction
                JsonObject transaction = null;
                int retries = includeMempool ? 5 : 1;
                for (int i = 0; i < retries; i++) {
                    transaction = httpClient.getTransaction(coin.getTicker(), txid, true);
                    if (transaction == null || transaction.has("result") && transaction.get("result").isJsonNull()) {
                        if (i < retries - 1) {
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            continue;
                        }
                    } else {
                        break;
                    }
                }

                if (transaction == null || transaction.has("error") && !transaction.get("error").isJsonNull()) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();

                    errorJSON.addProperty("code", -5);
                    errorJSON.addProperty("message", "Invalid transaction ID");
                    response.add("error", errorJSON);
                    break;
                }

                // extract the UTXO
                try {
                    // Note: will throw when attempting to parse a coinbase utxo (that's fine)
                    JsonObject result = transaction.getAsJsonObject("result");
                    JsonElement confirmations = result.get("confirmations");
                    JsonArray vout = result.getAsJsonArray("vout");

                    if (vout.size() <= n) {
                        response.add("result", JsonNull.INSTANCE);
                        JsonObject errorJSON = new JsonObject();

                        errorJSON.addProperty("code", -5);
                        errorJSON.addProperty("message", "Invalid transaction output index");
                        response.add("error", errorJSON);
                        break;
                    }

                    JsonObject entry = vout.get(n).getAsJsonObject();
                    JsonElement value = entry.get("value");
                    JsonObject scriptPubKey = entry.getAsJsonObject("scriptPubKey");
                    JsonElement addr = scriptPubKey.get("address");
                    JsonArray addresses = scriptPubKey.getAsJsonArray("addresses");

                    String address = null;
                    if (addr != null) {
                        address = addr.getAsString();
                    } else {
                        address = addresses.asList().get(0).getAsString();
                    }

                    // ensure address belongs to our wallet
                    boolean isOurs = false;
                    for (AddressBalance addressBalance : coin.getAddressKeyPairs()) {
                        String addressCheck = addressBalance.getAddress().toString();
                        if (address.equals(addressCheck)) {
                            isOurs = true;
                            break;
                        }
                    }

                    if (!isOurs) {
                        LOGGER.warning("[http-server-handler] WARNING: Client requested UTXO that cannot be ours!");
                        response.add("result", JsonNull.INSTANCE);
                        JsonObject errorJSON = new JsonObject();

                        errorJSON.addProperty("code", -5);
                        errorJSON.addProperty("message", "Invalid or non-wallet transaction ID (cannot be ours)");
                        response.add("error", errorJSON);
                        break;
                    }

                    int count = 0;
                    if (confirmations != null) {
                        count = confirmations.getAsInt();
                    }

                    // ensure UTXO is unspent
                    // Caution: Beware of race conditions; the backend might return results for 'getTransaction'
                    // before updating entries for 'getUtxos'. As a result, we only check confirmed transactions. 
                    // Note that unconfirmed UTXOs spent in the memory pool will still be returned, leading to 
                    // a slightly different behavior compared to a core wallet.
                    if (count > 0) {
                        // Note: this request is expensive
                        boolean unspent = false;
                        JsonArray utxos = httpClient.getUtxosUncached(coin.getTicker(), new String[]{address});
                        for (JsonElement utxo : utxos.asList()) {
                            String newtxid = utxo.getAsJsonObject().get("txid").getAsString();
                            int newvout = utxo.getAsJsonObject().get("vout").getAsInt();
                            if (newtxid.equals(txid) && newvout == n) {
                                unspent = true;
                                break;
                            }
                        }

                        if (!unspent) {
                            LOGGER.warning("[http-server-handler] WARNING: Client requested UTXO that was already spent!");
                            response.add("result", JsonNull.INSTANCE);
                            JsonObject errorJSON = new JsonObject();

                            errorJSON.addProperty("code", -5);
                            errorJSON.addProperty("message", "Invalid or non-wallet transaction ID (already spent)");
                            response.add("error", errorJSON);
                            break;
                        }
                    }

                    // assemble result
                    JsonObject resultJSON = new JsonObject();
                    resultJSON.addProperty("confirmations", count);
                    resultJSON.add("value", value);
                    resultJSON.add("scriptPubKey", scriptPubKey);
                    resultJSON.addProperty("coinbase", false);

                    response.add("result", resultJSON);
                    response.add("error", JsonNull.INSTANCE);
                } catch (Exception e) {
                    LOGGER.warning("[http-server-handler] ERROR: Error while parsing transaction!");
                    LOGGER.warning("[http-server-handler] Error parsing transaction in gettxout for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());

                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1010);
                    errorJSON.addProperty("message", "Error while parsing transaction");

                    response.add("error", errorJSON);
                }

                break;
            }
            case "getnewaddress": {
                if (params.size() != 0) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Usage: getnewaddress");

                    response.add("error", errorJSON);
                    break;
                }

                AddressBalance newAddress = coin.generateAddress(true);
                response.addProperty("result", newAddress.getAddress().toBase58());
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "importprivkey": {
                if (params.size() != 1) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Usage: importprivkey privatekey\n\nNOTE: Key's are not persistent!\n\nprivatekey (string, required)");

                    response.add("error", errorJSON);
                    break;
                }

                coin.importPrivateKey(params.get(0).getAsString());
                response.addProperty("result", "");
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "dumpprivkey": {
                if (params.size() != 1) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Usage: dumpprivkey address\n\naddress (string, required)");

                    response.add("error", errorJSON);
                    break;
                }

                AddressBalance addressBalance = coin.getAddress(params.get(0).getAsString());

                if (addressBalance == null) {
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -4);
                    errorJSON.addProperty("message", "Address does not exist");

                    response.add("result", JsonNull.INSTANCE);
                    response.add("error", errorJSON);

                    break;
                }

                response.addProperty("result", addressBalance.getPrivateKey().toBase58());
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "signmessage": {
                if (params.size() != 2) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Usage: signmessage address message\n\naddress (string, required) - The address whose private key to use to sign the message\nmessage (string, required) - The message to sign");

                    response.add("error", errorJSON);
                    break;
                }

                String addr = params.get(0).getAsString();
                String message = params.get(1).getAsString();

                AddressBalance address = coin.getAddressBalance(addr);
                if (address == null) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -5);
                    errorJSON.addProperty("message", "Invalid or non-wallet address");

                    response.add("error", errorJSON);
                    break;
                }

                ECKey key = address.getPrivateKey().getKey();
                String signatureB64 = signMessage(key, message);

                response.addProperty("result", signatureB64);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "verifymessage": {
                if (params.size() != 3) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Usage: verifymessage address signature message\n\naddress (string, required) - The address whose private key to use to sign the message\nsignature (string, required) - The signature to verify, base64 encoded\nmessage (string, required) - The message to sign");

                    response.add("error", errorJSON);
                    break;
                }

                String addr = params.get(0).getAsString();
                String signatureB64 = params.get(1).getAsString();
                String message = params.get(2).getAsString();

                boolean verified = false;

                try {
                    ECKey key = signedMessageToKey(signatureB64, message);
                    verified = verifyMessage(key, signatureB64, message);
                    if (!verified)
                        throw new SignatureException("Signature was not verified.");

                    String derivedAddr = LegacyAddress.fromKey(coin.getNetworkParameters(), key).toBase58();
                    if (!addr.equals(derivedAddr)) {
                        LOGGER.warning("[http-server-handler] ERROR: Addresses do not match! Failing.");
                        verified = false;
                    }
                } catch (Exception e) {
                    LOGGER.warning("[http-server-handler] Error while verifying signature! Invalid signature?");
                    LOGGER.warning("[http-server-handler] Error verifying message for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());

                    response.addProperty("result", verified);
                    response.add("error", JsonNull.INSTANCE);
                    break;
                }

                response.addProperty("result", verified);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "sendtransaction": {
                if (params.size() < 2 || params.size() > 3) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Usage: sendtransaction address amount [dryrun]\n\naddress (string, required)\namount (number, required)\ndryrun (boolean, optional) - build and sign the transaction and return it with its fee breakdown instead of broadcasting it. For a chain whose transactions carry nTime (Verge, Emercoin, Pocketcoin) the nTime is taken as the later of the current time and the times of the parent transactions, so each input's parent is fetched over the network first: this call is slower, and spending an output whose parent is not yet available upstream fails rather than being sent without a valid nTime.");

                    response.add("error", errorJSON);
                    break;
                }


                String address;
                double amount;
                io.xlite.daemon.app.wallet.WalletHelper.BuildFailure[] buildFailure =
                        new io.xlite.daemon.app.wallet.WalletHelper.BuildFailure[1];
                boolean dryRun;
                try {
                    address = params.get(0).getAsString();
                    amount = params.get(1).getAsDouble();
                    dryRun = parseDryRunFlag(params, 2);
                } catch (RuntimeException e) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    // The cause, not a generic "Error parsing JSON!". This is a
                    // send, so a caller told only that parsing failed cannot tell a
                    // malformed address from a bad dryrun value, and the latter is
                    // the reason this response exists.
                    errorJSON.addProperty("message", e.getMessage() != null
                            ? e.getMessage() : "invalid sendtransaction arguments");
                    response.add("error", errorJSON);

                    LOGGER.warning("[http-server-handler] Error parsing JSON in sendtransaction for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());
                    break;
                }

                // Serializes the input-selecting part of this send against every
                // other send on this coin, including one on another connection.
                java.util.concurrent.locks.ReentrantLock sendLock =
                        sendLockFor(coin.getTicker());
                sendLock.lock();
                try {
                    // The wallet's unspent set is populated only by a UTXO fetch, and
                    // that fetch is cached. Nothing else fills it, so on a freshly
                    // started daemon — or once the cache has expired — input
                    // selection would run against an empty set and report "no UTXOs
                    // available" on a wallet that holds plenty. Refresh first, the
                    // same way listunspent does, so a send does not depend on some
                    // earlier unrelated call having happened to populate it.
                    if (httpClient.getUtxos(coin.getTicker(), UTXO_FETCH_EXPIRY_MS) == null) {
                        response.add("result", JsonNull.INSTANCE);
                        JsonObject notReady = new JsonObject();
                        notReady.addProperty("message", "wallet not ready: could not load unspent outputs");
                        notReady.addProperty("code", 3002);
                        response.add("error", notReady);
                        break;
                    }

                    WalletHelper.BuiltTransaction built;
                    try {
                        built = WalletHelper.createTransactionSimpleWithFee(coin.getTicker(), address, amount,
                                this::resolveNTimeForInputs, buildFailure);
                        if (built == null) {
                            response.add("result", JsonNull.INSTANCE);
                            JsonObject errorJSON = new JsonObject();
                            errorJSON.addProperty("code", -6);
                            errorJSON.addProperty("message", "Failed to create transaction");
                            // buildSimple fails for several unrelated reasons, so the
                            // actual one is reported rather than assumed. Naming an nTime
                            // failure for an empty wallet would send the caller looking
                            // for an upstream problem that was never involved.
                            errorJSON.addProperty("failureReason",
                                    buildFailure[0] == null ? "UNKNOWN" : buildFailure[0].name());
                            if (buildFailure[0] == io.xlite.daemon.app.wallet.WalletHelper
                                    .BuildFailure.NTIME_UNRESOLVED) {
                                errorJSON.addProperty("nTimeResolved", false);
                            }
                            response.add("error", errorJSON);
                            break;
                        }
                    } catch (RuntimeException e) {
                        response.add("result", JsonNull.INSTANCE);
                        JsonObject errorJSON = new JsonObject();
                        errorJSON.addProperty("code", -6);
                        errorJSON.addProperty("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                        response.add("error", errorJSON);

                        LOGGER.warning("[http-server-handler] Error creating transaction in sendtransaction for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());
                        break;
                    }

                    Transaction transaction = built.transaction();
                    String rawTxHex;
                    try {
                        rawTxHex = serializeForBroadcast(coin.getTicker(), transaction, built.nTime());
                    } catch (RuntimeException e) {
                        // In a try because this throws a RuntimeException naming its
                        // cause; outside one it would surface as a channel error with
                        // no body instead of the JSON-RPC error the caller expects.
                        response.add("result", JsonNull.INSTANCE);
                        JsonObject errorJSON = new JsonObject();
                        errorJSON.addProperty("code", -6);
                        errorJSON.addProperty("message", e.getMessage() != null
                                ? e.getMessage() : e.getClass().getSimpleName());
                        response.add("error", errorJSON);
                        LOGGER.warning("[http-server-handler] Error serializing sendtransaction for "
                                + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());
                        break;
                    }
                    LOGGER.fine("[http-server-handler] sendtransaction: rawTx size=" + rawTxHex.length() + " bytes");
                    LOGGER.finer("[http-server-handler] sendtransaction: rawTx hex=" + rawTxHex);

                    // Dry run: built, signed and serialized exactly as it would be
                    // for the network, but nothing leaves the wallet -- no
                    // broadcast, and the inputs stay unspent so the same funds can
                    // still be sent afterwards. The returned hex can be checked
                    // against the node's testmempoolaccept, or handed to
                    // sendrawtransaction to broadcast those exact bytes later.
                    if (dryRun) {
                        response.add("result", buildDryRunResult(coin.getNetworkParameters(),
                                CoinConfigRegistry.get(CoinTickerUtils.tickerToString(coin.getTicker())).coinFactor(),
                                built.feeSats(), rawTxHex, transaction));
                        response.add("error", JsonNull.INSTANCE);
                        break;
                    }

                    JsonObject txid = httpClient.sendRawTransaction(coin.getTicker(), rawTxHex);
                    if (txid == null || txid.has("error") && !txid.get("error").isJsonNull()) {
                        int code = -1;
                        String errorMsg = "Error sending transaction!";
                        if (txid != null) {
                            JsonElement errorNode = txid.get("error");
                            if (errorNode.isJsonPrimitive()) {
                                code = errorNode.getAsInt();
                            } else if (errorNode.isJsonObject()) {
                                JsonObject errObj = errorNode.getAsJsonObject();
                                if (errObj.has("code")) code = errObj.get("code").getAsInt();
                                if (errObj.has("message")) errorMsg = errObj.get("message").getAsString();
                            }
                        }
                        LOGGER.warning("[http-server-handler] sendrawtransaction failed for " + CoinTickerUtils.tickerToString(coin.getTicker()) + " code=" + code + " message=" + errorMsg);
                        response.add("result", JsonNull.INSTANCE);
                        JsonObject errorJSON = new JsonObject();
                        errorJSON.addProperty("code", code);
                        errorJSON.addProperty("message", errorMsg);
                        response.add("error", errorJSON);
                        break;
                    }

                    try {
                        WalletHelper.setAsSpent(coin.getTicker(), transaction, true);
                    } catch (RuntimeException e) {
                        // The broadcast already happened, so this must not turn a
                        // successful send into an error response. Report it and carry
                        // on to the txid the caller actually asked for.
                        LOGGER.warning("[http-server-handler] post-broadcast bookkeeping failed for "
                                + CoinTickerUtils.tickerToString(coin.getTicker()) + ": " + e.getMessage());
                    }

                    if (txid.has("result")) {
                        response.add("result", txid.get("result"));
                        response.add("error", JsonNull.INSTANCE);

                        break;
                    }

                    buildSendFallbackResult(response, txid);
                } finally {
                    sendLock.unlock();
                }
                // Without this the switch falls through to "version", which
                // overwrites the txid with a version string. The broadcast has
                // already happened and cannot be undone, so the caller would be
                // told it sent something it never sent.
                break;
            }

            case "version": {
                response.addProperty("result", Version.CLIENT_VERSION);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "validateaddress": {
                if (params.size() != 1) {
                    response.add("result", JsonNull.INSTANCE);
                    JsonObject errorJSON = new JsonObject();
                    errorJSON.addProperty("code", -1);
                    errorJSON.addProperty("message", "Usage: validateaddress address\n\naddress (string, required) - The address to validate.");

                    response.add("error", errorJSON);
                    break;
                }

                JsonObject resultJSON = new JsonObject();
                String address = params.get(0).getAsString();

                boolean isValidAddress = Utility.isValidAddress(coin.getNetworkParameters(), address);

                resultJSON.addProperty("isvalid", isValidAddress);
                resultJSON.addProperty("address", address);

                boolean isP2SH = false;
                String scriptPubKey = "";
                if (isValidAddress) {
                    Address parsed;
                    try {
                        parsed = LegacyAddress.fromBase58(coin.getNetworkParameters(), address);
                    } catch (AddressFormatException e) {
                        // Bech32/segwit: derive the witness scriptPubKey
                        // (OP_<ver> <push> <program>) instead of crashing —
                        // isValidAddress legitimately accepted this address.
                        SegwitAddress segwit = SegwitAddress.fromBech32(coin.getNetworkParameters(), address);
                        byte[] program = segwit.getWitnessProgram();
                        int version = segwit.getWitnessVersion();
                        String opVersion = (version == 0) ? "00"
                                : Integer.toHexString(0x50 + version);
                        String pushOpCode = String.format("%02x", program.length);
                        scriptPubKey = opVersion + pushOpCode
                                + new String(Hex.encode(program));
                        parsed = null;
                    }
                    if (parsed != null && isP2SHAddress(address)) {
                        isP2SH = true;

                        TransactionOutput output = new TransactionOutput(coin.getNetworkParameters(), null, Coin.valueOf(0), parsed);
                        scriptPubKey = new String(Hex.encode(output.getScriptPubKey().getProgram()));
                    }
                }

                resultJSON.addProperty("scriptPubKey", scriptPubKey);
                resultJSON.addProperty("isscript", isP2SH);

                response.add("result", resultJSON);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "ping": {
                response.addProperty("result", 1);
                response.add("error", JsonNull.INSTANCE);
                break;
            }
            case "help": {
                String helpString = "JSON-RPC server for " + CoinTickerUtils.tickerToString(coin.getTicker()) + "\n"
                        + "This JSON-RPC server is served by " + CoinInstance.getVersionString() + "\n"
                        + "\n"
                        + "help - This command help.\n"
                        + "version - Get version\n"
                        + "\n=====Blockchain=====\n"
                        + "gettxout <txid> <vout> - Get info about an unspent transaction output\n"
                        + "\n=====Network=====\n"
                        + "ping - Lightweight liveness probe (result 1 if RPC listener alive)\n"
                        + "getinfo - Get information such as balances, protocol version, and more.\n"
                        + "getblockcount - Get block count\n"
                        + "getnetworkinfo - Get network information\n"
                        + "getfees - Get fee per byte and minimum transaction fee\n"
                        + "getrawmempool - Get raw mempool\n"
                        + "getblockchaininfo - Get blockchain info\n"
                        + "getblockhash <height> - Get the hash of a block at a given height\n"
                        + "getblock <hash> - Get a block's JSON representation given its hash\n"
                        + "\n=====Wallet=====\n"
                        + "listunspent - Get all UTXOs in the wallet\n"
                        + "listtransactions - Get all transactions in the wallet\n"
                        + "getnewaddress - Generate a new address\n"
                        + "gettransaction <txid> - Get a transaction given its TXID\n"
                        + "getaddressesbyaccount <account> - Get addresses belonging to a given account. The only account available is 'main' which contains all addresses.\n"
                        + "importprivkey <privkey> - Import an address given it's privkey\n"
                        + "dumpprivkey <address> - Dump an addresses private key\n"
                        + "\n=====Utilities=====\n"
                        + "signmessage <address> <message> - Sign a message with a given address' private key\n"
                        + "verifymessage <address> <signature> <message> - Verify a signature for a message signed by a given address\n"
                        + "validateaddress <address> - Validate a given address\n"
                        + "reloadconfig <ticker> - Reload configuration for the specified coin ticker\n"
                        + "sendtransaction <address> <amount> - Create and broadcast a signed transaction to the network\n"
                        + "\n=====Raw Transactions=====\n"
                        + "getrawtransaction <txid> [verbose] - Get a transaction's serialized hex (JSON when verbose)\n"
                        + "createrawtransaction <inputs> <outputs> - Create a raw transaction given inputs and outputs in JSON format. For more info, run createrawtransaction with no arguments.\n"
                        + "decoderawtransaction <rawtx> - Get a raw transaction's JSON representation\n"
                        + "signrawtransaction <rawtx> - Sign a raw transaction\n"
                        + "sendrawtransaction <rawtx> - Broadcast a signed raw transaction to the network\n";

                response.addProperty("result", helpString);
                response.add("error", JsonNull.INSTANCE);
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

        return response;
    }

    private String canonicalizeASM(String asm) {
        return asm
                .replaceAll("DUP", "OP_DUP")
                .replaceAll("HASH160", "OP_HASH160")
                .replaceAll("EQUALVERIFY", "OP_EQUALVERIFY")
                .replaceAll("CHECKSIG", "OP_CHECKSIG")
                .replaceAll("RETURN", "OP_RETURN")
                .replaceAll("PUSHDATA", "")
                .replaceAll("\\[", "")
                .replaceAll("]", "")
                .replaceAll("\\([0-9]+\\)", "");
    }

    /**
     * Whether the coin's wire format carries nTime (the BCF TxWithTimeField
     * flag, set by Peercoin-derived chains). All nTime handling below keys
     * off this flag; there are no per-coin branches anywhere. Delegates
     * rather than reading the config itself, so the flag has exactly one
     * implementation.
     */
    private static boolean hasTxTimeField(CoinTicker ticker) {
        return io.xlite.daemon.app.util.TxTimeField.appliesTo(ticker);
    }

    /**
     * Serialize a signed transaction for broadcast, inserting nTime for
     * time-field coins. nTime is max(wall-clock, parent nTimes) to satisfy
     * the input-time consensus rule. Throws RuntimeException naming the
     * cause: an unsendable tx must fail here, never broadcast malformed.
     *
     * <p>Static and package-private so a test can assert the bytes it returns
     * carry exactly the nTime that was signed. That equality is the property
     * deciding whether the node accepts the broadcast, and nothing else in
     * the suite can observe it.
     */
    static String serializeForBroadcast(CoinTicker ticker, Transaction transaction, long signedNTime) {
        byte[] standard = transaction.bitcoinSerialize();
        if (!hasTxTimeField(ticker))
            return new String(Hex.encode(standard));
        if (signedNTime < 0 || signedNTime > 0xFFFFFFFFL)
            throw new RuntimeException(
                    "cannot serialize: no nTime was signed for this transaction");
        return new String(Hex.encode(TxTimeField.insertNTime(standard, signedNTime)));
    }

    /**
     * The nTime a transaction spending {@code selected} must carry: the
     * newest of wall-clock time and every input parent's nTime.
     *
     * <p>Runs inside the wallet's build, after the inputs are chosen and
     * before signing, because the chain signs nTime -- the value has to be
     * known while signing and must be the same one spliced into the wire
     * bytes afterwards. Throws on any fetch or parse failure so the send
     * fails rather than being signed against a guessed time.
     */
    private long resolveNTimeForInputs(List<UTXO> selected) {
        List<String> parentTxids = new ArrayList<>();
        for (UTXO utxo : selected) {
            if (!utxo.isSpent())
                parentTxids.add(utxo.getTxid());
        }
        List<Long> parentNTimes = fetchParentNTimes(coin.getTicker(), parentTxids);
        return TxTimeField.spendNTime(System.currentTimeMillis() / 1000, parentNTimes);
    }

    /**
     * Optional trailing boolean flag, accepting the same true/1 spellings as
     * the getrawtransaction verbose flag. Read with getAsString rather than
     * toString so a quoted {@code "true"} works as well as a bare JSON
     * boolean -- toString on a string primitive keeps the quotes. A missing
     * or unrecognized value means false, so an existing two-parameter call
     * keeps its behavior.
     */
    static boolean parseDryRunFlag(JsonArray params, int index) {
        if (params.size() <= index)
            return false;
        JsonElement element = params.get(index);
        // A JSON null is an omitted flag: that is how an explicit null reads to
        // every JSON caller, and it is how a pre-flag two-argument call arrives.
        if (element == null || element.isJsonNull())
            return false;
        // A non-primitive here is not a boolean in any encoding, it is a wrong
        // argument. It used to be read as "not a dry run", which meant an object
        // or array in that position silently produced a real broadcast -- the one
        // outcome that must not be reached by guessing, since the caller asked
        // to have the transaction reviewed before it was spent.
        if (!element.isJsonPrimitive())
            throw new IllegalArgumentException(
                    "dryrun must be true or false, got " + element);
        String value = element.getAsString();
        if (value.equalsIgnoreCase("true") || value.equals("1"))
            return true;
        if (value.equalsIgnoreCase("false") || value.equals("0"))
            return false;
        // Anything else is a typo, and here the cost of guessing is asymmetric:
        // the verbose flag degrades output, whereas a misspelled dryrun that
        // resolved to false would spend the money the caller asked to have
        // reviewed first. Refuse rather than assume. The caller's existing catch
        // turns this into a normal error response.
        throw new IllegalArgumentException(
                "dryrun must be true or false, got '" + value + "'");
    }

    /**
     * The {@code sendtransaction} success response when the upstream envelope
     * carries no {@code "result"} member.
     *
     * <p>Some upstreams return the txid as the whole envelope rather than
     * nested under {@code "result"}. The caller still asked for a txid, so
     * the envelope itself is the result. Extracted so the fallback is
     * directly testable: it sits two lines above a switch fall-through into
     * {@code "version"} that once overwrote this result with a version
     * string, and only a test that drives this exact path can prove the
     * response shape survives.
     */
    static void buildSendFallbackResult(JsonObject response, JsonObject envelope) {
        response.add("result", envelope);
        response.add("error", JsonNull.INSTANCE);
    }

    /**
     * Dry-run review payload: the exact bytes that would be broadcast, the
     * txid those bytes hash to, and the fee the wallet is charging.
     *
     * <p>The txid is derived from the returned hex, not from
     * {@code transaction.getHash()}: time-field coins serialize an nTime that
     * bitcoinj's in-memory hash does not cover, so the object hash belongs to
     * a different transaction than the bytes on the wire. Reporting that value
     * would hand back a txid the network will never produce.
     *
     * <p>The fee is passed in rather than recomputed from the transaction's
     * input and output counts: {@code fundTransaction} charges for one input
     * more than the finished transaction has, so the formula understates the
     * real charge whenever the linear term, rather than the minimum, wins.
     */
    static JsonObject buildDryRunResult(NetworkParameters params, long coinUnit, long feeSats,
            String rawTxHex, Transaction transaction) {
        JsonObject result = new JsonObject();
        result.addProperty("dryRun", true);
        result.addProperty("txid", txidForHex(rawTxHex));
        result.addProperty("hex", rawTxHex);
        result.addProperty("sizeBytes", Hex.decode(rawTxHex).length);
        result.addProperty("inputs", transaction.getInputs().size());
        result.addProperty("outputs", transaction.getOutputs().size());
        result.addProperty("feeSats", feeSats);
        result.addProperty("fee", io.xlite.daemon.app.util.Sats.toWholeCoins(feeSats, coinUnit));
        result.addProperty("feePerByte", WalletHelper.getFeePerByte(params));
        result.addProperty("minTxFee", WalletHelper.getMinTxFee(params));
        result.addProperty("coinUnit", coinUnit);
        return result;
    }

    /** The txid the network will use for exactly these bytes. */
    private static String txidForHex(String rawTxHex) {
        return Sha256Hash.wrapReversed(Sha256Hash.hashTwice(Hex.decode(rawTxHex))).toString();
    }

    /**
     * Read every input parent's nTime from chain (bytes 4-8 of the parent
     * raw tx). Any fetch/parse failure aborts the send: without parent
     * times nTime cannot satisfy the input-time rule.
     */
    private List<Long> fetchParentNTimes(CoinTicker ticker, List<String> parentTxids) {
        List<Long> parents = new ArrayList<>();
        for (String parentTxid : parentTxids) {
            JsonObject parent = httpClient.getRawTransaction(ticker, parentTxid, false);
            if (parent == null || !parent.has("result") || parent.get("result").isJsonNull())
                throw new RuntimeException(
                        "Cannot fetch parent " + parentTxid + " for nTime");
            try {
                parents.add(TxTimeField.parentNTime(
                        Hex.decode(parent.get("result").getAsString())));
            } catch (RuntimeException e) {
                throw new RuntimeException(
                        "Cannot parse parent " + parentTxid + " nTime: " + e.getMessage(), e);
            }
        }
        return parents;
    }

    /**
     * Decode raw tx bytes for bitcoinj parsing, stripping nTime for
     * time-field coins. Throws IllegalArgumentException on undersized
     * input (handled by callers as an invalid-tx response).
     */
    private static byte[] decodeForParsing(CoinTicker ticker, byte[] rawBytes) {
        if (!hasTxTimeField(ticker))
            return rawBytes;
        // A standard-layout transaction has no nTime, so reading one would
        // shift every remaining field and yield a transaction that parses into
        // something else. No length threshold can tell the layouts apart -- a
        // real standard transaction is far longer than any minimum -- so the
        // only sound check is structural: after stripping, the result must
        // parse as a transaction whose fields are self-consistent. Callers
        // surface the IllegalArgumentException as an invalid-tx response.
        return TxTimeField.stripNTime(rawBytes);
    }

    static String txidForDecoded(CoinTicker ticker, byte[] wireBytes, Transaction parsed) {
        return hasTxTimeField(ticker)
                ? io.xlite.daemon.app.util.TxTimeField.txidOfWireBytes(wireBytes)
                : parsed.getHashAsString();
    }

    /**
     * Parse raw transaction bytes for a time-field coin, rejecting a hex that
     * is in the wrong wire layout.
     *
     * <p>Stripping nTime from a standard-layout transaction leaves the vin
     * count read from the wrong offset, so the parse either fails outright or
     * produces a transaction whose input count disagrees with the bytes that
     * were supplied. Comparing the re-serialized form against the input
     * catches the second case, which a plain parse would not.
     *
     * <p>Package-private static for testability, like {@link #txidForDecoded}:
     * this is the one check that decides whether a hex blob is in the right
     * layout for its chain, so it is worth pinning directly.
     */
    static Transaction parseForCoin(CoinTicker ticker, NetworkParameters params,
            byte[] rawBytes) {
        byte[] parsed = decodeForParsing(ticker, rawBytes);
        Transaction tx = new Transaction(params, parsed);
        if (hasTxTimeField(ticker) && !java.util.Arrays.equals(tx.bitcoinSerialize(), parsed))
            throw new IllegalArgumentException(
                    "raw transaction is not in this chain's wire layout: it re-serializes "
                            + "differently, so it carries no nTime");
        return tx;
    }

    /**
     * Returns the coin-specific signed-message header prefix, or null when the
     * coin has no mapping. Package-private static for testability.
     */
    static String signedMessageHeader(CoinTicker ticker) {
        if (ticker == null)
            return null;
        switch (ticker) {
            case BLOCKNET:
                return "Blocknet Signed Message:\n";
            case BITCOIN:
            case BITCOIN_CASH:
                return "Bitcoin Signed Message:\n";
            case LITECOIN:
                return "Litecoin Signed Message:\n";
            case PIVX:
                return "DarkNet Signed Message:\n";
            case DASHCOIN:
                return "DarkCoin Signed Message:\n";
            case UNOBTANIUM:
                return "Unobtanium Signed Message:\n";
            case PKOIN:
                return "Pocketcoin Signed Message:\n";
            case DIGIBYTE:
                return "DigiByte Signed Message:\n";
            case RAVENCOIN:
                return "Raven Signed Message:\n";
            case DOGECOIN:
                return "Dogecoin Signed Message:\n";
            case SYSCOIN:
                return "Syscoin Signed Message:\n";
            case VERGE:
                return "VERGE Signed Message:\n";
            default:
                LOGGER.warning("[http-server-handler] ERROR: Unsupported coin. This should never happen.");
                return null;
        }
    }

    private byte[] formatMessageForSigning(String message) {
        String header = signedMessageHeader(coin.getTicker());

        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
            bos.write(headerBytes.length);
            bos.write(headerBytes);
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            VarInt size = new VarInt(messageBytes.length);
            bos.write(size.encode());
            bos.write(messageBytes);
            return bos.toByteArray();
        } catch (IOException e) {
            LOGGER.warning("[http-server-handler] Error formatting message for signing for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());
        }

        return null;
    }

    private String signMessage(ECKey key, String message) {
        byte[] formatted = formatMessageForSigning(message);
        Preconditions.checkNotNull(formatted, "Formatted message is null");
        Sha256Hash hash = Sha256Hash.twiceOf(formatted);
        ECKey.ECDSASignature signature = key.sign(hash);
        byte recoveryId = -1;
        for (int i = 0; i < 4; i++) {
            ECKey k = ECKey.recoverFromSignature(i, signature, hash, key.isCompressed());
            if (k != null && Arrays.equals(k.getPubKey(), key.getPubKey())) {
                recoveryId = (byte) i;
                break;
            }
        }
        if (recoveryId == -1)
            throw new IllegalStateException("Recovery ID is invalid.");

        byte[] sigData = new byte[65];
        byte headerByte = (byte) (recoveryId + 27 + (key.isCompressed() ? 4 : 0));
        sigData[0] = headerByte;
        System.arraycopy(Utils.bigIntegerToBytes(signature.r, 32), 0, sigData, 1, 32);
        System.arraycopy(Utils.bigIntegerToBytes(signature.s, 32), 0, sigData, 33, 32);

        return new String(Base64.encode(sigData));
    }

    private ECKey signedMessageToKey(String signatureB64, String message) throws SignatureException {
        byte[] signatureEncoded;
        try {
            signatureEncoded = Base64.decode(signatureB64);
        } catch (RuntimeException e) {
            throw new SignatureException("Could not decode base64", e);
        }

        if (signatureEncoded.length < 65)
            throw new SignatureException("Signature truncated, expected 65 bytes and got " + signatureEncoded.length);
        int header = signatureEncoded[0] & 0xFF;

        if (header < 27 || header > 34)
            throw new SignatureException("Header byte out of range: " + header);

        BigInteger r = new BigInteger(1, Arrays.copyOfRange(signatureEncoded, 1, 33));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(signatureEncoded, 33, 65));
        ECKey.ECDSASignature sig = new ECKey.ECDSASignature(r, s);
        byte[] messageBytes = formatMessageForSigning(message);
        Preconditions.checkNotNull(messageBytes, "Message bytes are null.");

        Sha256Hash messageHash = Sha256Hash.twiceOf(messageBytes);
        boolean compressed = false;
        if (header >= 31) {
            compressed = true;
            header -= 4;
        }
        int recId = header - 27;
        ECKey key = ECKey.recoverFromSignature(recId, sig, messageHash, compressed);
        if (key == null)
            throw new SignatureException("Could not recover public key from signature");
        return key;
    }

    private boolean verifyMessage(ECKey key, String signatureB64, String message) {
        boolean verified = false;
        try {
            ECKey k = signedMessageToKey(signatureB64, message);
            if (Arrays.equals(k.getPubKey(), key.getPubKey()))
                verified = true;
        } catch (SignatureException e) {
            LOGGER.warning("[http-server-handler] Error verifying message for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());
        }

        return verified;
    }

    private boolean isP2SHAddress(String address) {
        byte[] versionAndDataBytes = Base58.decodeChecked(address);
        int version = versionAndDataBytes[0] & 0xFF;
        return coin.getNetworkParameters().getP2SHHeader() == version;
    }

    private void getScriptType(JsonObject scriptPubKey, Script.ScriptType type) {
        String typeStr = "unknown";

        switch (type) {
            case P2PKH:
                typeStr = "pubkeyhash";
                break;
            case P2SH:
                typeStr = "scripthash";
                break;
            default:
                break;
        }

        scriptPubKey.addProperty("type", typeStr);
    }

    private UTXO getUtxo(Sha256Hash txid, long vout) {
        for (AddressBalance addressBalance : coin.getAddressKeyPairs()) {
            for (UTXO utxo : addressBalance.getUtxos()) {
                if (utxo.createUTXO().getHash().equals(txid) && utxo.getVout() == vout) {
                    return utxo;
                } else {
                    LOGGER.finer("[http-server-handler] DEBUG: UTXO " + utxo.createUTXO().getHash().toString() + " does not equal " + txid.toString());
                }
            }
        }

        return null;
    }

    private ECKey getSigningKey(Sha256Hash txid, long vout) {
        for (AddressBalance addressBalance : coin.getAddressKeyPairs()) {
            for (UTXO utxo : addressBalance.getUtxos()) {
                if (utxo.createUTXO().getHash().equals(txid) && utxo.getVout() == vout) {
                    return addressBalance.getPrivateKey().getKey();
                } else {
                    LOGGER.finer("[http-server-handler] DEBUG: UTXO " + utxo.createUTXO().getHash().toString() + " does not equal " + txid.toString());
                }
            }
        }

        return null;
    }

    private void getInvalidTxResponse(JsonObject response, Exception e) {
        LOGGER.warning("[http-server-handler] Error decoding raw tx for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());

        response.add("result", JsonNull.INSTANCE);
        JsonObject errorJSON = new JsonObject();
        errorJSON.addProperty("code", -1007);
        errorJSON.addProperty("message", "Error decoding raw tx. Invalid transaction?");

        response.add("error", errorJSON);
    }

    private void txConstructionError(JsonObject response, Exception e, String s) {
        LOGGER.warning("[http-server-handler] Error constructing transaction for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());

        response.add("result", JsonNull.INSTANCE);
        JsonObject errorJSON = new JsonObject();
        errorJSON.addProperty("code", -1006);
        errorJSON.addProperty("message", s);
        response.add("error", errorJSON);
    }

    private boolean isXRouterConfigInvalid(JsonObject response, BlocknetPeer blocknetPeer) {
        if (blocknetPeer.getxRouterConfiguration() == null) {
            response.add("result", JsonNull.INSTANCE);
            JsonObject errorJSON = new JsonObject();
            errorJSON.addProperty("code", -1005);
            errorJSON.addProperty("message", "Server has not received XRouter configuration from node.");
            response.add("error", errorJSON);

            return true;
        }
        return false;
    }

    private void getXRouterResponse(JsonObject response, CountDownLatch latch, AtomicReference<String> xRouterResult, BlocknetPeer blocknetPeer) {
        int timeoutPeriod = blocknetPeer.getxRouterConfiguration().getTimeout();

        try {
            if (timeoutPeriod == 0) {
                latch.await(10, TimeUnit.SECONDS);
            } else {
                latch.await(timeoutPeriod, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            LOGGER.warning("[http-server-handler] Interrupted waiting for XRouter response for " + CoinTickerUtils.tickerToString(coin.getTicker()) + ", " + e.getMessage());
        }

        if (xRouterResult.get() == null || xRouterResult.get().isEmpty()) {
            LOGGER.finer("[http-server-handler] ERROR: XRouter request timed out or errored! Timeout period = " + timeoutPeriod);

            response.add("result", JsonNull.INSTANCE);
            JsonObject errorJSON = new JsonObject();
            errorJSON.addProperty("code", -1004);
            errorJSON.addProperty("message", "XRouter request timed out");

            response.add("error", errorJSON);
        } else {
            JsonObject xRouterResObj = null;
            if (isValidJSON(xRouterResult.get())) {
                xRouterResObj = new Gson().fromJson(xRouterResult.get(), JsonObject.class);
            }

            if (xRouterResObj != null && xRouterResObj.has("result")) {
                if (xRouterResObj.has("code"))
                    response.add("code", xRouterResObj.get("code"));
                if (xRouterResObj.has("error"))
                    response.add("error", xRouterResObj.get("error"));
                response.add("result", xRouterResObj.get("result"));
            } else {
                if (xRouterResObj != null) {
                    response.add("result", xRouterResObj);
                } else {
                    response.addProperty("result", xRouterResult.get());
                }

                response.add("error", JsonNull.INSTANCE);
            }
        }
    }

    private boolean isValidJSON(String content) {
        try {
            new Gson().fromJson(content, JsonObject.class);
        } catch (JsonParseException jsExcp) {
            try {
                new Gson().fromJson(content, JsonArray.class);
            } catch (JsonParseException jsExcp1) {
                return false;
            }
        }
        return true;
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
