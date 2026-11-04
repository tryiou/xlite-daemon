package io.xlite.daemon.app.net.api.http.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.electrumx.ElectrumApi;
import io.xlite.daemon.app.net.electrumx.ScriptHashes;
import io.xlite.daemon.app.util.AddressBalance;
import io.xlite.daemon.app.util.ConfigHelper;
import io.xlite.daemon.app.util.UTXO;

import org.bitcoinj.core.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Eviction of provider-omitted addresses from the UTXO view.
 *
 * <p>{@code processUtxos} only clears addresses present in a response, so an address the provider
 * reports as holding nothing must be evicted explicitly. Without that a spent output keeps showing
 * live-looking confirmations forever. A whole-call failure must instead keep the stored rows: an
 * outage must never wipe the wallet.
 */
public class HTTPClientUtxoEvictionTest {

    // Checksum-valid BIP39 test vector; only used to derive wallet addresses.
    private static final String MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"
                    + " abandon about";
    private static final String PASSWORD = "eviction-test-password";
    private static final String TX_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TX_B =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final long ROW_VALUE = 1000599;

    @TempDir Path tempDir;

    private CoinInstance coin;
    private String addrA;
    private String addrB;
    private FakeApi api;

    /** One native session: per-scripthash unspent rows, no network. */
    static final class FakeApi implements ElectrumApi {
        final Map<String, JsonArray> rows = new HashMap<>();
        boolean failing;

        @Override
        public void setListener(io.xlite.daemon.app.net.electrumx.ElectrumClient.Listener l) {}

        @Override
        public void start() {}

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public CompletableFuture<JsonElement> request(String method, JsonArray params) {
            CompletableFuture<JsonElement> result = new CompletableFuture<>();
            if (failing) {
                result.completeExceptionally(new IllegalStateException("provider down"));
                return result;
            }
            result.complete(rows.getOrDefault(params.get(0).getAsString(), new JsonArray()));
            return result;
        }

        @Override
        public List<CompletableFuture<JsonElement>> batch(List<Call> calls) {
            List<CompletableFuture<JsonElement>> futures = new ArrayList<>();
            for (Call call : calls) {
                futures.add(request(call.method(), call.params()));
            }
            return futures;
        }

        @Override
        public void close() {}
    }

    static final class FakeClient extends HTTPClient {
        private final FakeApi api;

        FakeClient(FakeApi api) {
            this.api = api;
        }

        @Override
        ElectrumApi api(CoinTicker ticker) {
            return api;
        }
    }

    @BeforeEach
    void setup() {
        ConfigHelper.setConfigDir(tempDir.toString());
        CoinInstance.getCoinInstances().clear();
        CoinInstance.setAddressDiscoveryEnabled(false);
        installCoinConfig();
        coin = CoinInstance.getInstance(CoinTicker.BLOCKNET);
        assertNotNull(coin);
        coin.getConfigHelper().setAddressCount(2);
        startWallet(coin);
        List<AddressBalance> pairs = coin.getAddressKeyPairs();
        assertTrue(pairs.size() >= 2);
        addrA = pairs.get(0).getAddress().toBase58();
        addrB = pairs.get(1).getAddress().toBase58();
        assertNotEquals(addrA, addrB);
        api = new FakeApi();
    }

    private void installCoinConfig() {
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
        block.put("TxVersion", "1");
        block.put("CreateTxMethod", "BTC");
        block.put("Title", "Blocknet");
        cfgs.put(
                "BLOCK",
                new io.xlite.daemon.app.coinconfig.CoinConfig(
                        "BLOCK", "Blocknet", "blocknet--v4.2.0", block));
        if (!io.xlite.daemon.app.coinconfig.CoinConfigRegistry.isLoaded()) {
            io.xlite.daemon.app.coinconfig.CoinConfigRegistry.loadForTest(cfgs);
        }
    }

