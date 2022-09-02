package io.cloudchains.app.net.protocols.blocknet;

import com.subgraph.orchid.encoders.Hex;
import io.cloudchains.app.net.xrouter.XRouterMessageSerializer;
import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.utils.MonetaryFormat;

import java.math.BigInteger;

public class BlocknetTestnet5NetworkParameters extends BlocknetParameters {

	public BlocknetTestnet5NetworkParameters() {
		super();
	}

	@Override
	public String getPaymentProtocolId() {
		return "test";
	}

	@Override
	public void checkDifficultyTransitions(StoredBlock storedPrev, Block next, BlockStore blockStore) throws VerificationException {

	}

	@Override
	public int[] getAcceptableAddressCodes() {
		return new int[] {getAddressHeader(), getP2SHHeader()};
	}

	@Override
	public Sha256Hash getGenesisBlockHash() {
		return Sha256Hash.wrap("0fd62ae4f74c7ee0c11ef60fc5a2e69a5c02eaee2e77b21c3db70934b5a5c8b9");
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
		return new MonetaryFormat().code(0, "tBLOCK");
	}

	@Override
	public String getUriScheme() {
		return "blockdx:";
	}

	@Override
	public boolean hasMaxMoney() {
		return true;
	}

	@Override
	public BlocknetSerializer getSerializer(boolean parseRetain) {
		return new BlocknetSerializer(this, parseRetain);
	}

	public XRouterMessageSerializer getXRouterMessageSerializer(boolean parseRetain) {
		return new XRouterMessageSerializer(parseRetain, this);
	}

	@Override
	public int getProtocolVersionNum(ProtocolVersion version) {
		return 70712;
	}

	@Override
	public int getSubsidyDecreaseBlockCount() {
		return 210000;
	}

	@Override
	public byte[] getAlertSigningKey() {
		return Hex.decode("000010e83b2703ccf322f7dbd62dd5855ac7c10bd055814ce121ba32607d573b8810c02c0582aed05b4deb9c4b77b26d92428c61256cd42774babea0a073b2ed0c9");
	}

	@Override
	public int getMajorityEnforceBlockUpgrade() {
		return 51;
	}

	@Override
	public int getMajorityRejectBlockOutdated() {
		return 75;
	}

	@Override
	public int getMajorityWindow() {
		return 100;
	}

	@Override
	public int getPort() {
		return 41474;
	}

	@Override
	public long getPacketMagic() {
		return 0x457665BAL;
	}

	@Override
	public int getInterval() {
		return 1;
	}

	@Override
	public int getTargetTimespan() {
		return 60;
	}

	@Override
	public int getAddressHeader() {
		return 139;
	}

	@Override
	public int getP2SHHeader() {
		return 19;
	}

	@Override
	public int getDumpedPrivateKeyHeader() {
		return 239;
	}

	@Override
	public int getBip32HeaderPub() {
		return 0x3A8061A0;
	}

	@Override
	public int getBip32HeaderPriv() {
		return 0x3A805837;
	}

	@Override
	public String[] getDnsSeeds() {
		return new String[] {
				"104.238.198.122",
		};
	}

	@Override
	public BigInteger getMaxTarget() {
		return Utils.decodeCompactBits(0x203FFFFF);
	}

	@Override
	public String getId() {
		return "tBLOCK";
	}
}
