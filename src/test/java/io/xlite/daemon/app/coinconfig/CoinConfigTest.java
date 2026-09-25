package io.xlite.daemon.app.coinconfig;

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
    void testDustAbsentNullMalformedThrows() {
        assertNull(sample().dustAmountOrNull());

        Map<String, String> withDust = new LinkedHashMap<>();
        withDust.put("DustAmount", "5460");
        assertEquals(5460L, new CoinConfig("X", "X", "", withDust).dustAmountOrNull());

        Map<String, String> badDust = new LinkedHashMap<>();
        badDust.put("DustAmount", "zero");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CoinConfig("X", "X", "", badDust).dustAmountOrNull());
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
}
