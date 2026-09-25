package io.xlite.daemon.app.coinconfig;

import io.xlite.daemon.app.net.HasFeeParams;
import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.MonetaryFormat;

import java.util.Locale;
import java.util.Objects;

/**
 * Network parameters for one coin, driven entirely by its
 * {@link CoinConfig} (blockchain-configuration-files data) plus the small
 * {@link CompiledCoinSupplement} constants bcf does not carry.
 *
 * <p>Replaces the per-coin hardcoded parameter classes. Behavior is
 * byte-compatible with those classes except where the loaded config
 * intentionally differs (e.g. DGB ScriptPrefix 63, RVN FeePerByte 3000).</p>
 *
 * <p>Deliberately preserved status quo: the segwit HRP stays unset — bech32
 * address validation keeps failing for these coins (segwit is unsupported);
 * difficulty/interval/subsidy overrides remain inert stubs (no BlockChain is
 * ever constructed in this daemon); monetary format and URI scheme are dead
 * stubs returning ticker-derived values; {@code getPort()} is likewise not
 * overridden — only BTC's legacy class carried a real p2p port (8333 via
 * MainNetParams); all other legacy classes left the same base default this
 * class returns. Safe because nothing in this daemon reads a migrated coin's
 * params-port: the local RPC port comes from {@code CoinInstance} and the
 * only params-port consumer ({@code BlocknetPeerGroup}) is BLOCK-only.</p>
 */
public final class ConfigurableNetworkParameters extends NetworkParameters implements HasFeeParams {

    private final CoinConfig config;
    private final CompiledCoinSupplement.Supplement supplement;
    private final Coin maxMoney;
    private final Coin minNonDustOutput;

    public static ConfigurableNetworkParameters from(CoinConfig config) {
        return new ConfigurableNetworkParameters(config,
                CompiledCoinSupplement.forTicker(config.getTicker()));
    }

    public ConfigurableNetworkParameters(CoinConfig config, CompiledCoinSupplement.Supplement supplement) {
        this.config = Objects.requireNonNull(config, "config");
        this.supplement = Objects.requireNonNull(supplement, "supplement");
        CoinConfigGate.validate(config);
        this.maxMoney = Coin.valueOf(Math.multiplyExact(supplement.maxMoneyCoins, Coin.COIN.value));
        this.minNonDustOutput = Coin.valueOf(supplement.dustSat);
    }

    public CoinConfig getConfig() {
        return config;
    }

    /**
     * True once OUR constructor body has run. The NetworkParameters base
     * constructor calls several of these getters virtually before that, so
     * every accessor degrades to a neutral value until then.
     */
    private boolean ready() {
        return config != null && supplement != null;
    }

    @Override
    public String getPaymentProtocolId() {
        return ready() ? supplement.chainName : "main";
    }

    @Override
    public void checkDifficultyTransitions(StoredBlock storedPrev, Block next, BlockStore blockStore)
            throws VerificationException, BlockStoreException {
        // no-op: this daemon never verifies block headers (EXR HTTP client)
    }

    @Override
    public Coin getMaxMoney() {
        return ready() ? maxMoney : NetworkParameters.MAX_MONEY;
    }

    @Override
    public Coin getMinNonDustOutput() {
        return ready() ? minNonDustOutput : Coin.ZERO;
    }

    @Override
    public MonetaryFormat getMonetaryFormat() {
        return ready() ? new MonetaryFormat().code(0, config.getTicker()) : new MonetaryFormat();
    }

    @Override
    public String getUriScheme() {
        return ready() ? config.getTicker().toLowerCase(Locale.ROOT) + ":" : "";
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
        return ready() ? supplement.protocolVersion
                : ProtocolVersion.CURRENT.getBitcoinProtocolVersion();
    }

    @Override
    public int getAddressHeader() {
        return ready() ? config.addressPrefix() : 0;
    }

    @Override
    public int getP2SHHeader() {
        return ready() ? config.scriptPrefix() : 0;
    }

    @Override
    public int getDumpedPrivateKeyHeader() {
        return ready() ? config.secretPrefix() : 0;
    }

    @Override
    public int getInterval() {
        return 210_000; // inert: no BlockChain exists in this daemon
    }

    @Override
    public int getSubsidyDecreaseBlockCount() {
        return 210_000; // inert: see getInterval()
    }

    @Override
    public String getId() {
        // must be non-null and != ID_UNITTESTNET (wallet creation checks it)
        return ready() ? supplement.id : "";
    }

    @Override
    public long getFeePerByte() {
        return ready() ? config.feePerByte() : 1L;
    }

    @Override
    public long getMinTxFee() {
        return ready() ? config.minTxFee() : 1000L;
    }
}
