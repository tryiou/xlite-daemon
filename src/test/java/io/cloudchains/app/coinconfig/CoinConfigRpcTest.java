package io.cloudchains.app.coinconfig;

import io.cloudchains.app.net.api.http.master.HTTPServerHandler;
import io.cloudchains.app.util.ConfigHelper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CoinConfigRpcTest {

    private String savedConfigDir;

    private static Map<String, CoinConfig> simpleCfgs() {
        Map<String, CoinConfig> m = new LinkedHashMap<>();
        Map<String, String> ltc = new LinkedHashMap<>();
        ltc.put("AddressPrefix", "48"); ltc.put("ScriptPrefix", "50"); ltc.put("SecretPrefix", "176");
        ltc.put("COIN", "100000000"); ltc.put("FeePerByte", "10"); ltc.put("MinTxFee", "5000");
        ltc.put("Port", "9332"); ltc.put("DustAmount", "0");
        m.put("LTC", new CoinConfig("LTC", "Litecoin", "litecoin--v0.21.1", ltc));
        Map<String, String> block = new LinkedHashMap<>();
        block.put("AddressPrefix", "26"); block.put("ScriptPrefix", "28"); block.put("SecretPrefix", "154");
        block.put("COIN", "100000000"); block.put("FeePerByte", "20"); block.put("MinTxFee", "10000");
        block.put("Port", "41414"); block.put("DustAmount", "0");
        m.put("BLOCK", new CoinConfig("BLOCK", "Blocknet", "blocknet--v4.2.0", block));
        return m;
    }

    @BeforeEach
    void reset() {
        savedConfigDir = ConfigHelper.CONFIG_DIR;
        CoinConfigRegistry.resetForTest();
    }

    @AfterEach
    void resetAfter() {
        CoinConfigRegistry.resetForTest();
        ConfigHelper.CONFIG_DIR = savedConfigDir;
    }

    private JsonObject call(String method, JsonArray params) {
        HTTPServerHandler h = new HTTPServerHandler();
        return h.getResponse(method, params);
    }

    @Test
    public void testNotLoadedReturnsMinusOne(@TempDir Path tmp) {
        ConfigHelper.CONFIG_DIR = tmp.toString();
        JsonObject resp = call("getCoins", new JsonArray());
        assertEquals(-1, resp.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(resp.get("result").isJsonNull());
    }

    @Test
    public void testReturnsFullDtoSorted(@TempDir Path tmp) {
        ConfigHelper.CONFIG_DIR = tmp.toString();
        CoinConfigRegistry.loadForTest(simpleCfgs());
        JsonObject resp = call("getCoins", new JsonArray());
        assertTrue(resp.get("error").isJsonNull());
        JsonArray arr = resp.getAsJsonArray("result");
        assertFalse(arr.isEmpty());
        assertEquals(2, arr.size());
        String prev = "";
        for (int i = 0; i < arr.size(); i++) {
            JsonObject o = arr.get(i).getAsJsonObject();
            for (String k : new String[]{"ticker","blockchain","verId","addressPrefix","scriptPrefix","secretPrefix","coin","feePerByte","minTxFee","port","dustAmount"})
                assertTrue(o.has(k), "missing " + k);
            if (!o.get("dustAmount").isJsonNull())
                assertTrue(o.get("dustAmount").getAsLong() >= 0);
            String t = o.get("ticker").getAsString();
            assertTrue(t.compareTo(prev) >= 0, "not sorted " + prev + ">" + t);
            prev = t;
        }
        JsonObject ltc = null;
        for (int i = 0; i < arr.size(); i++) if ("LTC".equals(arr.get(i).getAsJsonObject().get("ticker").getAsString())) ltc = arr.get(i).getAsJsonObject();
        assertNotNull(ltc);
        assertEquals(48, ltc.get("addressPrefix").getAsInt());
        assertEquals(50, ltc.get("scriptPrefix").getAsInt());
        assertEquals(176, ltc.get("secretPrefix").getAsInt());
        assertEquals(100000000, ltc.get("coin").getAsLong());
        assertEquals(10, ltc.get("feePerByte").getAsLong());
        assertEquals(5000, ltc.get("minTxFee").getAsLong());
        assertEquals(9332, ltc.get("port").getAsInt());
        assertTrue(ltc.get("dustAmount").isJsonNull(), "interim bcf 0 placeholder maps to null until bcf populated");
    }

    @Test
    public void testAliasListCoinsDeepEquality(@TempDir Path tmp) {
        ConfigHelper.CONFIG_DIR = tmp.toString();
        CoinConfigRegistry.loadForTest(simpleCfgs());
        JsonArray a = call("listCoins", new JsonArray()).getAsJsonArray("result");
        JsonArray b = call("getCoins", new JsonArray()).getAsJsonArray("result");
        assertEquals(a.toString(), b.toString());
    }

    @Test
    public void testCaseInsensitive(@TempDir Path tmp) {
        ConfigHelper.CONFIG_DIR = tmp.toString();
        CoinConfigRegistry.loadForTest(simpleCfgs());
        assertTrue(call("GETCOINS", new JsonArray()).get("error").isJsonNull());
        assertTrue(call("ListCoins", new JsonArray()).get("error").isJsonNull());
    }

    @Test
    public void testRejectsParams(@TempDir Path tmp) {
        ConfigHelper.CONFIG_DIR = tmp.toString();
        CoinConfigRegistry.loadForTest(simpleCfgs());
        JsonArray p = new JsonArray(); p.add("extra");
        assertEquals(-1, call("getCoins", p).getAsJsonObject("error").get("code").getAsInt());
        assertEquals(-1, call("getCoins", null).getAsJsonObject("error").get("code").getAsInt());
    }
}