    private static void startWallet(CoinInstance coin) {
        char[] password = PASSWORD.toCharArray();
        try {
            assertNull(coin.init(password, MNEMONIC, false));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @AfterEach
    void teardown() {
        coin.deinit();
        CoinInstance.getCoinInstances().clear();
    }

    /** Publish the unspent rows for one address, keyed by its native script hash. */
    private void setRows(String address, String txid, int vout) {
        JsonArray entries = new JsonArray();
        if (txid != null) {
            JsonObject row = new JsonObject();
            row.addProperty("tx_hash", txid);
            row.addProperty("tx_pos", vout);
            row.addProperty("height", 100);
            row.addProperty("value", ROW_VALUE);
            entries.add(row);
        }
        api.rows.put(scripthash(address), entries);
    }

    private String scripthash(String address) {
        return ScriptHashes.scripthash(Address.fromString(coin.getNetworkParameters(), address));
    }

    private static double balanceOf(CoinInstance c, String addr) {
        AddressBalance balance = c.getAddressBalance(addr);
        assertNotNull(balance);
        return balance.getBalanceProp();
    }

    private static List<String> txidsOf(CoinInstance c, String addr) {
        AddressBalance balance = c.getAddressBalance(addr);
        List<String> out = new ArrayList<>();
        if (balance != null) {
            for (UTXO utxo : balance.getUtxos()) {
                out.add(utxo.getTxid());
            }
        }
        return out;
    }

    @Test
    void omittedAddressRowsAreEvictedOnNextFetch() {
        FakeClient client = new FakeClient(api);
        setRows(addrA, TX_A, 0);
        setRows(addrB, TX_B, 0);
        assertNotNull(client.getUtxos(CoinTicker.BLOCKNET, 0));
        assertEquals(List.of(TX_A), txidsOf(coin, addrA));
        assertEquals(List.of(TX_B), txidsOf(coin, addrB));
        assertEquals(0.01000599, balanceOf(coin, addrA), 1e-9);

        setRows(addrB, null, 0);
        assertNotNull(client.getUtxos(CoinTicker.BLOCKNET, 0));
        assertEquals(List.of(TX_A), txidsOf(coin, addrA));
        assertTrue(
                txidsOf(coin, addrB).isEmpty(),
                "provider-omitted address kept stale rows: " + txidsOf(coin, addrB));
        assertEquals(0.01000599, balanceOf(coin, addrA), 1e-9);
        assertEquals(0.0, balanceOf(coin, addrB), 1e-9);
    }

    @Test
    void wholeCallFailureKeepsStoredRows() {
        FakeClient client = new FakeClient(api);
        setRows(addrA, TX_A, 0);
        assertNotNull(client.getUtxos(CoinTicker.BLOCKNET, 0));
        api.failing = true;
        assertNull(client.getUtxos(CoinTicker.BLOCKNET, 0));
        assertEquals(List.of(TX_A), txidsOf(coin, addrA));
    }

    @Test
    void spentMarkedRowsSurviveEviction() {
        FakeClient client = new FakeClient(api);
        setRows(addrA, TX_A, 0);
        setRows(addrB, TX_B, 0);
        assertNotNull(client.getUtxos(CoinTicker.BLOCKNET, 0));
        for (UTXO utxo : coin.getAddressBalance(addrB).getUtxos()) {
            utxo.setSpent(true);
        }
        setRows(addrB, null, 0);
        assertNotNull(client.getUtxos(CoinTicker.BLOCKNET, 0));
        assertEquals(1, coin.getAddressBalance(addrB).getSpentUtxos().size());
        assertTrue(txidsOf(coin, addrB).isEmpty());
    }

    @Test
    void unspentRowsCarryTheRpcSurfaceFields() {
        FakeClient client = new FakeClient(api);
        setRows(addrA, TX_A, 1);
        JsonArray rows = client.getUtxosUncached(CoinTicker.BLOCKNET, new String[] {addrA});
        assertNotNull(rows);
        assertEquals(1, rows.size());
        JsonObject row = rows.get(0).getAsJsonObject();
        assertEquals(TX_A, row.get("txid").getAsString());
        assertEquals(1, row.get("vout").getAsInt());
        assertEquals(addrA, row.get("address").getAsString());
        assertEquals(0.01000599, row.get("value").getAsDouble(), 1e-9);
        assertTrue(row.get("spendable").getAsBoolean());
        assertTrue(row.has("scriptPubKey"));
        assertTrue(row.has("confirmations"));
    }
}
