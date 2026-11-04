package io.xlite.daemon.app.net.electrumx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.xlite.daemon.app.util.history.Transaction;

import org.bitcoinj.core.NetworkParameters;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Turns native ElectrumX history plus verbose transactions into wallet rows.
 *
 * <p>This is the wallet's own classification, not the indexer's: ElectrumX knows that a script
 * changed and at which height, while only the wallet knows which addresses it owns and therefore
 * what an input or output means. Ownership is decided by script hash, never by address spelling: a
 * BCH node reports cashaddr while the wallet may hold the legacy form, and both are the same
 * script.
 *
 * <p>The rules are the ones the deployed history service used, so a migration does not change a
 * single row:
 *
 * <ul>
 *   <li>an owned output of a transaction the wallet did not fund is a receive;
 *   <li>a foreign output funded by our inputs is a send capped by what we supplied;
 *   <li>an own output the wallet funded is change, and produces no row;
 *   <li>the fee is {@code sum(vout) - sum(vin)}, attached once to the largest send;
 *   <li>same-address send/receive pairs are netted, a pure self-transfer is invisible.
 * </ul>
 */
final class HistoryMaterialiser {

    static final int MAX_TXS = 2000;
    private static final int MIN_SPENDS_TO_COMPARE = 2;
    private static final double EPSILON = Math.ulp(1.0);

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private final NetworkParameters parameters;
    private final Function<String, JsonObject> transactions;
    private final Function<String, String> displayAddress;

    HistoryMaterialiser(NetworkParameters parameters, Function<String, JsonObject> transactions) {
        this(parameters, transactions, Function.identity());
    }

    HistoryMaterialiser(
            NetworkParameters parameters,
            Function<String, JsonObject> transactions,
            Function<String, String> displayAddress) {
        this.parameters = parameters;
        this.transactions = transactions;
        this.displayAddress = displayAddress;
    }

    /** One address's rows, in chain order, from its own confirmed and mempool history. */
    List<Transaction> rows(String address, String addressHashX, List<TxRef> history) {
        return rows(
                address,
                addressHashX,
                addressHashX == null ? Set.of() : Set.of(addressHashX),
                history);
    }

    /**
     * One address's rows, with the whole wallet's script set for ownership.
     *
     * <p>{@code walletHashX} is every address the wallet owns, not just {@code address}. A change
     * output paid to another wallet address is ours: calling it foreign would emit a phantom send
     * alongside the receive row that address's own pass produces.
     */
    List<Transaction> rows(
            String address, String addressHashX, Set<String> walletHashX, List<TxRef> history) {
        if (history.size() > MAX_TXS) {
            throw new HistoryTooLargeException(address, history.size());
        }
        if (addressHashX == null) {
            LOGGER.warning("[history] cannot decode wallet address " + address);
            return List.of();
        }

        List<Transaction> rows = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        for (TxRef reference : history) {
            if (!processed.add(reference.txid())) {
                continue;
            }
            JsonObject tx = transactions.apply(reference.txid());
            if (tx == null) {
                LOGGER.warning("[history] transaction " + reference.txid() + " is unavailable");
                continue;
            }
            try {
                rows.addAll(
                        classify(
                                tx,
                                reference.height(),
                                new WalletView(address, addressHashX, walletHashX)));
            } catch (RuntimeException e) {
                // One malformed transaction must not discard the address's other rows.
                LOGGER.log(
                        Level.WARNING,
                        "[history] skipping malformed transaction " + reference.txid(),
                        e);
            }
        }
        return rows;
    }

    private List<Transaction> classify(JsonObject tx, int blockHeight, WalletView view) {
        Context context = new Context(tx, blockHeight, view);
        scanInputs(context);
        addForeignOutputs(context);
        addOwnOutputs(context);
        attachFee(context);
        consolidate(context.spends);
        return context.spends;
    }

