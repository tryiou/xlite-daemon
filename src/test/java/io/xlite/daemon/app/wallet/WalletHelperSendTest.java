package io.xlite.daemon.app.wallet;

import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.util.UTXO;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the {@code sendtransaction} "No network found" failure.
 *
 * <p>Live daemon rejected valid external LTC/DASH/PIVX destination addresses
 * because the send path parsed them with {@code LegacyAddress.fromBase58(null, …)},
 * which only resolves networks in bitcoinj's global registry (BTC mainnet +
 * testnet). The vectors below are real mainnet addresses produced by live
 * full-node wallets.
 */
class WalletHelperSendTest {

    // Real mainnet P2PKH addresses issued by live full-node wallets.
    private static final String LTC_P2PKH = "LWzaDk84d3N1pTL6rN14GCcLXxEjQQ45Lx";
    private static final String DASH_P2PKH = "XuqKo24hcwbnSikBDEdHw8sdk3SbYWkMFq";
    private static final String PIVX_P2PKH = "D5rFeo85qYnCYQyYfn9L3k2No9eCizHECq";

    /**
     * Each coin's prefixes and fees come from its configuration, so the
     * registry must hold the tested tickers. Reset afterwards: the loaded state
     * is process-global and must not leak into other classes.
     *
     * <p>The prefixes are the coins' published address prefixes, pinned because
     * the address vectors below are real mainnet addresses whose version bytes
     * those prefixes define. Fees and the COIN factor are chosen by the test and
     * are not claims about any coin's current configuration.
     */
    @BeforeAll
    static void loadRegistry() {
        CoinConfigRegistry.loadForTest(Map.of(
                "LTC", fixture("LTC", "Litecoin", 48, 50, 176, 100000000L, 9332),
                "DASH", fixture("DASH", "Dash", 76, 16, 204, 100000000L, 9998),
                "PIVX", fixture("PIVX", "PIVX", 30, 13, 212, 100000000L, 51474),
                "BLOCK", fixture("BLOCK", "Blocknet", 26, 28, 154, 100000000L, 41414)));
    }

    @AfterAll
    static void resetRegistry() {
        CoinConfigRegistry.resetForTest();
    }

    /**
     * The coin's parameters as the daemon builds them: from its configuration.
     * The per-coin hardcoded parameter classes these tests used to instantiate
     * are gone, and the address vectors below are only meaningful against the
     * prefixes the coin actually ships.
     */
    private static NetworkParameters paramsFor(String ticker) {
        return ConfigurableNetworkParameters.from(CoinConfigRegistry.get(ticker));
    }

    private static CoinConfig fixture(String ticker, String blockchain,
            int addrPrefix, int scriptPrefix, int secretPrefix,
            long coin, int port) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("AddressPrefix", Integer.toString(addrPrefix));
        entries.put("ScriptPrefix", Integer.toString(scriptPrefix));
        entries.put("SecretPrefix", Integer.toString(secretPrefix));
        entries.put("COIN", Long.toString(coin));
        entries.put("FeePerByte", "10");
        entries.put("MinTxFee", "5000");
        entries.put("Port", Integer.toString(port));
        entries.put("DustAmount", "0");
        entries.put("Title", blockchain);
        return new CoinConfig(ticker, blockchain, ticker.toLowerCase() + "--test", entries);
    }

    /**
     * Pins the bitcoinj contract behind the original failure: a null-params
     * lookup cannot resolve non-BTC version bytes. Documents why the old
     * call was wrong; passes independently of the fix.
     */
    @Test
    void testNullParamsLookupRejectsAltcoinAddresses() {
        assertThrows(AddressFormatException.class,
                () -> LegacyAddress.fromBase58(null, LTC_P2PKH));
        assertThrows(AddressFormatException.class,
                () -> LegacyAddress.fromBase58(null, DASH_P2PKH));
        assertThrows(AddressFormatException.class,
                () -> LegacyAddress.fromBase58(null, PIVX_P2PKH));
    }

    @Test
    void testCreateTransactionOutput_LitecoinP2PKH() {
        NetworkParameters params = paramsFor("LTC");
        UTXO out = WalletHelper.createTransactionOutput(
                CoinTicker.LITECOIN, LTC_P2PKH, 0.005, params);
        assertEquals(LTC_P2PKH, out.getAddress());
        assertEquals(500_000L, out.getValue());
    }

    @Test
    void testCreateTransactionOutput_DashP2PKH() {
        NetworkParameters params = paramsFor("DASH");
        UTXO out = WalletHelper.createTransactionOutput(
                CoinTicker.DASHCOIN, DASH_P2PKH, 0.005, params);
        assertEquals(DASH_P2PKH, out.getAddress());
        assertEquals(500_000L, out.getValue());
    }

    @Test
    void testCreateTransactionOutput_PivxP2PKH() {
        NetworkParameters params = paramsFor("PIVX");
        UTXO out = WalletHelper.createTransactionOutput(
                CoinTicker.PIVX, PIVX_P2PKH, 0.2, params);
        assertEquals(PIVX_P2PKH, out.getAddress());
        assertEquals(20_000_000L, out.getValue());
    }

    @Test
    void testCreateTransactionOutput_P2SHRoundTrip() {
        // P2SH vectors constructed from the coin's own params, so the version
        // bytes are definitionally correct for each network.
        assertP2SHRoundTrip(CoinTicker.LITECOIN, paramsFor("LTC"));
        assertP2SHRoundTrip(CoinTicker.DASHCOIN, paramsFor("DASH"));
        assertP2SHRoundTrip(CoinTicker.PIVX, paramsFor("PIVX"));
    }

    private static void assertP2SHRoundTrip(CoinTicker ticker, NetworkParameters params) {
        byte[] hash = new byte[20];
        for (int i = 0; i < hash.length; i++)
            hash[i] = (byte) (i * 7 + 3);
        String p2sh = LegacyAddress.fromScriptHash(params, hash).toBase58();
        assertTrue(LegacyAddress.fromBase58(params, p2sh).isP2SHAddress());
        UTXO out = WalletHelper.createTransactionOutput(ticker, p2sh, 0.001, params);
        assertEquals(p2sh, out.getAddress());
        assertEquals(100_000L, out.getValue());
    }

    @Test
    void testCreateTransactionOutput_BlocknetRegression() {
        NetworkParameters params = paramsFor("BLOCK");
        String p2pkh = LegacyAddress.fromPubKeyHash(params, new byte[20]).toBase58();
        UTXO out = WalletHelper.createTransactionOutput(
                CoinTicker.BLOCKNET, p2pkh, 0.05, params);
        assertEquals(p2pkh, out.getAddress());
        assertEquals(5_000_000L, out.getValue());
    }

    @Test
    void testCreateTransactionOutput_InexactDoubleRoundsToNearest() {
        // 0.00050001 is not exactly representable in binary; the stored
        // double sits just below 50001 sats, so (long) truncation yields
        // 50000. Must round to nearest like the UTXO ingest path.
        NetworkParameters params = paramsFor("LTC");
        UTXO out = WalletHelper.createTransactionOutput(
                CoinTicker.LITECOIN, LTC_P2PKH, 0.00050001, params);
        assertEquals(50_001L, out.getValue());
    }
}
