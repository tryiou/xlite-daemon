package io.xlite.daemon.app.util;

import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters;
import io.xlite.daemon.app.net.CoinTicker;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Outbound transactions carry the version their coin's configuration assigns.
 *
 * <p>BCF's {@code TxVersion} is the version found in that chain's raw
 * transactions, so it is the only correct value. bitcoinj defaults to 1, which
 * is right for some coins and silently wrong for the others; this pins the
 * value to configuration for both cases.
 */
class CoinTransactionsTest {

    @BeforeEach
    void loadRegistry() {
        Map<String, CoinConfig> cfgs = new LinkedHashMap<>();
        cfgs.put("XVG", config("XVG", 1));
        cfgs.put("PKOIN", config("PKOIN", 2));
        cfgs.put("LTC", config("LTC", 2));
        CoinConfigRegistry.loadForTest(cfgs);
    }

    @AfterEach
    void resetRegistry() {
        CoinConfigRegistry.resetForTest();
    }

    private static CoinConfig config(String ticker, int txVersion) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("AddressPrefix", "30");
        e.put("ScriptPrefix", "33");
        e.put("SecretPrefix", "158");
        e.put("COIN", "100000000");
        e.put("FeePerByte", "2");
        e.put("MinTxFee", "678");
        e.put("Port", "9332");
        e.put("DustAmount", "0");
        e.put("TxVersion", Integer.toString(txVersion));
        return new CoinConfig(ticker, ticker, ticker.toLowerCase() + "--test", e);
    }

    private static NetworkParameters paramsFor(String ticker) {
        return ConfigurableNetworkParameters.from(CoinConfigRegistry.get(ticker));
    }

    @Test
    @DisplayName("the version comes from configuration, not from bitcoinj's default")
    void testUsesConfiguredVersion() {
        // bitcoinj's new Transaction(...) is version 1; PKOIN and LTC are 2.
        assertEquals(1, CoinTransactions.newTransaction(
                paramsFor("XVG"), CoinTicker.VERGE).getVersion());
        assertEquals(2, CoinTransactions.newTransaction(
                paramsFor("PKOIN"), CoinTicker.PKOIN).getVersion());
        assertEquals(2, CoinTransactions.newTransaction(
                paramsFor("LTC"), CoinTicker.LITECOIN).getVersion());
    }

    @Test
    @DisplayName("a coin without a loaded configuration fails instead of defaulting")
    void testMissingConfigurationFailsLoudly() {
        assertThrows(IllegalStateException.class,
                () -> CoinTransactions.newTransaction(
                        paramsFor("XVG"), CoinTicker.DOGECOIN));
    }

    @Test
    @DisplayName("signing carries the unsigned transaction's version and locktime")
    void testForSigningCarriesVersionAndLockTime() {
        Transaction unsigned = new Transaction(paramsFor("PKOIN"));
        unsigned.setVersion(2);
        unsigned.setLockTime(123_456L);

        Transaction signed = CoinTransactions.forSigning(paramsFor("PKOIN"), unsigned);

        assertEquals(2, signed.getVersion());
        assertEquals(123_456L, signed.getLockTime());
    }
}
