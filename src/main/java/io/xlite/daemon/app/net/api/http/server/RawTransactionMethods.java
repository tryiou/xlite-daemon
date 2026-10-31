package io.xlite.daemon.app.net.api.http.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.subgraph.orchid.encoders.Hex;

import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.net.api.http.client.HTTPClient;
import io.xlite.daemon.app.util.CoinTransactions;
import io.xlite.daemon.app.util.Sats;
import io.xlite.daemon.app.util.TxTimeField;
import io.xlite.daemon.app.util.TxTimeFieldSigner;
import io.xlite.daemon.app.util.UTXO;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/** Raw transaction methods: build, decode, sign and broadcast a hex transaction. */
final class RawTransactionMethods {

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private static final String CREATE_USAGE =
            "Usage: createrawtransaction inputs outputs\n\n"
                    + "inputs (string, required) - Inputs in JSON format\n"
                    + "example: [{\"txid\": \"id\", \"vout\": n}, ...]\n\n"
                    + "outputs (string, required) - Outputs in JSON format\n"
                    + "example (legacy): {\"address1\": amount1, \"address2\": amount2, ...}\n"
                    + "example (new): [{\"address\": \"address1\", \"amount\": amount1},"
                    + " {\"address\": \"address1\", \"amount\": amount2}, ...]";

    private static final String DECODE_USAGE =
            "Usage: decoderawtransaction rawtx\n\n"
                    + "rawtx (string, required) - The raw transaction to decode, hex encoded.";

    private static final String SIGN_USAGE =
            "Usage: signrawtransaction rawtx\n\n"
                    + "rawtx (string, required) - The raw transaction to sign, hex encoded.";

    private static final String SEND_USAGE =
            "Usage: sendrawtransaction hex-tx\n\n"
                    + "hex-tx (string, required) - Raw transaction, hex encoded";

    private final CoinInstance coin;
    private final HTTPClient httpClient;
    private final WalletUtxos utxos;
    private final NTimeSource nTimes;

    RawTransactionMethods(
            CoinInstance coin, HTTPClient httpClient, WalletUtxos utxos, NTimeSource nTimes) {
        this.coin = coin;
        this.httpClient = httpClient;
        this.utxos = utxos;
        this.nTimes = nTimes;
    }

    Map<String, RpcMethod> methods() {
        return Map.of(
                "createrawtransaction", this::createRawTransaction,
                "decoderawtransaction", this::decodeRawTransaction,
                "signrawtransaction", this::signRawTransaction,
                "sendrawtransaction", this::sendRawTransaction);
    }

    private JsonObject createRawTransaction(JsonArray params) {
        if (params.size() < 2 || params.size() > 3) {
            return RpcResponse.usage(CREATE_USAGE);
        }
        long locktime = params.size() >= 3 ? params.get(2).getAsLong() : 0;
        JsonArray inputs;
        List<OutputEntry> outputs;
        try {
            inputs = params.get(0).getAsJsonArray();
            requireInputFields(inputs);
            outputs = parseOutputs(params.get(1));
        } catch (JsonParseException e) {
            LOGGER.warning(
                    "[http-server-handler] ERROR: Error while parsing JSON for"
                            + " createrawtransaction!");
            return RpcResponse.error(-2, "Error parsing JSON");
        }

        Transaction transaction =
                CoinTransactions.newTransaction(coin.getNetworkParameters(), coin.getTicker());
        if (locktime > 0 && !transaction.isTimeLocked()) {
            transaction.setLockTime(locktime);
        }
        try {
            addInputs(transaction, inputs);
        } catch (RuntimeException e) {
            return constructionError("Error while constructing transaction (input phase)", e);
        }
        try {
            addOutputs(transaction, outputs);
        } catch (RuntimeException e) {
            return constructionError("Error while constructing transaction (output phase)", e);
        }
        return RpcResponse.ok(TxWire.serializeUnsigned(coin.getTicker(), transaction));
    }

    private JsonObject constructionError(String message, RuntimeException cause) {
        LOGGER.warning(
                "[http-server-handler] ERROR: "
                        + message
                        + " for "
                        + CoinTickerUtils.tickerToString(coin.getTicker())
                        + ", "
                        + cause.getMessage());
        return RpcResponse.error(-1006, message);
    }

    private static void requireInputFields(JsonArray inputs) {
        for (JsonElement element : inputs) {
            JsonObject input = element.getAsJsonObject();
            if (!input.has("txid") || !input.has("vout")) {
                throw new JsonParseException("Bad transaction input.");
            }
        }
    }

