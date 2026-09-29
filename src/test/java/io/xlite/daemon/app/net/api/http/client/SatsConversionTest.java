package io.xlite.daemon.app.net.api.http.client;

import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.util.UTXO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Whole-coin to satoshi conversion must round to nearest, never truncate.
 *
 * <p>A live DASH UTXO worth 0.00050001 (50001 sats) was ingested as 50000
 * sats because {@code (long) Math.floor(value * 1e8)} truncates the binary
 * floating-point representation error. The one-satoshi shortfall then
 * invalidated the BIP137 ownership proof on takes (the hub re-verifies
 * against chain truth), producing servicenode {@code crNoMoney} rejects
 * for fully-funded wallets.
 */
class SatsConversionTest {
    // The ingest path that replaced the deleted per-site conversion. The factor
    // is resolved from the coin itself, so the two decimal regimes under test
    // are two real coins rather than a synthetic pair of factors.
    private static final CoinTicker TICKER_8DP = CoinTicker.LITECOIN;
    private static final CoinTicker TICKER_6DP = CoinTicker.VERGE;

    /**
     * The conversion resolves the factor from the coin's configuration, so the
     * registry has to carry both coins under test. Loaded here and dropped
     * afterwards because it is process-global.
     */
    @org.junit.jupiter.api.BeforeAll
    static void loadRegistry() {
        java.util.Map<String, CoinConfig> cfgs = new java.util.LinkedHashMap<>();
        cfgs.put("LTC", config("LTC", "Litecoin", 48, 50, 176, 100_000_000L));
        // CoinTicker.VERGE carries the manifest ticker XVG.
        cfgs.put("XVG", config("XVG", "Verge", 30, 33, 158, 1_000_000L));
        CoinConfigRegistry.loadForTest(cfgs);
    }

    @org.junit.jupiter.api.AfterAll
    static void releaseRegistry() {
        CoinConfigRegistry.resetForTest();
    }

    private static CoinConfig config(String ticker, String name, int addr, int p2sh, int dpk,
                                     long coinFactor) {
        java.util.Map<String, String> e = new java.util.LinkedHashMap<>();
        e.put("AddressPrefix", Integer.toString(addr));
        e.put("ScriptPrefix", Integer.toString(p2sh));
        e.put("SecretPrefix", Integer.toString(dpk));
        e.put("COIN", Long.toString(coinFactor));
        e.put("FeePerByte", "2");
        e.put("MinTxFee", "678");
        e.put("Port", "9332");
        e.put("DustAmount", "0");
        return new CoinConfig(ticker, name, ticker.toLowerCase() + "--test", e);
    }

    @Test
    void testLiveDashCase() {
        // 0.00050001 is not exactly representable in binary; floor() yields 50000.
        assertEquals(50001L, UTXO.baseUnitsForTicker(TICKER_8DP, 0.00050001));
    }

    @Test
    void testExactValues() {
        assertEquals(500000L, UTXO.baseUnitsForTicker(TICKER_8DP, 0.005));
        assertEquals(20000000L, UTXO.baseUnitsForTicker(TICKER_8DP, 0.2));
        assertEquals(1L, UTXO.baseUnitsForTicker(TICKER_8DP, 0.00000001));
        assertEquals(0L, UTXO.baseUnitsForTicker(TICKER_8DP, 0.0));
    }

    @Test
    void testSubSatoshiRoundsToZero() {
        assertEquals(0L, UTXO.baseUnitsForTicker(TICKER_8DP, 0.000000001));
    }

    @Test
    void testMaxSupplyNoOverflow() {
        assertEquals(2_100_000_000_000_000L, UTXO.baseUnitsForTicker(TICKER_8DP, 21_000_000.0));
    }

    @Test
    void testSixDecimalCoinUsesOwnFactor() {
        // Verge live case: the adapter reports 0.1 XVG for a 100000-base-unit
        // UTXO. With COIN=1000000 the daemon must ingest 100000, not 10000000.
        assertEquals(100000L, UTXO.baseUnitsForTicker(TICKER_6DP, 0.1));
        assertEquals(2900000L, UTXO.baseUnitsForTicker(TICKER_6DP, 2.9));
    }

    @Test
    void testSixDecimalSubUnitRoundsToZero() {
        assertEquals(0L, UTXO.baseUnitsForTicker(TICKER_6DP, 0.0000001));
    }

    @Test
    void testSixDecimalMaxSupplyNoOverflow() {
        assertEquals(16_555_000_000_000_000L,
                UTXO.baseUnitsForTicker(TICKER_6DP, 16_555_000_000.0));
    }
}
