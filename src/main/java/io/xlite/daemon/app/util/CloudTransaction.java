package io.xlite.daemon.app.util;

import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTickerUtils;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class CloudTransaction {
    private AtomicReference<String> txType;
    private AtomicReference<String> address;
    private AtomicReference<String> txHash;
    private AtomicReference<String> amount;
    private int height;

    public CloudTransaction(Object cloudTransaction) {
        if (cloudTransaction instanceof UTXO) {
            this.txType = new AtomicReference<>("Receive To: ");

            this.address = new AtomicReference<>(((UTXO) cloudTransaction).getAddress());
            this.txHash = new AtomicReference<>(((UTXO) cloudTransaction).getTxid());
            this.amount = new AtomicReference<>(formatForTicker(
                    ((UTXO) cloudTransaction).getValue(),
                    CoinTickerUtils.tickerToString(((UTXO) cloudTransaction).ticker)));
            this.height = ((UTXO) cloudTransaction).getHeight();
        } else if (cloudTransaction instanceof Transaction) {
            NetworkParameters networkParameters = CoinInstance.getActiveCurrency().getNetworkParameters();
            this.txType = new AtomicReference<>("Sent To: ");

            if (((Transaction) cloudTransaction).getOutputs().size() > 1 && !Objects.requireNonNull(((Transaction) cloudTransaction).getOutput(1).getAddressFromP2PKHScript(networkParameters)).isP2SHAddress()) {
                this.address = new AtomicReference<>(Objects.requireNonNull(((Transaction) cloudTransaction).getOutput(1).getAddressFromP2PKHScript(networkParameters)).toString());
            } else {
                this.address = new AtomicReference<>(Objects.requireNonNull(((Transaction) cloudTransaction).getOutput(1).getAddressFromP2SH(networkParameters)).toString());
            }

            this.txHash = new AtomicReference<>(((Transaction) cloudTransaction).getHashAsString());

            this.amount = new AtomicReference<>(formatForTicker(
                    ((Transaction) cloudTransaction).getOutput(1).getValue().value,
                    CoinTickerUtils.tickerToString(CoinInstance.getActiveCurrency().getTicker())));
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
        return Sats.toWholeCoinsString(baseUnits,
                CoinConfigRegistry.get(ticker).coinFactor());
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
