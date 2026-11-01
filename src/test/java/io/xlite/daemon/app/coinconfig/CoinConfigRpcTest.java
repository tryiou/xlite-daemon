package io.xlite.daemon.app.coinconfig;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.xlite.daemon.app.net.api.http.master.HTTPServerHandler;
import io.xlite.daemon.app.util.ConfigHelper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class CoinConfigRpcTest {

    private String savedConfigDir;

    private static Map<String, CoinConfig> simpleCfgs() {
        Map<String, CoinConfig> m = new LinkedHashMap<>();
        m.put("LTC", ltc());
        m.put("BLOCK", block());
        return m;
    }

    private static CoinConfig ltc() {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("AddressPrefix", "48");
        e.put("ScriptPrefix", "50");
        e.put("SecretPrefix", "176");
        e.put("COIN", "100000000");
        e.put("FeePerByte", "10");
        e.put("MinTxFee", "5000");
        e.put("Port", "9332");
        e.put("DustAmount", "0");
        e.put("TxVersion", "2");
        e.put("CreateTxMethod", "BTC");
        return new CoinConfig("LTC", "Litecoin", "litecoin--v0.21.1", e);
    }

    private static CoinConfig block() {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("AddressPrefix", "26");
        e.put("ScriptPrefix", "28");
        e.put("SecretPrefix", "154");
        e.put("COIN", "100000000");
        e.put("FeePerByte", "20");
        e.put("MinTxFee", "10000");
        e.put("Port", "41414");
        e.put("DustAmount", "0");
        e.put("TxVersion", "1");
        e.put("CreateTxMethod", "BTC");
        return new CoinConfig("BLOCK", "Blocknet", "blocknet--v4.2.0", e);
    }

    @BeforeEach
    void reset() {
        savedConfigDir = ConfigHelper.getConfigDir();
        CoinConfigRegistry.resetForTest();
    }

    @AfterEach
    void resetAfter() {
        CoinConfigRegistry.resetForTest();
        ConfigHelper.setConfigDir(savedConfigDir);
    }

    private JsonObject call(String method, JsonArray params) {
        HTTPServerHandler h = new HTTPServerHandler();
        return h.getResponse(method, params);
    }

    @Test
    public void testNotLoadedReturnsMinusOne(@TempDir Path tmp) {
        ConfigHelper.setConfigDir(tmp.toString());
        JsonObject resp = call("getCoins", new JsonArray());
        assertEquals(-1, resp.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(resp.get("result").isJsonNull());
    }

    @Test
    public void testReturnsFullDtoSorted(@TempDir Path tmp) {
        ConfigHelper.setConfigDir(tmp.toString());
        CoinConfigRegistry.loadForTest(simpleCfgs());
        JsonObject resp = call("getCoins", new JsonArray());
        assertTrue(resp.get("error").isJsonNull());
        JsonArray arr = resp.getAsJsonArray("result");
        assertFalse(arr.isEmpty());
        assertEquals(2, arr.size());
        String previous = "";
        for (int i = 0; i < arr.size(); i++) {
            JsonObject coin = arr.get(i).getAsJsonObject();
            assertFullDto(coin);
            String ticker = coin.get("ticker").getAsString();
            assertTrue(ticker.compareTo(previous) >= 0, "not sorted " + previous + ">" + ticker);
            previous = ticker;
        }
        JsonObject ltc = findByTicker(arr, "LTC");
        assertNotNull(ltc);
        assertEquals(48, ltc.get("addressPrefix").getAsInt());
        assertEquals(50, ltc.get("scriptPrefix").getAsInt());
        assertEquals(176, ltc.get("secretPrefix").getAsInt());
        assertEquals(100000000, ltc.get("coin").getAsLong());
        assertEquals(10, ltc.get("feePerByte").getAsLong());
        assertEquals(5000, ltc.get("minTxFee").getAsLong());
        assertEquals(9332, ltc.get("port").getAsInt());
        assertEquals(
                0,
                ltc.get("dustAmount").getAsLong(),
                "a real 0 (no dust gate) must be passed through, not nulled");
    }

    @Test
    public void testAliasListCoinsDeepEquality(@TempDir Path tmp) {
        ConfigHelper.setConfigDir(tmp.toString());
        CoinConfigRegistry.loadForTest(simpleCfgs());
        JsonArray a = call("listCoins", new JsonArray()).getAsJsonArray("result");
        JsonArray b = call("getCoins", new JsonArray()).getAsJsonArray("result");
        assertEquals(a.toString(), b.toString());
    }

    @Test
    public void testCaseInsensitive(@TempDir Path tmp) {
        ConfigHelper.setConfigDir(tmp.toString());
        CoinConfigRegistry.loadForTest(simpleCfgs());
        assertTrue(call("GETCOINS", new JsonArray()).get("error").isJsonNull());
        assertTrue(call("ListCoins", new JsonArray()).get("error").isJsonNull());
    }

    @Test
    public void testRejectsParams(@TempDir Path tmp) {
        ConfigHelper.setConfigDir(tmp.toString());
        CoinConfigRegistry.loadForTest(simpleCfgs());
        JsonArray p = new JsonArray();
        p.add("extra");
        assertEquals(-1, call("getCoins", p).getAsJsonObject("error").get("code").getAsInt());
        assertEquals(-1, call("getCoins", null).getAsJsonObject("error").get("code").getAsInt());
    }

    private static void assertFullDto(JsonObject coin) {
        for (String key :
                new String[] {
                    "ticker",
                    "blockchain",
                    "verId",
                    "addressPrefix",
                    "scriptPrefix",
                    "secretPrefix",
                    "coin",
                    "feePerByte",
                    "minTxFee",
                    "port",
                    "dustAmount"
                }) {
            assertTrue(coin.has(key), "missing " + key);
        }
        if (!coin.get("dustAmount").isJsonNull()) {
            assertTrue(coin.get("dustAmount").getAsLong() >= 0);
        }
    }

    private static JsonObject findByTicker(JsonArray arr, String ticker) {
        for (int i = 0; i < arr.size(); i++) {
            JsonObject coin = arr.get(i).getAsJsonObject();
            if (ticker.equals(coin.get("ticker").getAsString())) {
                return coin;
            }
        }
        return null;
    }
}
