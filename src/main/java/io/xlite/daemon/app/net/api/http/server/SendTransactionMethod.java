package io.xlite.daemon.app.net.api.http.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.subgraph.orchid.encoders.Hex;

import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.net.api.http.client.HTTPClient;
import io.xlite.daemon.app.util.Sats;
import io.xlite.daemon.app.wallet.WalletHelper;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * {@code sendtransaction}: build, sign, and broadcast a payment from the wallet.
 *
 * <p>Sends are serialized per coin. Inputs are marked spent only after the broadcast succeeds, so
 * between selecting them and marking them another request could select the same outputs and build a
 * second transaction spending them. A {@code ReentrantLock} held on a per-coin map does that across
 * every connection: Netty creates a handler per channel, so a lock on the handler would be a
 * different lock per connection and would exclude nothing.
 *
 * <p>The lock is held across the network round trip on purpose. Releasing it before the broadcast
 * reopens exactly the window it closes, and a send is not latency-critical enough to trade a double
 * spend for.
 */
final class SendTransactionMethod {

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private static final Map<CoinTicker, ReentrantLock> SEND_LOCKS = new ConcurrentHashMap<>();

    private static final String USAGE =
            "Usage: sendtransaction address amount [dryrun]\n\n"
                + "address (string, required)\n"
                + "amount (number, required)\n"
                + "dryrun (boolean, optional) - build and sign the transaction and return it with"
                + " its fee breakdown instead of broadcasting it. For a chain whose transactions"
                + " carry nTime (Verge, Emercoin, Pocketcoin) the nTime is taken as the later of"
                + " the current time and the times of the parent transactions, so each input's"
                + " parent is fetched over the network first: this call is slower, and spending an"
                + " output whose parent is not yet available upstream fails rather than being sent"
                + " without a valid nTime.";

    private final CoinInstance coin;
    private final HTTPClient httpClient;
    private final WalletUtxos utxos;
    private final NTimeSource nTimes;

    SendTransactionMethod(
            CoinInstance coin, HTTPClient httpClient, WalletUtxos utxos, NTimeSource nTimes) {
        this.coin = coin;
        this.httpClient = httpClient;
        this.utxos = utxos;
        this.nTimes = nTimes;
    }

    Map<String, RpcMethod> methods() {
        return Map.of("sendtransaction", this::sendTransaction);
    }

    private JsonObject sendTransaction(JsonArray params) {
        if (params.size() < 2 || params.size() > 3) {
            return RpcResponse.usage(USAGE);
        }
        SendRequest request;
        try {
            request =
                    new SendRequest(
                            params.get(0).getAsString(),
                            params.get(1).getAsDouble(),
                            parseDryRunFlag(params, 2));
        } catch (RuntimeException e) {
            LOGGER.warning(
                    "[http-server-handler] Error parsing JSON in sendtransaction for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + ", "
                            + e.getMessage());
            // The cause, not a generic "Error parsing JSON!". This is a send, so a caller told
            // only that parsing failed cannot tell a malformed address from a bad dryrun value.
            return RpcResponse.error(
                    -1,
                    e.getMessage() != null ? e.getMessage() : "invalid sendtransaction arguments");
        }

        ReentrantLock sendLock =
                SEND_LOCKS.computeIfAbsent(coin.getTicker(), k -> new ReentrantLock());
        sendLock.lock();
        try {
            return send(request);
        } finally {
            sendLock.unlock();
        }
    }

    private JsonObject send(SendRequest request) {
        if (utxos.fetch() == null) {
            return RpcResponse.error(3002, "wallet not ready: could not load unspent outputs");
        }

        WalletHelper.BuildResult result;
        try {
            result =
                    WalletHelper.build(
                            new WalletHelper.BuildRequest(
                                    coin.getTicker(),
                                    request.address(),
                                    request.amount(),
                                    nTimes::forInputs));
        } catch (RuntimeException e) {
            LOGGER.warning(
                    "[http-server-handler] Error creating transaction in sendtransaction for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + ", "
                            + e.getMessage());
            return RpcResponse.error(-6, messageOf(e));
        }
        if (result.failure() != null) {
            return buildFailure(result.failure());
        }
        WalletHelper.BuiltTransaction built = result.transaction();

        String rawTxHex;
        try {
            rawTxHex =
                    TxWire.serializeForBroadcast(
                            coin.getTicker(), built.transaction(), built.nTime());
        } catch (RuntimeException e) {
            LOGGER.warning(
                    "[http-server-handler] Error serializing sendtransaction for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + ", "
                            + e.getMessage());
            return RpcResponse.error(-6, messageOf(e));
        }
        LOGGER.fine(
                "[http-server-handler] sendtransaction: rawTx size="
                        + rawTxHex.length()
                        + " bytes");
        LOGGER.finer("[http-server-handler] sendtransaction: rawTx hex=" + rawTxHex);

        if (request.dryRun()) {
            return RpcResponse.ok(
                    buildDryRunResult(coin.getNetworkParameters(), coinFactor(), rawTxHex, built));
        }
        return broadcast(built.transaction(), rawTxHex);
    }

