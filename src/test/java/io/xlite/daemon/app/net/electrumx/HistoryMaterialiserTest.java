package io.xlite.daemon.app.net.electrumx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.xlite.daemon.app.util.history.Transaction;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class HistoryMaterialiserTest {

    private static final String WALLET = "Bs8CAHhLFpStESjXqnRF4cTPKbD1ehGu2Y";
    private static final byte[] WALLET_SCRIPT = script("11");
    private static final String WALLET_HASH_X = ScriptHashes.hashX(WALLET_SCRIPT);

    private final Map<String, JsonObject> transactions = new HashMap<>();

    private HistoryMaterialiser materialiser() {
        return new HistoryMaterialiser(null, transactions::get);
    }

    private List<Transaction> rows(String historyTxid, int height) {
        return materialiser().rows(WALLET, WALLET_HASH_X, List.of(new TxRef(historyTxid, height)));
    }

    @Test
    void receiveIsPositiveAndFeeFree() {
        transactions.put("p1", tx("p1", List.of(), List.of(output(500.0, 0, script("21"), "F"))));
        transactions.put(
                "t1",
                tx(
                        "t1",
                        List.of(input("p1", 0)),
                        List.of(output(500.0, 0, WALLET_SCRIPT, WALLET))));

        List<Transaction> rows = rows("t1", 1);

        assertEquals(1, rows.size());
        assertEquals("receive", rows.get(0).getCategory());
        assertEquals(WALLET, rows.get(0).getAddress());
        assertEquals(500.0, rows.get(0).getValue());
        assertEquals(0.0, rows.get(0).getFee());
        assertEquals(1, rows.get(0).getBlockheight());
        assertEquals(1000, rows.get(0).getTime());
    }

    @Test
    void sendWithChangeReportsOneNegativeRowAndTheFee() {
        byte[] external = script("31");
        transactions.put(
                "p1", tx("p1", List.of(), List.of(output(1000.0, 0, WALLET_SCRIPT, WALLET))));
        transactions.put(
                "t1",
                tx(
                        "t1",
                        List.of(input("p1", 0)),
                        List.of(
                                output(10.0, 0, external, "X"),
                                output(900.0, 1, WALLET_SCRIPT, WALLET))));

        List<Transaction> rows = rows("t1", 1);

        assertEquals(1, rows.size(), rows.toString());
        assertEquals("send", rows.get(0).getCategory());
        assertEquals("X", rows.get(0).getAddress());
        assertEquals(-10.0, rows.get(0).getValue());
        assertEquals(-90.0, rows.get(0).getFee(), 1e-10);
    }

    @Test
    void foreignSendConsumesTheWholeInput() {
        transactions.put(
                "p1", tx("p1", List.of(), List.of(output(1000.0, 0, WALLET_SCRIPT, WALLET))));
        transactions.put(
                "t1",
                tx("t1", List.of(input("p1", 0)), List.of(output(10.0, 0, script("41"), "X"))));

        List<Transaction> rows = rows("t1", 1);

        assertEquals(1, rows.size());
        assertEquals(-10.0, rows.get(0).getValue());
        assertEquals(-990.0, rows.get(0).getFee(), 1e-10);
    }

    @Test
    void twoForeignOutputsAreTwoSendsAndTheFeeGoesToTheLargest() {
        transactions.put(
                "p1", tx("p1", List.of(), List.of(output(1000.0, 0, WALLET_SCRIPT, WALLET))));
        transactions.put(
                "t1",
                tx(
                        "t1",
                        List.of(input("p1", 0)),
                        List.of(
                                output(400.0, 0, script("51"), "B"),
                                output(500.0, 1, WALLET_SCRIPT, WALLET),
                                output(90.0, 2, script("61"), "X"))));

        List<Transaction> rows = rows("t1", 1);

        assertEquals(2, rows.size(), rows.toString());
        Transaction toB = find(rows, "B");
        Transaction toX = find(rows, "X");
        assertEquals(-400.0, toB.getValue());
        assertEquals(-10.0, toB.getFee(), 1e-10);
        assertEquals(-90.0, toX.getValue());
        assertEquals(0.0, toX.getFee());
    }

    @Test
    void duplicateHistoryEntriesProduceOneRow() {
        transactions.put("p1", tx("p1", List.of(), List.of(output(100.0, 0, script("71"), "F"))));
        transactions.put(
                "t1",
                tx(
                        "t1",
                        List.of(input("p1", 0)),
                        List.of(output(100.0, 0, WALLET_SCRIPT, WALLET))));

        List<Transaction> rows =
                materialiser()
                        .rows(
                                WALLET,
                                WALLET_HASH_X,
                                List.of(new TxRef("t1", 1), new TxRef("t1", 1)));

        assertEquals(1, rows.size());
    }

    @Test
    void aMalformedTransactionIsSkippedAndEarlierRowsKept() {
        transactions.put("p1", tx("p1", List.of(), List.of(output(100.0, 0, script("81"), "F"))));
        transactions.put(
                "t1",
                tx(
                        "t1",
                        List.of(input("p1", 0)),
                        List.of(output(100.0, 0, WALLET_SCRIPT, WALLET))));
        transactions.put(
                "bad",
                tx(
                        "bad",
                        List.of(input("p1", 99)),
                        List.of(output(1.0, 0, WALLET_SCRIPT, WALLET))));

        List<Transaction> rows =
                materialiser()
                        .rows(
                                WALLET,
                                WALLET_HASH_X,
                                List.of(new TxRef("t1", 1), new TxRef("bad", 2)));

        assertEquals(1, rows.size());
        assertEquals("t1", rows.get(0).getTxid());
    }

    @Test
    void mempoolRowHasZeroHeightAndAUsableTime() {
        JsonObject pending = tx("m1", List.of(), List.of(output(2.0, 0, WALLET_SCRIPT, WALLET)));
        pending.addProperty("time", 1_700_000_000);
        pending.remove("blockhash");
        pending.remove("blocktime");
        pending.remove("confirmations");
        transactions.put("m1", pending);

        List<Transaction> rows = rows("m1", 0);

        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).getBlockheight());
        assertEquals(0, rows.get(0).getConfirmations());
        assertEquals(1_700_000_000, rows.get(0).getTime());
        assertNull(rows.get(0).getBlockhash());
    }

    /**
     * A wallet that only moves its own coins still moved them, and paid a miner fee doing it.
     *
     * <p>No value left the wallet, so the row reports zero; the fee is the whole story, and without
     * the row the transaction is absent from a history whose other rows then cannot account for the
     * coins it consumed.
     */
    @Test
    void aPureSelfTransferIsOneZeroValuedSendCarryingTheFee() {
        transactions.put(
                "p1", tx("p1", List.of(), List.of(output(1000.0, 0, WALLET_SCRIPT, WALLET))));
        transactions.put(
                "t1",
                tx(
                        "t1",
                        List.of(input("p1", 0)),
                        List.of(output(999.5, 0, WALLET_SCRIPT, WALLET))));

        List<Transaction> rows = rows("t1", 1);

        assertEquals(1, rows.size(), rows.toString());
        assertEquals("send", rows.get(0).getCategory());
        assertEquals(WALLET, rows.get(0).getAddress());
        assertEquals(0.0, rows.get(0).getValue());
        assertEquals(-0.5, rows.get(0).getFee());
        assertEquals(0, rows.get(0).getVout());
    }

    /** The row names the largest own output, so a reader can follow it on chain. */
    @Test
    void aSelfTransferNamesTheLargestOwnOutput() {
        transactions.put(
                "p1", tx("p1", List.of(), List.of(output(1000.0, 0, WALLET_SCRIPT, WALLET))));
        transactions.put(
                "t1",
                tx(
                        "t1",
                        List.of(input("p1", 0)),
                        List.of(
                                output(100.0, 0, WALLET_SCRIPT, WALLET),
                                output(899.0, 1, WALLET_SCRIPT, WALLET))));

        List<Transaction> rows = rows("t1", 1);

        assertEquals(1, rows.size(), rows.toString());
        assertEquals(1, rows.get(0).getVout());
        assertEquals(-1.0, rows.get(0).getFee());
    }

    /** One transaction, one row: a wallet spending from several addresses reports it once. */
    @Test
    void aSelfTransferFromTwoWalletAddressesIsStillOneRow() {
        byte[] otherScript = script("91");
        String otherHashX = ScriptHashes.hashX(otherScript);
        transactions.put(
                "p1", tx("p1", List.of(), List.of(output(600.0, 0, WALLET_SCRIPT, WALLET))));
        transactions.put(
                "p2", tx("p2", List.of(), List.of(output(400.0, 0, otherScript, "OTHER"))));
        transactions.put(
                "t1",
                tx(
                        "t1",
                        List.of(input("p1", 0), input("p2", 0)),
                        List.of(output(999.0, 0, WALLET_SCRIPT, WALLET))));

        Set<String> wallet = Set.of(WALLET_HASH_X, otherHashX);
        List<Transaction> first =
                materialiser().rows(WALLET, WALLET_HASH_X, wallet, List.of(new TxRef("t1", 1)));
        List<Transaction> second =
                materialiser().rows("OTHER", otherHashX, wallet, List.of(new TxRef("t1", 1)));

        // Exactly one of the two passes owns the row, so the merged snapshot holds one.
        assertEquals(1, first.size() + second.size(), first + " / " + second);
        List<Transaction> owning = first.isEmpty() ? second : first;
        assertEquals(0.0, owning.get(0).getValue());
        assertEquals(-1.0, owning.get(0).getFee());
    }

    /** A transaction that pays someone else is not a self-transfer and gets no zero row. */
    @Test
    void aSendToAForeignOutputGetsNoSelfTransferRow() {
        transactions.put(
                "p1", tx("p1", List.of(), List.of(output(1000.0, 0, WALLET_SCRIPT, WALLET))));
        transactions.put(
                "t1",
                tx(
                        "t1",
                        List.of(input("p1", 0)),
                        List.of(
                                output(10.0, 0, script("A1"), "X"),
                                output(989.0, 1, WALLET_SCRIPT, WALLET))));

        List<Transaction> rows = rows("t1", 1);

        assertEquals(1, rows.size(), rows.toString());
        assertEquals(-10.0, rows.get(0).getValue());
        assertEquals(-1.0, rows.get(0).getFee());
    }

    @Test
    void ownershipUsesTheScriptNotTheReportedAddressSpelling() {
        transactions.put(
                "t1",
                tx(
                        "t1",
                        List.of(),
                        List.of(output(1.5, 0, WALLET_SCRIPT, "bitcoincash:qpwrongspelling"))));

        List<Transaction> rows = rows("t1", 1);

        assertEquals(1, rows.size());
        assertEquals("receive", rows.get(0).getCategory());
        assertEquals(WALLET, rows.get(0).getAddress());
    }

    @Test
    void changeToAnotherWalletAddressIsNotASend() {
        byte[] otherWalletScript = script("91");
        String otherHashX = ScriptHashes.hashX(otherWalletScript);
        transactions.put(
                "p1", tx("p1", List.of(), List.of(output(1000.0, 0, WALLET_SCRIPT, WALLET))));
        transactions.put(
                "t1",
                tx(
                        "t1",
                        List.of(input("p1", 0)),
                        List.of(
                                output(10.0, 0, script("A1"), "X"),
                                output(900.0, 1, otherWalletScript, "OTHER"))));

        List<Transaction> rows =
                materialiser()
                        .rows(
                                WALLET,
                                WALLET_HASH_X,
                                Set.of(WALLET_HASH_X, otherHashX),
                                List.of(new TxRef("t1", 1)));

        assertEquals(1, rows.size(), rows.toString());
        assertEquals("send", rows.get(0).getCategory());
        assertEquals("X", rows.get(0).getAddress());
        assertEquals(-10.0, rows.get(0).getValue());
    }

    @Test
    void changeToAnotherWalletAddressIsNotAReceiveEither() {
        byte[] otherWalletScript = script("92");
        String otherWallet = "OTHER";
        String otherHashX = ScriptHashes.hashX(otherWalletScript);
        transactions.put(
                "p1", tx("p1", List.of(), List.of(output(1000.0, 0, WALLET_SCRIPT, WALLET))));
        transactions.put(
                "t1",
                tx(
                        "t1",
                        List.of(input("p1", 0)),
                        List.of(
                                output(10.0, 0, script("A2"), "X"),
                                output(900.0, 1, otherWalletScript, otherWallet))));

        List<Transaction> rows =
                materialiser()
                        .rows(
                                otherWallet,
                                otherHashX,
                                Set.of(WALLET_HASH_X, otherHashX),
                                List.of(new TxRef("t1", 1)));

        assertEquals(List.of(), rows, "change is not income");
    }

    @Test
    void aSpendFromTwoWalletAddressesReportsTheWholeForeignOutput() {
        byte[] otherWalletScript = script("93");
        String otherWallet = "OTHER2";
        String otherHashX = ScriptHashes.hashX(otherWalletScript);
        transactions.put(
                "p1", tx("p1", List.of(), List.of(output(1000.0, 0, WALLET_SCRIPT, WALLET))));
        transactions.put(
                "p2",
                tx("p2", List.of(), List.of(output(500.0, 0, otherWalletScript, otherWallet))));
        transactions.put(
                "t1",
                tx(
                        "t1",
                        List.of(input("p1", 0), input("p2", 0)),
                        List.of(
                                output(10.0, 0, script("A4"), "X"),
                                output(1485.0, 1, WALLET_SCRIPT, WALLET))));

        Set<String> wallet = Set.of(WALLET_HASH_X, otherHashX);
        List<Transaction> first =
                materialiser().rows(WALLET, WALLET_HASH_X, wallet, List.of(new TxRef("t1", 1)));
        List<Transaction> second =
                materialiser().rows(otherWallet, otherHashX, wallet, List.of(new TxRef("t1", 1)));

        Map<String, Transaction> merged = new HashMap<>();
        first.forEach(row -> merged.put(row.uid(), row));
        second.forEach(row -> merged.put(row.uid(), row));

        assertEquals(1, merged.size(), merged.toString());
        Transaction send = merged.values().iterator().next();
        assertEquals("send", send.getCategory());
        assertEquals(-10.0, send.getValue());
        assertEquals(-5.0, send.getFee(), 1e-10);
    }

    @Test
    void foreignOutputIsShownInTheWalletSpelling() {
        transactions.put(
                "p1", tx("p1", List.of(), List.of(output(1000.0, 0, WALLET_SCRIPT, WALLET))));
        transactions.put(
                "t1",
                tx(
                        "t1",
                        List.of(input("p1", 0)),
                        List.of(output(10.0, 0, script("A3"), "bitcoincash:qpforeign"))));

        HistoryMaterialiser materialiser =
                new HistoryMaterialiser(
                        null,
                        transactions::get,
                        address ->
                                address.startsWith("bitcoincash:") ? "1LegacySpelling" : address);
        List<Transaction> rows =
                materialiser.rows(WALLET, WALLET_HASH_X, List.of(new TxRef("t1", 1)));

        assertEquals(1, rows.size(), rows.toString());
        assertEquals("1LegacySpelling", rows.get(0).getAddress());
    }

    private Transaction find(List<Transaction> rows, String address) {
        assertTrue(rows.stream().anyMatch(row -> address.equals(row.getAddress())));
        return rows.stream()
                .filter(row -> address.equals(row.getAddress()))
                .findFirst()
                .orElseThrow();
    }

    private static byte[] script(String tag) {
        byte[] raw = new byte[25];
        raw[0] = 0x76;
        raw[1] = (byte) 0xA9;
        raw[2] = 0x14;
        for (int index = 0; index < 20; index++) {
            raw[3 + index] = (byte) (tag.charAt(0) + index);
        }
        raw[23] = (byte) 0x88;
        raw[24] = (byte) 0xAC;
        return raw;
    }

    private static JsonObject output(double value, int n, byte[] script, String address) {
        JsonObject scriptPubKey = new JsonObject();
        scriptPubKey.addProperty("type", "pubkeyhash");
        scriptPubKey.addProperty("hex", org.bitcoinj.core.Utils.HEX.encode(script));
        scriptPubKey.addProperty("address", address);
        JsonObject output = new JsonObject();
        output.addProperty("value", value);
        output.addProperty("n", n);
        output.add("scriptPubKey", scriptPubKey);
        return output;
    }

    private static JsonObject input(String txid, int vout) {
        JsonObject input = new JsonObject();
        input.addProperty("txid", txid);
        input.addProperty("vout", vout);
        return input;
    }

    private static JsonObject tx(String txid, List<JsonObject> inputs, List<JsonObject> outputs) {
        JsonObject tx = new JsonObject();
        tx.addProperty("txid", txid);
        JsonArray vin = new JsonArray();
        inputs.forEach(vin::add);
        tx.add("vin", vin);
        JsonArray vout = new JsonArray();
        outputs.forEach(vout::add);
        tx.add("vout", vout);
        tx.addProperty("blockhash", "b".repeat(64));
        tx.addProperty("blocktime", 1000);
        tx.addProperty("confirmations", 10);
        return tx;
    }
}
