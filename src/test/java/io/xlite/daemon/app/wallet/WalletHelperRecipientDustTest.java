package io.xlite.daemon.app.wallet;

import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.util.AddressBalance;
import io.xlite.daemon.app.util.UTXO;
import io.xlite.daemon.app.util.ConfigHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A recipient output below the coin's dust threshold must be refused before the
 * transaction is built.
 *
 * <p>The threshold is the coin's own {@code DustAmount} from the configuration
 * repository, surfaced through {@code getMinNonDustOutput()}. A send of one
 * satoshi on Litecoin used to produce a correctly signed transaction carrying a
 * one-satoshi output: every node rejects it at mempool acceptance, so the caller
 * only learned the amount was unspendable after the broadcast failed.
 */
class WalletHelperRecipientDustTest {

    private static final String TEST_MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon "
                    + "abandon abandon abandon about";
    private static final String TEST_PASSWORD = "test-password";
    private static final int ADDRESS_COUNT = 3;
    /** The fixture's threshold, in Blocknet base units. */
    private static final long DUST = 546;
    @TempDir
    static java.nio.file.Path configDir;
    private static String previousConfigDir;

    @BeforeAll
    static void setup() {
        // Own the config dir: the wallet this test funds must be the one it
        // created, not whatever wallet a previous class left on disk.
        previousConfigDir = ConfigHelper.CONFIG_DIR;
        ConfigHelper.CONFIG_DIR = configDir.toString();
        CoinInstance.setAddressDiscoveryEnabled(false);
        CoinConfigRegistry.resetForTest();

        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("AddressPrefix", "26");
        entries.put("ScriptPrefix", "28");
        entries.put("SecretPrefix", "154");
        entries.put("COIN", "100000000");
        entries.put("FeePerByte", "20");
        entries.put("MinTxFee", "10000");
        entries.put("Port", "41414");
        entries.put("DustAmount", Long.toString(DUST));
        entries.put("Title", "Blocknet");

        Map<String, CoinConfig> cfgs = new LinkedHashMap<>();
        cfgs.put("BLOCK", new CoinConfig("BLOCK", "Blocknet", "blocknet--test", entries));
        CoinConfigRegistry.loadForTest(cfgs);
    }

    @AfterAll
    static void releaseGlobals() {
        ConfigHelper.CONFIG_DIR = previousConfigDir;
        CoinInstance.setAddressDiscoveryEnabled(true);
        CoinConfigRegistry.resetForTest();
    }

    /**
     * Runs the real build path over a funded wallet and reports what it did.
     *
     * <p>The wallet is restored from the BIP-39 all-ones vector, so the address
     * exists without any key material of this test's own. No key is needed
     * either way: the dust guard decides before inputs are selected and signed.
     */
    private static WalletHelper.BuildFailure build(long amountInBaseUnits) {
        CoinInstance coin = CoinInstance.getInstance(CoinTicker.BLOCKNET);
        assertNotNull(coin);
        coin.getConfigHelper().setAddressCount(ADDRESS_COUNT);
        char[] pw = TEST_PASSWORD.toCharArray();
        try {
            assertNull(coin.init(pw, TEST_MNEMONIC, false));
        } finally {
            java.util.Arrays.fill(pw, '\0');
        }

        long coinFactor = CoinConfigRegistry.get("BLOCK").coinFactor();
        AddressBalance addressBalance = coin.getAddressKeyPairs().get(0);
        // Fund it well above the threshold, so the only reason a build can fail
        // is the requested output size.
        coin.processUtxos(Collections.singletonList(new UTXO(CoinTicker.BLOCKNET,
                addressBalance.getAddress().toBase58(),
                "2d05f9436cdfd7e86968649ed76e6c36c1468a21b7203f730adacd52e3ee72ae",
                0, 100, 290_000_000L)));

        WalletHelper.BuildFailure[] failure = new WalletHelper.BuildFailure[1];
        WalletHelper.BuiltTransaction built = WalletHelper.createTransactionSimpleWithFee(
                CoinTicker.BLOCKNET, addressBalance.getAddress().toBase58(),
                (double) amountInBaseUnits / coinFactor, null, failure);
        return built == null ? failure[0] : null;
    }

    @Test
    @DisplayName("one base unit below the threshold is refused as dust")
    void testRecipientOneBelowTheThresholdIsRefused() {
        // Named rather than compared to the enum constant on purpose: this is the
        // `failureReason` string the RPC reports, so pinning the name pins the
        // contract a caller reads.
        WalletHelper.BuildFailure reason = build(DUST - 1);
        assertEquals("DUST_OUTPUT", reason == null ? null : reason.name(),
                "an output below the coin's DustAmount must be refused, not built "
                        + "and then rejected by the network");
    }

    @Test
    @DisplayName("an output exactly at the threshold is allowed")
    void testRecipientAtTheThresholdIsAllowed() {
        // The daemon's charge gate keeps change when it is not strictly less than
        // the threshold, and the node agrees: 5460 is a valid output on Litecoin.
        // The recipient check must use the same comparison, or the boundary would
        // reject an amount the network accepts.
        assertNull(build(DUST),
                "an output equal to the coin's DustAmount is spendable and must be built");
    }
}
