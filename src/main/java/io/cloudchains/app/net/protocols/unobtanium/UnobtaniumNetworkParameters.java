package io.cloudchains.app.net.protocols.unobtanium;

import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.MonetaryFormat;

public class UnobtaniumNetworkParameters extends NetworkParameters {

    public UnobtaniumNetworkParameters() {
        super();
    }

	@Override
	public String getPaymentProtocolId() {
		return "main";
	}

	@Override
	public void checkDifficultyTransitions(StoredBlock storedPrev, Block next, BlockStore blockStore) throws VerificationException, BlockStoreException {}


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
    public int[] getAcceptableAddressCodes() {
        return new int[]{getAddressHeader(), getP2SHHeader()};
    }

    @Override
    public int getBip32HeaderPriv() {
        return 0x0488ADE4;
    }

    @Override
    public int getBip32HeaderPub() {
        return 0x0488B21E;
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
}