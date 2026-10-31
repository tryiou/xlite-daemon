package io.xlite.daemon.app.util;

import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Ports a stepping listener must not take: the master listener's port and every other coin's daemon
 * and derived listener ports.
 *
 * <p>The master port is passed in rather than read here: it is the operator's configured value, not
 * a constant, and this class stays free of file access.
 */
public final class RpcPortClaims {

    private RpcPortClaims() {}

    /**
     * Ports claimed by everything except {@code ticker}.
     *
     * @param ticker the asking coin; its own ports are not claimed
     * @param masterPort the master listener's port, never handed to a coin
     */
    public static Set<Integer> excluding(String ticker, int masterPort) {
        Set<Integer> claimed = new LinkedHashSet<>();
        claimed.add(masterPort);
        if (!CoinConfigRegistry.isLoaded()) {
            return claimed;
        }
        for (CoinTicker other : CoinTicker.coins()) {
            String otherTicker = CoinTickerUtils.tickerToString(other);
            if (otherTicker == null || otherTicker.equals(ticker)) {
                continue;
            }
            claim(claimed, otherTicker);
        }
        return claimed;
    }

    private static void claim(Set<Integer> claimed, String otherTicker) {
        CoinConfig config;
        try {
            config = CoinConfigRegistry.get(otherTicker);
        } catch (IllegalStateException e) {
            return; // not configured here
        }
        claimed.add(config.port());
        claimed.add(RpcPorts.xlitePort(config.port()));
    }
}
