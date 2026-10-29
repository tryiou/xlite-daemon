package io.xlite.daemon.app.coinconfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The divisor must be right, and it must be reachable.
 *
 * <p>What this pins: each coin's factor is read from its own configuration, so
 * a 6-decimal coin is not priced with 1e8.
 *
 * <p>On the other failure mode -- no factor available at all -- it pins the
 * throw, not the absence of one. An unloaded registry does throw from
 * {@link CoinConfigRegistry#get}, and the daemon stays up because
 * CoinInstance turns that into UNSUPPORTEDCOIN while initialising a coin. The
 * amount paths therefore stay unreachable in that mode rather than never
 * throwing: which coins reach them is a property of coin initialisation, not of
 * the conversion.
 */
class CoinConfigDivisorTest {

    private static final long SIX_DECIMAL = 1_000_000L;
    private static final long EIGHT_DECIMAL = 100_000_000L;

    @AfterEach
    void reset() {
        CoinConfigRegistry.resetForTest();
    }

    private static CoinConfig config(String ticker, String blockchain, long coin) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("AddressPrefix", "26");
        e.put("ScriptPrefix", "28");
        e.put("SecretPrefix", "154");
        e.put("COIN", Long.toString(coin));
        e.put("FeePerByte", "20");
        e.put("MinTxFee", "10000");
        e.put("Port", "41414");
        e.put("DustAmount", "0");
        e.put("Title", blockchain);
        return new CoinConfig(ticker, blockchain, ticker.toLowerCase() + "--test", e);
    }

    @Test
    @DisplayName("each coin's COIN factor is read from its own config")
    void testCoinFactorIsPerCoin() {
        Map<String, CoinConfig> cfgs = new LinkedHashMap<>();
        cfgs.put("PIVX", config("PIVX", "PIVX", SIX_DECIMAL));
        cfgs.put("BLOCK", config("BLOCK", "Blocknet", EIGHT_DECIMAL));
        CoinConfigRegistry.loadForTest(cfgs);

        assertEquals(SIX_DECIMAL, CoinConfigRegistry.get("PIVX").coinFactor());
        assertEquals(EIGHT_DECIMAL, CoinConfigRegistry.get("BLOCK").coinFactor());
    }




    @Test
    @DisplayName("another coin still fails fast without configuration")
    void testOtherCoinsStillFailFast() {
        assertThrows(IllegalStateException.class, () -> CoinConfigRegistry.get("PIVX"),
                "a coin with no built-in config and no registry must fail loudly "
                        + "rather than guess a divisor");
    }


    @Test
    @DisplayName("a config whose COIN is not a power of ten is rejected at load")
    void testGateRejectsNonPowerOfTenCoin() {
        CoinConfig bad = config("ODD", "Odd", 3L);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CoinConfigGate.validate(bad));
        org.junit.jupiter.api.Assertions.assertTrue(
                e.getMessage().contains("power of ten"),
                "the rejection must name the problem: " + e.getMessage());
    }

}
