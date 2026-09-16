package io.xlite.daemon.app.util;

import org.bitcoinj.core.Coin;

/**
 * Whole-coin doubles to integer base units. Single choke point so every
 * path (ingest, send, fees, display) converts identically.
 *
 * <p>Must round to nearest: binary doubles cannot exactly represent most
 * decimal fractions, and truncating the representation error loses
 * satoshis (e.g. 0.00050001 becomes 50000 instead of 50001).
 */
public final class Sats {

    private Sats() {
    }

    public static long fromWholeCoins(double whole) {
        return Math.round(whole * Coin.COIN.value);
    }
}
