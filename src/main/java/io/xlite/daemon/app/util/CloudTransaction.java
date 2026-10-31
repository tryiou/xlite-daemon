package io.xlite.daemon.app.util;

import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTickerUtils;

import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptPattern;

import java.util.concurrent.atomic.AtomicReference;

public class CloudTransaction {
    private AtomicReference<String> txType;
    private AtomicReference<String> address;
    private AtomicReference<String> txHash;
    private AtomicReference<String> amount;
    private int height;

    private static boolean isP2SHOutput(Script scriptPubKey) {
        return ScriptPattern.isP2SH(scriptPubKey);
    }

    public CloudTransaction(Object cloudTransaction) {
        if (cloudTransaction instanceof UTXO utxo) {
            this.txType = new AtomicReference<>("Receive To: ");

            this.address = new AtomicReference<>(utxo.getAddress());
            this.txHash = new AtomicReference<>(utxo.getTxid());
            this.amount =
                    new AtomicReference<>(
                            formatForTicker(
                                    utxo.getValue(), CoinTickerUtils.tickerToString(utxo.ticker)));
            this.height = utxo.getHeight();
        } else if (cloudTransaction instanceof Transaction tx) {
            NetworkParameters networkParameters =
                    CoinInstance.getActiveCurrency().getNetworkParameters();
            this.txType = new AtomicReference<>("Sent To: ");

            if (tx.getOutputs().size() > 1 && !isP2SHOutput(tx.getOutput(1).getScriptPubKey())) {
                this.address =
                        new AtomicReference<>(
                                LegacyAddress.fromPubKeyHash(
                                                networkParameters,
                                                ScriptPattern.extractHashFromP2PKH(
                                                        tx.getOutput(1).getScriptPubKey()))
                                        .toString());
            } else {
                this.address =
                        new AtomicReference<>(
                                LegacyAddress.fromP2SHHash(
                                                networkParameters,
                                                ScriptPattern.extractHashFromP2SH(
                                                        tx.getOutput(1).getScriptPubKey()))
                                        .toString());
            }

            this.txHash = new AtomicReference<>(tx.getTxId().toString());

            this.amount =
                    new AtomicReference<>(
                            formatForTicker(
                                    tx.getOutput(1).getValue().value,
                                    CoinTickerUtils.tickerToString(
                                            CoinInstance.getActiveCurrency().getTicker())));
            this.height = 0;
        }
    }

    // Package-private for tests: the two-decimal truncation this replaced
    // lived here, so the new rendering is pinned against the real code.
    static String formatForTicker(long baseUnits, String ticker) {
        // Exact decimal division: BCF COIN factors are powers of ten, so
        // this always terminates.
        //
        // Replacing BtcFormat.COIN_SCALE, which resolves to a fixed two-decimal
        // format and so silently truncated every amount: 100000 base units
        // rendered as "0.00", 123456789 as "1,23" -- and used the locale's
        // decimal mark. Nothing a user of a 6-decimal coin could reconcile.
        return Sats.toWholeCoinsString(baseUnits, CoinConfigRegistry.get(ticker).coinFactor());
    }

    public String getTxType() {
        return txType.get();
    }

    public String getAddress() {
        return address.get();
    }

    public String getTxHash() {
        return txHash.get();
    }

    public int getHeight() {
        return height;
    }

    public double getAmount() {
        return Double.parseDouble(amount.get());
    }

    public AtomicReference<String> txTypeProperty() {
        return txType;
    }

    public AtomicReference<String> addressProperty() {
        return address;
    }

    public AtomicReference<String> txHashProperty() {
        return txHash;
    }

    public AtomicReference<String> amountProperty() {
        return amount;
    }
}