    /** Both output shapes: the legacy address-keyed object, and the array that allows repeats. */
    private static List<OutputEntry> parseOutputs(JsonElement outputs) {
        List<OutputEntry> entries = new ArrayList<>();
        if (outputs.isJsonArray()) {
            for (JsonElement element : outputs.getAsJsonArray()) {
                JsonObject output = element.getAsJsonObject();
                if (!output.has("address") || !output.has("amount")) {
                    throw new JsonParseException("Output entry must have 'address' and 'amount'");
                }
                entries.add(
                        new OutputEntry(
                                output.get("address").getAsString(),
                                output.get("amount").getAsDouble()));
            }
        } else if (outputs.isJsonObject()) {
            for (Map.Entry<String, JsonElement> output : outputs.getAsJsonObject().entrySet()) {
                entries.add(new OutputEntry(output.getKey(), output.getValue().getAsDouble()));
            }
        } else {
            throw new JsonParseException("Invalid outputs format");
        }
        return entries;
    }

    private void addInputs(Transaction transaction, JsonArray inputs) {
        for (JsonElement element : inputs) {
            JsonObject input = element.getAsJsonObject();
            transaction.addInput(
                    Sha256Hash.wrap(input.get("txid").getAsString()),
                    input.get("vout").getAsInt(),
                    ScriptBuilder.createInputScript(null));
        }
    }

    /**
     * Adds the outputs in two passes, script-hash outputs first.
     *
     * <p>The order is part of the transaction's bytes, so it must not depend on the order the
     * caller listed them in.
     */
    private void addOutputs(Transaction transaction, List<OutputEntry> outputs) {
        long coinUnit = coinFactor();
        for (OutputEntry output : outputs) {
            addOutput(transaction, output, coinUnit, true);
        }
        for (OutputEntry output : outputs) {
            addOutput(transaction, output, coinUnit, false);
        }
    }

    private void addOutput(
            Transaction transaction, OutputEntry output, long coinUnit, boolean scriptHash) {
        LegacyAddress address =
                LegacyAddress.fromBase58(coin.getNetworkParameters(), output.address());
        boolean isScriptHash =
                BitcoinScripts.isPayToScriptHash(coin.getNetworkParameters(), output.address());
        if (isScriptHash != scriptHash) {
            return;
        }
        Coin value = Coin.valueOf(Sats.fromWholeCoins(output.amount(), coinUnit));
        if (isScriptHash) {
            transaction.addOutput(value, ScriptBuilder.createP2SHOutputScript(address.getHash()));
        } else {
            transaction.addOutput(value, address);
        }
    }

    private JsonObject decodeRawTransaction(JsonArray params) {
        if (params.size() != 1) {
            return RpcResponse.usage(DECODE_USAGE);
        }
        byte[] rawBytes;
        Transaction transaction;
        try {
            rawBytes = Hex.decode(params.get(0).getAsString());
            transaction =
                    TxWire.parseForCoin(coin.getTicker(), coin.getNetworkParameters(), rawBytes);
        } catch (RuntimeException e) {
            LOGGER.warning(
                    "[http-server-handler] Error decoding raw transaction in"
                            + " decoderawtransaction for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + ", "
                            + e.getMessage());
            return RpcResponse.error(-1007, "Error decoding raw tx. Invalid transaction?");
        }

        JsonObject decoded = new JsonObject();
        decoded.addProperty("txid", TxWire.txidForDecoded(coin.getTicker(), rawBytes, transaction));
        decoded.addProperty("version", transaction.getVersion());
        decoded.addProperty("locktime", transaction.getLockTime());

        JsonArray vin;
        try {
            vin = decodeInputs(transaction);
        } catch (RuntimeException e) {
            LOGGER.warning(
                    "[http-server-handler] Error parsing transaction inputs for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + ", "
                            + e.getMessage());
            return RpcResponse.error(-1008, "Error while parsing transaction inputs");
        }
        decoded.add("vin", vin);

        JsonArray vout;
        try {
            vout = decodeOutputs(transaction);
        } catch (RuntimeException e) {
            LOGGER.warning(
                    "[http-server-handler] Error parsing transaction outputs for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + ", "
                            + e.getMessage());
            return RpcResponse.error(-1009, "Error while parsing transaction outputs");
        }
        decoded.add("vout", vout);
        return RpcResponse.ok(decoded);
    }

