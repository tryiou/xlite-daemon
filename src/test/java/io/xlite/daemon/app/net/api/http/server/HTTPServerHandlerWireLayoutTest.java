package io.xlite.daemon.app.net.api.http.server;

import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.util.TxTimeField;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The send path's wire-layout guard, pinned directly.
 *
 * <p>This is the one check that decides whether hex a caller supplied is in the
 * chain's layout at all. On a time-field chain a standard-layout transaction
 * does not merely decode to different numbers: four bytes stripped at the wrong
 * offset shift the input count, so the parse either fails outright or yields a
 * transaction whose input count contradicts the bytes it was handed. The second
 * case is the dangerous one, and only re-serializing and comparing catches it.
 *
 * <p>Nothing else in the suite reaches this method, so while it was private the
 * guard was unproven. The sighash tests show the signer is self-consistent; they
 * say nothing about whether the bytes handed to it are bytes the chain accepts.
 */
class HTTPServerHandlerWireLayoutTest {

    /** A plausible nTime: inside the uint32 range. */
    private static final long NTIME = 1_790_592_305L;

    @BeforeAll
    static void loadRegistry() {
        Map<String, CoinConfig> cfgs = new LinkedHashMap<>();
        cfgs.put("LTC", config("LTC", "Litecoin", 48, 50, 176, false));
        cfgs.put("XVG", config("XVG", "Verge", 30, 33, 158, true));
        CoinConfigRegistry.loadForTest(cfgs);
    }

    @AfterAll
    static void releaseRegistry() {
        CoinConfigRegistry.resetForTest();
    }

    private static CoinConfig config(String ticker, String name, int addr, int p2sh, int dpk,
                                     boolean timeField) {
        Map<String, String> e = new LinkedHashMap<>();
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

    private static NetworkParameters litecoin() {
        return ConfigurableNetworkParameters.from(CoinConfigRegistry.get("LTC"));
    }

    /** A minimal but genuinely serializable one-input, one-output transaction. */
    private static byte[] standardTransaction(NetworkParameters params) {
        byte[] hash160 = new byte[20];
        for (int i = 0; i < hash160.length; i++) {
            hash160[i] = (byte) (i * 7 + 3);
        }
        Transaction tx = new Transaction(params);
        tx.addInput(new TransactionInput(params, null, new byte[0],
                new TransactionOutPoint(params, 0L, Sha256Hash.ZERO_HASH), Coin.valueOf(150_000L)));
        tx.addOutput(new TransactionOutput(params, null, Coin.valueOf(140_000L),
                LegacyAddress.fromPubKeyHash(params, hash160)));
        return tx.bitcoinSerialize();
    }

    @Test
    @DisplayName("a transaction in the chain's own layout is accepted")
    void testTimeFieldLayoutIsAccepted() {
        NetworkParameters ltc = litecoin();
        byte[] timeField = TxTimeField.insertNTime(standardTransaction(ltc), NTIME);

        Transaction parsed = HTTPServerHandler.parseForCoin(CoinTicker.VERGE, ltc, timeField);
        assertNotNull(parsed, "a transaction in the right layout must parse");
        assertArrayEquals(timeField, TxTimeField.insertNTime(parsed.bitcoinSerialize(), NTIME),
                "re-inserting nTime must reproduce the bytes supplied");
    }

    @Test
    @DisplayName("a standard-layout transaction is rejected where nTime is required")
    void testStandardLayoutIsRejectedOnTimeFieldChain() {
        NetworkParameters ltc = litecoin();
        byte[] standard = standardTransaction(ltc);

        // The failure the guard exists for: these bytes are a valid transaction,
        // they simply carry no nTime, so nothing about the call looks wrong until
        // the fields are read back at the wrong offsets.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> HTTPServerHandler.parseForCoin(CoinTicker.VERGE, ltc, standard),
                "a standard-layout transaction must be refused where nTime is required");
        assertTrue(e.getMessage().contains("wire layout"),
                "the failure must name the layout mismatch, got: " + e.getMessage());
    }

