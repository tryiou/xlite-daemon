package io.xlite.daemon.app.net.api.http.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Whole-coin to satoshi conversion must round to nearest, never truncate.
 *
 * <p>A live DASH UTXO worth 0.00050001 (50001 sats) was ingested as 50000
 * sats because {@code (long) Math.floor(value * 1e8)} truncates the binary
 * floating-point representation error. The one-satoshi shortfall then
 * invalidated the BIP137 ownership proof on takes (the hub re-verifies
 * against chain truth), producing servicenode {@code crNoMoney} rejects
 * for fully-funded wallets.
 */
class SatsConversionTest {

    @Test
    void testLiveDashCase() {
        // 0.00050001 is not exactly representable in binary; floor() yields 50000.
        assertEquals(50001L, HTTPClient.satsFromWholeCoins(0.00050001));
    }

    @Test
    void testExactValues() {
        assertEquals(500000L, HTTPClient.satsFromWholeCoins(0.005));
        assertEquals(20000000L, HTTPClient.satsFromWholeCoins(0.2));
        assertEquals(1L, HTTPClient.satsFromWholeCoins(0.00000001));
        assertEquals(0L, HTTPClient.satsFromWholeCoins(0.0));
    }

    @Test
    void testSubSatoshiRoundsToZero() {
        assertEquals(0L, HTTPClient.satsFromWholeCoins(0.000000001));
    }

    @Test
    void testMaxSupplyNoOverflow() {
        assertEquals(2_100_000_000_000_000L, HTTPClient.satsFromWholeCoins(21_000_000.0));
    }
}
