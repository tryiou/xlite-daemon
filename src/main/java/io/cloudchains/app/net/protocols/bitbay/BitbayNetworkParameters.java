package io.cloudchains.app.net.protocols.bitbay;

import io.cloudchains.app.net.HasFeeParams;
import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.MonetaryFormat;

public class BitbayNetworkParameters extends NetworkParameters implements HasFeeParams {

    public BitbayNetworkParameters() {
        super();
    }

    @Override
    public String getPaymentProtocolId() {
        return "main";
    }

    @Override
    public void checkDifficultyTransitions(StoredBlock storedPrev, Block next, BlockStore blockStore) throws VerificationException, BlockStoreException {
    }

    @Override
    public Coin getMaxMoney() {
        return Coin.valueOf(100000000 * Coin.COIN.value);
    }

    @Override
    public Coin getMinNonDustOutput() {
        return Transaction.MIN_NONDUST_OUTPUT;
    }

    @Override
    public MonetaryFormat getMonetaryFormat() {
        return new MonetaryFormat().code(0, "BAY");
    }

    @Override
    public String getUriScheme() {
        return "bitbay:";
    }

    @Override
    public boolean hasMaxMoney() {
        return true;
    }

    @Override
    public BitcoinSerializer getSerializer(boolean parseRetain) {
        return new BitcoinSerializer(this, parseRetain);
    }

    @Override
    public int getProtocolVersionNum(ProtocolVersion version) {
        return 70719;
    }

    @Override
    public int getAddressHeader() {
        return 25;
    }

    @Override
    public int getP2SHHeader() {
        return 85;
    }

    @Override
    public int getDumpedPrivateKeyHeader() {
        return 153;
    }


    @Override
    public int getBip32HeaderP2PKHpriv() {
        return 0x0488ADE4;
    }

    @Override
    public int getBip32HeaderP2PKHpub() {
        return 0x0488B21E;
    }

    @Override
    public int getSubsidyDecreaseBlockCount() {
        return 210240;
    }

    @Override
    public int getInterval() {
        return 60;
    }

    @Override
    public String getId() {
        return "BAY";
    }

    public long getFeePerByte() {
        return 100;
    }

    public long getMinTxFee() {
        return 20000;
    }
}
