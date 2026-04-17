package io.cloudchains.app.net.protocols.bitcoin;

import io.cloudchains.app.net.HasFeeParams;
import org.bitcoinj.params.MainNetParams;

public class BitcoinNetworkParameters extends MainNetParams implements HasFeeParams {

    public BitcoinNetworkParameters() {
        super();
    }

    public long getFeePerByte() {
        return 60;
    }

    public long getMinTxFee() {
        return 12000;
    }
}