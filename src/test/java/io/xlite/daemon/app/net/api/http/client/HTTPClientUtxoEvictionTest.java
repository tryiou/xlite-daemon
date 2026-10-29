package io.xlite.daemon.app.net.api.http.client;

import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.util.AddressBalance;
import io.xlite.daemon.app.util.ConfigHelper;
import io.xlite.daemon.app.util.UTXO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Eviction of backend-omitted addresses from the UTXO view.
 *
 * A successful getutxos response that omits a tracked address (or flags it
 * in the backend "errors" array) means the backend holds no unspent outputs
 * for it. Stored rows for such addresses must be dropped: otherwise a stale
 * view re-serves dead outputs with live-looking confirmations indefinitely
 * (live 2026-09-24: hub rejects on just-spent fee/funding inputs the wallet
 * kept listing). Whole-batch failures (null / unparseable) must keep old
 * rows (an outage must never wipe the wallet).
 */
public class HTTPClientUtxoEvictionTest {

    // Checksum-valid BIP39 test vector; only used to derive wallet addresses.
    private static final String MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    private static final String PASSWORD = "eviction-test-password";

    @TempDir
    Path tempDir;

    private CoinInstance coin;
    private String addrA;
    private String addrB;

    /** Canned backend: overrides the package-private transport, no network. */
    static class CannedClient extends HTTPClient {
        String canned;

        CannedClient() {
            super(2);
        }

        @Override
        String executeRequest(String endpoint, com.google.gson.JsonObject params) {
            return canned;
        }
    }

    @BeforeEach
    void setup() {
        ConfigHelper.CONFIG_DIR = tempDir.toString();
        CoinInstance.getCoinInstances().clear();
        CoinInstance.setAddressDiscoveryEnabled(false);
        Map<String, io.xlite.daemon.app.coinconfig.CoinConfig> cfgs = new LinkedHashMap<>();
        Map<String, String> block = new LinkedHashMap<>();
        block.put("AddressPrefix", "26");
        block.put("ScriptPrefix", "28");
        block.put("SecretPrefix", "154");
        block.put("COIN", "100000000");
        block.put("FeePerByte", "20");
        block.put("MinTxFee", "10000");
        block.put("Port", "41414");
        block.put("DustAmount", "0");
        block.put("Title", "Blocknet");
        cfgs.put("BLOCK", new io.xlite.daemon.app.coinconfig.CoinConfig("BLOCK", "Blocknet", "blocknet--v4.2.0", block));
        if (!io.xlite.daemon.app.coinconfig.CoinConfigRegistry.isLoaded()) {
            io.xlite.daemon.app.coinconfig.CoinConfigRegistry.loadForTest(cfgs);
        }
        coin = CoinInstance.getInstance(CoinTicker.BLOCKNET);
        assertNotNull(coin);
        coin.getConfigHelper().setAddressCount(2);
        char[] pw = PASSWORD.toCharArray();
        try {
            assertNull(coin.init(pw, MNEMONIC, false));
        } finally {
            Arrays.fill(pw, '\0');
        }
        List<AddressBalance> pairs = coin.getAddressKeyPairs();
        assertTrue(pairs.size() >= 2);
        addrA = pairs.get(0).getAddress().toBase58();
        addrB = pairs.get(1).getAddress().toBase58();
        assertNotEquals(addrA, addrB);
    }

    @AfterEach
    void teardown() {
        coin.deinit();
        CoinInstance.getCoinInstances().clear();
    }

    // Well-formed 64-hex txids so the getAllUTXOS rendering path parses them
    // (short placeholders are skipped as unparseable and would bypass the
    // end-to-end fetch assertions below).
    private static final String TX_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TX_B =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final double ROW_VALUE = 0.01000599;

    private static double balanceOf(CoinInstance c, String addr) {
        AddressBalance balance = c.getAddressBalance(addr);
        assertNotNull(balance);
        return balance.getBalanceProp();
    }

    private static String utxoRow(String address, String txid, int vout) {
        return "{\"address\":\"" + address + "\",\"txhash\":\"" + txid
                + "\",\"vout\":" + vout + ",\"block_number\":100,\"value\":0.01000599}";
    }

    private static List<String> txidsOf(CoinInstance c, String addr) {
        AddressBalance balance = c.getAddressBalance(addr);
        List<String> out = new ArrayList<>();
        if (balance != null) {
            for (UTXO u : balance.getUtxos()) {
                out.add(u.getTxid());
            }
        }
        return out;
    }

