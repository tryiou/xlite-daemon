import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.protocols.blocknet.BlocknetNetworkParameters;
import io.xlite.daemon.app.wallet.WalletHelper;
import org.bitcoinj.core.NetworkParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Blocknet is configured like every other coin.
 *
 * <p>It used to take a hardcoded parameter class, so its fees came from
 * constants in this source tree. Those constants had drifted away from what
 * the configuration repository carries for the same coin, so the daemon
 * charged Blocknet a different rate than the configuration specified. The
 * constants are gone; this pins the wiring that replaced them.
 *
 * <p>The fee values below are chosen by the test and deliberately unusual. The
 * point is not any particular rate — configuration values change routinely —
 * but that whatever the configuration says is what the coin reports. A
 * hardcoded parameter set cannot satisfy that.
 */
class BlocknetConfiguredParametersTest extends TestHelper {

    /**
     * Tears down every initialised coin and drops it from the cache. init()
     * starts an RPC listener and its threads, and removing an instance from the
     * cache makes it unreachable without stopping any of that — so both the
     * hook on the way in and the one on the way out have to deinit, or the last
     * test in the class leaves a bound port behind for the rest of the JVM.
     */
    private static void deinitAllCoins() {
        for (CoinInstance coin : CoinInstance.getCoinInstances()) {
            coin.deinit();
        }
        CoinInstance.getCoinInstances().clear();
    }

    @BeforeEach
    void releasePreviousCoin() {
        deinitAllCoins();
    }

    /**
     * Tears down every initialised coin and drops it from the cache, before each
     * test and after it.
     *
     * <p>init() starts an RPC listener and its threads, and removing an instance
     * from the cache makes it unreachable without stopping any of that, so both
     * hooks have to deinit: entering without it would re-initialise in place an
     * instance another class cached against its own configuration, and leaving
     * without it would keep the last test's bound port for the rest of the JVM.
     */    /**
     * The registry is process-global. Left loaded with only BLOCK it would
     * silently starve every later test class that reloads it, so it is dropped
     * here and rebuilt by whoever needs it.
     */
    @AfterEach
    void releaseRegistry() {
        CoinConfigRegistry.resetForTest();
        // The coin instances are process-wide singletons and this class re-inits
        // BLOCKNET with a synthetic configuration, so they are dropped here
        // rather than left for the next class to trip over. Other classes recover
        // on their own because they re-init from their own fixture; this makes
        // that a decision instead of a coincidence.
        deinitAllCoins();
    }

    /**
     * Arbitrary, and deliberately unlike any real Blocknet value: the prefixes
     * as much as the fees. If the p2p parameter class still carried its own
     * copies of the old constants, comparing it against this configuration
     * would pass or fail for the wrong reason.
     */
    private static final long FEE_PER_BYTE = 4242L;
    private static final long MIN_TX_FEE = 9091L;
    private static final int ADDRESS_PREFIX = 111;
    private static final int P2SH_PREFIX = 222;
    private static final int SECRET_PREFIX = 99;

    private static CoinConfig blockConfig() {
        return testCoinConfig("BLOCK", "Blocknet", ADDRESS_PREFIX, P2SH_PREFIX, SECRET_PREFIX,
                41414, FEE_PER_BYTE, MIN_TX_FEE);
    }

    private CoinInstance initializedBlocknet() {
        CoinConfigRegistry.loadForTest(Map.of("BLOCK", blockConfig()));
        CoinInstance blocknet = CoinInstance.getInstance(CoinTicker.BLOCKNET);
        assertNotNull(blocknet, "BLOCKNET instance");
        char[] pw = getPassword().toCharArray();
        try {
            // Asserted, not discarded: a coin that fails to initialise must
            // never be reported as healthy by a test that only inspects it.
            assertNull(blocknet.init(pw, getMnemonic(), false), "Blocknet must initialise");
        } finally {
            Arrays.fill(pw, '\0');
        }
        return blocknet;
    }

