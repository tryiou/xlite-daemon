package io.cloudchains.app.coinconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link CoinConfigSource} against the workspace's real
 * blockchain-configuration-files checkout — the same data the daemon will
 * consume in production, without any network. The workspace layout is a
 * deliberate precondition: this daemon is developed against that sibling
 * checkout, so its absence is an error, not a skip.
 */
class CoinConfigSourceLocalTest {

    private static final Path BCF = Paths.get(
            "..", "blockchain-configuration-files");

    private Map<String, CoinConfig> loadAll() {
        return new CoinConfigSource(BCF.toAbsolutePath().normalize().toString()).loadAll();
    }

    @Test
    void testLoadsEveryManifestCoin() {
        Map<String, CoinConfig> all = loadAll();
        assertFalse(all.isEmpty());
        assertTrue(all.containsKey("LTC"), "LTC must be present");
        assertTrue(all.containsKey("BLOCK"), "BLOCK must be present");
    }

    @Test
    void testLitecoinValuesMatchShippedConf() {
        CoinConfig ltc = loadAll().get("LTC");
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
        CoinConfig ltc = loadAll().get("LTC");
        assertEquals("Litecoin", ltc.getConfEntries().get("Title"));
        assertTrue(ltc.getConfEntries().containsKey("TxVersion"));
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
        // Historical manifests (remote master) contain multiple entries per ticker;
        // the loader keeps the last occurrence (latest version).
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
