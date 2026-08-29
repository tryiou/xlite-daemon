package io.xlite.daemon.app.net.protocols.unobtanium;

import io.xlite.daemon.app.net.HasFeeParams;
import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.MonetaryFormat;

public class UnobtaniumNetworkParametersLegacy extends NetworkParameters implements HasFeeParams {

    public UnobtaniumNetworkParametersLegacy() {
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
        return Coin.valueOf(250000 * Coin.COIN.value);
    }

    @Override
    public Coin getMinNonDustOutput() {
        return Transaction.MIN_NONDUST_OUTPUT;
    }

    @Override
    public MonetaryFormat getMonetaryFormat() {
        return new MonetaryFormat().code(0, "UNO");
    }

    @Override
    public String getUriScheme() {
        return "unobtanium:";
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
    public int getProtocolVersionNum(final ProtocolVersion version) {
        return 70002;
    }

    @Override
    public int getAddressHeader() {
        return 130;
    }

    @Override
    public int getP2SHHeader() {
        return 30;
    }

    @Override
    public int getDumpedPrivateKeyHeader() {
        return 224;
    }


    @Override
    public int getSubsidyDecreaseBlockCount() {
        return 100000; // Adjusted for UNO
    }

    @Override
    public int getInterval() {
        return 60;
    }

    @Override
    public String getId() {
        return "UNO";
    }

    public long getFeePerByte() {
        return 3;
    }

    public long getMinTxFee() {
        return 1000;
    }
}