import io.xlite.daemon.app.net.protocols.bitcoin.BitcoinNetworkParametersLegacy;
import io.xlite.daemon.app.net.protocols.blocknet.BlocknetNetworkParameters;
import io.xlite.daemon.app.net.protocols.blocknet.BlocknetTestnet5NetworkParameters;
import io.xlite.daemon.app.net.protocols.dashcoin.DashcoinNetworkParametersLegacy;
import io.xlite.daemon.app.net.protocols.digibyte.DigibyteNetworkParametersLegacy;
import io.xlite.daemon.app.net.protocols.dogecoin.DogecoinNetworkParametersLegacy;
import io.xlite.daemon.app.net.protocols.litecoin.LitecoinNetworkParametersLegacy;
import io.xlite.daemon.app.net.protocols.pivx.PivxNetworkParametersLegacy;
import io.xlite.daemon.app.net.protocols.pocketcoin.PocketcoinNetworkParametersLegacy;
import io.xlite.daemon.app.net.protocols.ravencoin.RavencoinNetworkParametersLegacy;
import io.xlite.daemon.app.net.protocols.syscoin.SyscoinNetworkParametersLegacy;
import io.xlite.daemon.app.net.protocols.unobtanium.UnobtaniumNetworkParametersLegacy;
import io.xlite.daemon.app.wallet.WalletHelper;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.TestNet3Params;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    // Happy Path - getFeePerByte() tests
    // ========================================================================

    @Test
    void testGetFeePerByte_Bitcoin() {
        BitcoinNetworkParametersLegacy params = new BitcoinNetworkParametersLegacy();
        assertEquals(60L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Litecoin() {
        LitecoinNetworkParametersLegacy params = new LitecoinNetworkParametersLegacy();
        assertEquals(10L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Blocknet() {
        BlocknetNetworkParameters params = new BlocknetNetworkParameters();
        assertEquals(20L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Dashcoin() {
        DashcoinNetworkParametersLegacy params = new DashcoinNetworkParametersLegacy();
        assertEquals(5L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Digibyte() {
        DigibyteNetworkParametersLegacy params = new DigibyteNetworkParametersLegacy();
        assertEquals(200L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Dogecoin() {
        DogecoinNetworkParametersLegacy params = new DogecoinNetworkParametersLegacy();
        assertEquals(2500L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Syscoin() {
        SyscoinNetworkParametersLegacy params = new SyscoinNetworkParametersLegacy();
        assertEquals(40L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Pivx() {
        PivxNetworkParametersLegacy params = new PivxNetworkParametersLegacy();
        assertEquals(20L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Unobtanium() {
        UnobtaniumNetworkParametersLegacy params = new UnobtaniumNetworkParametersLegacy();
        assertEquals(3L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Pocketcoin() {
        PocketcoinNetworkParametersLegacy params = new PocketcoinNetworkParametersLegacy();
        assertEquals(20L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Ravencoin() {
        RavencoinNetworkParametersLegacy params = new RavencoinNetworkParametersLegacy();
        assertEquals(1000L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_BlocknetTestnet5() {
        BlocknetTestnet5NetworkParameters params = new BlocknetTestnet5NetworkParameters();
        assertEquals(20L, WalletHelper.getFeePerByte(params));
    }

    // ========================================================================
    // Happy Path - getMinTxFee() tests
    // ========================================================================

    @Test
    void testGetMinTxFee_Bitcoin() {
        BitcoinNetworkParametersLegacy params = new BitcoinNetworkParametersLegacy();
        assertEquals(12000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Litecoin() {
        LitecoinNetworkParametersLegacy params = new LitecoinNetworkParametersLegacy();
        assertEquals(5000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Blocknet() {
        BlocknetNetworkParameters params = new BlocknetNetworkParameters();
        assertEquals(10000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Dashcoin() {
        DashcoinNetworkParametersLegacy params = new DashcoinNetworkParametersLegacy();
        assertEquals(2500L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Digibyte() {
        DigibyteNetworkParametersLegacy params = new DigibyteNetworkParametersLegacy();
        assertEquals(100000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Dogecoin() {
        DogecoinNetworkParametersLegacy params = new DogecoinNetworkParametersLegacy();
        assertEquals(225000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Syscoin() {
        SyscoinNetworkParametersLegacy params = new SyscoinNetworkParametersLegacy();
        assertEquals(20000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Pivx() {
        PivxNetworkParametersLegacy params = new PivxNetworkParametersLegacy();
        assertEquals(10000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Unobtanium() {
        UnobtaniumNetworkParametersLegacy params = new UnobtaniumNetworkParametersLegacy();
        assertEquals(1000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Pocketcoin() {
        PocketcoinNetworkParametersLegacy params = new PocketcoinNetworkParametersLegacy();
        assertEquals(10000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Ravencoin() {
        RavencoinNetworkParametersLegacy params = new RavencoinNetworkParametersLegacy();
        assertEquals(100000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_BlocknetTestnet5() {
        BlocknetTestnet5NetworkParameters params = new BlocknetTestnet5NetworkParameters();
        assertEquals(10000L, WalletHelper.getMinTxFee(params));
    }

    // ========================================================================
    // Generic path — fee values must match loaded configs
    // ========================================================================

    @Test
    void testGenericFeesMatchLoadedConfigs() {
        // Simple in-memory set — validates logic without filesystem
        java.util.Map<String, io.xlite.daemon.app.coinconfig.CoinConfig> cfgs =
                io.xlite.daemon.app.coinconfig.CoinConfigRegistry.list();
        org.junit.jupiter.api.Assertions.assertFalse(cfgs.isEmpty(), "registry empty — TestHelper must load LTC/BLOCK");
        for (java.util.Map.Entry<String, io.xlite.daemon.app.coinconfig.CoinConfig> e : cfgs.entrySet()) {
            String ticker = e.getKey();
            io.xlite.daemon.app.coinconfig.CoinConfig cfg = e.getValue();
            // Only check migrated tickers that WalletHelper knows via HasFeeParams
            if (!io.xlite.daemon.app.coinconfig.CompiledCoinSupplement.supports(ticker))
                continue;
            io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters generic =
                    io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters.from(cfg);
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
}