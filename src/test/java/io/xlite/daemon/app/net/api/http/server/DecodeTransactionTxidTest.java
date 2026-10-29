package io.xlite.daemon.app.net.api.http.server;

import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.util.TxTimeField;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A decoded transaction must be reported under the identifier the network gave
 * it.
 *
 * <p>For a chain whose wire layout carries a timestamp, the bytes on the wire
 * are the hashed preimage, so the identifier is the hash of exactly those
 * bytes. A parser that hands the transaction to bitcoinj has to strip that
 * field to read the rest correctly, and bitcoinj then re-serializes without it
 * — so the hash it computes is over a different message than the one the chain
 * hashed. The decoded fields come out right, which is what makes this easy to
 * miss: only the identifier is wrong.
 */
class DecodeTransactionTxidTest {

    private static final long NTIME = 1_790_592_305L;

    @org.junit.jupiter.api.BeforeAll
    static void loadRegistry() {
        // The time-field flag lives in the coin's configuration, so deciding
        // whether it applies needs a loaded registry.
        java.util.Map<String, CoinConfig> cfgs = new java.util.LinkedHashMap<>();
        cfgs.put("LTC", config("LTC", "Litecoin", 48, 50, 176, false));
        cfgs.put("XVG", config("XVG", "Verge", 30, 33, 158, true));
        io.xlite.daemon.app.coinconfig.CoinConfigRegistry.loadForTest(cfgs);
    }

    @org.junit.jupiter.api.AfterAll
    static void clearRegistry() {
        io.xlite.daemon.app.coinconfig.CoinConfigRegistry.resetForTest();
    }

    private static CoinConfig config(String ticker, String name, int addr, int p2sh, int dpk,
                                     boolean timeField) {
        java.util.Map<String, String> e = new java.util.LinkedHashMap<>();
        e.put("AddressPrefix", Integer.toString(addr));
        e.put("ScriptPrefix", Integer.toString(p2sh));
        e.put("SecretPrefix", Integer.toString(dpk));
        e.put("COIN", "100000000");
        e.put("FeePerByte", "2");
        e.put("MinTxFee", "678");
        e.put("Port", "9332");
        e.put("DustAmount", "0");
        e.put("TxWithTimeField", Boolean.toString(timeField));
        return new CoinConfig(ticker, name, ticker.toLowerCase() + "--test", e);
    }

    private static byte[] standardTransaction(NetworkParameters params, byte[] hash160) {
        Transaction tx = new Transaction(params);
        TransactionInput in = new TransactionInput(params, null,
                new byte[0], new TransactionOutPoint(params, 0L, Sha256Hash.ZERO_HASH),
                Coin.valueOf(150_000L));
        tx.addInput(in);
        tx.addOutput(new TransactionOutput(params, null, Coin.valueOf(140_000L),
                LegacyAddress.fromPubKeyHash(params, hash160)));
        return tx.bitcoinSerialize();
    }

    /** Independently computed: digest, then rendered big-endian as chains do. */
    private static String sha256d(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(md.digest(bytes));
        StringBuilder hex = new StringBuilder(h.length * 2);
        for (int i = h.length - 1; i >= 0; i--) {
            hex.append(String.format("%02x", h[i]));
        }
        return hex.toString();
    }

    /** The coin's real parameters, built the way the daemon builds them. */
    private static NetworkParameters litecoin() {
        java.util.Map<String, String> e = new java.util.LinkedHashMap<>();
        e.put("AddressPrefix", "48");
        e.put("ScriptPrefix", "50");
        e.put("SecretPrefix", "176");
        e.put("COIN", "100000000");
        e.put("FeePerByte", "2");
        e.put("MinTxFee", "678");
        e.put("Port", "9332");
        e.put("DustAmount", "0");
        CoinConfig cfg = new CoinConfig("LTC", "Litecoin", "litecoin--test", e);
        return io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters.from(cfg);
    }

    @Test
    @DisplayName("a time-field chain's decoded txid is the hash of the wire bytes")
    void testTimeFieldDecodedTxidIsTheWireHash() throws Exception {
        NetworkParameters params = litecoin();
        byte[] hash160 = new byte[20];
        for (int i = 0; i < hash160.length; i++) {
            hash160[i] = (byte) (i * 7 + 3);
        }
        byte[] wire = TxTimeField.insertNTime(standardTransaction(params, hash160), NTIME);

        // What the network would compute for these exact bytes.
        String chainTxid = sha256d(wire);

        // What a stripped-and-rehashed parse produces: different, and wrong.
        Transaction parsed = new Transaction(params, TxTimeField.stripNTime(wire));
        String bitcoinjTxid = parsed.getHashAsString();
        assertNotEquals(chainTxid, bitcoinjTxid,
                "the two must differ for this test to be meaningful");

        assertEquals(chainTxid,
                HTTPServerHandler.txidForDecoded(CoinTicker.VERGE, wire, parsed),
                "a time-field coin must be reported under the chain's identifier");
    }

    @Test
    @DisplayName("a chain without a time field is reported exactly as before")
    void testStandardChainKeepsItsExistingTxid() {
        NetworkParameters params = litecoin();
        byte[] hash160 = new byte[20];
        byte[] wire = standardTransaction(params, hash160);
        Transaction parsed = new Transaction(params, wire);

        assertEquals(parsed.getHashAsString(),
                HTTPServerHandler.txidForDecoded(CoinTicker.LITECOIN, wire, parsed),
                "a coin with no time field must keep the bitcoinj identifier");
    }

    @Test
    @DisplayName("the reported txid is the network's, for every coin it applies to")
    void testEveryTimeFieldCoinIsCovered() throws Exception {
        NetworkParameters params = litecoin();
        byte[] hash160 = new byte[20];
        for (int i = 0; i < hash160.length; i++) {
            hash160[i] = (byte) (0xA0 + i);
        }
        byte[] wire = TxTimeField.insertNTime(standardTransaction(params, hash160), NTIME);
        Transaction parsed = new Transaction(params, TxTimeField.stripNTime(wire));
        String expected = sha256d(wire);

        // Only the tickers the fixture configures, since deciding whether the
        // field applies is itself a configuration lookup.
        for (CoinTicker t : new CoinTicker[]{CoinTicker.LITECOIN, CoinTicker.VERGE}) {
            if (!TxTimeField.appliesTo(t)) {
                continue;
            }
            assertEquals(expected, HTTPServerHandler.txidForDecoded(t, wire, parsed),
                    t + " declares a time field and must be reported over the wire bytes");
        }
    }
}
