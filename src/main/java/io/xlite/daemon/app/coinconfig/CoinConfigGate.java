package io.xlite.daemon.app.coinconfig;

import io.xlite.daemon.app.util.Sats;

import java.util.ArrayList;
import java.util.List;

/**
 * Sanity gate applied to a {@link CoinConfig} before its values may drive live address/fee
 * behavior. Violations abort with every problem listed — a config that fails here must never
 * silently fall back to anything.
 */
public final class CoinConfigGate {

    public static void validate(CoinConfig cfg) {
        List<String> violations = new ArrayList<>();
        checkPrefixes(cfg, violations);
        checkAmounts(cfg, violations);
        checkDust(cfg, violations);
        checkNetwork(cfg, violations);
        checkWireVersion(cfg, violations);
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "["
                            + cfg.getTicker()
                            + "] coin config rejected: "
                            + String.join("; ", violations));
        }
    }

    private static void checkPrefixes(CoinConfig cfg, List<String> violations) {
        checkByteRange("AddressPrefix", cfg.addressPrefix(), violations);
        checkByteRange("ScriptPrefix", cfg.scriptPrefix(), violations);
        checkByteRange("SecretPrefix", cfg.secretPrefix(), violations);
        if (cfg.addressPrefix() == cfg.scriptPrefix()) {
            violations.add("AddressPrefix equals ScriptPrefix (" + cfg.addressPrefix() + ")");
        }
        if (cfg.addressPrefix() == cfg.secretPrefix()) {
            violations.add("AddressPrefix equals SecretPrefix (" + cfg.addressPrefix() + ")");
        }
        if (cfg.scriptPrefix() == cfg.secretPrefix()) {
            violations.add("ScriptPrefix equals SecretPrefix (" + cfg.scriptPrefix() + ")");
        }
    }

    private static void checkAmounts(CoinConfig cfg, List<String> violations) {
        if (cfg.coinFactor() <= 0) {
            violations.add("COIN must be positive, got " + cfg.coinFactor());
        } else {
            // COIN is base units per whole coin, so it must be a power of ten. Any other
            // value cannot be rendered as a decimal at all and would silently misprice
            // every amount, so it is rejected here, where every other bad value is
            // reported, rather than at first use deep in the wallet.
            try {
                Sats.decimalPlaces(cfg.coinFactor());
            } catch (IllegalArgumentException e) {
                violations.add("COIN is not a power of ten: " + cfg.coinFactor());
            }
        }
        if (cfg.feePerByte() <= 0) {
            violations.add("FeePerByte must be positive, got " + cfg.feePerByte());
        }
        if (cfg.minTxFee() <= 0) {
            violations.add("MinTxFee must be positive, got " + cfg.minTxFee());
        }
    }

    /**
     * A dust floor is required: absent fails here rather than silently meaning "no floor". Zero is
     * legal (Firo has no dust gate); negative would let every change output through, paying it to
     * the miner.
     */
    private static void checkDust(CoinConfig cfg, List<String> violations) {
        try {
            long dust = cfg.dustAmount();
            if (dust < 0) {
                violations.add("DustAmount must not be negative, got " + dust);
            }
        } catch (RuntimeException e) {
            violations.add("DustAmount invalid: " + e.getMessage());
        }
    }

    private static void checkNetwork(CoinConfig cfg, List<String> violations) {
        int port = cfg.port();
        if (port < 1024 || port > 65_535) {
            violations.add("Port out of range 1024..65535: " + port);
        }
        // TxWithTimeField decides whether signatures are computed over an nTime-bearing
        // preimage. A malformed value throws when the send path first reads it, i.e. as a
        // per-send failure; report it here instead, where every other bad value is
        // reported.
        try {
            cfg.txWithTimeField();
        } catch (RuntimeException e) {
            violations.add("TxWithTimeField invalid: " + e.getMessage());
        }
    }

    /**
     * TxVersion is the coin's own transaction version, required for the same reason as the
     * wire-format flag: building a version the chain's wallet does not use is a deviation the
     * caller cannot see. Only a positive value is meaningful, but the range is deliberately not
     * capped at 2 -- BCF carries larger values (Particl's 160).
     */
    private static void checkWireVersion(CoinConfig cfg, List<String> violations) {
        try {
            int txVersion = cfg.txVersion();
            if (txVersion < 1) {
                violations.add("TxVersion must be positive, got " + txVersion);
            }
        } catch (RuntimeException e) {
            violations.add("TxVersion invalid: " + e.getMessage());
        }
    }

    private static void checkByteRange(String key, int value, List<String> violations) {
        if (value < 0 || value > 255) {
            violations.add(key + " out of byte range 0..255: " + value);
        }
    }

    private CoinConfigGate() {}
}
