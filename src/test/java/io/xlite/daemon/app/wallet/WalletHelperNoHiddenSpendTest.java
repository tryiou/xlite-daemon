package io.xlite.daemon.app.wallet;

import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.util.AddressBalance;
import io.xlite.daemon.app.util.UTXO;
import org.bitcoinj.core.Transaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Building a transaction must not consume the inputs it spends.
 *
 * <p>The send path can stop after building: a dry run signs exactly the bytes
 * a real send would broadcast and then returns them for review, without
 * broadcasting. If building marked the inputs spent, the preview would consume
 * the wallet's funds, exclude those outputs from the next selection and
 * reduce the reported balance -- so the very funds being previewed could no
 * longer be sent. Marking is therefore the caller's decision, taken only after
 * the transaction is committed.
 */
class WalletHelperNoHiddenSpendTest {

    /**
     * The BIP-39 all-ones vector: a published test mnemonic, not a secret, so
     * this fixture needs no key material of its own.
     */
    private static final String TEST_MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon "
                    + "abandon abandon abandon about";
    private static final String TEST_PASSWORD = "test-password";
    private static final int ADDRESS_COUNT = 3;
    /**
     * A throwaway key, imported so the test has an address whose private key is
     * actually held. Nothing here is funded; it exists only to be signed with.
     *
     * <p>The version byte is Blocknet's own dumped-private-key prefix, taken from
     * the fixture config above (SecretPrefix 154). bitcoinj rejects a key whose
     * header is not the network's, so a mainnet-bitcoin WIF will not do.
     */
    private static final String TEST_PRIVATE_KEY_WIF =
            "PnL3o2iyzXWrHprssaN9bktc6q45H6Tnwpa9HCWGWgickUQhsxUt";
    private static String previousConfigDir;

    @BeforeAll
    static void setup() {
        // Same prerequisites every CoinInstance-backed test needs: a config
        // directory, no address discovery (it would race the fixture), and a
        // loaded coin registry.
        previousConfigDir = io.xlite.daemon.app.util.ConfigHelper.CONFIG_DIR;
        io.xlite.daemon.app.util.ConfigHelper.CONFIG_DIR = ".";
        CoinInstance.setAddressDiscoveryEnabled(false);
        if (!io.xlite.daemon.app.coinconfig.CoinConfigRegistry.isLoaded()) {
            java.util.Map<String, String> block = new java.util.LinkedHashMap<>();
            block.put("AddressPrefix", "26");
            block.put("ScriptPrefix", "28");
            block.put("SecretPrefix", "154");
            block.put("COIN", "100000000");
            block.put("FeePerByte", "20");
            block.put("MinTxFee", "10000");
            block.put("Port", "41414");
            block.put("DustAmount", "0");
            block.put("Title", "Blocknet");
            java.util.Map<String, io.xlite.daemon.app.coinconfig.CoinConfig> cfgs =
                    new java.util.LinkedHashMap<>();
            cfgs.put("BLOCK", new io.xlite.daemon.app.coinconfig.CoinConfig(
                    "BLOCK", "Blocknet", "blocknet--test", block));
            io.xlite.daemon.app.coinconfig.CoinConfigRegistry.loadForTest(cfgs);
        }
    }

    /**
     * Both settings are process-global. Left behind, a later test class would
     * read and write under this class's config directory and inherit a registry
     * this class loaded -- and because setup() only loads a registry when none
     * is present, this class would itself silently use whichever fixture ran
     * first instead of its own.
     */
    @AfterAll
    static void releaseGlobals() {
        io.xlite.daemon.app.util.ConfigHelper.CONFIG_DIR = previousConfigDir;
        CoinInstance.setAddressDiscoveryEnabled(true);
        io.xlite.daemon.app.coinconfig.CoinConfigRegistry.resetForTest();
    }

