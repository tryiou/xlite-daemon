package io.xlite.daemon.app.net.protocols.digibyte;

import io.xlite.daemon.app.net.HasFeeParams;
import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.MonetaryFormat;

public class DigibyteNetworkParametersLegacy extends NetworkParameters implements HasFeeParams {

    public DigibyteNetworkParametersLegacy() {
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
        return Coin.valueOf(2000000000 * Coin.COIN.value);
    }

    @Override
    public Coin getMinNonDustOutput() {
        return Coin.valueOf(1000);
    }

    @Override
    public MonetaryFormat getMonetaryFormat() {
        return new MonetaryFormat().code(0, "DGB");
    }

    @Override
    public String getUriScheme() {
        return "digibyte:";
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
        return 70002;
    }

    @Override
    public int getAddressHeader() {
        return 30;
    }

    @Override
    public int getP2SHHeader() {
        return 5;
    }

    @Override
    public int getDumpedPrivateKeyHeader() {
        return 128;
    }


    @Override
    public int getSubsidyDecreaseBlockCount() {
        return 100000;
    }

    @Override
    public int getInterval() {
        return 108;
    }

    @Override
    public String getId() {
        return "DGB";
    }

    public long getFeePerByte() {
        return 200;
    }

    public long getMinTxFee() {
        return 100000;
    }
}
