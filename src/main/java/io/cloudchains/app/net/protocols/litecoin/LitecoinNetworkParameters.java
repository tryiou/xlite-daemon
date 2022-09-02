package io.cloudchains.app.net.protocols.litecoin;

import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.MonetaryFormat;

public class LitecoinNetworkParameters extends NetworkParameters {

	public LitecoinNetworkParameters() {
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
		return Coin.valueOf(84000000 * Coin.COIN.value);
	}

	@Override
	public Coin getMinNonDustOutput() {
		return Coin.valueOf(100000);
	}

	@Override
	public MonetaryFormat getMonetaryFormat() {
		return new MonetaryFormat().code(0, "LTC");
	}

	@Override
	public String getUriScheme() {
		return "litecoin:";
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
		return 70015;
	}

	@Override
	public int getAddressHeader() {
		return 48;
	}

	@Override
	public int getP2SHHeader() {
		return 50;
	}

	public int getP2SHLegacyHeader() {
		return 5;
	}

	@Override
	public int getDumpedPrivateKeyHeader() {
		return 176;
	}

	@Override
	public int[] getAcceptableAddressCodes() {
		return new int[] {getAddressHeader(), getP2SHHeader(), getP2SHLegacyHeader()};
	}

	@Override
	public int getBip32HeaderPriv() {
		return 0x0488B21E;
	}

	@Override
	public int getBip32HeaderPub() {
		return 0x0488ADE4;
	}

	@Override
	public int getSubsidyDecreaseBlockCount() {
		return 840000;
	}

	@Override
	public int getInterval() {
		return 2016;
	}

	@Override
	public String getId() {
		return "LTC";
	}
}
