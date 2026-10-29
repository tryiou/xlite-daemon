package io.xlite.daemon.app.util;

import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinTicker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The divisor is read at several call sites, and a wrong divisor there does not
 * throw -- it silently reports an amount 100x off, which is the failure this
 * whole change exists to prevent. Each case below supplies a coin whose COIN
 * factor is not 1e8 and pins the value that reaches the call site, so a
 * regression to a hardcoded 1e8 is caught where it happens rather than on a
 * live chain.
 */
class SatsDivisorCallSiteTest {

    private static final long EIGHT_DECIMAL = 100_000_000L;
    private static final long SIX_DECIMAL = 1_000_000L;
    private static final String ADDR = "DLp6jdqQjUC3JM5neBNV5UwPzyJFSA4qx3";
    private static final String TXID =
            "2d05f9436cdfd7e86968649ed76e6c36c1468a21b7203f730adacd52e3ee72ae";

    @BeforeEach
    void useSixDecimalCoin() {
        CoinConfigRegistry.loadForTest(Map.of(
                "PIVX", config("PIVX", "PIVX", 30, 13, 212, SIX_DECIMAL),
                "BLOCK", config("BLOCK", "Blocknet", 26, 28, 154, EIGHT_DECIMAL),
                "BTC", config("BTC", "Bitcoin", 0, 5, 128, EIGHT_DECIMAL)));
    }

    @AfterEach
    void reset() {
        CoinConfigRegistry.resetForTest();
    }

    private static CoinConfig config(String ticker, String blockchain,
            int addrPrefix, int scriptPrefix, int secretPrefix, long coin) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("AddressPrefix", Integer.toString(addrPrefix));
        e.put("ScriptPrefix", Integer.toString(scriptPrefix));
        e.put("SecretPrefix", Integer.toString(secretPrefix));
        e.put("COIN", Long.toString(coin));
        e.put("FeePerByte", "10");
        e.put("MinTxFee", "5000");
        e.put("Port", "10000");
        e.put("DustAmount", "0");
        e.put("Title", blockchain);
        return new CoinConfig(ticker, blockchain, ticker.toLowerCase() + "--test", e);
    }

    @Test
    @DisplayName("a stored amount is divided by the coin's own factor")
    void testStoredAmountUsesOwnFactor() {
        UTXO six = new UTXO(CoinTicker.PIVX, ADDR, TXID, 0, 100, 100_000L);
        assertEquals(0.1, six.getAmount(), 1e-9);

        UTXO eight = new UTXO(CoinTicker.BLOCKNET, ADDR, TXID, 0, 100, 100_000L);
        assertEquals(0.001, eight.getAmount(), 1e-12,
                "an 8-decimal coin keeps the 1e8 divisor");
    }

    @Test
    @DisplayName("an address balance is divided by the coin's own factor, once")
    void testAddressBalanceUsesOwnFactorExactlyOnce() {
        // The divisor is taken from the first row's coin, so the rows must
        // belong to the coin whose factor is under test.
        org.bitcoinj.core.LegacyAddress address = org.bitcoinj.core.LegacyAddress.fromBase58(
                io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters
                        .from(io.xlite.daemon.app.coinconfig.CoinConfigRegistry.get("PIVX")),
                ADDR);
        AddressBalance balance = new AddressBalance(address, null);
        balance.addUtxo(new UTXO(CoinTicker.PIVX, ADDR, TXID, 0, 100, 100_000L));
        balance.calculateBalance();
        assertEquals(0.1, balance.getBalanceProp(), 1e-9,
                "100000 base units of a 6-decimal coin is 0.1");
    }

    @Test
    @DisplayName("whole coins to base units uses the coin's own factor")
    void testFromWholeCoinsUsesOwnFactor() {
        assertEquals(100_000L, Sats.fromWholeCoins(0.1, SIX_DECIMAL));
        assertEquals(10_000_000L, Sats.fromWholeCoins(0.1, EIGHT_DECIMAL));
    }

    @Test
    @DisplayName("base units to whole coins uses the coin's own factor")
    void testToWholeCoinsUsesOwnFactor() {
        assertEquals(0.1, Sats.toWholeCoins(100_000L, SIX_DECIMAL), 1e-12);
        assertEquals(0.001, Sats.toWholeCoins(100_000L, EIGHT_DECIMAL), 1e-15);
        assertNotEquals(Sats.toWholeCoins(100_000L, SIX_DECIMAL),
                Sats.toWholeCoins(100_000L, EIGHT_DECIMAL),
                "the two factors must not be interchangeable");
    }

    @Test
    @DisplayName("the exact decimal rendering is lossless for both factors")
    void testWholeCoinStringIsExact() {
        assertEquals("0.1", Sats.toWholeCoinsString(100_000L, SIX_DECIMAL));
        assertEquals("0.001", Sats.toWholeCoinsString(100_000L, EIGHT_DECIMAL));
        assertEquals("123.456789", Sats.toWholeCoinsString(123_456_789L, SIX_DECIMAL));
        assertEquals("1.23456789", Sats.toWholeCoinsString(123_456_789L, EIGHT_DECIMAL));
        assertEquals("0", Sats.toWholeCoinsString(0L, SIX_DECIMAL));
    }

    @Test
    @DisplayName("decimal places follow the coin's own factor")
    void testDecimalPlacesFollowFactor() {
        assertEquals(6, Sats.decimalPlaces(SIX_DECIMAL));
        assertEquals(8, Sats.decimalPlaces(EIGHT_DECIMAL));
        assertEquals(0, Sats.decimalPlaces(1L));
    }

    @Test
    @DisplayName("CloudTransaction renders a base-unit amount at the coin's own decimals")
    void testCloudTransactionRenderingHonorsCoinFactor() {
        // BtcFormat.COIN_SCALE rendered 100000 base units as "0.00".
        assertEquals("0.1", CloudTransaction.formatForTicker(100_000L, "PIVX"));
        assertEquals("0.001", CloudTransaction.formatForTicker(100_000L, "BTC"));
        assertNotEquals(CloudTransaction.formatForTicker(100_000L, "PIVX"),
                CloudTransaction.formatForTicker(100_000L, "BTC"),
                "the factor must come from the ticker, not a constant");
    }

    @Test
    @DisplayName("every UTXO-ingest path converts a response amount at that coin's own factor")
    void testUtxoIngestChokePoint() {
        // Three sites used to do this inline, once per row, each resolving the
        // divisor itself. They now share one audited conversion.
        assertEquals(100_000L, UTXO.baseUnitsForTicker(CoinTicker.PIVX, 0.1),
                "0.1 of a 6-decimal coin is 100000 base units");
        assertEquals(100_000L, UTXO.baseUnitsForTicker(CoinTicker.BITCOIN, 0.001),
                "0.001 of an 8-decimal coin is the same base units, by coincidence");
        assertNotEquals(UTXO.baseUnitsForTicker(CoinTicker.PIVX, 1.0),
                UTXO.baseUnitsForTicker(CoinTicker.BITCOIN, 1.0),
                "one whole coin must be the coin's own factor, never a constant");
    }
}