    @Test
    @DisplayName("Blocknet's fees are whatever its configuration says, not constants in code")
    void testBlocknetFeesFollowTheConfiguration() {
        NetworkParameters params = initializedBlocknet().getNetworkParameters();

        assertEquals(FEE_PER_BYTE, WalletHelper.getFeePerByte(params),
                "Blocknet's fee rate must come from its configuration");
        assertEquals(MIN_TX_FEE, WalletHelper.getMinTxFee(params),
                "Blocknet's minimum fee must come from its configuration");
    }

    @Test
    @DisplayName("Blocknet uses the same config-driven parameter class as every other coin")
    void testBlocknetSharesTheConfigDrivenClass() {
        NetworkParameters params = initializedBlocknet().getNetworkParameters();

        // One class serves every coin, so a fix to it reaches Blocknet too and
        // no per-coin class can drift out of step with the configuration.
        assertEquals(ConfigurableNetworkParameters.class, params.getClass());
        assertEquals(ADDRESS_PREFIX, params.getAddressHeader(), "address prefix from configuration");
        assertEquals(P2SH_PREFIX, params.getP2SHHeader(), "p2sh prefix from configuration");
    }

    @Test
    @DisplayName("the supply ceiling follows the configured coin factor, not an assumed 1e8")
    void testPeerSupplyCeilingFollowsTheConfiguredCoinFactor() {
        // Six decimals, as Verge and Emercoin actually use. Every other fixture
        // in the suite is 1e8, which is why a hardcoded 1e8 divisor can sit in
        // two places at once and still make them agree.
        final long SIX_DECIMALS = 1_000_000L;
        final long BLOCK_SUPPLY_IN_COINS = 43_199_500L;
        final long EXPECTED_CEILING = BLOCK_SUPPLY_IN_COINS * SIX_DECIMALS;

        CoinConfigRegistry.loadForTest(Map.of("BLOCK", testCoinConfig(
                "BLOCK", "Blocknet", ADDRESS_PREFIX, P2SH_PREFIX, SECRET_PREFIX,
                41414, FEE_PER_BYTE, MIN_TX_FEE, SIX_DECIMALS)));
        ConfigurableNetworkParameters fromConfig = ConfigurableNetworkParameters.from(
                CoinConfigRegistry.get("BLOCK"));
        BlocknetNetworkParameters peer =
                new BlocknetNetworkParameters();

        assertEquals(EXPECTED_CEILING, fromConfig.getMaxMoney().value,
                "the ceiling must be the supply expressed in the coin's own base units");
        assertEquals(fromConfig.getMaxMoney(), peer.getMaxMoney(),
                "the p2p object must report the ceiling, not a second copy of it");
    }

    @Test
    @DisplayName("an unrepresentable configuration is refused, not thrown out of init")
    void testUnrepresentableCoinFactorIsRefusedNotThrown() {
        // A power of ten, so the gate accepts it as a coin factor, but large
        // enough that the supply ceiling cannot be held in a long. The coin's
        // parameter set is built from configuration now, so a value like this
        // arrives from a fetch rather than from a constant: the failure has to
        // arrive as the coin error callers already handle, not as an exception
        // thrown out of init().
        final long UNREPRESENTABLE = 1_000_000_000_000_000_000L;

        CoinConfigRegistry.loadForTest(Map.of("BLOCK", testCoinConfig(
                "BLOCK", "Blocknet", ADDRESS_PREFIX, P2SH_PREFIX, SECRET_PREFIX,
                41414, FEE_PER_BYTE, MIN_TX_FEE, UNREPRESENTABLE)));
        CoinInstance blocknet = CoinInstance.getInstance(CoinTicker.BLOCKNET);
        assertNotNull(blocknet, "BLOCKNET instance");
        char[] pw = getPassword().toCharArray();
        try {
            CoinInstance.CoinError err = blocknet.init(pw, getMnemonic(), false);
            assertNotNull(err, "a configuration that cannot be represented must not start");
            assertEquals(CoinInstance.CoinError.CoinErrorCode.UNSUPPORTEDCOIN, err.getCode(),
                    "it must arrive as the coin error callers already handle");
        } finally {
            Arrays.fill(pw, '\0');
        }
    }

