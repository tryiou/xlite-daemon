package io.xlite.daemon.app.net.api.http.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.subgraph.orchid.encoders.Hex;

import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.net.electrumx.ElectrumApi;
import io.xlite.daemon.app.net.electrumx.ElectrumApi.Call;
import io.xlite.daemon.app.net.electrumx.ElectrumException;
import io.xlite.daemon.app.net.electrumx.ScriptHashes;
import io.xlite.daemon.app.util.UTXO;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * The remote provider behind the coin's local RPC surface.
 *
 * <p>Every call is a native ElectrumX request on the coin's one provider connection. There is no
 * second transport and no fallback: a coin whose provider is unreachable returns {@code null},
 * which the existing handlers already read as "not ready". The local RPC contract is unchanged;
 * only the source of the data moved.
 *
 * <p>Stock ElectrumX has no block-by-hash and no raw mempool. Those two calls, plus {@code
 * getblockhash}, are answered by the node on the same socket; they are the node's own methods, not
 * a second endpoint.
 */
public class HTTPClient {

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private static final long REQUEST_TIMEOUT_SECONDS = 60;

    private final ConcurrentHashMap<CoinTicker, Long> lastFetchTimes = new ConcurrentHashMap<>();

    public HTTPClient() {}

    /** Kept for callers that build one per channel; the connection is owned by the coin. */
    public void close() {
        // The provider session is CoinInstance-owned and outlives every RPC channel.
    }

    // -- UTXOs -------------------------------------------------------------

    /**
     * Returns all unspent outputs for the given addresses, in the local RPC row shape.
     *
     * @param coinTicker Coin to query
     * @param addresses Addresses to query
     * @return rows, or null when the provider is unavailable
     */
    public JsonArray getUtxosUncached(CoinTicker coinTicker, String[] addresses) {
        UtxoBatch batch = fetchUtxos(coinTicker, addresses);
        return batch == null ? null : renderUtxos(coinTicker, batch.utxos());
    }

