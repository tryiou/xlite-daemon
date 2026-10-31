package io.xlite.daemon.app.net.api.http.server;

import com.subgraph.orchid.encoders.Hex;

import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.util.TxTimeField;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The wire format of a transaction on a given chain.
 *
 * <p>Most chains serialize a transaction exactly as bitcoinj does. Peercoin-derived chains
 * interleave a four-byte nTime after the version, so one transaction has two layouts: the in-memory
 * form bitcoinj hashes and the bytes that travel. Every operation crossing that boundary -- parsing
 * hex, serializing for broadcast, computing the txid -- lives here so the exception is implemented
 * once.
 */
final class TxWire {

    private TxWire() {}

    /** Whether the chain's transactions carry nTime (the BCF TxWithTimeField flag). */
    static boolean hasTimeField(CoinTicker ticker) {
        return TxTimeField.appliesTo(ticker);
    }

    /** Hex of an unsigned transaction, stamped with a current nTime on a time-field chain. */
    static String serializeUnsigned(CoinTicker ticker, Transaction transaction) {
        byte[] standard = transaction.bitcoinSerialize();
        if (!hasTimeField(ticker)) {
            return hex(standard);
        }
        // An unsigned transaction handed to an external signer must still decode on a
        // time-field chain. It is wall-clock only: sendrawtransaction forwards the bytes
        // it is given unchanged, so a caller whose inputs carry a newer nTime must re-time
        // and re-sign before broadcasting.
        return hex(TxTimeField.insertNTime(standard, System.currentTimeMillis() / 1000));
    }

    /**
     * Serialize a signed transaction for broadcast, inserting nTime for time-field coins. nTime is
     * max(wall-clock, parent nTimes) to satisfy the input-time consensus rule. Throws
     * RuntimeException naming the cause: an unsendable transaction must fail here, never broadcast
     * malformed.
     */
    static String serializeForBroadcast(
            CoinTicker ticker, Transaction transaction, long signedNTime) {
        byte[] standard = transaction.bitcoinSerialize();
        if (!hasTimeField(ticker)) {
            return hex(standard);
        }
        if (signedNTime < 0 || signedNTime > 0xFFFFFFFFL) {
            throw new IllegalStateException(
                    "cannot serialize: no nTime was signed for this transaction");
        }
        return hex(TxTimeField.insertNTime(standard, signedNTime));
    }

    /**
     * Decode raw transaction bytes for bitcoinj, stripping nTime for time-field coins.
     *
     * <p>A standard-layout transaction has no nTime, so reading one as a time-field transaction
     * would shift every remaining field. No length threshold can tell the layouts apart -- a real
     * standard transaction is far longer than any minimum -- so the only sound check is structural,
     * and it is applied by {@link #parseForCoin}.
     */
    static byte[] decodeForParsing(CoinTicker ticker, byte[] rawBytes) {
        return hasTimeField(ticker) ? TxTimeField.stripNTime(rawBytes) : rawBytes;
    }

    /**
     * Parse raw transaction bytes, rejecting a hex that is in the wrong wire layout.
     *
     * <p>Stripping nTime from a standard-layout transaction leaves the vin count read from the
     * wrong offset, so the parse either fails outright or produces a transaction whose input count
     * disagrees with the bytes that were supplied. Comparing the re-serialized form against the
     * input catches the second case, which a plain parse would not.
     */
    static Transaction parseForCoin(CoinTicker ticker, NetworkParameters params, byte[] rawBytes) {
        byte[] parsed = decodeForParsing(ticker, rawBytes);
        Transaction transaction = new Transaction(params, parsed);
        if (hasTimeField(ticker) && !Arrays.equals(transaction.bitcoinSerialize(), parsed)) {
            throw new IllegalArgumentException(
                    "raw transaction is not in this chain's wire layout: it re-serializes "
                            + "differently, so it carries no nTime");
        }
        return transaction;
    }

    /**
     * The txid the network will use for exactly these bytes: nTime changes the hash, so it cannot
     * come from the in-memory transaction on a time-field chain.
     */
    static String txidForDecoded(CoinTicker ticker, byte[] wireBytes, Transaction parsed) {
        return hasTimeField(ticker)
                ? TxTimeField.txidOfWireBytes(wireBytes)
                : parsed.getTxId().toString();
    }

    /** The txid of a hex-encoded transaction, as hashed on the wire. */
    static String txidForHex(String rawTxHex) {
        return Sha256Hash.wrapReversed(Sha256Hash.hashTwice(Hex.decode(rawTxHex))).toString();
    }

    private static String hex(byte[] bytes) {
        return new String(Hex.encode(bytes), StandardCharsets.UTF_8);
    }
}
