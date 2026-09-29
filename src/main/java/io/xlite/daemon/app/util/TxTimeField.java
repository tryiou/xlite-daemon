package io.xlite.daemon.app.util;

import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Wire-format adapter for coins whose transactions carry an nTime field
 * after nVersion (Peercoin-derived chains; flagged per coin by the BCF
 * {@code TxWithTimeField} key and exposed as
 * {@link io.xlite.daemon.app.coinconfig.CoinConfig#txWithTimeField()}).
 *
 * <p>bitcoinj builds the standard {@code version + vin + vout + locktime}
 * layout. A time-field chain serializes {@code version + nTime + vin +
 * vout + locktime}; a standard-serialized tx is undecodable there
 * (the vin-count byte is misread as nTime) and rejected at
 * deserialization, before fees or signatures are ever evaluated.
 *
 * <p>The flag is a boolean, so it can only record that a chain is
 * time-field, not <em>which</em> of its transactions are. A few
 * chains gate the field rather than always writing it -- Emercoin
 * (BCF {@code emercoin--v0.8.5emc.conf}, {@code TxWithTimeField=true})
 * omits nTime for auxiliary-PoW Bitcoin transactions via
 * {@code SER_BTC_TX}, and upstream Peercoin gates on {@code nVersion < 3}.
 * Every coin this daemon currently serves writes the field
 * unconditionally, so the distinction has no effect today; onboarding a
 * chain with a conditional layout needs a richer flag than a boolean
 * before its output would be correct.
 *
 * <p>Splicing the field in is only half the job, and getting that half wrong
 * is worse than not implementing it at all:
 * <ul>
 *   <li>nTime IS sighash-covered. The chain's signature serializer writes
 *   nVersion and then nTime ahead of every input, so a signature computed
 *   over a preimage without nTime verifies against nothing the node accepts
 *   and the transaction is rejected at script verification. The nTime must
 *   therefore be chosen <em>before</em> signing, and signed as part of the
 *   preimage -- see {@link TxTimeFieldSigner}. Splicing afterwards produces
 *   a correctly framed transaction carrying a signature over different
 *   bytes.</li>
 *   <li>nTime is also checked against inputs ({@code
 *   coin.nOriginTransactionTime > tx.nTime} rejects, with the origin time
 *   taken from the creating tx's nTime). The spending tx needs
 *   {@code nTime >= max(parent nTimes)}, which wall-clock time alone cannot
 *   guarantee when a parent carries a future time.</li>
 * </ul>
 */
public final class TxTimeField {

    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    /** Length of nVersion / nTime / nLockTime wire fields. */
    private static final int WORD_LENGTH = 4;


    /**
     * Minimum standard-serialized tx: version + 1-byte vin count +
     * 1-byte vout count + locktime.
     */
    private static final int MIN_STANDARD_TX_LENGTH = WORD_LENGTH + 1 + 1 + WORD_LENGTH;

    private TxTimeField() {
    }

    /**
     * Whether this coin's transactions carry nTime, per the per-coin BCF
     * {@code TxWithTimeField} flag.
     */
    public static boolean appliesTo(CoinTicker ticker) {
        return CoinConfigRegistry.get(CoinTickerUtils.tickerToString(ticker)).txWithTimeField();
    }

    /**
     * Insert nTime after nVersion, converting standard serialization to
     * the time-field wire layout. Throws IllegalArgumentException on
     * undersized input or out-of-range nTime rather than emitting a
     * corrupt tx.
     */
    public static byte[] insertNTime(byte[] standardTx, long nTime) {
        if (standardTx == null || standardTx.length < MIN_STANDARD_TX_LENGTH)
            throw new IllegalArgumentException(
                    "standard tx too short for nTime insertion: "
                            + (standardTx == null ? "null" : standardTx.length));
        if (nTime < 0 || nTime > 0xFFFFFFFFL)
            throw new IllegalArgumentException("nTime out of uint32 range: " + nTime);
        byte[] out = new byte[standardTx.length + WORD_LENGTH];
        System.arraycopy(standardTx, 0, out, 0, WORD_LENGTH);
        ByteBuffer.wrap(out, WORD_LENGTH, WORD_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN).putInt((int) nTime);
        System.arraycopy(standardTx, WORD_LENGTH, out, WORD_LENGTH + WORD_LENGTH,
                standardTx.length - WORD_LENGTH);
        return out;
    }

    /**
     * Remove nTime after nVersion, converting time-field wire layout back
     * to standard serialization (for bitcoinj parsing/display paths).
     * Throws IllegalArgumentException on undersized input.
     */
    public static byte[] stripNTime(byte[] timeFieldTx) {
        if (timeFieldTx == null || timeFieldTx.length < MIN_STANDARD_TX_LENGTH + WORD_LENGTH)
            throw new IllegalArgumentException(
                    "time-field tx too short for nTime removal: "
                            + (timeFieldTx == null ? "null" : timeFieldTx.length));
        byte[] out = new byte[timeFieldTx.length - WORD_LENGTH];
        System.arraycopy(timeFieldTx, 0, out, 0, WORD_LENGTH);
        System.arraycopy(timeFieldTx, WORD_LENGTH + WORD_LENGTH, out, WORD_LENGTH,
                timeFieldTx.length - WORD_LENGTH - WORD_LENGTH);
        return out;
    }

    /**
     * The identifier the network gives a time-field transaction: the double
     * SHA-256 of the bytes exactly as they appear on the wire.
     *
     * <p>Those bytes are the hashed preimage — the field this class models is
     * part of what the chain hashes. A parser must strip it to read the
     * remaining fields with bitcoinj, and bitcoinj then re-serializes without
     * it, so its own hash covers a different message and yields an identifier
     * that does not exist on chain.
     *
     * @param timeFieldTx the wire bytes, timestamp included
     * @return the hex transaction id, reversed as chains render it
     */
    public static String txidOfWireBytes(byte[] timeFieldTx) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(digest.digest(timeFieldTx));
            // Rendered the way every chain prints a transaction id: big-endian,
            // which is the digest bytes reversed.
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (int i = hash.length - 1; i >= 0; i--) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM by the platform spec.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Read the nTime field (bytes 4-8, uint32 LE) from a time-field
     * parent raw transaction. Used to satisfy the input-time consensus
     * rule when spending its outputs.
     */
    public static long parentNTime(byte[] parentTimeFieldTx) {
        if (parentTimeFieldTx == null || parentTimeFieldTx.length < WORD_LENGTH + WORD_LENGTH)
            throw new IllegalArgumentException(
                    "parent tx too short to carry nTime: "
                            + (parentTimeFieldTx == null ? "null" : parentTimeFieldTx.length));
        return ByteBuffer.wrap(parentTimeFieldTx, WORD_LENGTH, WORD_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
    }

    /**
     * nTime for a spending transaction: the newest of wall-clock time and
     * every input parent's nTime. Wall-clock alone is insufficient --
     * parents may carry future nTimes (sender clock skew), and the
     * input-time rule rejects anything older.
     */
    public static long spendNTime(long nowUnix, List<Long> parentNTimes) {
        long nTime = nowUnix;
        if (parentNTimes != null) {
            for (Long parent : parentNTimes) {
                if (parent != null && parent > nTime)
                    nTime = parent;
            }
        }
        LOGGER.fine("[tx-time-field] spend nTime=" + nTime + " (now=" + nowUnix + ")");
        return nTime;
    }
}
