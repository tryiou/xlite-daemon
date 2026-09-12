package io.xlite.daemon.app.wallet;

import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.protocols.blocknet.BlocknetNetworkParameters;
import io.xlite.daemon.app.net.protocols.dashcoin.DashcoinNetworkParametersLegacy;
import io.xlite.daemon.app.net.protocols.litecoin.LitecoinNetworkParametersLegacy;
import io.xlite.daemon.app.net.protocols.pivx.PivxNetworkParametersLegacy;
import io.xlite.daemon.app.util.UTXO;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.junit.jupiter.api.Test;

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
        NetworkParameters params = new LitecoinNetworkParametersLegacy();
        UTXO out = WalletHelper.createTransactionOutput(
                CoinTicker.LITECOIN, LTC_P2PKH, 0.005, params);
        assertEquals(LTC_P2PKH, out.getAddress());
        assertEquals(500_000L, out.getValue());
    }

    @Test
    void testCreateTransactionOutput_DashP2PKH() {
        NetworkParameters params = new DashcoinNetworkParametersLegacy();
        UTXO out = WalletHelper.createTransactionOutput(
                CoinTicker.DASHCOIN, DASH_P2PKH, 0.005, params);
        assertEquals(DASH_P2PKH, out.getAddress());
        assertEquals(500_000L, out.getValue());
    }

    @Test
    void testCreateTransactionOutput_PivxP2PKH() {
        NetworkParameters params = new PivxNetworkParametersLegacy();
        UTXO out = WalletHelper.createTransactionOutput(
                CoinTicker.PIVX, PIVX_P2PKH, 0.2, params);
        assertEquals(PIVX_P2PKH, out.getAddress());
        assertEquals(20_000_000L, out.getValue());
    }

    @Test
    void testCreateTransactionOutput_P2SHRoundTrip() {
        // P2SH vectors constructed from the coin's own params, so the version
        // bytes are definitionally correct for each network.
        assertP2SHRoundTrip(CoinTicker.LITECOIN, new LitecoinNetworkParametersLegacy());
        assertP2SHRoundTrip(CoinTicker.DASHCOIN, new DashcoinNetworkParametersLegacy());
        assertP2SHRoundTrip(CoinTicker.PIVX, new PivxNetworkParametersLegacy());
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
        NetworkParameters params = new BlocknetNetworkParameters();
        String p2pkh = LegacyAddress.fromPubKeyHash(params, new byte[20]).toBase58();
        UTXO out = WalletHelper.createTransactionOutput(
                CoinTicker.BLOCKNET, p2pkh, 0.05, params);
        assertEquals(p2pkh, out.getAddress());
        assertEquals(5_000_000L, out.getValue());
    }
}
