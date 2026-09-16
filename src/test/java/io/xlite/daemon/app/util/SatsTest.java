package io.xlite.daemon.app.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shared whole-coin to satoshi conversion must round to nearest, never truncate.
 *
 * <p>Binary doubles cannot exactly represent most decimal fractions. A value
 * like 0.00050001 (50001 sats) is stored as slightly less, so {@code (long)}
 * truncation yields 50000 — a 1-satoshi shortfall that invalidated BIP137
 * ownership proofs on takes (servicenode {@code crNoMoney} rejects). Every
 * whole-coin to base-unit site (ingest, send, fees, display) must go through
 * {@link Sats#fromWholeCoins}.
 */
class SatsTest {

    @Test
    void testInexactDoubleRoundsToNearest() {
        // 0.00050001 is not exactly representable in binary; floor() yields 50000.
        assertEquals(50001L, Sats.fromWholeCoins(0.00050001));
    }

    @Test
    void testExactValues() {
        assertEquals(500000L, Sats.fromWholeCoins(0.005));
        assertEquals(20000000L, Sats.fromWholeCoins(0.2));
        assertEquals(1L, Sats.fromWholeCoins(0.00000001));
        assertEquals(0L, Sats.fromWholeCoins(0.0));
    }

    @Test
    void testSubSatoshiRoundsToZero() {
        assertEquals(0L, Sats.fromWholeCoins(0.000000001));
    }

    @Test
    void testMaxSupplyNoOverflow() {
        assertEquals(2_100_000_000_000_000L, Sats.fromWholeCoins(21_000_000.0));
    }
}
