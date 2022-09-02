package io.cloudchains.app.net.protocols.blocknet;

import com.subgraph.orchid.encoders.Hex;
import io.cloudchains.app.net.xrouter.XRouterMessageSerializer;
import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.MonetaryFormat;

import java.math.BigInteger;

public class BlocknetNetworkParameters extends BlocknetParameters {

	public BlocknetNetworkParameters() {
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
	public int[] getAcceptableAddressCodes() {
		return new int[] {getAddressHeader(), getP2SHHeader()};
	}

	@Override
	public Sha256Hash getGenesisBlockHash() {
		return Sha256Hash.wrap("00000eb7919102da5a07dc90905651664e6ebf0811c28f06573b9a0fd84ab7b8");
	}

	@Override
	public Coin getMaxMoney() {
		return Coin.valueOf(43199500).times(Coin.COIN.value);
	}

	@Override
	public Coin getMinNonDustOutput() {
		return Coin.valueOf(5500);
	}

	@Override
	public MonetaryFormat getMonetaryFormat() {
		return new MonetaryFormat().code(0, "BLOCK");
	}

	@Override
	public String getUriScheme() {
		return "blocknetdx:";
	}

	@Override
	public boolean hasMaxMoney() {
		return true;
	}

	@Override
	public BlocknetSerializer getSerializer(boolean parseRetain) {
		return new BlocknetSerializer(this, parseRetain);
	}

	@Override
	public int getProtocolVersionNum(ProtocolVersion version) {
		return 70712;
	}

	/*@Override
	public Block getGenesisBlock() {
		return genesisBlock;
	}*/

	@Override
	public int getSubsidyDecreaseBlockCount() {
		return 210000;
	}

	@Override
	public byte[] getAlertSigningKey() {
		return Hex.decode("0415758705177c87c35dadf7ebf66e93ecc2710253bbac955e695664011fa39ff29a84fa21ae9e203a43debb487170c143ab6eaffe4fa3b12e162d8a6d4da87395");
	}

	@Override
	public int getMajorityEnforceBlockUpgrade() {
		return 750;
	}

	@Override
	public int getMajorityRejectBlockOutdated() {
		return 950;
	}

	@Override
	public int getMajorityWindow() {
		return 1000;
	}

	@Override
	public int getPort() {
		return 41412;
	}

	@Override
	public long getPacketMagic() {
		return 0xA1A0A2A3;
	}

	@Override
	public String[] getDnsSeeds() {
		int nodeCount = 5;

		String[] dnsSeeds = new String[nodeCount];

		for (int i = 0; i < nodeCount; i++)
			dnsSeeds[i] = "node-" + i + ".cloudchainsinc.com";

		return dnsSeeds;
	}

	@Override
	public int getInterval() {
		return 1;
	}

	@Override
	public int getAddressHeader() {
		return 26;
	}

	@Override
	public int getP2SHHeader() {
		return 28;
	}

	@Override
	public int getDumpedPrivateKeyHeader() {
		return 154;
	}

	@Override
	public int getBip32HeaderPub() {
		return 0x0488B21E;
	}

	@Override
	public int getBip32HeaderPriv() {
		return 0x0488ADE4;
	}

	@Override
	public BigInteger getMaxTarget() {
		return Utils.decodeCompactBits(0x1E0FFFFF);
	}

	@Override
	public int getTargetTimespan() {
		return 60;
	}

	@Override
	public String getId() {
		return "BLOCK";
	}

	@Override
	public XRouterMessageSerializer getXRouterMessageSerializer(boolean parseRetain) {
		return new XRouterMessageSerializer(parseRetain, this);
	}

}
