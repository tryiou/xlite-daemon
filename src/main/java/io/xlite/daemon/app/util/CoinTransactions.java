package io.xlite.daemon.app.util;

import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;

/**
 * Construction of outbound transactions.
 *
 * <p>Every transaction the daemon builds starts here so one rule cannot drift between call sites:
 * its {@code nVersion} is the coin's own {@code TxVersion} from the blockchain-configuration-files
 * repository, never bitcoinj's default. BCF defines that key as the version found in the chain's
 * raw transactions, so the daemon has no basis for choosing a different one.
 */
public final class CoinTransactions {

    private CoinTransactions() {}

    /**
     * A new, empty transaction carrying the coin's configured version.
     *
     * @param params the coin's network parameters
     * @param ticker the coin, used to look up its BCF {@code TxVersion}
     * @return a transaction ready for inputs and outputs
     * @throws IllegalStateException if the coin has no loaded configuration or its configuration
     *     does not carry {@code TxVersion}
     */
    public static Transaction newTransaction(NetworkParameters params, CoinTicker ticker) {
        Transaction tx = new Transaction(params);
        tx.setVersion(CoinConfigRegistry.get(CoinTickerUtils.tickerToString(ticker)).txVersion());
        return tx;
    }

    /**
     * The empty transaction a signature is attached to.
     *
     * <p>Signing must not rewrite the caller's transaction: the version was fixed when it was built
     * from BCF, and the locktime is part of what was asked for. Inputs and outputs are added by the
     * caller; only the fields that describe the transaction itself are carried over here.
     *
     * @param params the coin's network parameters
     * @param unsigned the transaction being signed
     * @return a transaction with the same version and locktime as {@code unsigned}
     */
    public static Transaction forSigning(NetworkParameters params, Transaction unsigned) {
        Transaction tx = new Transaction(params);
        tx.setVersion((int) unsigned.getVersion());
        tx.setLockTime(unsigned.getLockTime());
        return tx;
    }
}