    private JsonObject broadcast(Transaction transaction, String rawTxHex) {
        JsonObject txid = httpClient.sendRawTransaction(coin.getTicker(), rawTxHex);
        if (txid == null || RpcResponse.hasError(txid)) {
            RpcResponse.ProviderError error =
                    RpcResponse.providerError(txid, -1, "Error sending transaction!");
            LOGGER.warning(
                    "[http-server-handler] sendrawtransaction failed for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + " code="
                            + error.code()
                            + " message="
                            + error.message());
            return RpcResponse.error(error.code(), error.message());
        }
        utxos.markSpent(transaction);
        if (txid.has("result")) {
            return RpcResponse.ok(txid.get("result"));
        }
        JsonObject response = new JsonObject();
        buildSendFallbackResult(response, txid);
        return response;
    }

    /**
     * The failure envelope for a transaction the wallet could not build.
     *
     * <p>The builder reports several unrelated failures the same way, so the reason is reported
     * rather than assumed: naming an nTime failure for an empty wallet would send the caller
     * looking for an upstream problem that was never involved.
     */
    private static JsonObject buildFailure(WalletHelper.BuildFailure reason) {
        JsonObject response = RpcResponse.error(-6, "Failed to create transaction");
        JsonObject error = response.getAsJsonObject("error");
        error.addProperty("failureReason", reason == null ? "UNKNOWN" : reason.name());
        if (reason == WalletHelper.BuildFailure.NTIME_UNRESOLVED) {
            error.addProperty("nTimeResolved", false);
        }
        return response;
    }

    /**
     * Optional trailing boolean flag, accepting the same true/1 spellings as the getrawtransaction
     * verbose flag.
     *
     * <p>Read with {@code getAsString} rather than {@code toString} so a quoted {@code "true"}
     * works as well as a bare JSON boolean. A missing value means false, so a two-parameter call
     * keeps its behavior; a value that is neither spelling is refused rather than guessed at,
     * because a misspelled dryrun read as false would broadcast the transaction the caller asked to
     * review first.
     */
    static boolean parseDryRunFlag(JsonArray params, int index) {
        if (params.size() <= index) {
            return false;
        }
        JsonElement element = params.get(index);
        // A JSON null is an omitted flag: that is how an explicit null reads to every JSON caller,
        // and how a pre-flag two-argument call arrives.
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (!element.isJsonPrimitive()) {
            throw new IllegalArgumentException("dryrun must be true or false, got " + element);
        }
        String value = element.getAsString();
        if (value.equalsIgnoreCase("true") || value.equals("1")) {
            return true;
        }
        if (value.equalsIgnoreCase("false") || value.equals("0")) {
            return false;
        }
        throw new IllegalArgumentException("dryrun must be true or false, got '" + value + "'");
    }

    /**
     * The {@code sendtransaction} success response when the upstream envelope carries no {@code
     * "result"} member.
     *
     * <p>Some upstreams return the txid as the whole envelope rather than nested under {@code
     * "result"}. The caller still asked for a txid, so the envelope itself is the result.
     */
    static void buildSendFallbackResult(JsonObject response, JsonObject envelope) {
        response.add("result", envelope);
        response.add("error", JsonNull.INSTANCE);
    }

    /**
     * Dry-run review payload: the exact bytes that would be broadcast, the txid those bytes hash
     * to, and the fee the wallet is charging.
     *
     * <p>The txid is derived from the returned hex, not from {@code transaction.getHash()}:
     * time-field coins serialize an nTime that bitcoinj's in-memory hash does not cover, so the
     * object hash belongs to a different transaction than the bytes on the wire.
     *
     * <p>The fee is passed in rather than recomputed: {@code fundTransaction} charges for one input
     * more than the finished transaction has, so a formula over the finished transaction
     * understates the real charge whenever the linear term wins.
     */
    static JsonObject buildDryRunResult(
            NetworkParameters params,
            long coinUnit,
            String rawTxHex,
            WalletHelper.BuiltTransaction built) {
        Transaction transaction = built.transaction();
        JsonObject result = new JsonObject();
        result.addProperty("dryRun", true);
        result.addProperty("txid", TxWire.txidForHex(rawTxHex));
        result.addProperty("hex", rawTxHex);
        result.addProperty("sizeBytes", Hex.decode(rawTxHex).length);
        result.addProperty("inputs", transaction.getInputs().size());
        result.addProperty("outputs", transaction.getOutputs().size());
        result.addProperty("feeSats", built.feeSats());
        result.addProperty("fee", Sats.toWholeCoins(built.feeSats(), coinUnit));
        result.addProperty("feePerByte", WalletHelper.getFeePerByte(params));
        result.addProperty("minTxFee", WalletHelper.getMinTxFee(params));
        result.addProperty("coinUnit", coinUnit);
        return result;
    }

    private long coinFactor() {
        return CoinConfigRegistry.get(CoinTickerUtils.tickerToString(coin.getTicker()))
                .coinFactor();
    }

    private static String messageOf(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** One send request, parsed and validated. */
    private record SendRequest(String address, double amount, boolean dryRun) {}
}
