package io.xlite.daemon.app.coinconfig;

import io.xlite.daemon.app.util.Sats;

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

        if (cfg.coinFactor() <= 0) {
            v.add("COIN must be positive, got " + cfg.coinFactor());
        } else {
            // COIN is base units per whole coin, so it must be a power of
            // ten. Any other value cannot be rendered as a decimal at all and
            // would silently misprice every amount, so it is rejected here,
            // where every other bad value is reported, rather than at first
            // use deep in the wallet.
            try {
                Sats.decimalPlaces(cfg.coinFactor());
            } catch (IllegalArgumentException e) {
                v.add("COIN is not a power of ten: " + cfg.coinFactor());
            }
        }
        if (cfg.feePerByte() <= 0)
            v.add("FeePerByte must be positive, got " + cfg.feePerByte());
        if (cfg.minTxFee() <= 0)
            v.add("MinTxFee must be positive, got " + cfg.minTxFee());

        // Absent or zero means no dust floor, which is a legitimate choice and
        // is honoured as such. Negative does not: a negative floor sits below
        // every output, so nothing is ever trimmed and change is paid to the
        // miner as fee instead of returned. That is silent money loss, so it
        // is rejected here rather than becoming a runtime surprise.
        Long dust = cfg.dustAmountOrNull();
        if (dust != null && dust < 0)
            v.add("DustAmount must not be negative, got " + dust);

        int port = cfg.port();
        if (port < 1024 || port > 65_535)
            v.add("Port out of range 1024..65535: " + port);

        // TxWithTimeField decides whether signatures are computed over an
        // nTime-bearing preimage. A malformed value throws when the send path
        // first reads it, i.e. as a per-send failure; report it here instead,
        // where every other bad value is reported.
        try {
            cfg.txWithTimeField();
        } catch (RuntimeException e) {
            v.add("TxWithTimeField invalid: " + e.getMessage());
        }

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
