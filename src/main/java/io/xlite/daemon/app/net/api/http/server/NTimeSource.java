package io.xlite.daemon.app.net.api.http.server;

import com.google.gson.JsonObject;
import com.subgraph.orchid.encoders.Hex;

import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.api.http.client.HTTPClient;
import io.xlite.daemon.app.util.TxTimeField;
import io.xlite.daemon.app.util.UTXO;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Resolves the nTime a Peercoin-derived chain signs.
 *
 * <p>The chain hashes nTime into the signature preimage, so the value must be known before signing
 * and must be the same one spliced into the wire bytes afterwards. Its consensus rule is that a
 * transaction may not be older than its inputs, so the value is the newest of wall-clock time and
 * every parent's nTime -- read from chain, never guessed. Any fetch or parse failure aborts the
 * send rather than signing bytes no node hashes.
 */
final class NTimeSource {

    private final CoinInstance coin;
    private final HTTPClient httpClient;

    NTimeSource(CoinInstance coin, HTTPClient httpClient) {
        this.coin = coin;
        this.httpClient = httpClient;
    }

    /**
     * The nTime for a transaction spending {@code selected}: the newest of wall-clock time and
     * every input parent's nTime. Runs inside the wallet's build, after the inputs are chosen.
     */
    long forInputs(List<UTXO> selected) {
        List<String> parentTxids = new ArrayList<>();
        for (UTXO utxo : selected) {
            if (!utxo.isSpent()) {
                parentTxids.add(utxo.getTxid());
            }
        }
        return TxTimeField.spendNTime(
                System.currentTimeMillis() / 1000, fetchParentNTimes(parentTxids));
    }

    /**
     * The nTime to sign, or -1 for a chain whose transactions carry none.
     *
     * <p>The value carried by the unsigned transaction is the floor: wall-clock time must not be
     * injected while signing. A chain that enforces the input-time rule may raise it to the newest
     * parent's time; one that only signs the field keeps it. Throws when a time-field transaction
     * carries no nTime at all.
     */
    long forSigning(long preservedNTime, List<String> inputTxids) {
        if (!TxWire.hasTimeField(coin.getTicker())) {
            return -1;
        }
        long parentMax = -1;
        if (TxTimeField.requiresParentNTime(coin.getTicker()) && !inputTxids.isEmpty()) {
            parentMax = newestParentNTime(inputTxids);
        }
        long signedNTime = Math.max(preservedNTime, parentMax);
        if (signedNTime < 0) {
            throw new IllegalStateException("the unsigned transaction has no nTime");
        }
        return signedNTime;
    }

    private long newestParentNTime(List<String> parentTxids) {
        // Several inputs may spend different outputs of the same parent, and its nTime is a
        // property of that transaction: fetch each parent once.
        long newest = -1;
        for (Long parent : fetchParentNTimes(new ArrayList<>(new LinkedHashSet<>(parentTxids)))) {
            if (parent != null && parent > newest) {
                newest = parent;
            }
        }
        return newest;
    }

    private List<Long> fetchParentNTimes(List<String> parentTxids) {
        List<Long> parentNTimes = new ArrayList<>();
        for (String parentTxid : parentTxids) {
            parentNTimes.add(fetchParentNTime(parentTxid));
        }
        return parentNTimes;
    }

    /** Read a parent's nTime from the first four bytes of its raw transaction. */
    private long fetchParentNTime(String parentTxid) {
        JsonObject parent = httpClient.getRawTransaction(coin.getTicker(), parentTxid, false);
        if (parent == null || !parent.has("result") || parent.get("result").isJsonNull()) {
            throw new IllegalStateException("Cannot fetch parent " + parentTxid + " for nTime");
        }
        try {
            return TxTimeField.parentNTime(Hex.decode(parent.get("result").getAsString()));
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Cannot parse parent " + parentTxid + " nTime: " + e.getMessage(), e);
        }
    }
}
