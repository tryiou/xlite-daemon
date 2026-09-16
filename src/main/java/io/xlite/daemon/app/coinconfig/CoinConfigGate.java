package io.xlite.daemon.app.coinconfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Sanity gate applied to a {@link CoinConfig} before its values may drive
 * live address/fee behavior. Violations abort with every problem listed —
 * a config that fails here must never silently fall back to anything.
 */
public final class CoinConfigGate {

    public static void validate(CoinConfig cfg) {
        List<String> v = new ArrayList<>();

        checkByteRange("AddressPrefix", cfg.addressPrefix(), v);
        checkByteRange("ScriptPrefix", cfg.scriptPrefix(), v);
        checkByteRange("SecretPrefix", cfg.secretPrefix(), v);
        if (cfg.addressPrefix() == cfg.scriptPrefix())
            v.add("AddressPrefix equals ScriptPrefix (" + cfg.addressPrefix() + ")");
        if (cfg.addressPrefix() == cfg.secretPrefix())
            v.add("AddressPrefix equals SecretPrefix (" + cfg.addressPrefix() + ")");
        if (cfg.scriptPrefix() == cfg.secretPrefix())
            v.add("ScriptPrefix equals SecretPrefix (" + cfg.scriptPrefix() + ")");

        if (cfg.coinFactor() <= 0)
            v.add("COIN must be positive, got " + cfg.coinFactor());
        if (cfg.feePerByte() <= 0)
            v.add("FeePerByte must be positive, got " + cfg.feePerByte());
        if (cfg.minTxFee() <= 0)
            v.add("MinTxFee must be positive, got " + cfg.minTxFee());

        int port = cfg.port();
        if (port < 1024 || port > 65_535)
            v.add("Port out of range 1024..65535: " + port);

        if (!v.isEmpty()) {
            throw new IllegalStateException("["
                    + cfg.getTicker() + "] coin config rejected: " + String.join("; ", v));
        }
    }

    private static void checkByteRange(String key, int value, List<String> violations) {
        if (value < 0 || value > 255)
            violations.add(key + " out of byte range 0..255: " + value);
    }

    private CoinConfigGate() {
    }
}