    private void scanInputs(Context context) {
        for (JsonElement element : array(context.tx, "vin")) {
            JsonObject input = element.getAsJsonObject();
            String inputTxid = string(input, "txid");
            if (inputTxid == null || inputTxid.replace("0", "").isEmpty()) {
                continue; // coinbase-style input
            }
            JsonObject previous = transactions.apply(inputTxid);
            if (previous == null) {
                continue;
            }
            JsonObject previousOutput = outputAt(previous, input.get("vout").getAsInt());
            double amount = previousOutput.get("value").getAsDouble();
            String previousHashX = hashXOf(previousOutput);
            if (previousHashX == null) {
                continue;
            }
            if (previousHashX.equals(context.view.addressHashX)) {
                context.totalOwnInput += amount;
            } else {
                context.totalForeignInput += amount;
            }
            if (context.view.owned.contains(previousHashX)) {
                context.walletInput = true;
                context.totalWalletInput += amount;
            }
        }
        // The wallet, not the single address being read, is what funds a send:
        // a transaction may spend from several owned addresses and the row is
        // published once for the transaction, so capping per address would
        // report only that address's share and lose the rest of the debit.
        // Only an address that actually supplied an input emits the send; a
        // change address merely touched by the transaction contributes none.
        context.runningOwnInput = context.totalOwnInput > 0 ? context.totalWalletInput : 0;
        context.sending = context.totalOwnInput > 0;
        context.fees = (context.totalForeignInput + context.totalOwnInput) * -1;
    }

    /** Foreign outputs first: a send is capped by the coin we actually supplied. */
    private void addForeignOutputs(Context context) {
        for (JsonElement element : array(context.tx, "vout")) {
            JsonObject output = element.getAsJsonObject();
            double amount = output.get("value").getAsDouble();
            context.fees += amount;
            String outputAddress = displayAddressOf(output);
            if (outputAddress == null || isOwnedByWallet(output, context.view.owned)) {
                continue;
            }
            if (context.runningOwnInput <= EPSILON) {
                continue;
            }
            if (amount > context.biggestForeignAmount) {
                context.biggestForeignAmount = amount;
                context.biggestForeignAddress = outputAddress;
            }
            double adjusted = Math.min(context.runningOwnInput, amount);
            context.runningOwnInput -= adjusted;
            context.addRow(outputAddress, -adjusted, "send", output);
        }
    }

    /**
     * Our own outputs.
     *
     * <p>When the wallet funded the transaction an own output is change: it returns value the
     * wallet already had, so it is neither income nor a new spend, and the send rows for the
     * foreign outputs already carry the whole debit. Emitting a receive for it — which is what a
     * change address different from the input's address used to do — makes the visible rows add up
     * to more than the wallet's balance movement. Only a transaction the wallet did not fund has a
     * receive row.
     */
    private void addOwnOutputs(Context context) {
        if (context.walletInput) {
            return;
        }
        for (JsonElement element : array(context.tx, "vout")) {
            JsonObject output = element.getAsJsonObject();
            if (!isOwned(output, context.view.addressHashX)) {
                continue;
            }
            context.addRow(
                    context.view.address, output.get("value").getAsDouble(), "receive", output);
        }
    }

    private static void attachFee(Context context) {
        if (!context.sending || context.fees >= 0) {
            return;
        }
        String biggest = context.biggestForeignAddress;
        if (biggest == null) {
            return;
        }
        for (Transaction spend : context.spends) {
            if (biggest.equals(spend.getAddress()) && "send".equals(spend.getCategory())) {
                spend.setFee(truncate(context.fees));
                return;
            }
        }
    }

    private static void consolidate(List<Transaction> spends) {
        if (spends.size() < MIN_SPENDS_TO_COMPARE) {
            return;
        }
        List<Transaction> remove = new ArrayList<>();
        for (Transaction spend : new ArrayList<>(spends)) {
            List<Transaction> sameAddress = new ArrayList<>();
            for (Transaction candidate : spends) {
                if (candidate.getAddress().equals(spend.getAddress())) {
                    sameAddress.add(candidate);
                }
            }
            Transaction send = firstOf(sameAddress, "send");
            Transaction receive = firstOf(sameAddress, "receive");
            if (send == null || receive == null) {
                continue;
            }
            if (Math.abs(send.getValue()) - receive.getValue() > -EPSILON) {
                send.setValue(send.getValue() + receive.getValue());
                send.setFee(send.getFee() + receive.getFee());
                remove.add(receive);
            } else {
                receive.setValue(receive.getValue() + send.getValue());
                receive.setFee(receive.getFee() + send.getFee());
                remove.add(send);
            }
            if (spends.size() - remove.size() < MIN_SPENDS_TO_COMPARE) {
                break;
            }
        }
        spends.removeAll(remove);
        spends.removeIf(row -> Math.abs(row.getValue()) <= EPSILON);
    }

    private static Transaction firstOf(List<Transaction> rows, String category) {
        for (Transaction row : rows) {
            if (category.equals(row.getCategory())) {
                return row;
            }
        }
        return null;
    }