    @Test
    @DisplayName("signing leaves every selected input unspent")
    void testSigningDoesNotMarkInputsSpent() {
        CoinInstance coin = CoinInstance.getInstance(CoinTicker.BLOCKNET);
        assertNotNull(coin);
        coin.getConfigHelper().setAddressCount(ADDRESS_COUNT);
        char[] pw = TEST_PASSWORD.toCharArray();
        try {
            assertNull(coin.init(pw, TEST_MNEMONIC, false));
        } finally {
            Arrays.fill(pw, '\0');
        }

        AddressBalance addressBalance = coin.getAddressKeyPairs().get(0);
        UTXO utxo = new UTXO(CoinTicker.BLOCKNET, addressBalance.getAddress().toBase58(),
                "2d05f9436cdfd7e86968649ed76e6c36c1468a21b7203f730adacd52e3ee72ae",
                0, 100, 290_000_000L);
        addressBalance.getUtxos().add(utxo);

        ArrayList<UTXO> selected = new ArrayList<>();
        selected.add(utxo);

        // Signing requires a transaction with outputs: the chain's preimage
        // covers them, and bitcoinj refuses an output-less transaction.
        Transaction tx = new Transaction(coin.getNetworkParameters());
        tx.addOutput(org.bitcoinj.core.Coin.valueOf(10_000L),
                org.bitcoinj.core.LegacyAddress.fromPubKeyHash(
                        coin.getNetworkParameters(), new byte[20]));
        // Blocknet carries no nTime, so NO_NTIME is the standard signing path.
        Transaction signed = coin.getWalletHelper().signTransactionWithUtxosForTest(
                tx, selected, WalletHelper.NO_NTIME);

        assertNotNull(signed, "signing should have produced a transaction");
        assertFalse(utxo.isSpent(),
                "signing must not mark an input spent; only committing the "
                        + "transaction may do that, or a dry run would consume "
                        + "the funds it is previewing");
    }

    private static void assertNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }

    @Test
    @DisplayName("a transaction handed to the XRouter layer marks its inputs spent")
    void testRawFeeTransactionMarksItsInputs() {
        // The test above pins the negative: signing must not spend, or a dry run
        // would consume the funds it is previewing. This path is the opposite
        // requirement. createRawTransactionWithAllUTXOs builds a transaction and
        // gives it to the XRouter service, which broadcasts it; its inputs must
        // be marked, or the next XR call re-selects and re-signs the same outputs
        // into a second transaction spending them again.
        CoinInstance coin = CoinInstance.getInstance(CoinTicker.BLOCKNET);
        assertNotNull(coin);
        coin.getConfigHelper().setAddressCount(ADDRESS_COUNT);
        char[] pw = TEST_PASSWORD.toCharArray();
        Transaction signed;
        try {
            assertNull(coin.init(pw, TEST_MNEMONIC, false));

            // Signing needs a private key, and the pairs rebuilt from a wallet
            // on disk carry none (only generateAddress/importPrivateKey attach
            // one). Importing is therefore the way to get a pair this test can
            // spend from; a generated address from the same mnemonic would not
            // do, because its key is not in the restored wallet either.
            coin.importPrivateKey(TEST_PRIVATE_KEY_WIF);
            AddressBalance addressBalance = coin.getAddressKeyPairs().stream()
                    .filter(ab -> ab.getPrivateKey() != null)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no tracked address holds a key"));
            UTXO utxo = new UTXO(CoinTicker.BLOCKNET, addressBalance.getAddress().toBase58(),
                    "2d05f9436cdfd7e86968649ed76e6c36c1468a21b7203f730adacd52e3ee72ae",
                    1, 200, 290_000_000L);
            // Through the coin, not through getUtxos(): that accessor returns a
            // filtered copy, so adding to it leaves the wallet's own list
            // untouched and the selector finds nothing. processUtxos is the
            // path a real fetch takes.
            coin.processUtxos(java.util.Collections.singletonList(utxo));
            assertFalse(addressBalance.getUtxos().isEmpty(),
                    "the fixture output must be visible to the selector");

            // A destination output is required: bitcoinj refuses to sign a
            // transaction with none.
            Transaction tx = new Transaction(coin.getNetworkParameters());
            // The input holds 290_000_000 base units, which is 2.90 whole coins
            // for Blocknet. The amount argument is in whole coins and the output
            // in base units, so both are stated for what they are.
            tx.addOutput(org.bitcoinj.core.Coin.valueOf(250_000_000L),
                    org.bitcoinj.core.LegacyAddress.fromPubKeyHash(
                            coin.getNetworkParameters(), new byte[20]));

            signed = coin.getWalletHelper().createRawTransactionWithAllUTXOs(tx, 2.5);
        } finally {
            Arrays.fill(pw, '\0');
        }


        assertNotNull(signed, "the fee transaction must be built for a coin without nTime");

        // The property that matters: the marking happened, and it happened for
        // inputs of a transaction the caller now holds. Read the spent set,
        // because getUtxos() filters spent rows out by design -- a correctly
        // marked input disappearing from it IS the behaviour under test.
        long spentNow = coin.getAddressKeyPairs().stream()
                .mapToLong(ab -> ab.getSpentUtxos().size())
                .sum();
        assertTrue(spentNow > 0,
                "a transaction handed to the service must have marked its inputs, or "
                        + "the next XR call spends the same outputs into a second transaction");

        // And the signed transaction must actually name an input, otherwise
        // "marked something" would pass with nothing having been spent.
        assertFalse(signed.getInputs().isEmpty(),
                "the fee transaction must have at least one input, or there was "
                        + "nothing to mark and this test proves nothing");
    }
}
