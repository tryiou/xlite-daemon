package io.xlite.daemon.app.coinconfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoinConfigTest {

    private CoinConfig sample() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("AddressPrefix", "48");
        entries.put("ScriptPrefix", "50");
        entries.put("SecretPrefix", "176");
        entries.put("COIN", "100000000");
        entries.put("FeePerByte", "10");
        entries.put("MinTxFee", "5000");
        entries.put("Port", "9332");
        entries.put("DustAmount", "0");
        return new CoinConfig("LTC", "Litecoin", "litecoin--v0.21.1", entries);
    }

    @Test
    void testTypedAccessorsParseShippedValues() {
        CoinConfig c = sample();
        assertEquals(48, c.addressPrefix());
        assertEquals(50, c.scriptPrefix());
        assertEquals(176, c.secretPrefix());
        assertEquals(100_000_000L, c.coinFactor());
        assertEquals(10L, c.feePerByte());
        assertEquals(5000L, c.minTxFee());
        assertEquals(9332, c.port());
    }

    @Test
    void testMissingKeyFailsHardWithTickerContext() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CoinConfig("X", "Xcoin", "", Map.of("A", "1")).feePerByte());
        assertTrue(e.getMessage().contains("[X] missing required config key 'FeePerByte'"), e.getMessage());
    }

    @Test
    void testMalformedNumberFailsHardWithContext() {
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("FeePerByte", "abc");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CoinConfig("X", "Xcoin", "", bad).feePerByte());
        assertTrue(e.getMessage().contains("[X] config key 'FeePerByte' is not a number: 'abc'"), e.getMessage());
    }

    @Test
    void testTxWithTimeFieldAbsentFalseTrueFalseMalformedThrows() {
        // Opt-in flag: 33 fleet confs lack the key entirely.
        assertFalse(sample().txWithTimeField());

        Map<String, String> on = new LinkedHashMap<>();
        on.put("TxWithTimeField", "true");
        assertTrue(new CoinConfig("X", "X", "", on).txWithTimeField());

        Map<String, String> off = new LinkedHashMap<>();
        off.put("TxWithTimeField", "false");
        assertFalse(new CoinConfig("X", "X", "", off).txWithTimeField());

        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("TxWithTimeField", "yes");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CoinConfig("X", "X", "", bad).txWithTimeField());
        assertTrue(e.getMessage().contains("'TxWithTimeField' is not true/false"), e.getMessage());
    }

    @Test
    void testDustAmountRequiredZeroIsAValueAndMalformedThrows() {
        // Zero is a real floor: Firo's daemon rejects nothing, and its conf
        // carries DustAmount=0. Only a missing key is "unspecified".
        assertEquals(0L, sample().dustAmount());

        Map<String, String> withDust = new LinkedHashMap<>();
        withDust.put("DustAmount", "5460");
        assertEquals(5460L, new CoinConfig("X", "X", "", withDust).dustAmount());

        Map<String, String> absent = new LinkedHashMap<>();
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> new CoinConfig("X", "X", "", absent).dustAmount());
        assertTrue(missing.getMessage().contains("missing required config key 'DustAmount'"),
                missing.getMessage());

        Map<String, String> badDust = new LinkedHashMap<>();
        badDust.put("DustAmount", "zero");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CoinConfig("X", "X", "", badDust).dustAmount());
        assertTrue(e.getMessage().contains("'DustAmount' is not a number"), e.getMessage());
    }

    @Test
    void testPortOverflowFailsHardWithContext() {
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("Port", "99999999999");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CoinConfig("X", "X", "", bad).port());
        assertTrue(e.getMessage().contains("[X] config key 'Port' exceeds the int range"), e.getMessage());
    }

    @Test
    void testConfEntriesUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> sample().getConfEntries().put("New", "1"));
    }

    /** A config the gate accepts, so a test can break exactly one thing. */
    private static Map<String, String> gateValid(String ticker, String timeField) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("AddressPrefix", "30");
        m.put("ScriptPrefix", "63");
        m.put("SecretPrefix", "128");
        m.put("COIN", "100000000");
        m.put("FeePerByte", "10");
        m.put("MinTxFee", "5000");
        m.put("Port", "9332");
        m.put("DustAmount", "0");
        m.put("TxWithTimeField", timeField);
        return m;
    }

    @Test
    @DisplayName("the gate refuses a malformed TxWithTimeField at load, not on first use")
    void testGateRejectsMalformedTxWithTimeField() {
        // The gate runs when configurations load, so a coin with a malformed flag
        // is dropped from the registry there rather than at first send. That
        // matters: this flag decides whether signatures are computed over an
        // nTime-bearing preimage, and a coin that only discovers it mid-send has
        // already built a transaction by the wrong rules.
        CoinConfig bad = new CoinConfig("LTC", "Litecoin", "ltc--test", gateValid("LTC", "yes"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CoinConfigGate.validate(bad),
                "a malformed TxWithTimeField must be rejected at load");
        assertTrue(e.getMessage().contains("coin config rejected"), e.getMessage());
        assertTrue(e.getMessage().contains("TxWithTimeField"), e.getMessage());
    }

    @Test
    @DisplayName("the gate accepts both spellings of the flag")
    void testGateAcceptsValidTxWithTimeField() {
        for (String value : new String[]{"true", "false"}) {
            CoinConfig cfg = new CoinConfig("LTC", "Litecoin", "ltc--test", gateValid("LTC", value));
            assertDoesNotThrow(() -> CoinConfigGate.validate(cfg),
                    "TxWithTimeField=" + value + " must be accepted");
        }
    }
}
