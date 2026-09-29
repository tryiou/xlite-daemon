package io.xlite.daemon.app.net.protocols.blocknet;

import com.subgraph.orchid.encoders.Hex;
import io.xlite.daemon.app.net.xrouter.XRouterMessageSerializer;
import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.MonetaryFormat;

import java.math.BigInteger;

/**
 * The XRouter-over-p2p parameters for Blocknet.
 *
 * <p>This is NOT a second copy of the coin's network parameters. Blocknet's
 * prefixes, dust floor, supply ceiling and monetary format come from
 * {@link io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters}, which
 * is the single place the coin's configuration is read and converted. The
 * supply figure, dust floor, network id and protocol version are consensus-era
 * constants the configuration repository does not carry: the compiled
 * supplement owns them, and this class reads that one table. No getter here
 * recomputes a value another class also computes — the prefixes, dust floor,
 * supply ceiling and monetary format are all delegated — so a second copy of a
 * coin's value cannot drift from the first.
 *
 * <p>What is genuinely here, and not expressible in a configuration file, is
 * the p2p surface: DNS seeds, the p2p port, packet magic, the genesis hash and
 * the message serializers.
 *
 * <p>This object no longer carries the coin's fee rate or minimum fee. It used
 * to implement the fee accessor interface with its own copies of them, and that
 * is how those copies drifted from what the daemon actually charged. The fees
 * now live only on the config-driven parameter set the money and address paths
 * use, so asking this object for a fee is no longer possible by design: a fee
 * read here would be a read of a second, unowned number.
 */
public class BlocknetNetworkParameters extends BlocknetParameters {

    /**
     * Resolved on first use and cached. Building it validates the whole
     * configuration, and the getters below read more than one header per
     * operation, so rebuilding per call would revalidate the configuration on
     * every use.
     *
     * <p>This object is a per-coin adapter, so the coin's configuration must
     * already be loaded when it is constructed. That is not only for the
     * address prefixes: the base class asks for the supply ceiling while it is
     * still running its own constructor, so the very first thing this class is
     * asked for may be a delegating getter. With no configuration loaded that
     * call throws, which is the honest outcome for a coin that was never
     * configured.
     *
     * <p>The cache lives as long as this object, not as long as the process: the
     * coin builds a new instance on every init, so a registry reloaded between
     * two inits is picked up by the next one. What is not picked up is a reload
     * that happens after a given instance has already resolved — that instance
     * keeps serving what it resolved. The field is left uninitialised on
     * purpose: the cache is populated during the superclass call described
     * above, and an explicit {@code = null} initialiser would run afterwards and
     * throw it away, so the whole configuration would be revalidated on every
     * call instead of once.
     *
     * <p>That makes the requirement wider than construction. Every delegating
     * getter here — and every bitcoinj operation that reaches one, address
     * derivation and serializer use among them — throws
     * {@link IllegalStateException} if the coin's configuration was never
     * loaded, not just the constructor. For a coin that was never configured
     * that is the intended failure, but a caller that reaches one of these
     * getters without a loaded configuration is a bug in that caller, not a
     * coin that can be started on guesswork. is a bug in that caller, not a
     * coin that can be started on guesswork.
     */
    private io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters coin;

    public BlocknetNetworkParameters() {
        super();
    }

    // ---------------------------------------------------------------------
    // The coin's parameters, delegated rather than duplicated
    //
    // The five consensus values the configuration repository does not carry
    // come from the compiled supplement, which is their one home. Reading them
    // from a static table rather than copying them here is what stops this
    // object from drifting from the values the daemon actually uses.
    // ---------------------------------------------------------------------

    /** The coin's ticker, resolved the same way every other lookup resolves it. */
    private static String ticker() {
        return io.xlite.daemon.app.net.CoinTickerUtils
                .tickerToString(io.xlite.daemon.app.net.CoinTicker.BLOCKNET);
    }

    private static io.xlite.daemon.app.coinconfig.CompiledCoinSupplement.Supplement supplement() {
        return io.xlite.daemon.app.coinconfig.CompiledCoinSupplement.forTicker(ticker());
    }

    @Override
    public String getPaymentProtocolId() {
        return supplement().chainName;
    }

    @Override
    public Coin getMaxMoney() {
        // Delegated rather than recomputed. The conversion from the supply in
        // coins to the ceiling in base units is where the coin's own factor
        // matters, and a second copy of that expression is free to hardcode a
        // divisor the coin does not use: it reads 1e8, which happens to be
        // right for Blocknet and wrong by two orders of magnitude for a coin
        // with fewer decimals, and a comparison against the config-driven
        // object cannot catch that when both sides hardcode the same constant.
        return coin().getMaxMoney();
    }

    @Override
    public Coin getMinNonDustOutput() {
        // Delegated for the same reason as the ceiling above: reading the
        // supplement here as well would be a second copy of a conversion the
        // config-driven object already performs, and the class comment claims
        // there is no such copy.
        return coin().getMinNonDustOutput();
    }

    @Override
    public MonetaryFormat getMonetaryFormat() {
        // Delegated: building this from the supplement id was a second copy of
        // the same derivation the config-driven object performs, and the class
        // comment above claims there is no such copy.
        return coin().getMonetaryFormat();
    }

    @Override
    public int getProtocolVersionNum(ProtocolVersion version) {
        return supplement().protocolVersion;
    }

    @Override
    public String getId() {
        return supplement().id;
    }

    /**
     * The address prefixes come from the coin's configuration file, not from
     * here. Asking this object for them means asking the XRouter p2p surface
     * for the coin's address format, which is why it needs the configuration to
     * be loaded: encoding a Blocknet address is the coin's business, and the
     * coin's parameters are the right object to do it with.
     */
    private synchronized io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters coin() {
        if (coin == null) {
            coin = io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters
                    .from(io.xlite.daemon.app.coinconfig.CoinConfigRegistry.get(ticker()));
        }
        return coin;
    }

    @Override
    public int getAddressHeader() {
        return coin().getAddressHeader();
    }

    @Override
    public int getP2SHHeader() {
        return coin().getP2SHHeader();
    }

    @Override
    public int getDumpedPrivateKeyHeader() {
        return coin().getDumpedPrivateKeyHeader();
    }

    // ---------------------------------------------------------------------
    // The p2p surface
    // ---------------------------------------------------------------------

    @Override
    public void checkDifficultyTransitions(StoredBlock storedPrev, Block next, BlockStore blockStore) throws VerificationException, BlockStoreException {

    }


    @Override
    public Sha256Hash getGenesisBlockHash() {
        return Sha256Hash.wrap("00000eb7919102da5a07dc90905651664e6ebf0811c28f06573b9a0fd84ab7b8");
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
    public BigInteger getMaxTarget() {
        return Utils.decodeCompactBits(0x1E0FFFFF);
    }

    @Override
    public int getTargetTimespan() {
        return 60;
    }

    @Override
    public XRouterMessageSerializer getXRouterMessageSerializer(boolean parseRetain) {
        return new XRouterMessageSerializer(parseRetain, this);
    }

}
