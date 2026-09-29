package io.xlite.daemon.app.util;

import java.math.BigDecimal;

/**
 * Conversion between whole coins and integer base units, and the decimal
 * places a coin's COIN factor implies. Single choke point so every path
 * (ingest, send, fees, balance, display) converts identically and in one
 * direction.
 *
 * <p>The divisor is always the coin's own BCF COIN factor (base units per
 * whole coin: 100000000 for 8-decimal coins, 1000000 for XVG/EMC). It is
 * a parameter precisely so no call site can fall back to a hardcoded
 * universal constant -- that shortcut mispriced every 6-decimal coin 100x.
 *
 * <p>Must round to nearest: binary doubles cannot exactly represent most
 * decimal fractions, and truncating the representation error loses
 * satoshis (e.g. 0.00050001 becomes 50000 instead of 50001).
 */
public final class Sats {

    private Sats() {
    }

    public static long fromWholeCoins(double whole, long coinFactor) {
        return Math.round(whole * coinFactor);
    }

    /**
     * Whole coins from base units, for arithmetic callers.
     *
     * <p>The counterpart of {@link #fromWholeCoins(double, long)}, and the
     * only place the reverse conversion is expressed: a caller dividing by a
     * raw 1e8 literal gets a 100x-wrong result on every 6-decimal coin.
     */
    public static double toWholeCoins(long baseUnits, long coinFactor) {
        return baseUnits / (double) coinFactor;
    }

    /**
     * Whole coins from base units as an exact decimal string, for display.
     *
     * <p>Exact because the factor is a power of ten (enforced at config load),
     * so the division terminates and no rounding mode is needed -- unlike
     * routing a double through {@link BigDecimal#divide(BigDecimal)}, which
     * throws on a non-terminating expansion.
     */
    public static String toWholeCoinsString(long baseUnits, long coinFactor) {
        return BigDecimal.valueOf(baseUnits)
                .divide(BigDecimal.valueOf(coinFactor))
                .stripTrailingZeros()
                .toPlainString();
    }

    /**
     * Decimal places implied by a BCF COIN factor (100000000 -> 8,
     * 1000000 -> 6). BCF COIN values are powers of ten (fleet-wide
     * audited); anything else fails fast rather than mis-scaling
     * display formatting.
     */
    public static int decimalPlaces(long coinFactor) {
        // Count by dividing the factor rather than by multiplying a running
        // power of ten: multiplication overflows past 10^18, and once the
        // running value wraps negative the comparison stops being able to
        // terminate. Division cannot overflow, so this always finishes.
        int places = 0;
        long remaining = coinFactor;
        while (remaining > 1 && remaining % 10 == 0) {
            remaining /= 10;
            places++;
        }
        if (remaining != 1)
            throw new IllegalArgumentException(
                    "coin factor is not a power of ten: " + coinFactor);
        return places;
    }
}
