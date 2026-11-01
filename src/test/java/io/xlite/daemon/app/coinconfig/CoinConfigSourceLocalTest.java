package io.xlite.daemon.app.coinconfig;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates CoinConfigSource logic with simple synthetic sets — no committed fixture, no ../
 * sibling. The gate is unit-tested against synthetic configurations; real production bcf is not
 * read by any test in this repository.
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
        m.put("CreateTxMethod", "BTC");
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
        assertEquals(0L, ltc.dustAmount());
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
        Files.writeString(
                dir.resolve("manifest-latest.json"),
                "[{\"blockchain\":\"Litecoin\",\"ticker\":\"LTC\",\"xbridge_conf\":\"ltc.conf\"},"
                    + "{\"blockchain\":\"Blocknet\",\"ticker\":\"BLOCK\",\"xbridge_conf\":\"block.conf\"}]");
        Files.writeString(
                dir.resolve("xbridge-confs").resolve("ltc.conf"),
                "[LTC]\n"
                        + "Title=Litecoin\n"
                        + "AddressPrefix=48\n"
                        + "ScriptPrefix=50\n"
                        + "SecretPrefix=176\n"
                        + "COIN=100000000\n"
                        + "FeePerByte=10\n"
                        + "MinTxFee=5000\n"
                        + "Port=9332\n"
                        + "DustAmount=0\n"
                        + "TxVersion=1\n");
        Files.writeString(
                dir.resolve("xbridge-confs").resolve("block.conf"),
                "[BLOCK]\n"
                        + "Title=Blocknet\n"
                        + "AddressPrefix=26\n"
                        + "ScriptPrefix=28\n"
                        + "SecretPrefix=154\n"
                        + "COIN=100000000\n"
                        + "FeePerByte=20\n"
                        + "MinTxFee=10000\n"
                        + "Port=41414\n"
                        + "TxVersion=1\n");
        Map<String, CoinConfig> all = new CoinConfigSource(dir.toString()).loadAll();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("LTC"));
        assertTrue(all.containsKey("BLOCK"));
        assertEquals(10L, all.get("LTC").feePerByte());
    }

    @Test
    void testMissingSourceFailsHard() {
        CoinConfigSource bad =
                new CoinConfigSource(
                        Paths.get("..", "no-such-bcf-dir").toAbsolutePath().normalize().toString());
        IllegalStateException e = assertThrows(IllegalStateException.class, bad::loadAll);
        assertTrue(e.getMessage().contains("Cannot read"));
    }

    @Test
    void testManifestEntryWithoutSectionFailsHard(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("xbridge-confs"));
        Files.writeString(
                dir.resolve("manifest-latest.json"),
                "[{\"blockchain\":\"Litecoin\",\"ticker\":\"LTC\","
                        + "\"xbridge_conf\":\"ltc--v1.conf\"}]");
        Files.writeString(
                dir.resolve("xbridge-confs").resolve("ltc--v1.conf"), "[OTHER]\nAddressPrefix=1\n");
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () -> new CoinConfigSource(dir.toString()).loadAll());
        assertTrue(e.getMessage().contains("has no [LTC] section"), e.getMessage());
    }

    @Test
    void testDuplicateManifestTickerKeepsLast(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("xbridge-confs"));
        Files.writeString(
                dir.resolve("manifest-latest.json"),
                "[{\"blockchain\":\"A\",\"ticker\":\"LTC\",\"xbridge_conf\":\"a.conf\"},"
                        + "{\"blockchain\":\"B\",\"ticker\":\"LTC\",\"xbridge_conf\":\"b.conf\"}]");
        Files.writeString(dir.resolve("xbridge-confs").resolve("a.conf"), "[LTC]\nK=V1\n");
        Files.writeString(dir.resolve("xbridge-confs").resolve("b.conf"), "[LTC]\nK=V2\n");
        Map<String, CoinConfig> all = new CoinConfigSource(dir.toString()).loadAll();
        assertEquals(1, all.size());
        assertEquals("B", all.get("LTC").getBlockchain());
        assertEquals("V2", all.get("LTC").getConfEntries().get("K"));
    }

    @Test
    void testBadRootShapeFailsHardWithManifestContext(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("manifest-latest.json"), "{}");
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () -> new CoinConfigSource(dir.toString()).loadAll());
        assertTrue(e.getMessage().startsWith("manifest:"), e.getMessage());
        assertTrue(e.getMessage().contains("contracts"), e.getMessage());
    }

    @Test
    void testContractsWrappedManifestAlsoAccepted(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("xbridge-confs"));
        Files.writeString(
                dir.resolve("manifest-latest.json"),
                "{\"contracts\":[{\"blockchain\":\"Dogecoin\",\"ticker\":\"DOGE\","
                        + "\"xbridge_conf\":\"doge.conf\"}]}");
        Files.writeString(
                dir.resolve("xbridge-confs").resolve("doge.conf"),
                "[DOGE]\n"
                        + "AddressPrefix=30\n"
                        + "FeePerByte=1\n"
                        + "MinTxFee=2\n"
                        + "COIN=3\n"
                        + "Port=4\n"
                        + "ScriptPrefix=22\n"
                        + "SecretPrefix=158\n"
                        + "TxVersion=1\n");
        Map<String, CoinConfig> all = new CoinConfigSource(dir.toString()).loadAll();
        assertEquals(30, all.get("DOGE").addressPrefix());
    }
}