    @Test
    @DisplayName("a loaded registry that lacks the coin says which coin, not that it is empty")
    void testMissingCoinInALoadedRegistryNamesIt() {
        // Distinct from an empty registry: the load succeeded and simply has no
        // entry for this ticker. Reporting that as "no configuration loaded" at
        // all would send the reader looking for a fetch or cache problem instead
        // of a coin that is genuinely not configured.
        CoinConfigRegistry.loadForTest(Map.of(
                "LTC", testCoinConfig("LTC", "Litecoin", 48, 50, 176, 9332, 10L, 5_000L)));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CoinConfigRegistry.get("BLOCK"));
        // Qualified: TestHelper declares its own single-argument assertTrue,
        // which shadows the static import above, so the two-argument form has
        // to be asked for by name.
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("BLOCK"),
                "the failure must name the coin that is missing, got: " + e.getMessage());
    }

    @Test
    @DisplayName("Blocknet refuses to start on a coin it has no configuration for")
    void testBlocknetFailsLoudlyWithoutConfiguration() {
        CoinConfigRegistry.resetForTest();
        CoinInstance blocknet = CoinInstance.getInstance(CoinTicker.BLOCKNET);
        assertNotNull(blocknet, "BLOCKNET instance");
        char[] pw = getPassword().toCharArray();
        try {
            CoinInstance.CoinError err = blocknet.init(pw, getMnemonic(), false);
            // Falling back to in-code constants would hide a missing coin, so
            // the failure has to be reported rather than papered over.
            assertNotNull(err, "a coin with no configuration must not start");
            assertEquals(CoinInstance.CoinError.CoinErrorCode.UNSUPPORTEDCOIN, err.getCode());
        } finally {
            Arrays.fill(pw, '\0');
        }
    }

    @Test
    @DisplayName("the XRouter p2p parameters report the coin's values instead of holding their own")
    void testPeerParametersDoNotDuplicateTheCoinsValues() {
        CoinInstance blocknet = initializedBlocknet();
        ConfigurableNetworkParameters fromConfig =
                ConfigurableNetworkParameters.from(CoinConfigRegistry.get("BLOCK"));
        BlocknetNetworkParameters peer =
                new BlocknetNetworkParameters();

        // The p2p object used to carry its own copies of these, which is how
        // they drifted from what the daemon charges by. They must now come from
        // the one authoritative set.
        assertEquals(fromConfig.getMaxMoney(), peer.getMaxMoney(), "supply ceiling");
        assertEquals(fromConfig.getMinNonDustOutput(), peer.getMinNonDustOutput(), "dust floor");
        assertEquals(fromConfig.getProtocolVersionNum(NetworkParameters.ProtocolVersion.CURRENT),
                peer.getProtocolVersionNum(NetworkParameters.ProtocolVersion.CURRENT),
                "protocol version");
        assertEquals(fromConfig.getId(), peer.getId(), "network id");
        assertEquals(fromConfig.getAddressHeader(), peer.getAddressHeader(), "address header");
        assertEquals(fromConfig.getP2SHHeader(), peer.getP2SHHeader(), "p2sh header");
        assertEquals(fromConfig.getDumpedPrivateKeyHeader(), peer.getDumpedPrivateKeyHeader(),
                "dumped key header");

        // And it must still be able to answer the p2p questions only it can.
        assertNotNull(peer.getXRouterMessageSerializer(false), "xrouter serializer");
        assertNotNull(peer.getDnsSeeds());
        assertTrue(peer.getDnsSeeds().length > 0);
    }
}