    @Test
    @DisplayName("the guard stays out of the way for a standard chain")
    void testStandardLayoutIsAcceptedOnStandardChain() {
        // Otherwise every ordinary transaction on the other coins would become
        // unparseable, and the guard would be worse than nothing.
        NetworkParameters ltc = litecoin();
        assertNotNull(HTTPServerHandler.parseForCoin(CoinTicker.LITECOIN, ltc,
                        standardTransaction(ltc)),
                "a standard transaction on a standard chain must parse");
    }

    @Test
    @DisplayName("the ticker, not the parameters, decides which layout is expected")
    void testLayoutExpectationFollowsTheTicker() {
        // The same bytes, the same parameters, two tickers: the decision must
        // come from the coin's own configuration. Getting this backwards would
        // either reject valid Verge transactions or accept invalid ones.
        NetworkParameters ltc = litecoin();
        byte[] standard = standardTransaction(ltc);
        assertThrows(IllegalArgumentException.class,
                () -> HTTPServerHandler.parseForCoin(CoinTicker.VERGE, ltc, standard),
                "Verge carries nTime, so standard bytes are wrong for it");
        assertDoesNotThrow(
                () -> HTTPServerHandler.parseForCoin(CoinTicker.LITECOIN, ltc, standard),
                "Litecoin carries none, so the same bytes are right for it");
        assertTrue(CoinTickerUtils.tickerToString(CoinTicker.VERGE).equals("XVG"),
                "this test's fixture is keyed by the manifest ticker, not the enum name");
    }

    @Test
    @DisplayName("the nTime on the wire is the nTime that was signed")
    void testBroadcastCarriesExactlyTheSignedNTime() {
        // The whole point of resolving nTime before signing rather than after: a
        // transaction whose wire bytes carry a different nTime than the one the
        // signatures were computed over is rejected by the node, and every
        // decoded field of it would still look correct. Nothing else in the suite
        // can see this, because the two paths are separate methods.
        NetworkParameters ltc = litecoin();
        byte[] standard = standardTransaction(ltc);
        Transaction signed = new Transaction(ltc, standard);

        String hex = HTTPServerHandler.serializeForBroadcast(CoinTicker.VERGE, signed, NTIME);
        byte[] wire = hexToBytes(hex);

        assertEquals(NTIME, TxTimeField.parentNTime(wire),
                "the wire bytes must carry the nTime that was signed");
        assertArrayEquals(TxTimeField.insertNTime(standard, NTIME), wire,
                "the wire bytes must be exactly the signed transaction with that nTime");
    }

    @Test
    @DisplayName("a transaction signed without an nTime is never serialized for broadcast")
    void testUnsignedNTimeRefusesToSerialize() {
        // signTimeFieldInputs reports failure by leaving the nTime unset. If that
        // value ever reached the wire, the node would reject it; failing here
        // turns a would-be silent rejection into a named error.
        NetworkParameters ltc = litecoin();
        Transaction signed = new Transaction(ltc, standardTransaction(ltc));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> HTTPServerHandler.serializeForBroadcast(CoinTicker.VERGE, signed, -1L),
                "an absent nTime must not be broadcast");
        assertTrue(e.getMessage().contains("nTime"),
                "the failure must name what was missing, got: " + e.getMessage());
    }

    @Test
    @DisplayName("a standard chain's bytes go out with no nTime inserted")
    void testStandardChainBroadcastIsUnchanged() {
        NetworkParameters ltc = litecoin();
        byte[] standard = standardTransaction(ltc);
        Transaction signed = new Transaction(ltc, standard);

        assertArrayEquals(standard,
                hexToBytes(HTTPServerHandler.serializeForBroadcast(
                        CoinTicker.LITECOIN, signed, -1L)),
                "a coin without the field must broadcast the plain serialization");
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
