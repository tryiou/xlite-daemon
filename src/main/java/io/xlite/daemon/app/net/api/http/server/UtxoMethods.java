package io.xlite.daemon.app.net.api.http.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.subgraph.orchid.encoders.Hex;

import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.net.api.http.client.HTTPClient;
import io.xlite.daemon.app.util.AddressBalance;
import io.xlite.daemon.app.util.Sats;
import io.xlite.daemon.app.util.UTXO;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.script.Script;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/** Wallet unspent outputs: the cached set, and one output resolved against provider data. */
final class UtxoMethods {

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private static final String GET_TX_OUT_USAGE =
            "Usage: gettxout txid n [include_mempool]\\n"
                    + "\\n"
                    + "txid (string, required) - The transaction ID\\n"
                    + "n (numeric, required) - The vout value\\n"
                    + "include_mempool (boolean, optional, default=true) - Whether to include the"
                    + " mempool";

    private final CoinInstance coin;
    private final HTTPClient httpClient;
    private final WalletUtxos utxos;

    UtxoMethods(CoinInstance coin, HTTPClient httpClient, WalletUtxos utxos) {
        this.coin = coin;
        this.httpClient = httpClient;
        this.utxos = utxos;
    }

    Map<String, RpcMethod> methods() {
        return Map.of("listunspent", params -> listUnspent(), "gettxout", this::getTxOut);
    }

    private JsonObject listUnspent() {
        JsonArray unspent = utxos.fetch();
        if (unspent == null) {
            return RpcResponse.error(3002, "listunspent not ready");
        }
        return RpcResponse.ok(unspent);
    }

    private JsonObject getTxOut(JsonArray params) {
        if (params.size() < 2 || params.size() > 3) {
            return RpcResponse.usage(GET_TX_OUT_USAGE);
        }
        String txid = params.get(0).getAsString();
        int vout = params.get(1).getAsInt();
        boolean includeMempool = params.size() != 3 || params.get(2).getAsBoolean();

        UTXO cached = utxos.find(Sha256Hash.wrap(txid), vout);
        if (cached != null) {
            LOGGER.fine("[http-server-handler] Using cached UTXO for gettxout");
            return RpcResponse.ok(cachedUtxo(cached));
        }
        if (!includeMempool) {
            LOGGER.warning(
                    "[http-server-handler] WARNING: Client requested UTXO that is not ours!");
            return RpcResponse.error(-5, "Invalid or non-wallet transaction ID (not ours)");
        }
        return RpcResponse.ok(providerUtxo(txid, vout));
    }

    private JsonObject cachedUtxo(UTXO requested) {
        org.bitcoinj.core.UTXO utxo = requested.createUTXO();
        JsonObject result = new JsonObject();
        result.addProperty(
                "confirmations",
                CoinInstance.getBlockCountByTicker(coin.getTicker()) - utxo.getHeight() + 1);
        result.addProperty("value", Sats.toWholeCoins(utxo.getValue().value, coinFactor()));

        Script script = utxo.getScript();
        JsonObject scriptPubKey = new JsonObject();
        scriptPubKey.addProperty("asm", script.toString());
        scriptPubKey.addProperty(
                "hex", new String(Hex.encode(script.getProgram()), StandardCharsets.UTF_8));
        scriptPubKey.addProperty("reqSigs", script.getNumberOfSignaturesRequiredToSpend());
        scriptPubKey.addProperty("type", BitcoinScripts.typeName(script.getScriptType()));

        JsonArray addresses = new JsonArray();
        addresses.add(utxo.getAddress());
        scriptPubKey.add("addresses", addresses);

        result.add("scriptPubKey", scriptPubKey);
        result.addProperty("coinbase", utxo.isCoinbase());
        return result;
    }

    /**
     * One upstream ask, no wait loop: a missing transaction is code -5 at once; waiting for fresh
     * broadcasts belongs to the callers, which poll on their own cadence.
     */
    private JsonObject providerUtxo(String txid, int vout) {
        JsonObject transaction = httpClient.getTransaction(coin.getTicker(), txid, true);
        if (transaction == null
                || !transaction.has("result")
                || transaction.get("result").isJsonNull()) {
            return RpcResponse.error(-5, "Invalid transaction ID");
        }
        try {
            JsonObject result = transaction.getAsJsonObject("result");
            JsonElement confirmations = result.get("confirmations");
            JsonArray outputs = result.getAsJsonArray("vout");
            if (outputs.size() <= vout) {
                return RpcResponse.error(-5, "Invalid transaction output index");
            }
            JsonObject entry = outputs.get(vout).getAsJsonObject();
            JsonObject scriptPubKey = entry.getAsJsonObject("scriptPubKey");
            String address = addressOf(scriptPubKey);
            if (!isWalletAddress(address)) {
                LOGGER.warning(
                        "[http-server-handler] WARNING: Client requested UTXO that cannot be"
                                + " ours!");
                return RpcResponse.error(
                        -5, "Invalid or non-wallet transaction ID (cannot be ours)");
            }
            int count = confirmations != null ? confirmations.getAsInt() : 0;
            if (count > 0 && !isUnspent(address, txid, vout)) {
                LOGGER.warning(
                        "[http-server-handler] WARNING: Client requested UTXO that was already"
                                + " spent!");
                return RpcResponse.error(
                        -5, "Invalid or non-wallet transaction ID (already spent)");
            }
            JsonObject confirmed = new JsonObject();
            confirmed.addProperty("confirmations", count);
            confirmed.add("value", entry.get("value"));
            confirmed.add("scriptPubKey", scriptPubKey);
            confirmed.addProperty("coinbase", false);
            return confirmed;
        } catch (RuntimeException e) {
            LOGGER.warning(
                    "[http-server-handler] Error parsing transaction in gettxout for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + ", "
                            + e.getMessage());
            return RpcResponse.error(-1010, "Error while parsing transaction");
        }
    }

    /** The address a provider {@code scriptPubKey} describes, preferring the singular member. */
    private static String addressOf(JsonObject scriptPubKey) {
        JsonElement address = scriptPubKey.get("address");
        if (address != null) {
            return address.getAsString();
        }
        return scriptPubKey.getAsJsonArray("addresses").asList().get(0).getAsString();
    }

    private boolean isWalletAddress(String address) {
        for (AddressBalance balance : coin.getAddressKeyPairs()) {
            if (address.equals(balance.getAddress().toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a confirmed output is still unspent.
     *
     * <p>Only confirmed transactions are checked: the backend may return a transaction before its
     * outputs appear in the unspent set, so an unconfirmed output would be reported spent while the
     * broadcast is still settling.
     */
    private boolean isUnspent(String address, String txid, int vout) {
        JsonArray unspent = httpClient.getUtxosUncached(coin.getTicker(), new String[] {address});
        for (JsonElement entry : unspent.asList()) {
            JsonObject utxo = entry.getAsJsonObject();
            if (utxo.get("txid").getAsString().equals(txid)
                    && utxo.get("vout").getAsInt() == vout) {
                return true;
            }
        }
        return false;
    }

    private long coinFactor() {
        return CoinConfigRegistry.get(CoinTickerUtils.tickerToString(coin.getTicker()))
                .coinFactor();
    }
}
