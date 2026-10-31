package io.xlite.daemon.app.net.api.http.server;

import com.google.gson.JsonArray;

import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.net.api.http.client.HTTPClient;
import io.xlite.daemon.app.util.AddressBalance;
import io.xlite.daemon.app.util.UTXO;
import io.xlite.daemon.app.wallet.WalletHelper;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;

import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * The wallet's unspent outputs: the cached provider fetch that populates them, and the lookups the
 * RPC methods need.
 *
 * <p>The wallet is populated only by a fetch, and that fetch is cached upstream. Both the send path
 * and {@code listunspent} refresh through here, so a send never depends on some earlier unrelated
 * call having happened to populate the set, and every caller ages the cache the same way.
 */
final class WalletUtxos {

    /** How long a fetched unspent set stays usable before a caller refreshes it. */
    static final int FETCH_EXPIRY_MS = 30000;

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private final CoinInstance coin;
    private final HTTPClient httpClient;

    WalletUtxos(CoinInstance coin, HTTPClient httpClient) {
        this.coin = coin;
        this.httpClient = httpClient;
    }

    /** The provider's unspent set for this coin, or null while it is not available. */
    JsonArray fetch() {
        return httpClient.getUtxos(coin.getTicker(), FETCH_EXPIRY_MS);
    }

    /** The wallet's record of an unspent output, or null when it holds none. */
    UTXO find(Sha256Hash txid, long vout) {
        for (AddressBalance address : coin.getAddressKeyPairs()) {
            for (UTXO utxo : address.getUtxos()) {
                if (matches(utxo, txid, vout)) {
                    return utxo;
                }
            }
        }
        return null;
    }

    /** The key that signs an unspent output, or null when the wallet holds none. */
    ECKey signingKey(Sha256Hash txid, long vout) {
        for (AddressBalance address : coin.getAddressKeyPairs()) {
            for (UTXO utxo : address.getUtxos()) {
                if (matches(utxo, txid, vout)) {
                    return address.getPrivateKey().getKey();
                }
            }
        }
        return null;
    }

    /**
     * Marks a broadcast transaction's inputs spent.
     *
     * <p>The broadcast has already happened, so a failure here must not turn a successful send into
     * an error response; it is reported and the caller still receives the txid it asked for.
     */
    void markSpent(Transaction transaction) {
        try {
            WalletHelper.setAsSpent(coin.getTicker(), transaction, true);
        } catch (RuntimeException e) {
            LOGGER.warning(
                    "[http-server-handler] post-broadcast bookkeeping failed for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + ": "
                            + e.getMessage());
        }
    }

    private static boolean matches(UTXO utxo, Sha256Hash txid, long vout) {
        return utxo.createUTXO().getHash().equals(txid) && utxo.getVout() == vout;
    }
}
