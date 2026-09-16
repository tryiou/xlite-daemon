package io.xlite.daemon.app.coinconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates CoinConfigSource logic with simple synthetic sets — no committed
 * fixture, no ../ sibling. Production bcf is validated via gate and cross-check.
 */
class CoinConfigSourceLocalTest {

    private static CoinConfig ltcConfig() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Title", "Litecoin");
        m.put("AddressPrefix", "48");
        m.put("ScriptPrefix", "50");
        m.put("SecretPrefix", "176");
        m.put("COIN", "100000000");
        m.put("FeePerByte", "10");
        m.put("MinTxFee", "5000");
        m.put("Port", "9332");
        m.put("DustAmount", "0");
        m.put("TxVersion", "2");
        return new CoinConfig("LTC", "Litecoin", "litecoin--v0.21.1", m);
    }

    @Test
    void testLitecoinValuesMatchShippedConf() {
        CoinConfig ltc = ltcConfig();
        assertEquals("Litecoin", ltc.getBlockchain());
        assertEquals(48, ltc.addressPrefix());
        assertEquals(50, ltc.scriptPrefix());
        assertEquals(176, ltc.secretPrefix());
        assertEquals(100_000_000L, ltc.coinFactor());
        assertEquals(10L, ltc.feePerByte());
        assertEquals(5000L, ltc.minTxFee());
        assertEquals(9332, ltc.port());
        assertEquals(0L, ltc.dustAmountOrNull());
    }

    @Test
    void testConfEntriesExposeFullRawMap() {
        CoinConfig ltc = ltcConfig();
        assertEquals("Litecoin", ltc.getConfEntries().get("Title"));
        assertTrue(ltc.getConfEntries().containsKey("TxVersion"));
    }

    @Test
    void testLoadsSyntheticManifest(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("xbridge-confs"));
        Files.writeString(dir.resolve("manifest-latest.json"),
                "[{\"blockchain\":\"Litecoin\",\"ticker\":\"LTC\",\"xbridge_conf\":\"ltc.conf\"},"
                        + "{\"blockchain\":\"Blocknet\",\"ticker\":\"BLOCK\",\"xbridge_conf\":\"block.conf\"}]");
        Files.writeString(dir.resolve("xbridge-confs").resolve("ltc.conf"),
                "[LTC]\nTitle=Litecoin\nAddressPrefix=48\nScriptPrefix=50\nSecretPrefix=176\nCOIN=100000000\nFeePerByte=10\nMinTxFee=5000\nPort=9332\nDustAmount=0\n");
        Files.writeString(dir.resolve("xbridge-confs").resolve("block.conf"),
                "[BLOCK]\nTitle=Blocknet\nAddressPrefix=26\nScriptPrefix=28\nSecretPrefix=154\nCOIN=100000000\nFeePerByte=20\nMinTxFee=10000\nPort=41414\n");
        Map<String, CoinConfig> all = new CoinConfigSource(dir.toString()).loadAll();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("LTC"));
        assertTrue(all.containsKey("BLOCK"));
        assertEquals(10L, all.get("LTC").feePerByte());
    }

    @Test
    void testMissingSourceFailsHard() {
        CoinConfigSource bad = new CoinConfigSource(
                Paths.get("..", "no-such-bcf-dir").toAbsolutePath().normalize().toString());
        IllegalStateException e = assertThrows(IllegalStateException.class, bad::loadAll);
        assertTrue(e.getMessage().contains("Cannot read"));
    }

    @Test
    void testManifestEntryWithoutSectionFailsHard(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("xbridge-confs"));
        Files.writeString(dir.resolve("manifest-latest.json"),
                "[{\"blockchain\":\"Fake\",\"ticker\":\"FAKE\","
                        + "\"xbridge_conf\":\"fake--v1.conf\"}]");
        Files.writeString(dir.resolve("xbridge-confs").resolve("fake--v1.conf"),
                "[OTHER]\nAddressPrefix=1\n");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CoinConfigSource(dir.toString()).loadAll());
        assertTrue(e.getMessage().contains("has no [FAKE] section"), e.getMessage());
    }

    @Test
    void testDuplicateManifestTickerKeepsLast(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("xbridge-confs"));
        Files.writeString(dir.resolve("manifest-latest.json"),
                "[{\"blockchain\":\"A\",\"ticker\":\"DUP\",\"xbridge_conf\":\"a.conf\"},"
                        + "{\"blockchain\":\"B\",\"ticker\":\"DUP\",\"xbridge_conf\":\"b.conf\"}]");
        Files.writeString(dir.resolve("xbridge-confs").resolve("a.conf"), "[DUP]\nK=V1\n");
        Files.writeString(dir.resolve("xbridge-confs").resolve("b.conf"), "[DUP]\nK=V2\n");
        Map<String, CoinConfig> all = new CoinConfigSource(dir.toString()).loadAll();
        assertEquals(1, all.size());
        assertEquals("B", all.get("DUP").getBlockchain());
        assertEquals("V2", all.get("DUP").getConfEntries().get("K"));
    }

    @Test
    void testBadRootShapeFailsHardWithManifestContext(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("manifest-latest.json"), "{}");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CoinConfigSource(dir.toString()).loadAll());
        assertTrue(e.getMessage().startsWith("manifest:"), e.getMessage());
        assertTrue(e.getMessage().contains("contracts"), e.getMessage());
    }

    @Test
    void testContractsWrappedManifestAlsoAccepted(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("xbridge-confs"));
        Files.writeString(dir.resolve("manifest-latest.json"),
                "{\"contracts\":[{\"blockchain\":\"Wrap\",\"ticker\":\"WRAP\","
                        + "\"xbridge_conf\":\"w.conf\"}]}");
        Files.writeString(dir.resolve("xbridge-confs").resolve("w.conf"),
                "[WRAP]\nAddressPrefix=7\nFeePerByte=1\nMinTxFee=2\nCOIN=3\nPort=4\nScriptPrefix=5\nSecretPrefix=6\n");
        Map<String, CoinConfig> all = new CoinConfigSource(dir.toString()).loadAll();
        assertEquals(7, all.get("WRAP").addressPrefix());
    }
}
