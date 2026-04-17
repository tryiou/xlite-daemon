package io.cloudchains.app.net;

public interface HasFeeParams {
    default long getFeePerByte() {
        throw new UnsupportedOperationException("getFeePerByte not implemented");
    }

    default long getMinTxFee() {
        throw new UnsupportedOperationException("getMinTxFee not implemented");
    }
}