    /**
     * Returns the wallet's unspent set, refreshing it from the provider when the cache is stale.
     *
     * @param coinTicker Coin to query
     * @param expiry Time in milliseconds until the fetched set expires
     * @return the local RPC's unspent rows, or null when the provider is unavailable
     */
    public JsonArray getUtxos(CoinTicker coinTicker, int expiry) {
        CoinInstance coin = CoinInstance.getInstance(coinTicker);
        if (coin == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        Long last = lastFetchTimes.get(coinTicker);
        if (last != null && now - last < expiry) {
            return coin.getAllUTXOS();
        }

        List<String> addresses = new ArrayList<>();
        for (var balance : coin.getAddressKeyPairs()) {
            addresses.add(balance.getAddress().toBase58());
        }
        if (addresses.isEmpty()) {
            return coin.getAllUTXOS();
        }

        UtxoBatch batch = fetchUtxos(coinTicker, addresses.toArray(new String[0]));
        if (batch == null) {
            // An outage must never wipe the wallet's stored rows.
            return null;
        }
        evictOmittedAddresses(coin, coin.getUTXOParams(), batch.addresses());
        lastFetchTimes.put(coinTicker, now);
        coin.processUtxos(batch.utxos());
        return coin.getAllUTXOS();
    }

    private UtxoBatch fetchUtxos(CoinTicker coinTicker, String[] addresses) {
        ElectrumApi api = api(coinTicker);
        CoinInstance coin = CoinInstance.getInstance(coinTicker);
        if (api == null || coin == null || coin.getNetworkParameters() == null) {
            return null;
        }
        List<Call> calls = listUnspentCalls(coinTicker, coin.getNetworkParameters(), addresses);
        if (calls == null) {
            return null;
        }
        List<CompletableFuture<JsonElement>> futures = api.batch(calls);
        List<UTXO> utxos = new ArrayList<>();
        Set<String> returned = new HashSet<>();
        for (int index = 0; index < addresses.length; index++) {
            JsonElement result = awaitUtxos(coinTicker, addresses[index], futures.get(index));
            if (result == null) {
                return null;
            }
            if (collectUtxos(coinTicker, addresses[index], result, utxos) > 0) {
                returned.add(addresses[index]);
            }
        }
        return new UtxoBatch(utxos, returned);
    }

    /** One {@code scripthash.listunspent} call per address, or null when an address cannot hash. */
    private static List<Call> listUnspentCalls(
            CoinTicker coinTicker, NetworkParameters parameters, String[] addresses) {
        List<Call> calls = new ArrayList<>();
        for (String address : addresses) {
            try {
                calls.add(
                        new Call(
                                "blockchain.scripthash.listunspent",
                                args(
                                        ScriptHashes.scripthash(
                                                Address.fromString(parameters, address)))));
            } catch (RuntimeException error) {
                LOGGER.warning(
                        "[provider] "
                                + CoinTickerUtils.tickerToString(coinTicker)
                                + " cannot derive script hash for "
                                + address
                                + ": "
                                + error.getMessage());
                return null;
            }
        }
        return calls;
    }

    private JsonElement awaitUtxos(
            CoinTicker coinTicker, String address, CompletableFuture<JsonElement> future) {
        JsonElement result;
        try {
            result = future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException error) {
            // One line, no stack: an unserved coin is an expected state, not a programming fault.
            Throwable cause = error.getCause() == null ? error : error.getCause();
            String detail =
                    cause.getMessage() == null
                            ? cause.getClass().getSimpleName()
                            : cause.getMessage();
            LOGGER.warning(
                    "[provider] "
                            + CoinTickerUtils.tickerToString(coinTicker)
                            + " listunspent failed for "
                            + address
                            + ": "
                            + detail);
            return null;
        }
        if (result == null || !result.isJsonArray()) {
            LOGGER.warning(
                    "[provider] "
                            + CoinTickerUtils.tickerToString(coinTicker)
                            + " malformed listunspent for "
                            + address);
            return null;
        }
        return result;
    }

    /** Appends one address's unspent rows; returns how many were appended. */
    private static int collectUtxos(
            CoinTicker coinTicker, String address, JsonElement result, List<UTXO> utxos) {
        int added = 0;
        for (JsonElement element : result.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            utxos.add(
                    new UTXO(
                            coinTicker,
                            address,
                            row.get("tx_hash").getAsString(),
                            row.get("tx_pos").getAsInt(),
                            row.get("height").getAsInt(),
                            row.get("value").getAsLong()));
            added++;
        }
        return added;
    }

    private JsonArray renderUtxos(CoinTicker coinTicker, List<UTXO> utxos) {
        CoinInstance coin = CoinInstance.getInstance(coinTicker);
        NetworkParameters parameters = coin.getNetworkParameters();
        int tip = CoinInstance.getBlockCountByTicker(coinTicker);
        JsonArray rows = new JsonArray();
        for (UTXO utxo : utxos) {
            Script script =
                    ScriptBuilder.createOutputScript(
                            Address.fromString(parameters, utxo.getAddress()));
            JsonObject row = new JsonObject();
            row.addProperty("txid", utxo.getTxid());
            row.addProperty("vout", utxo.getVout());
            row.addProperty("value", utxo.getAmount());
            row.addProperty("spendable", true);
            row.addProperty("address", utxo.getAddress());
            row.addProperty(
                    "scriptPubKey",
                    new String(Hex.encode(script.getProgram()), StandardCharsets.UTF_8));
            int height = utxo.getHeight();
            row.addProperty("confirmations", height == 0 || tip <= 0 ? 0 : tip - height + 1);
            rows.add(row);
        }
        return rows;
    }

    private record UtxoBatch(List<UTXO> utxos, Set<String> addresses) {}

    /**
     * Clears stored UTXO rows for tracked addresses the provider omitted from a successful
     * response.
     *
     * <p>{@code processUtxos} only clears addresses present in the response, so without this an
     * address with no unspent outputs keeps its dead rows forever.
     */
    void evictOmittedAddresses(
            CoinInstance coinInstance, List<String> requestedParams, Set<String> returned) {
        Set<String> requested = new HashSet<>();
        try {
            if (requestedParams.size() > 1) {
                JsonArray array =
                        com.google.gson.JsonParser.parseString(requestedParams.get(1))
                                .getAsJsonArray();
                for (JsonElement element : array) {
                    requested.add(element.getAsString());
                }
            }
        } catch (RuntimeException error) {
            LOGGER.warning("[provider] cannot parse requested addresses: " + error.getMessage());
            return;
        }
        for (String address : requested) {
            if (returned.contains(address)) {
                continue;
            }
            var balance = coinInstance.getAddressBalance(address);
            if (balance == null) {
                continue;
            }
            balance.clearUtxos();
            balance.calculateBalance();
        }
    }

    // -- transactions ------------------------------------------------------

    public JsonObject getRawTransaction(CoinTicker coinTicker, String txid, boolean verbose) {
        return transaction(coinTicker, txid, verbose);
    }

    public JsonObject getTransaction(CoinTicker coinTicker, String txid, boolean verbose) {
        return transaction(coinTicker, txid, verbose);
    }

    private JsonObject transaction(CoinTicker coinTicker, String txid, boolean verbose) {
        JsonElement result = call(coinTicker, "blockchain.transaction.get", args(txid, verbose));
        return result == null ? null : ok(result);
    }

    public JsonObject sendRawTransaction(CoinTicker coinTicker, String rawTx) {
        ElectrumApi api = api(coinTicker);
        if (api == null) {
            return null;
        }
        try {
            JsonElement result =
                    api.request("blockchain.transaction.broadcast", args(rawTx))
                            .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return result == null ? null : ok(result);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof ElectrumException rejected) {
                return error(rejected.getCode(), rejected.getMessage());
            }
            LOGGER.warning(
                    "[provider] broadcast failed for "
                            + CoinTickerUtils.tickerToString(coinTicker)
                            + ": "
                            + cause);
            return null;
        } catch (TimeoutException error) {
            LOGGER.warning(
                    "[provider] broadcast timed out for "
                            + CoinTickerUtils.tickerToString(coinTicker));
            return null;
        }
    }

