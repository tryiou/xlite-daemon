package io.xlite.daemon.app.util;

import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Ports a stepping listener must not take: every port the master listener may bind, and every other
 * coin's daemon and derived listener ports.
 *
 * <p>The master port is passed in rather than read here: it is the operator's configured value, not
 * a constant, and this class stays free of file access. The whole window is passed because the
 * master steps within it, and coins bind first: reserving only the first port would let a coin take
 * the port the master then needs.
 */
public final class RpcPortClaims {

    private RpcPortClaims() {}

    /**
     * Ports claimed by everything except {@code ticker}.
     *
     * @param ticker the asking coin; its own ports are not claimed
     * @param masterPorts every port the master listener may bind, never handed to a coin
     */
    public static Set<Integer> excluding(String ticker, int... masterPorts) {
        Set<Integer> claimed = new LinkedHashSet<>();
        for (int masterPort : masterPorts) {
            claimed.add(masterPort);
        }
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