    @Test
    void testOmittedAddressRowsEvictedOnNextSuccessfulFetch() {
        CannedClient client = new CannedClient();
        client.canned = "{\"utxos\":[" + utxoRow(addrA, TX_A, 0) + "," + utxoRow(addrB, TX_B, 0) + "]}";
        assertNotNull(client.getUtxos(CoinTicker.BLOCKNET, 0));
        assertEquals(List.of(TX_A), txidsOf(coin, addrA));
        assertEquals(List.of(TX_B), txidsOf(coin, addrB));
        assertEquals(ROW_VALUE, balanceOf(coin, addrA), 1e-9);
        assertEquals(ROW_VALUE, balanceOf(coin, addrB), 1e-9);

        // Backend now omits addrB: its stored row must go, addrA untouched.
        client.canned = "{\"utxos\":[" + utxoRow(addrA, TX_A, 0) + "]}";
        assertNotNull(client.getUtxos(CoinTicker.BLOCKNET, 0));
        assertEquals(List.of(TX_A), txidsOf(coin, addrA));
        assertTrue(txidsOf(coin, addrB).isEmpty(),
                "backend-omitted address kept stale rows: " + txidsOf(coin, addrB));
        // Eviction must also drop the cached balance: without a recalculation
        // the address keeps reporting its old getbalance after its rows go.
        assertEquals(ROW_VALUE, balanceOf(coin, addrA), 1e-9);
        assertEquals(0.0, balanceOf(coin, addrB), 1e-9);
    }

    @Test
    void testBackendErrorArrayEvictsFlaggedAddress() {
        CannedClient client = new CannedClient();
        client.canned = "{\"utxos\":[" + utxoRow(addrA, TX_A, 0) + "," + utxoRow(addrB, TX_B, 0) + "]}";
        assertNotNull(client.getUtxos(CoinTicker.BLOCKNET, 0));

        // Both present but backend flags addrB failed: addrB rows must go.
        client.canned = "{\"utxos\":[" + utxoRow(addrA, TX_A, 0) + "," + utxoRow(addrB, TX_B, 0) + "],"
                + "\"errors\":[{\"address\":\"" + addrB + "\",\"message\":\"boom\"}]}";
        assertNotNull(client.getUtxos(CoinTicker.BLOCKNET, 0));
        assertEquals(List.of(TX_A), txidsOf(coin, addrA));
        assertTrue(txidsOf(coin, addrB).isEmpty());
        assertEquals(ROW_VALUE, balanceOf(coin, addrA), 1e-9);
        assertEquals(0.0, balanceOf(coin, addrB), 1e-9);
    }

    @Test
    void testWholeBatchFailureKeepsStoredRows() {
        CannedClient client = new CannedClient();
        client.canned = "{\"utxos\":[" + utxoRow(addrA, TX_A, 0) + "]}";
        assertNotNull(client.getUtxos(CoinTicker.BLOCKNET, 0));

        // Backend outage envelope (no "utxos" key): outage must never wipe.
        client.canned = "{\"result\":null,\"error\":{\"code\":-32000,\"message\":\"All addresses failed\"}}";
        assertNull(client.getUtxos(CoinTicker.BLOCKNET, 0));
        assertEquals(List.of(TX_A), txidsOf(coin, addrA));
    }

    @Test
    void testSpentMarkedRowsSurviveEviction() {
        CannedClient client = new CannedClient();
        client.canned = "{\"utxos\":[" + utxoRow(addrA, TX_A, 0) + "," + utxoRow(addrB, TX_B, 0) + "]}";
        assertNotNull(client.getUtxos(CoinTicker.BLOCKNET, 0));
        // Wallet-originated spend marker must survive address eviction.
        for (UTXO u : coin.getAddressBalance(addrB).getUtxos()) {
            u.setSpent(true);
        }
        client.canned = "{\"utxos\":[" + utxoRow(addrA, TX_A, 0) + "]}";
        assertNotNull(client.getUtxos(CoinTicker.BLOCKNET, 0));
        assertEquals(1, coin.getAddressBalance(addrB).getSpentUtxos().size());
        assertTrue(txidsOf(coin, addrB).isEmpty());
    }
}