    private static JsonObject outputAt(JsonObject tx, int index) {
        return array(tx, "vout").get(index).getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonArray()) {
            return new JsonArray();
        }
        return element.getAsJsonArray();
    }

    private static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private boolean isOwned(JsonObject output, String addressHashX) {
        String hashX = hashXOf(output);
        return hashX != null && hashX.equals(addressHashX);
    }

    private boolean isOwnedByWallet(JsonObject output, Set<String> walletHashX) {
        String hashX = hashXOf(output);
        return hashX != null && walletHashX.contains(hashX);
    }

    /** The output's address in the wallet's own spelling (see {@link #displayAddress}). */
    private String displayAddressOf(JsonObject output) {
        String address = addressOf(output);
        if (address == null) {
            return null;
        }
        String display = displayAddress.apply(address);
        return display == null || display.isEmpty() ? address : display;
    }

    private String hashXOf(JsonObject output) {
        JsonObject script = scriptPubKey(output);
        String hashX = ScriptHashes.hashXFromScriptHex(string(script, "hex"));
        if (hashX != null) {
            return hashX;
        }
        return ScriptHashes.hashXFromAddress(parameters, addressOf(output));
    }

    private static String addressOf(JsonObject output) {
        JsonObject script = scriptPubKey(output);
        String type = string(script, "type");
        if (type == null || "nonstandard".equals(type)) {
            return null;
        }
        String address = string(script, "address");
        if (address != null) {
            return address;
        }
        JsonElement addresses = script.get("addresses");
        if (addresses != null && addresses.isJsonArray() && !addresses.getAsJsonArray().isEmpty()) {
            return addresses.getAsJsonArray().get(0).getAsString();
        }
        if (addresses != null && addresses.isJsonPrimitive()) {
            return addresses.getAsString();
        }
        return null;
    }

    private static JsonObject scriptPubKey(JsonObject output) {
        JsonElement script = output.get("scriptPubKey");
        return script != null && script.isJsonObject()
                ? script.getAsJsonObject()
                : new JsonObject();
    }

    private static double truncate(double value) {
        return Double.parseDouble(String.format(Locale.ROOT, "%.10f", value));
    }

    /** The address being read and every script hash the wallet owns. */
    private record WalletView(String address, String addressHashX, Set<String> owned) {}

    /** Mutable state of one transaction's classification; it stays a value, not a service. */
    private static final class Context {
        private final JsonObject tx;
        private final int height;
        private final WalletView view;
        private final Set<String> rowIds = new HashSet<>();
        private final List<Transaction> spends = new ArrayList<>();

        private double totalForeignInput;
        private double totalOwnInput;
        private double totalWalletInput;
        private double runningOwnInput;
        private boolean sending;
        private boolean walletInput;
        private double fees;
        private double biggestForeignAmount;
        private String biggestForeignAddress;

        private Context(JsonObject tx, int height, WalletView view) {
            this.tx = tx;
            this.height = height;
            this.view = view;
        }

        private void addRow(String rowAddress, double amount, String category, JsonObject output) {
            int vout = output.get("n").getAsInt();
            String txid = tx.get("txid").getAsString();
            if (!rowIds.add(txid + ":" + vout + ":" + category)) {
                return;
            }
            String blockhash = string(tx, "blockhash");
            int confirmations = tx.has("confirmations") ? tx.get("confirmations").getAsInt() : 0;
            int blocktime = tx.has("blocktime") ? tx.get("blocktime").getAsInt() : 0;
            int time = tx.has("time") ? tx.get("time").getAsInt() : blocktime;
            if (height <= 0 && time <= 0) {
                // A mempool row must still sort and index by time in the wallet database.
                time = (int) (System.currentTimeMillis() / 1000);
            }
            Transaction row =
                    new Transaction(
                            rowAddress, txid, blockhash, vout, amount, confirmations, blocktime);
            row.setCategory(category);
            row.setBlockheight(height);
            row.setTime(time);
            spends.add(row);
        }
    }

    /** One address holds more transactions than one wallet snapshot may carry. */
    static final class HistoryTooLargeException extends RuntimeException {
        HistoryTooLargeException(String address, int count) {
            super(
                    "history too large for "
                            + address
                            + ": "
                            + count
                            + " transactions (max "
                            + MAX_TXS
                            + ")");
        }
    }
}
