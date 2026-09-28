import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.coinconfig.CompiledCoinSupplement;
import io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters;
import io.xlite.daemon.app.wallet.WalletHelper;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.TestNet3Params;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WalletHelperFeeTest extends TestHelper {

    @BeforeEach
    void setup() {
        commonSetup();
    }

    @AfterAll
    static void cleanup() {
        commonCleanup();
    }

    // ========================================================================
    // The parameters class reports the configured fees, for every coin
    // ========================================================================

    /**
     * Per-coin coverage on the class the daemon actually uses. The old version
     * of this file asserted the fee constants held by the per-coin hardcoded
     * parameter classes, which no longer exist; those constants were stale
     * relative to the configuration repository anyway, so pinning them tested
     * dead numbers.
     *
     * <p>What this pins is the wiring, for every supplemented coin at once: the
     * parameters class reports the configuration's fee values rather than
     * substituting a constant. The fee values themselves are NOT pinned to any
     * coin — configuration changes routinely, and a test that tracked it would
     * report a regression every time the repository moved while proving nothing
     * about the code. The fee figures asserted below are the synthetic ones this class builds,
 * not any real coin's configured rate.
     */
    @Test
    void testEveryMigratedCoinReportsItsConfiguredFees() {
        int index = 0;
        for (String ticker : supplementedTickers()) {
            index++;
            CoinConfig cfg = configFor(ticker, index);
            ConfigurableNetworkParameters params = ConfigurableNetworkParameters.from(cfg);

            assertEquals(cfg.feePerByte(), WalletHelper.getFeePerByte(params),
                    ticker + " feePerByte must equal its configured value");
            assertEquals(cfg.minTxFee(), WalletHelper.getMinTxFee(params),
                    ticker + " minTxFee must equal its configured value");
        }
    }

    // ========================================================================
    // Generic path — fee values must match loaded configs
    // ========================================================================

    @Test
    void testGenericFeesMatchLoadedConfigs() {
        Map<String, CoinConfig> cfgs = CoinConfigRegistry.list();
        assertFalse(cfgs.isEmpty(), "registry empty — TestHelper must load LTC/BLOCK");
        for (Map.Entry<String, CoinConfig> e : cfgs.entrySet()) {
            String ticker = e.getKey();
            CoinConfig cfg = e.getValue();
            // Only check migrated tickers that WalletHelper knows via HasFeeParams
            if (!CompiledCoinSupplement.supports(ticker))
                continue;
            ConfigurableNetworkParameters generic = ConfigurableNetworkParameters.from(cfg);
            assertEquals(cfg.feePerByte(), WalletHelper.getFeePerByte(generic),
                    ticker + " generic feePerByte must equal config");
            assertEquals(cfg.minTxFee(), WalletHelper.getMinTxFee(generic),
                    ticker + " generic minTxFee must equal config");
        }
    }

    // ========================================================================
    // Edge Case - unknown coin returns default
    // ========================================================================

    @Test
    void testGetFeePerByte_UnknownCoin_Throws() {
        NetworkParameters unknownParams = TestNet3Params.get();
        assertThrows(RuntimeException.class, () -> WalletHelper.getFeePerByte(unknownParams));
    }

    @Test
    void testGetMinTxFee_UnknownCoin_Throws() {
        NetworkParameters unknownParams = TestNet3Params.get();
        assertThrows(RuntimeException.class, () -> WalletHelper.getMinTxFee(unknownParams));
    }

    // ========================================================================
    // Fixture: one realistic configuration per migrated coin
    // ========================================================================

    /**
     * Every ticker the supplement can build parameters for, derived rather than
     * listed: a hand-copied array would silently miss a coin added to the
     * supplement, and the coverage claim would quietly rot.
     */
    private static List<String> supplementedTickers() {
        List<String> out = new ArrayList<>();
        // Active tickers only: the enum keeps delisted variants that have no
        // string mapping, and those are not coins the wallet can start.
        for (CoinTicker t : CoinTickerUtils.getActiveTickers()) {
            String ticker = CoinTickerUtils.tickerToString(t);
            if (CompiledCoinSupplement.supports(ticker)) {
                out.add(ticker);
            }
        }
        assertFalse(out.isEmpty(), "no supplemented tickers found");
        return out;
    }

    /**
     * A configuration shaped like a real one, with every value chosen by the
     * test and different per coin, so a class that defaulted or reused one
     * coin's numbers would not pass. Nothing here mirrors a real coin: these
     * are configuration values and they change.
     */
    private static CoinConfig configFor(String ticker, int index) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("AddressPrefix", Integer.toString(30 + index));
        e.put("ScriptPrefix", Integer.toString(60 + index));
        e.put("SecretPrefix", Integer.toString(120 + index));
        e.put("COIN", "100000000");
        e.put("FeePerByte", Long.toString(11L + index));
        e.put("MinTxFee", Long.toString(22_000L + index));
        e.put("Port", Integer.toString(34567 + index));
        e.put("DustAmount", "0");
        e.put("Title", ticker);
        return new CoinConfig(ticker, ticker, ticker.toLowerCase() + "--test", e);
    }
}
