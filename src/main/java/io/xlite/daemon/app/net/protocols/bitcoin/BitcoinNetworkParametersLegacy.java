package io.xlite.daemon.app.net.protocols.bitcoin;

import io.xlite.daemon.app.net.HasFeeParams;
import org.bitcoinj.params.MainNetParams;

public class BitcoinNetworkParametersLegacy extends MainNetParams implements HasFeeParams {

    public BitcoinNetworkParametersLegacy() {
        super();
    }

    public long getFeePerByte() {
        return 60;
    }

    public long getMinTxFee() {
        return 12000;
    }
}