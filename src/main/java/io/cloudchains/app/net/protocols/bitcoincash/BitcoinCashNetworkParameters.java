package io.cloudchains.app.net.protocols.bitcoincash;

import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.MonetaryFormat;

public class BitcoinCashNetworkParameters extends NetworkParameters {

	public BitcoinCashNetworkParameters() {
		super();
	}

	@Override
	public String getPaymentProtocolId() {
		return PAYMENT_PROTOCOL_ID_MAINNET;
	}

	@Override
	public void checkDifficultyTransitions(StoredBlock storedPrev, Block next, BlockStore blockStore) throws VerificationException, BlockStoreException {}

	@Override
	public Coin getMaxMoney() {
		return MAX_MONEY;
	}

	@Override
	public Coin getMinNonDustOutput() {
		return Transaction.MIN_NONDUST_OUTPUT;
	}

	@Override
	public MonetaryFormat getMonetaryFormat() {
		return new MonetaryFormat().code(0, "BCH");
	}

	@Override
	public String getUriScheme() {
		return "bch:";
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
		return version.getBitcoinProtocolVersion();
	}

	@Override
	public int getAddressHeader() {
		return 0;
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
	public int[] getAcceptableAddressCodes() {
		return new int[] {getAddressHeader(), getP2SHHeader()};
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
		return 210240;
	}

	@Override
	public int getInterval() {
		return INTERVAL;
	}

	@Override
	public String getId() {
		return "BCH";
	}
}
