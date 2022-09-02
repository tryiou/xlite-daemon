package io.cloudchains.app.util;

import io.cloudchains.app.net.CoinInstance;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.utils.BtcFormat;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class CloudTransaction {
    private AtomicReference<String> txType;
    private AtomicReference<String> address;
    private AtomicReference<String> txHash;
    private AtomicReference<String> amount;
    private int height;

    public CloudTransaction(Object cloudTransaction) {
        BtcFormat f = BtcFormat.getInstance(BtcFormat.COIN_SCALE);

        if (cloudTransaction instanceof UTXO) {
            this.txType = new AtomicReference<>("Receive To: ");

            this.address = new AtomicReference<>(((UTXO) cloudTransaction).getAddress());
            this.txHash = new AtomicReference<>(((UTXO) cloudTransaction).getTxid());
            this.amount = new AtomicReference<>(f.format(((UTXO) cloudTransaction).getValue()));
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

            this.amount = new AtomicReference<>(f.format(((Transaction) cloudTransaction).getOutput(1).getValue().value));
            this.height = 0;
        }
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
