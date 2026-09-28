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
 * <p>Replaced the per-coin hardcoded parameter classes, which have since been
 * deleted; their values are now split between the configuration repository and
 * {@link CompiledCoinSupplement}. Where a loaded config differs from what
 * those classes hardcoded, the config wins (e.g. DGB ScriptPrefix 63, RVN
 * FeePerByte 3000).</p>
 *
 * <p>Deliberately preserved status quo: the segwit HRP stays unset — bech32
 * address validation keeps failing for these coins (segwit is unsupported);
 * difficulty/interval/subsidy overrides remain inert stubs (no BlockChain is
 * ever constructed in this daemon); monetary format and URI scheme are dead
 * stubs returning ticker-derived values; {@code getPort()} is likewise not
 * overridden, so it returns bitcoinj's base default rather than any coin's p2p
 * port. That is not safe by construction — it is safe because of who asks:
 * the local RPC port comes from {@code CoinInstance}, and the one consumer of
 * a coin's params-port ({@code BlocknetPeerGroup}) is handed the separate
 * Blocknet p2p parameter object, not this one. A second caller asking this
 * class for a port would get the base default, so overriding it is a change
 * to make deliberately, with that caller in mind.</p>
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
        // The multiplier is the coin's own configured factor, so the product is
        // only as representable as the configuration is sane. A factor large
        // enough to overflow is a bad configuration, and is reported as one:
        // IllegalStateException is what the coin-loading path already catches
        // and turns into a coin error, so this way it cannot escape as an
        // unchecked arithmetic failure from init().
        try {
            this.maxMoney = Coin.valueOf(
                    Math.multiplyExact(supplement.maxMoneyCoins, config.coinFactor()));
        } catch (ArithmeticException e) {
            throw new IllegalStateException("[" + config.getTicker()
                    + "] COIN is too large to express the coin's supply in base units: COIN="
                    + config.coinFactor(), e);
        }
        // The dust floor is the configuration's value and nothing else. It used
        // to come from a per-coin constant compiled into this repository while
        // the configuration carried its own and went unread: Litecoin's stale
        // constant was 100000 satoshis, eighteen times every other coin's, so a
        // smaller change was dropped and charged as fee instead of returned.
        // There is deliberately no fallback constant here — a value invented on
        // this side would be a second source of truth wearing the first one's
        // clothes, which is the bug being removed. An absent value means no
        // floor, and the configuration repository is where a real per-coin dust
        // threshold belongs.
        Long configuredDust = config.dustAmountOrNull();
        try {
            this.minNonDustOutput = Coin.valueOf(configuredDust == null ? 0L : configuredDust);
        } catch (ArithmeticException e) {
            // Same reasoning as the ceiling above: this value arrives from a
            // fetch, so one too large to represent is a bad configuration and
            // is reported as one rather than thrown as raw arithmetic.
            throw new IllegalStateException("[" + config.getTicker()
                    + "] DustAmount is too large to represent in base units: DustAmount="
                    + configuredDust, e);
        }
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
