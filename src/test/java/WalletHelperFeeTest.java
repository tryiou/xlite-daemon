import io.cloudchains.app.net.protocols.alqocoin.AlqocoinNetworkParameters;
import io.cloudchains.app.net.protocols.bitbay.BitbayNetworkParameters;
import io.cloudchains.app.net.protocols.bitcoin.BitcoinNetworkParameters;
import io.cloudchains.app.net.protocols.blocknet.BlocknetNetworkParameters;
import io.cloudchains.app.net.protocols.blocknet.BlocknetTestnet5NetworkParameters;
import io.cloudchains.app.net.protocols.dashcoin.DashcoinNetworkParameters;
import io.cloudchains.app.net.protocols.digibyte.DigibyteNetworkParameters;
import io.cloudchains.app.net.protocols.dogecoin.DogecoinNetworkParameters;
import io.cloudchains.app.net.protocols.litecoin.LitecoinNetworkParameters;
import io.cloudchains.app.net.protocols.phorecoin.PhorecoinNetworkParameters;
import io.cloudchains.app.net.protocols.pivx.PivxNetworkParameters;
import io.cloudchains.app.net.protocols.pocketcoin.PocketcoinNetworkParameters;
import io.cloudchains.app.net.protocols.ravencoin.RavencoinNetworkParameters;
import io.cloudchains.app.net.protocols.syscoin.SyscoinNetworkParameters;
import io.cloudchains.app.net.protocols.unobtanium.UnobtaniumNetworkParameters;
import io.cloudchains.app.wallet.WalletHelper;
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
        BitcoinNetworkParameters params = new BitcoinNetworkParameters();
        assertEquals(60L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Litecoin() {
        LitecoinNetworkParameters params = new LitecoinNetworkParameters();
        assertEquals(10L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Blocknet() {
        BlocknetNetworkParameters params = new BlocknetNetworkParameters();
        assertEquals(20L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Dashcoin() {
        DashcoinNetworkParameters params = new DashcoinNetworkParameters();
        assertEquals(5L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Digibyte() {
        DigibyteNetworkParameters params = new DigibyteNetworkParameters();
        assertEquals(200L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Dogecoin() {
        DogecoinNetworkParameters params = new DogecoinNetworkParameters();
        assertEquals(2500L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Syscoin() {
        SyscoinNetworkParameters params = new SyscoinNetworkParameters();
        assertEquals(40L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Pivx() {
        PivxNetworkParameters params = new PivxNetworkParameters();
        assertEquals(20L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Unobtanium() {
        UnobtaniumNetworkParameters params = new UnobtaniumNetworkParameters();
        assertEquals(3L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Pocketcoin() {
        PocketcoinNetworkParameters params = new PocketcoinNetworkParameters();
        assertEquals(20L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Ravencoin() {
        RavencoinNetworkParameters params = new RavencoinNetworkParameters();
        assertEquals(1000L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Alqocoin() {
        AlqocoinNetworkParameters params = new AlqocoinNetworkParameters();
        assertEquals(20L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Bitbay() {
        BitbayNetworkParameters params = new BitbayNetworkParameters();
        assertEquals(100L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_BlocknetTestnet5() {
        BlocknetTestnet5NetworkParameters params = new BlocknetTestnet5NetworkParameters();
        assertEquals(20L, WalletHelper.getFeePerByte(params));
    }

    @Test
    void testGetFeePerByte_Phorecoin() {
        PhorecoinNetworkParameters params = new PhorecoinNetworkParameters();
        assertEquals(20L, WalletHelper.getFeePerByte(params));
    }

    // ========================================================================
    // Happy Path - getMinTxFee() tests
    // ========================================================================

    @Test
    void testGetMinTxFee_Bitcoin() {
        BitcoinNetworkParameters params = new BitcoinNetworkParameters();
        assertEquals(12000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Litecoin() {
        LitecoinNetworkParameters params = new LitecoinNetworkParameters();
        assertEquals(5000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Blocknet() {
        BlocknetNetworkParameters params = new BlocknetNetworkParameters();
        assertEquals(10000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Dashcoin() {
        DashcoinNetworkParameters params = new DashcoinNetworkParameters();
        assertEquals(2500L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Digibyte() {
        DigibyteNetworkParameters params = new DigibyteNetworkParameters();
        assertEquals(100000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Dogecoin() {
        DogecoinNetworkParameters params = new DogecoinNetworkParameters();
        assertEquals(225000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Syscoin() {
        SyscoinNetworkParameters params = new SyscoinNetworkParameters();
        assertEquals(20000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Pivx() {
        PivxNetworkParameters params = new PivxNetworkParameters();
        assertEquals(10000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Unobtanium() {
        UnobtaniumNetworkParameters params = new UnobtaniumNetworkParameters();
        assertEquals(1000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Pocketcoin() {
        PocketcoinNetworkParameters params = new PocketcoinNetworkParameters();
        assertEquals(10000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Ravencoin() {
        RavencoinNetworkParameters params = new RavencoinNetworkParameters();
        assertEquals(100000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Alqocoin() {
        AlqocoinNetworkParameters params = new AlqocoinNetworkParameters();
        assertEquals(10000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Bitbay() {
        BitbayNetworkParameters params = new BitbayNetworkParameters();
        assertEquals(20000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_BlocknetTestnet5() {
        BlocknetTestnet5NetworkParameters params = new BlocknetTestnet5NetworkParameters();
        assertEquals(10000L, WalletHelper.getMinTxFee(params));
    }

    @Test
    void testGetMinTxFee_Phorecoin() {
        PhorecoinNetworkParameters params = new PhorecoinNetworkParameters();
        assertEquals(10000L, WalletHelper.getMinTxFee(params));
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