    private JsonArray decodeInputs(Transaction transaction) {
        JsonArray vin = new JsonArray();
        for (TransactionInput input : transaction.getInputs()) {
            JsonObject decoded = new JsonObject();
            decoded.addProperty("txid", input.getOutpoint().getHash().toString());
            decoded.addProperty("vout", input.getOutpoint().getIndex());

            JsonObject scriptSig = new JsonObject();
            scriptSig.addProperty(
                    "asm", BitcoinScripts.canonicalizeAsm(input.getScriptSig().toString()));
            scriptSig.addProperty("hex", hex(input.getScriptSig().getProgram()));
            decoded.add("scriptSig", scriptSig);
            decoded.addProperty("sequence", input.getSequenceNumber());
            vin.add(decoded);
        }
        return vin;
    }

    private JsonArray decodeOutputs(Transaction transaction) {
        JsonArray vout = new JsonArray();
        for (TransactionOutput output : transaction.getOutputs()) {
            Script.ScriptType type = output.getScriptPubKey().getScriptType();
            JsonObject decoded = new JsonObject();
            decoded.addProperty("value", Sats.toWholeCoins(output.getValue().value, coinFactor()));
            decoded.addProperty("n", output.getIndex());

            JsonObject scriptPubKey = new JsonObject();
            scriptPubKey.addProperty(
                    "asm", BitcoinScripts.canonicalizeAsm(output.getScriptPubKey().toString()));
            scriptPubKey.addProperty("hex", hex(output.getScriptPubKey().getProgram()));
            scriptPubKey.addProperty(
                    "reqSigs",
                    type == Script.ScriptType.P2SH
                            ? 1
                            : output.getScriptPubKey().getNumberOfSignaturesRequiredToSpend());
            scriptPubKey.addProperty("type", BitcoinScripts.typeName(type));

            JsonArray addresses = new JsonArray();
            addresses.add(
                    ((LegacyAddress)
                                    output.getScriptPubKey()
                                            .getToAddress(coin.getNetworkParameters()))
                            .toBase58());
            scriptPubKey.add("addresses", addresses);
            decoded.add("scriptPubKey", scriptPubKey);
            vout.add(decoded);
        }
        return vout;
    }

    private JsonObject signRawTransaction(JsonArray params) {
        if (params.size() < 1 || params.size() > 3) {
            return RpcResponse.usage(SIGN_USAGE);
        }
        SigningRequest request;
        try {
            request = decodeForSigning(params);
        } catch (RuntimeException e) {
            LOGGER.warning(
                    "[http-server-handler] Error decoding raw tx in signrawtransaction for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + ", "
                            + e.getMessage());
            return invalidTransaction();
        }

        SigningInputs inputs;
        try {
            inputs = collectSigningInputs(request.transaction());
        } catch (RuntimeException e) {
            LOGGER.warning(
                    "[http-server-handler] signrawtransaction aborted with unsigned inputs for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + ", "
                            + e.getMessage());
            return invalidTransaction();
        }

        long signedNTime;
        try {
            signedNTime = nTimes.forSigning(request.preservedNTime(), inputTxids(inputs));
        } catch (RuntimeException e) {
            LOGGER.warning(
                    "[http-server-handler] signrawtransaction could not resolve nTime for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + ", "
                            + e.getMessage());
            return RpcResponse.error(-6, "Cannot resolve nTime: " + e.getMessage());
        }

        Transaction signed =
                CoinTransactions.forSigning(coin.getNetworkParameters(), request.transaction());
        for (TransactionOutput output : request.transaction().getOutputs()) {
            signed.addOutput(output);
        }
        try {
            // All inputs are attached before any signature is computed: the preimage covers
            // every input, so signing as we went would invalidate each earlier signature.
            signInputs(signed, inputs, signedNTime);
        } catch (RuntimeException e) {
            LOGGER.warning(
                    "[http-server-handler] signrawtransaction could not sign inputs for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + ", "
                            + e.getMessage());
            return invalidTransaction();
        }
        return RpcResponse.ok(signedTransaction(signed, signedNTime));
    }

    private JsonObject signedTransaction(Transaction signed, long signedNTime) {
        JsonObject result = new JsonObject();
        result.addProperty(
                "hex", TxWire.serializeForBroadcast(coin.getTicker(), signed, signedNTime));
        result.addProperty("complete", true);
        return result;
    }

