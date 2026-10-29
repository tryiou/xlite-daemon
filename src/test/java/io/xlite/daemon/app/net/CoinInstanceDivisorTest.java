package io.xlite.daemon.app.net;

import io.xlite.daemon.app.coinconfig.CompiledCoinSupplement;
import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters;
import io.xlite.daemon.app.util.Sats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The supply ceiling and the balance rendering both have to scale with the
 * coin's own COIN factor.
 *
 * <p>The rendering case is the subtle one: bitcoinj's format is configured
 * with {@code minDecimals}, which only controls how many trailing digits are
 * shown. The divisor comes from the value's own
 * {@code smallestUnitExponent()}, and bitcoinj's {@code Coin} hardcodes that
 * to 8 -- so a 6-decimal coin handed to a format as a {@code Coin} renders
 * 100x small no matter what the format is told.
 */
class CoinInstanceDivisorTest {

    private static final long SIX_DECIMAL = 1_000_000L;
    private static final long EIGHT_DECIMAL = 100_000_000L;

    @BeforeEach
    void loadSupplements() {
        // ConfigurableNetworkParameters needs both a registry entry and a
        // compiled supplement; each test that needs a coin registers both.
        CoinConfigRegistry.loadForTest(Map.of("BTC", config("BTC", EIGHT_DECIMAL)));
    }

    @AfterEach
    void reset() {
        CoinConfigRegistry.resetForTest();
        CompiledCoinSupplement.resetSupplementsForTest();
    }

    private static CoinConfig config(String ticker, long coin) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("AddressPrefix", "30");
        e.put("ScriptPrefix", "33");
        e.put("SecretPrefix", "158");
        e.put("COIN", Long.toString(coin));
        e.put("FeePerByte", "10");
        e.put("MinTxFee", "5000");
        e.put("Port", "20102");
        e.put("DustAmount", "0");
        e.put("Title", "Test");
        return new CoinConfig(ticker, "Test", ticker.toLowerCase() + "--test", e);
    }

    @Test
    @DisplayName("the supply ceiling scales with the coin's own factor, not a hardcoded 1e8")
    void testMaxMoneyScalesWithCoinFactor() {
        // Two coins, same 84 million supply, different COIN factors. A ceiling
        // computed with bitcoinj's universal Coin.COIN.value (1e8) would make
        // the six-decimal one 100x too permissive, which is a consensus
        // constant, not a display nicety.
        CoinConfigRegistry.loadForTest(Map.of(
                "EIGHT", config("EIGHT", EIGHT_DECIMAL),
                "SIX", config("SIX", SIX_DECIMAL)));
        CompiledCoinSupplement.loadSupplementsForTest(Map.of(
                "EIGHT", new CompiledCoinSupplement.Supplement(84_000_000L, 70012, "EIGHT", "main"),
                "SIX", new CompiledCoinSupplement.Supplement(84_000_000L, 70012, "SIX", "main")));

        assertEquals(8_400_000_000_000_000L,
                ConfigurableNetworkParameters.from(CoinConfigRegistry.get("EIGHT")).getMaxMoney().value);
        assertEquals(84_000_000_000_000L,
                ConfigurableNetworkParameters.from(CoinConfigRegistry.get("SIX")).getMaxMoney().value,
                "84 million coins at 1e6 base units each");
    }

    @Test
    @DisplayName("a base-unit amount renders at the coin's own decimal places")
    void testBaseUnitRenderingHonorsCoinFactor() {
        // 100000 base units: 0.1 of a 6-decimal coin, 0.001 of an 8-decimal
        // one. Rendered through the same helper the balance path uses.
        assertEquals("0.100000", CoinInstance.formatBaseUnits(100_000L, SIX_DECIMAL));
        assertEquals("0.00100000", CoinInstance.formatBaseUnits(100_000L, EIGHT_DECIMAL));
        assertNotEquals(CoinInstance.formatBaseUnits(100_000L, SIX_DECIMAL),
                CoinInstance.formatBaseUnits(100_000L, EIGHT_DECIMAL),
                "the two factors must not render identically");
    }

    @Test
    @DisplayName("the naive rendering is what this change exists to prevent")
    void testCoinValuedRenderingWouldBeWrongForSixDecimals() {
        // Guards the specific mistake: handing a 6-decimal amount to a format
        // as a bitcoinj Coin, which always divides by 1e8.
        String naive = org.bitcoinj.utils.MonetaryFormat.BTC.minDecimals(6)
                .repeatOptionalDecimals(1, 0).noCode()
                .format(org.bitcoinj.core.Coin.valueOf(100_000L)).toString();
        assertTrue(naive.startsWith("0.001"),
                "bitcoinj's Coin exponent is fixed at 8, so this is 100x small: " + naive);
    }

    @Test
    @DisplayName("a six-decimal coin's whole-coin string is exact")
    void testWholeCoinStringExactForBothFactors() {
        assertEquals("0.1", Sats.toWholeCoinsString(100_000L, SIX_DECIMAL));
        assertEquals("0.001", Sats.toWholeCoinsString(100_000L, EIGHT_DECIMAL));
    }
}
