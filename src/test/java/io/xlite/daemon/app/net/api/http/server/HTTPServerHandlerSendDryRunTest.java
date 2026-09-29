package io.xlite.daemon.app.net.api.http.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.script.ScriptBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the dry-run half of the sendtransaction contract.
 *
 * <p>A dry run must be reviewable without touching the network or the wallet,
 * and it must describe the transaction that a real send would produce -- the
 * same bytes, the same txid, and the fee actually charged rather than one
 * recomputed from the shape of the finished transaction.
 */
class HTTPServerHandlerSendDryRunTest {

    private static final long XVG_COIN_UNIT = 1_000_000L;
    private static final long XVG_FEE_PER_BYTE = 200L;
    private static final long XVG_MIN_TX_FEE = 100_000L;

    @BeforeAll
    static void loadRegistry() {
        Map<String, CoinConfig> cfgs = new LinkedHashMap<>();
        cfgs.put("XVG", fixture());
        CoinConfigRegistry.loadForTest(cfgs);
    }

    @AfterAll
    static void resetRegistry() {
        CoinConfigRegistry.resetForTest();
    }

    private static CoinConfig fixture() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("AddressPrefix", "30");
        entries.put("ScriptPrefix", "33");
        entries.put("SecretPrefix", "158");
        entries.put("COIN", Long.toString(XVG_COIN_UNIT));
        entries.put("FeePerByte", Long.toString(XVG_FEE_PER_BYTE));
        entries.put("MinTxFee", Long.toString(XVG_MIN_TX_FEE));
        entries.put("Port", "20102");
        entries.put("DustAmount", "0");
        entries.put("Title", "Verge");
        return new CoinConfig("XVG", "Verge", "verge--test", entries);
    }

    private static NetworkParameters xvgParams() {
        return ConfigurableNetworkParameters.from(CoinConfigRegistry.get("XVG"));
    }

    private static JsonArray params(Object... values) {
        JsonArray array = new JsonArray();
        for (Object value : values) {
            if (value instanceof Number)
                array.add(new JsonPrimitive((Number) value));
            else
                array.add(value.toString());
        }
        return array;
    }

    @Test
    @DisplayName("the dry-run flag is off unless explicitly requested")
    void testDryRunFlagDefaultsOff() {
        assertFalse(HTTPServerHandler.parseDryRunFlag(params("addr", 0.01), 2));
        assertFalse(HTTPServerHandler.parseDryRunFlag(params(), 2));
        assertFalse(HTTPServerHandler.parseDryRunFlag(params("addr", 0.01, "false"), 2));
        assertFalse(HTTPServerHandler.parseDryRunFlag(params("addr", 0.01, "0"), 2));
    }

    @Test
    @DisplayName("the dry-run flag accepts the same true/1 spellings as the verbose flag")
    void testDryRunFlagAcceptsTrueAndOne() {
        assertTrue(HTTPServerHandler.parseDryRunFlag(params("addr", 0.01, "true"), 2));
        assertTrue(HTTPServerHandler.parseDryRunFlag(params("addr", 0.01, "TRUE"), 2));
        assertTrue(HTTPServerHandler.parseDryRunFlag(params("addr", 0.01, "1"), 2));
    }

    @Test
    @DisplayName("a two-parameter call is not a dry run (backward compatibility)")
    void testDryRunFlagIgnoresAddressAndAmount() {
        // The flag only exists at the index the caller names. Reading index 2
        // from a two-parameter call is how a pre-flag caller keeps working, so
        // that is what is asserted -- not that a boolean can be scraped out of
        // the address or amount, which are never booleans.
        assertFalse(HTTPServerHandler.parseDryRunFlag(params("addr", 0.01), 2));
    }

    @Test
    @DisplayName("a misspelled dryrun is refused rather than read as a real send")
    void testDryRunFlagRejectsUnrecognisedValue() {
        // The asymmetry that matters: a wrong `verbose` only degrades output,
        // but a wrong `dryrun` read as false would broadcast the transaction the
        // caller asked to have reviewed first.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> HTTPServerHandler.parseDryRunFlag(params("addr", 0.01, "ture"), 2),
                "a misspelling must not resolve to a real broadcast");
        assertTrue(e.getMessage().contains("dryrun"),
                "the failure must name the flag, got: " + e.getMessage());
    }

    @Test
    @DisplayName("a JSON null flag means no dry run, not a thrown exception")
    void testDryRunFlagToleratesJsonNull() {
        // JsonNull.getAsString() throws UnsupportedOperationException, and the
        // dispatch switch has no outer handler: an exception here escapes the
        // RPC and the caller gets no response at all.
        JsonArray withNull = params("addr", 0.01);
        withNull.add(com.google.gson.JsonNull.INSTANCE);
        assertFalse(HTTPServerHandler.parseDryRunFlag(withNull, 2));
    }

    @Test
    @DisplayName("a non-primitive flag is refused, not read as a real send")
    void testDryRunFlagRejectsNonPrimitive() {
        // It used to be read as false, which meant an object in the flag position
        // produced a real broadcast. On this axis guessing is not symmetric: the
        // caller asked to review the transaction before it was spent.
        for (com.google.gson.JsonElement bad : new com.google.gson.JsonElement[]{
                new com.google.gson.JsonObject(), new com.google.gson.JsonArray()}) {
            JsonArray withBad = params("addr", 0.01);
            withBad.add(bad);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> HTTPServerHandler.parseDryRunFlag(withBad, 2),
                    "a non-primitive flag must not resolve to a broadcast");
            assertTrue(e.getMessage().contains("dryrun"),
                    "the failure must name the flag, got: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("an index past the end of the parameter list is not a dry run")
    void testDryRunFlagToleratesAbsentIndex() {
        assertFalse(HTTPServerHandler.parseDryRunFlag(params("addr", 0.01), 7));
    }

    @Test
    @DisplayName("dry run reports the txid of the exact bytes it returns")
    void testDryRunTxidMatchesReturnedHex() {
        NetworkParameters params = xvgParams();
        Transaction tx = new Transaction(params);
        tx.addOutput(Coin.valueOf(10_000L), LegacyAddress.fromPubKeyHash(params, new byte[20]));
        String hex = bytesToHex(tx.bitcoinSerialize());

        JsonObject result = HTTPServerHandler.buildDryRunResult(params, XVG_COIN_UNIT, 100_000L, hex, tx);

        assertTrue(result.get("dryRun").getAsBoolean());
        assertEquals(hex, result.get("hex").getAsString());
        // Independently recomputed: the txid must be the double-SHA256 of the
        // returned bytes, reversed. A txid computed from the in-memory
        // transaction would not match for time-field coins.
        String expected = Sha256Hash.wrapReversed(Sha256Hash.hashTwice(tx.bitcoinSerialize())).toString();
        assertEquals(expected, result.get("txid").getAsString());
    }

    @Test
    @DisplayName("dry run reports the charged fee, not a recomputed estimate")
    void testDryRunReportsChargedFee() {
        NetworkParameters params = xvgParams();
        Transaction tx = new Transaction(params);
        tx.addInput(Sha256Hash.wrapReversed(new byte[32]), 0, new org.bitcoinj.script.ScriptBuilder().build());
        tx.addOutput(Coin.valueOf(10_000L), LegacyAddress.fromPubKeyHash(params, new byte[20]));
        String hex = bytesToHex(tx.bitcoinSerialize());

        // 1 input / 1 output would be max(200 * 226, 100000) = 100000 by
        // formula; the wallet actually reserved for a change input and
        // charged the 120000 it passed in. The payload must show the latter.
        JsonObject result = HTTPServerHandler.buildDryRunResult(params, XVG_COIN_UNIT, 120_000L, hex, tx);

        assertEquals(120_000L, result.get("feeSats").getAsLong());
        assertEquals(0.12, result.get("fee").getAsDouble(), 1e-9);
        assertEquals(XVG_FEE_PER_BYTE, result.get("feePerByte").getAsLong());
        assertEquals(XVG_MIN_TX_FEE, result.get("minTxFee").getAsLong());
        assertEquals(XVG_COIN_UNIT, result.get("coinUnit").getAsLong());
    }

    @Test
    @DisplayName("a six-decimal coin reports the fee in whole coins, not 1e8 units")
    void testDryRunFeeUsesCoinUnit() {
        NetworkParameters params = xvgParams();
        Transaction tx = new Transaction(params);
        tx.addInput(Sha256Hash.wrapReversed(new byte[32]), 0, new org.bitcoinj.script.ScriptBuilder().build());
        String hex = bytesToHex(tx.bitcoinSerialize());

        // 100000 base units of a 1e6-divisor coin is 0.1 XVG. With a
        // hardcoded 1e8 divisor this would report 0.001.
        JsonObject result = HTTPServerHandler.buildDryRunResult(params, XVG_COIN_UNIT, 100_000L, hex, tx);

        assertEquals(0.1, result.get("fee").getAsDouble(), 1e-9);
    }

    @Test
    @DisplayName("dry run reports the byte size and shape of the transaction")
    void testDryRunReportsShape() {
        NetworkParameters params = xvgParams();
        Transaction tx = new Transaction(params);
        tx.addInput(Sha256Hash.wrapReversed(new byte[32]), 0, new org.bitcoinj.script.ScriptBuilder().build());
        tx.addInput(Sha256Hash.wrapReversed(new byte[32]), 1, new org.bitcoinj.script.ScriptBuilder().build());
        tx.addOutput(Coin.valueOf(10_000L), LegacyAddress.fromPubKeyHash(params, new byte[20]));
        String hex = bytesToHex(tx.bitcoinSerialize());

        JsonObject result = HTTPServerHandler.buildDryRunResult(params, XVG_COIN_UNIT, 100_000L, hex, tx);

        assertEquals(2, result.get("inputs").getAsInt());
        assertEquals(1, result.get("outputs").getAsInt());
        assertEquals(tx.bitcoinSerialize().length, result.get("sizeBytes").getAsInt());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Test
    @DisplayName("send fallback returns the envelope itself, not a version string")
    void testSendFallbackEnvelopeIsTheResult() {
        // The upstream sometimes returns the txid as the whole envelope
        // rather than nested under "result". The response must carry that
        // envelope as the result. This pins the shape at the exact point
        // where a missing break once fell through to the "version" case and
        // overwrote the txid with CLIENT_VERSION -- after an irreversible
        // broadcast.
        JsonObject envelope = new JsonObject();
        envelope.addProperty("txid", "abc123");
        JsonObject response = new JsonObject();
        HTTPServerHandler.buildSendFallbackResult(response, envelope);
        assertEquals(envelope, response.get("result"));
        assertTrue(response.get("error").isJsonNull());
    }
}
