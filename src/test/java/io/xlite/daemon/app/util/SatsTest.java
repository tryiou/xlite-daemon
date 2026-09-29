package io.xlite.daemon.app.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Shared whole-coin to base-unit conversion must round to nearest, never truncate.
 *
 * <p>Binary doubles cannot exactly represent most decimal fractions. A value
 * like 0.00050001 (50001 sats) is stored as slightly less, so {@code (long)}
 * truncation yields 50000 — a 1-satoshi shortfall that invalidated BIP137
 * ownership proofs on takes (servicenode {@code crNoMoney} rejects). Every
 * whole-coin to base-unit site (ingest, send, fees, display) must go through
 * {@link Sats#fromWholeCoins} with the coin's own BCF COIN factor.
 */
class SatsTest {

    private static final long EIGHT_DECIMALS = 100000000L;
    private static final long SIX_DECIMALS = 1000000L;

    @Test
    void testInexactDoubleRoundsToNearest() {
        // 0.00050001 is not exactly representable in binary; floor() yields 50000.
        assertEquals(50001L, Sats.fromWholeCoins(0.00050001, EIGHT_DECIMALS));
    }

    @Test
    void testExactValues() {
        assertEquals(500000L, Sats.fromWholeCoins(0.005, EIGHT_DECIMALS));
        assertEquals(20000000L, Sats.fromWholeCoins(0.2, EIGHT_DECIMALS));
        assertEquals(1L, Sats.fromWholeCoins(0.00000001, EIGHT_DECIMALS));
        assertEquals(0L, Sats.fromWholeCoins(0.0, EIGHT_DECIMALS));
    }

    @Test
    void testSubSatoshiRoundsToZero() {
        assertEquals(0L, Sats.fromWholeCoins(0.000000001, EIGHT_DECIMALS));
    }

    @Test
    void testMaxSupplyNoOverflow() {
        assertEquals(2_100_000_000_000_000L, Sats.fromWholeCoins(21_000_000.0, EIGHT_DECIMALS));
    }

    @Test
    void testSixDecimalCoinUsesOwnFactor() {
        // Verge live case: 0.1 XVG is 100000 base units, not 10000000.
        assertEquals(100000L, Sats.fromWholeCoins(0.1, SIX_DECIMALS));
        assertEquals(2900000L, Sats.fromWholeCoins(2.9, SIX_DECIMALS));
    }

    @Test
    void testDecimalPlaces() {
        assertEquals(8, Sats.decimalPlaces(EIGHT_DECIMALS));
        assertEquals(6, Sats.decimalPlaces(SIX_DECIMALS));
    }

    @Test
    void testDecimalPlacesRejectsNonPowerOfTen() {
        assertThrows(IllegalArgumentException.class, () -> Sats.decimalPlaces(12345L));
    }
}
