package io.xlite.daemon.app.util;

import io.xlite.daemon.app.net.CoinTicker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Missing/corrupt coin-config self-healing. The load-bearing guarantees:
 * a missing coin gets a first-run-style enabled config, and a configured
 * coin (even explicitly disabled) is never modified — byte-identical.
 */
class CoinConfigEnsurerTest {

    @TempDir
    Path tmp;

    private String savedConfigDir;

    @BeforeEach
    void setup() {
        savedConfigDir = ConfigHelper.CONFIG_DIR;
        ConfigHelper.CONFIG_DIR = tmp.toString();
    }

    @AfterEach
    void restore() {
        ConfigHelper.CONFIG_DIR = savedConfigDir;
    }

    private File settingsFile(String ticker) {
        File dir = new File(new File(tmp.toFile(), ConfigHelper.DATA_DIR_NAME), "settings");
        dir.mkdirs();
        return new File(dir, "config-" + ticker + ".json");
    }

    @Test
    void testMissingCreatedAndConfiguredUntouched() throws Exception {
        String configured = "{\"rpcEnabled\":false,\"rpcUsername\":\"keep\",\"rpcPassword\":\"keep\","
                + "\"rpcPort\":9332,\"feeperbyte\":10,\"mintxfee\":5000,\"addressCount\":3}";
        Files.write(settingsFile("LTC").toPath(), configured.getBytes(StandardCharsets.UTF_8));

        Map<CoinTicker, CoinConfigEnsurer.Outcome> report = CoinConfigEnsurer.ensureMissing(
                Arrays.asList(CoinTicker.LITECOIN, CoinTicker.VERGE));

        assertEquals(CoinConfigEnsurer.Outcome.CREATED, report.get(CoinTicker.VERGE));
        assertEquals(CoinConfigEnsurer.Outcome.PRESENT_UNTOUCHED, report.get(CoinTicker.LITECOIN));
        // Explicitly-disabled configured coin: byte-identical, still disabled
        assertEquals(configured,
                new String(Files.readAllBytes(settingsFile("LTC").toPath()), StandardCharsets.UTF_8));
        // Fresh coin: enabled with generated credentials
        String fresh = new String(Files.readAllBytes(settingsFile("XVG").toPath()), StandardCharsets.UTF_8);
        assertTrue(fresh.contains("\"rpcEnabled\": true") || fresh.contains("\"rpcEnabled\":true"), fresh);
        assertTrue(fresh.contains("rpcUsername"), fresh);
    }

    @Test
    void testCorruptQuarantinedAndRecreated() throws Exception {
        byte[] garbage = "not-json{{{".getBytes(StandardCharsets.UTF_8);
        Files.write(settingsFile("DOGE").toPath(), garbage);

        Map<CoinTicker, CoinConfigEnsurer.Outcome> report = CoinConfigEnsurer.ensureMissing(
                Arrays.asList(CoinTicker.DOGECOIN));

        assertEquals(CoinConfigEnsurer.Outcome.CORRUPT_QUARANTINED, report.get(CoinTicker.DOGECOIN));
        File dir = settingsFile("DOGE").getParentFile();
        File[] quarantined = dir.listFiles((d, n) -> n.startsWith("config-DOGE.json.corrupt-"));
        assertNotNull(quarantined);
        assertEquals(1, quarantined.length);
        assertArrayEquals(garbage, Files.readAllBytes(quarantined[0].toPath()));
        String fresh = new String(Files.readAllBytes(settingsFile("DOGE").toPath()), StandardCharsets.UTF_8);
        assertTrue(fresh.contains("rpcEnabled"), fresh);
    }

    @Test
    void testEnabledAndPartialConfigsUntouched() throws Exception {
        String enabled = "{\"rpcEnabled\":true,\"rpcUsername\":\"u\",\"rpcPassword\":\"p\","
                + "\"rpcPort\":22555,\"feeperbyte\":10,\"mintxfee\":5000,\"addressCount\":1}";
        Files.write(settingsFile("DOGE").toPath(), enabled.getBytes(StandardCharsets.UTF_8));
        // Valid JSON but missing keys: undecidable intent, must not touch
        String partial = "{\"rpcUsername\":\"partial\"}";
        Files.write(settingsFile("DGB").toPath(), partial.getBytes(StandardCharsets.UTF_8));

        Map<CoinTicker, CoinConfigEnsurer.Outcome> report = CoinConfigEnsurer.ensureMissing(
                Arrays.asList(CoinTicker.DOGECOIN, CoinTicker.DIGIBYTE));

        assertEquals(CoinConfigEnsurer.Outcome.PRESENT_UNTOUCHED, report.get(CoinTicker.DOGECOIN));
        assertEquals(CoinConfigEnsurer.Outcome.PRESENT_UNTOUCHED, report.get(CoinTicker.DIGIBYTE));
        assertEquals(enabled,
                new String(Files.readAllBytes(settingsFile("DOGE").toPath()), StandardCharsets.UTF_8));
        assertEquals(partial,
                new String(Files.readAllBytes(settingsFile("DGB").toPath()), StandardCharsets.UTF_8));
    }

    @Test
    void testEmptyFileRecreated() throws Exception {
        Files.write(settingsFile("RVN").toPath(), "   \n".getBytes(StandardCharsets.UTF_8));

        Map<CoinTicker, CoinConfigEnsurer.Outcome> report = CoinConfigEnsurer.ensureMissing(
                Arrays.asList(CoinTicker.RAVENCOIN));

        assertEquals(CoinConfigEnsurer.Outcome.CREATED, report.get(CoinTicker.RAVENCOIN));
        String fresh = new String(Files.readAllBytes(settingsFile("RVN").toPath()), StandardCharsets.UTF_8);
        assertTrue(fresh.contains("rpcEnabled"), fresh);
    }
    @Test
    void testUnwritableSkippedWithoutThrow() throws Exception {
        // CONFIG_DIR pointing at a regular file: no settings dir can exist
        File blocker = File.createTempFile("ensurer-blocker", ".tmp", tmp.toFile());
        ConfigHelper.CONFIG_DIR = blocker.getAbsolutePath();

        Map<CoinTicker, CoinConfigEnsurer.Outcome> report = CoinConfigEnsurer.ensureMissing(
                Arrays.asList(CoinTicker.VERGE));

        assertEquals(CoinConfigEnsurer.Outcome.SKIPPED, report.get(CoinTicker.VERGE));
    }
}
