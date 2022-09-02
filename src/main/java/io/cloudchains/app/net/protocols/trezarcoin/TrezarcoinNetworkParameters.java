package io.cloudchains.app.net.protocols.trezarcoin;

import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.MonetaryFormat;

public class TrezarcoinNetworkParameters extends NetworkParameters {

	public TrezarcoinNetworkParameters() {
		super();
	}

	@Override
	public int[] getAcceptableAddressCodes() {
		return new int[] {getAddressHeader(), getP2SHHeader()};
	}

	@Override
	public String getPaymentProtocolId() {
		return "main";
	}

	@Override
	public void checkDifficultyTransitions(StoredBlock storedPrev, Block next, BlockStore blockStore) throws VerificationException, BlockStoreException {}

	@Override
	public Coin getMaxMoney() {
		return Coin.valueOf(888000000 * Coin.COIN.value);
	}

	@Override
	public Coin getMinNonDustOutput() {
		return Coin.valueOf(5500);
	}

	@Override
	public MonetaryFormat getMonetaryFormat() {
		return new MonetaryFormat().code(0, "TZC");
	}

	@Override
	public String getUriScheme() {
		return "trezarcoin:";
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
		return 70000;
	}

	@Override
	public int getAddressHeader() {
		return 66;
	}

	@Override
	public int getP2SHHeader() {
		return 8;
	}

	@Override
	public int getDumpedPrivateKeyHeader() {
		return 194;
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
		return 1600000;
	}

	@Override
	public int getInterval() {
		return 600;
	}

	@Override
	public String getId() {
		return "TZC";
	}
}