    // -- blocks and mempool ------------------------------------------------

    public JsonObject getBlock(CoinTicker coinTicker, String hash, boolean verbose) {
        JsonElement result = call(coinTicker, "getblock", args(hash, verbose));
        return result == null ? null : ok(result);
    }

    public JsonObject getBlockHash(CoinTicker coinTicker, int height) {
        JsonElement result = call(coinTicker, "getblockhash", args(height));
        return result == null ? null : ok(result);
    }

    public JsonObject getRawMempool(CoinTicker coinTicker, boolean verbose) {
        JsonElement result = call(coinTicker, "getrawmempool", args(verbose));
        return result == null ? null : ok(result);
    }

    // -- transport ---------------------------------------------------------

    /** Overridable so a unit test can supply a fake native session. */
    ElectrumApi api(CoinTicker coinTicker) {
        CoinInstance coin = CoinInstance.getInstance(coinTicker);
        return coin == null ? null : coin.getElectrumApi();
    }

    private JsonElement call(CoinTicker coinTicker, String method, JsonArray params) {
        ElectrumApi api = api(coinTicker);
        if (api == null) {
            return null;
        }
        try {
            return api.request(method, params).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            LOGGER.warning(
                    "[provider] "
                            + CoinTickerUtils.tickerToString(coinTicker)
                            + " "
                            + method
                            + " failed: "
                            + (cause == null ? error : cause.getMessage()));
            return null;
        } catch (TimeoutException error) {
            LOGGER.warning(
                    "[provider] "
                            + CoinTickerUtils.tickerToString(coinTicker)
                            + " "
                            + method
                            + " timed out");
            return null;
        }
    }

    private static JsonObject ok(JsonElement result) {
        JsonObject envelope = new JsonObject();
        envelope.add("result", result);
        envelope.add("error", com.google.gson.JsonNull.INSTANCE);
        return envelope;
    }

    private static JsonObject error(int code, String message) {
        JsonObject envelope = new JsonObject();
        envelope.add("result", com.google.gson.JsonNull.INSTANCE);
        JsonObject detail = new JsonObject();
        detail.addProperty("code", code);
        detail.addProperty("message", message == null ? "" : message);
        envelope.add("error", detail);
        return envelope;
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
            } else {
                throw new IllegalArgumentException("unsupported provider argument " + value);
            }
        }
        return array;
    }
}
