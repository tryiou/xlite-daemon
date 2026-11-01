package io.xlite.daemon.app.coinconfig;

import java.util.Locale;

/**
 * How a chain computes the hash a transaction signature covers.
 *
 * <p>Bitcoin and the chains derived from it before the 2017 split sign the legacy preimage, which
 * bitcoinj implements. Bitcoin Cash replaced it with a BIP143-shaped preimage and requires the
 * signature's hash-type byte to carry {@code SIGHASH_FORKID} (0x40); a legacy signature is rejected
 * with {@code MUST_USE_FORKID} at script verification. Which of the two a chain uses is a consensus
 * rule, not a fee or port preference, so it is compiled in the supplement next to the other signing
 * rule (Verge's parent-nTime requirement) and cross-checked against the configuration repository's
 * {@code CreateTxMethod}.
 */
public enum SighashStyle {
    /** The original Bitcoin signature hash, as implemented by bitcoinj. */
    LEGACY,
    /** The Bitcoin Cash signature hash with SIGHASH_FORKID. */
    FORKID;

    /**
     * The style a configuration's {@code CreateTxMethod} implies, or null when that value names a
     * transaction builder this wallet does not implement.
     *
     * <p>The configuration field is what XBridge uses to choose its transaction builder. It is
     * consulted as a consistency check, never as the authority: the signer's rule is compiled in.
     */
    /**
     * Whether a configuration's transaction-builder name agrees with this compiled style. Unknown
     * names never agree: a builder this wallet does not implement must not sign.
     */
    public boolean matchesCreateTxMethod(String method) {
        return this == fromCreateTxMethod(method);
    }

    public static SighashStyle fromCreateTxMethod(String method) {
        if (method == null) {
            return null;
        }
        return switch (method.trim().toUpperCase(Locale.ROOT)) {
            case "BTC" -> LEGACY;
            case "BCH" -> FORKID;
            default -> null;
        };
    }
}
