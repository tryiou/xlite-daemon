package io.xlite.daemon.app.net.api.http.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.net.api.http.client.HTTPClient;

import java.util.Map;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/** Chain reads served from provider data: blocks, transactions and the mempool. */
final class ChainMethods {

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private final CoinInstance coin;
    private final HTTPClient httpClient;

    ChainMethods(CoinInstance coin, HTTPClient httpClient) {
        this.coin = coin;
        this.httpClient = httpClient;
    }

    Map<String, RpcMethod> methods() {
        return Map.of(
                "getblockhash", this::blockHash,
                "getblock", this::block,
                "gettransaction", this::transaction,
                "getrawtransaction", this::rawTransaction,
                "getrawmempool", this::rawMempool);
    }

    private JsonObject blockHash(JsonArray params) {
        if (params.size() != 1) {
            return RpcResponse.usage("Usage: getblockhash index\n\nindex (num, required)");
        }
        int blockIndex = params.get(0).getAsInt();
        JsonObject blockHash = httpClient.getBlockHash(coin.getTicker(), blockIndex);
        if (blockHash == null || RpcResponse.hasError(blockHash)) {
            return RpcResponse.error(-1, "Error obtaining blockhash!");
        }
        return RpcResponse.fromEnvelope(blockHash);
    }

    private JsonObject block(JsonArray params) {
        if (params.size() != 1) {
            return RpcResponse.usage(
                    "Usage: getblock hash\n\nhash (string, required) - Hash of block to retrieve");
        }
        String hash;
        try {
            hash = params.get(0).getAsString().replaceAll("\"", "");
        } catch (JsonParseException e) {
            LOGGER.warning(
                    "[http-server-handler] Error parsing JSON in getblock for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + ", "
                            + e.getMessage());
            return RpcResponse.error(-1, "Error parsing JSON!");
        }
        JsonObject block = httpClient.getBlock(coin.getTicker(), hash, true);
        if (block == null || RpcResponse.hasError(block)) {
            return RpcResponse.error(-1, "Error while retrieving block");
        }
        return RpcResponse.fromEnvelope(block);
    }

    private JsonObject transaction(JsonArray params) {
        if (params.size() != 1) {
            return RpcResponse.usage(
                    "Usage: gettransaction txid\n\ntxid (string, required) - The transaction ID");
        }
        String txid = params.get(0).getAsString();
        JsonObject transaction = httpClient.getTransaction(coin.getTicker(), txid, true);
        if (transaction == null
                || !transaction.has("result")
                || transaction.get("result").isJsonNull()) {
            return RpcResponse.error(-1, "Error while obtaining transaction!");
        }
        return RpcResponse.ok(transaction.get("result"));
    }

    private JsonObject rawTransaction(JsonArray params) {
        if (params.size() < 1 || params.size() > 2) {
            return RpcResponse.usage(
                    "Usage: getrawtransaction\n\ntxid (string, required) - TXID is required,"
                            + " verbose(optional)");
        }
        String txid = params.get(0).getAsString();
        boolean verbose =
                params.size() == 2
                        && (params.get(1).toString().equalsIgnoreCase("true")
                                || params.get(1).toString().equals("1"));
        JsonObject rawTransaction = httpClient.getRawTransaction(coin.getTicker(), txid, verbose);
        if (rawTransaction == null || RpcResponse.hasError(rawTransaction)) {
            return RpcResponse.error(-5, "No information available about transaction");
        }
        return RpcResponse.fromEnvelope(rawTransaction);
    }

    private JsonObject rawMempool(JsonArray params) {
        if (params.size() > 2) {
            return RpcResponse.usage(
                    "Usage: getrawmempool\n\nverbose (int, optional) - VERBOSE is optional");
        }
        boolean verbose = params.size() == 2 && isTruthy(params.get(1));
        JsonObject rawMempool = httpClient.getRawMempool(coin.getTicker(), verbose);
        if (rawMempool == null || RpcResponse.hasError(rawMempool)) {
            return RpcResponse.error(-1, "No information available");
        }
        return RpcResponse.fromEnvelope(rawMempool);
    }

    /** The verbose spellings the legacy API accepts: a bare {@code true} or {@code 1}. */
    private static boolean isTruthy(JsonElement value) {
        String text = value.toString();
        return text.equalsIgnoreCase("true") || text.equals("1");
    }
}
