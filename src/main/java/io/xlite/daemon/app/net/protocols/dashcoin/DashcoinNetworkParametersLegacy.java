package io.xlite.daemon.app.net.protocols.dashcoin;

import io.xlite.daemon.app.net.HasFeeParams;
import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.MonetaryFormat;

public class DashcoinNetworkParametersLegacy extends NetworkParameters implements HasFeeParams {

    public DashcoinNetworkParametersLegacy() {
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
        return Coin.valueOf(22000000 * Coin.COIN.value);
    }

    @Override
    public Coin getMinNonDustOutput() {
        return Coin.valueOf(5460);
    }

    @Override
    public MonetaryFormat getMonetaryFormat() {
        return new MonetaryFormat().code(0, "DASH");
    }

    @Override
    public String getUriScheme() {
        return "dashcoin:";
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
        return 70210;
    }

    @Override
    public int getAddressHeader() {
        return 76;
    }

    @Override
    public int getP2SHHeader() {
        return 16;
    }

    @Override
    public int getDumpedPrivateKeyHeader() {
        return 204;
    }


    @Override
    public int getSubsidyDecreaseBlockCount() {
        return 210240;
    }

    @Override
    public int getInterval() {
        return 57;
    }

    @Override
    public String getId() {
        return "DASH";
    }

    public long getFeePerByte() {
        return 5;
    }

    public long getMinTxFee() {
        return 2500;
    }
}
