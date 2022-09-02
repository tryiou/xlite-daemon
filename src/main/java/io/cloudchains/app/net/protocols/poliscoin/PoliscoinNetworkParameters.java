package io.cloudchains.app.net.protocols.poliscoin;

import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.MonetaryFormat;

public class PoliscoinNetworkParameters extends NetworkParameters {

	public PoliscoinNetworkParameters() {
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
		return Coin.valueOf(25000000 * Coin.COIN.value);
	}

	@Override
	public Coin getMinNonDustOutput() {
		return Transaction.MIN_NONDUST_OUTPUT;
	}

	@Override
	public MonetaryFormat getMonetaryFormat() {
		return new MonetaryFormat().code(0, "POLIS");
	}

	@Override
	public String getUriScheme() {
		return "polis:";
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
		return 70219;
	}

	@Override
	public int getAddressHeader() {
		return 55;
	}

	@Override
	public int getP2SHHeader() {
		return 56;
	}

	@Override
	public int getDumpedPrivateKeyHeader() {
		return 60;
	}

	@Override
	public int[] getAcceptableAddressCodes() {
		return new int[] {getAddressHeader(), getP2SHHeader()};
	}

	@Override
	public int getBip32HeaderPriv() {
		return 0x03E25945;
	}

	@Override
	public int getBip32HeaderPub() {
		return 0x03E25D7E;
	}

	@Override
	public int getSubsidyDecreaseBlockCount() {
		return 210240;
	}

	@Override
	public int getInterval() {
		return 120;
	}

	@Override
	public String getId() {
		return "DASH";
	}
}