    /** The transaction to sign, and the nTime it already carries (or -1 for a standard chain). */
    private SigningRequest decodeForSigning(JsonArray params) {
        byte[] rawBytes = Hex.decode(params.get(0).getAsString());
        long preservedNTime =
                TxWire.hasTimeField(coin.getTicker()) ? TxTimeField.parentNTime(rawBytes) : -1;
        return new SigningRequest(
                TxWire.parseForCoin(coin.getTicker(), coin.getNetworkParameters(), rawBytes),
                preservedNTime);
    }

    private SigningInputs collectSigningInputs(Transaction transaction) {
        List<ECKey> keys = new ArrayList<>();
        List<TransactionOutPoint> outPoints = new ArrayList<>();
        List<Script> scripts = new ArrayList<>();
        for (TransactionInput input : transaction.getInputs()) {
            Sha256Hash txid = input.getOutpoint().getHash();
            long vout = input.getOutpoint().getIndex();
            ECKey key = utxos.signingKey(txid, vout);
            UTXO utxo = utxos.find(txid, vout);
            if (utxo == null || key == null) {
                throw new IllegalStateException(
                        "transaction contains an input the wallet does not hold");
            }
            org.bitcoinj.core.UTXO spent = utxo.createUTXO();
            keys.add(key);
            outPoints.add(
                    new TransactionOutPoint(
                            coin.getNetworkParameters(), spent.getIndex(), spent.getHash()));
            scripts.add(spent.getScript());
        }
        return new SigningInputs(keys, outPoints, scripts);
    }

    private void signInputs(Transaction signed, SigningInputs inputs, long signedNTime) {
        if (signedNTime >= 0) {
            // The chain signs nTime, so the chosen value belongs in the preimage, not
            // spliced in afterwards.
            TxTimeFieldSigner.addSignedInputs(
                    coin.getNetworkParameters(),
                    signed,
                    new TxTimeFieldSigner.InputsToSign(
                            inputs.keys(), inputs.outPoints(), inputs.scripts()),
                    new TxTimeFieldSigner.SignaturePolicy(Transaction.SigHash.ALL, signedNTime));
            return;
        }
        for (int i = 0; i < inputs.keys().size(); i++) {
            signed.addSignedInput(
                    inputs.outPoints().get(i),
                    inputs.scripts().get(i),
                    inputs.keys().get(i),
                    Transaction.SigHash.ALL,
                    true);
        }
    }

    private static List<String> inputTxids(SigningInputs inputs) {
        List<String> txids = new ArrayList<>();
        for (TransactionOutPoint outPoint : inputs.outPoints()) {
            txids.add(outPoint.getHash().toString());
        }
        return txids;
    }

    private JsonObject sendRawTransaction(JsonArray params) {
        if (params.size() != 1) {
            return RpcResponse.usage(SEND_USAGE);
        }
        String rawTx;
        Transaction transaction;
        try {
            rawTx = params.get(0).getAsString();
            transaction =
                    TxWire.parseForCoin(
                            coin.getTicker(), coin.getNetworkParameters(), Hex.decode(rawTx));
        } catch (RuntimeException e) {
            LOGGER.warning(
                    "[http-server-handler] Error parsing transaction in sendrawtransaction for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + ", "
                            + e.getMessage());
            return RpcResponse.error(-1, "Error parsing transaction!");
        }

        JsonObject txid = httpClient.sendRawTransaction(coin.getTicker(), rawTx);
        if (txid == null || RpcResponse.hasError(txid)) {
            RpcResponse.ProviderError error =
                    RpcResponse.providerError(txid, -1, "Error sending transaction!");
            return RpcResponse.error(error.code(), error.message());
        }
        utxos.markSpent(transaction);
        return RpcResponse.fromEnvelope(txid);
    }

    /** The single error envelope for a transaction that cannot be decoded or signed. */
    private static JsonObject invalidTransaction() {
        return RpcResponse.error(-1007, "Error decoding raw tx. Invalid transaction?");
    }

    private long coinFactor() {
        return CoinConfigRegistry.get(CoinTickerUtils.tickerToString(coin.getTicker()))
                .coinFactor();
    }

    private static String hex(byte[] bytes) {
        return new String(Hex.encode(bytes), StandardCharsets.UTF_8);
    }

    /** One recipient of a {@code createrawtransaction} request. */
    private record OutputEntry(String address, double amount) {}

    /** A transaction to sign and the nTime it already carries. */
    private record SigningRequest(Transaction transaction, long preservedNTime) {}

    /** The keys, outpoints and scripts needed to sign every input of a transaction. */
    private record SigningInputs(
            List<ECKey> keys, List<TransactionOutPoint> outPoints, List<Script> scripts) {}
}
