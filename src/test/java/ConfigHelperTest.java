import io.xlite.daemon.app.util.ConfigHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for ConfigHelper functionality.
 * Tests configuration file operations, address count management, and directory handling.
 */
class ConfigHelperTest extends TestHelper {

    private ConfigHelper configHelper;

    @BeforeEach
    void setup() {
        commonSetup();
        configHelper = new ConfigHelper("test");
    }

    @AfterAll
    static void cleanup() {
        commonCleanup();
    }

    @Test
    void testConfigFileCreation() {
        // The config file should be created when ConfigHelper is instantiated
        // We can verify this by checking if the config directory exists and has files
        String localDataDir = ConfigHelper.getLocalDataDirectory();
        File settingsDir = new File(localDataDir, "settings");
        assertTrue(settingsDir.exists());

        // Check if config file exists by trying to read it
        File configFile = new File(settingsDir, "config-test.json");
        assertTrue(configFile.exists());
    }

    @Test
    void testDefaultConfigurationValues() {
        assertEquals(0L, configHelper.getFeePerByte());
        assertEquals(0L, configHelper.getMinTxFee());
        assertFalse(configHelper.isRpcEnabled());
        assertEquals("", configHelper.getRpcUsername());
        assertEquals("", configHelper.getRpcPassword());
        assertEquals(-1000, configHelper.getRpcPort());
        assertEquals(0, configHelper.getAddressCount());
    }

    @Test
    void testSetAndGetFeePerByte() {
        long newFeePerByte = 60L;
        configHelper.setFeePerByte(newFeePerByte);
        configHelper.writeConfig();

        ConfigHelper reloadedConfig = new ConfigHelper("test");
        assertEquals(newFeePerByte, reloadedConfig.getFeePerByte());
    }

    @Test
    void testSetAndGetMinTxFee() {
        long newMinTxFee = 12000L;
        configHelper.setMinTxFee(newMinTxFee);
        configHelper.writeConfig();

        ConfigHelper reloadedConfig = new ConfigHelper("test");
        assertEquals(newMinTxFee, reloadedConfig.getMinTxFee());
    }

    @Test
    void testSetAndGetRpcEnabled() {
        configHelper.setRpcEnabled(true);
        configHelper.writeConfig();

        ConfigHelper reloadedConfig = new ConfigHelper("test");
        assertTrue(reloadedConfig.isRpcEnabled());
    }

    @Test
    void testSetAndGetRpcCredentials() {
        String username = "testuser";
        String password = "testpass";

        configHelper.setRpcUsername(username);
        configHelper.setRpcPassword(password);
        configHelper.writeConfig();

        ConfigHelper reloadedConfig = new ConfigHelper("test");
        assertEquals(username, reloadedConfig.getRpcUsername());
        assertEquals(password, reloadedConfig.getRpcPassword());
    }

    @Test
    void testSetAndGetRpcPort() {
        int port = 8080;
        configHelper.setRpcPort(port);
        configHelper.writeConfig();

        ConfigHelper reloadedConfig = new ConfigHelper("test");
        assertEquals(port, reloadedConfig.getRpcPort());
    }

    @Test
    void testSetAndGetAddressCount() {
        int addressCount = 50;
        configHelper.setAddressCount(addressCount);
        configHelper.writeConfig();

        ConfigHelper reloadedConfig = new ConfigHelper("test");
        assertEquals(addressCount, reloadedConfig.getAddressCount());
    }

    @Test
    void testValidAuth() {
        assertFalse(configHelper.validAuth());

        configHelper.setRpcUsername("user");
        configHelper.setRpcPassword("pass");
        configHelper.writeConfig();

        ConfigHelper reloadedConfig = new ConfigHelper("test");
        assertTrue(reloadedConfig.validAuth());
    }

    @Test
    void testInvalidAuthWithEmptyUsername() {
        configHelper.setRpcUsername("");
        configHelper.setRpcPassword("pass");
        configHelper.writeConfig();

        ConfigHelper reloadedConfig = new ConfigHelper("test");
        assertFalse(reloadedConfig.validAuth());
    }

    @Test
    void testInvalidAuthWithEmptyPassword() {
        configHelper.setRpcUsername("user");
        configHelper.setRpcPassword("");
        configHelper.writeConfig();

        ConfigHelper reloadedConfig = new ConfigHelper("test");
        assertFalse(reloadedConfig.validAuth());
    }

    @Test
    void testConfigDirectoryCreation() {
        String localDataDir = ConfigHelper.getLocalDataDirectory();
        assertNotNull(localDataDir);

        File settingsDir = new File(localDataDir, "settings");
        assertTrue(settingsDir.exists() || settingsDir.mkdirs());
    }

    @Test
    void testConfigFilePersistence() {
        configHelper.setFeePerByte(60L);
        configHelper.setMinTxFee(12000L);
        configHelper.setRpcEnabled(true);
        configHelper.setAddressCount(100);
        configHelper.writeConfig();

        ConfigHelper newConfig = new ConfigHelper("test");
        assertEquals(60L, newConfig.getFeePerByte());
        assertEquals(12000L, newConfig.getMinTxFee());
        assertTrue(newConfig.isRpcEnabled());
        assertEquals(100, newConfig.getAddressCount());
    }

    @Test
    void testLoadConfigFromFile() {
        String configContent = "{\n" +
                "    \"feeperbyte\": 60,\n" +
                "    \"mintxfee\": 12000,\n" +
                "    \"rpcEnabled\": true,\n" +
                "    \"rpcUsername\": \"testuser\",\n" +
                "    \"rpcPassword\": \"testpass\",\n" +
                "    \"rpcPort\": 9000,\n" +
                "    \"addressCount\": 25\n" +
                "}";

        String localDataDir = ConfigHelper.getLocalDataDirectory();
        File settingsDir = new File(localDataDir, "settings");
        File configFile = new File(settingsDir, "config-test.json");

        try {
            Files.write(configFile.toPath(), configContent.getBytes());
        } catch (IOException e) {
            fail("Failed to write config file", e);
        }

        ConfigHelper loadedConfig = new ConfigHelper("test");

        assertEquals(60L, loadedConfig.getFeePerByte());
        assertEquals(12000L, loadedConfig.getMinTxFee());
        assertTrue(loadedConfig.isRpcEnabled());
        assertEquals("testuser", loadedConfig.getRpcUsername());
        assertEquals("testpass", loadedConfig.getRpcPassword());
        assertEquals(9000, loadedConfig.getRpcPort());
        assertEquals(25, loadedConfig.getAddressCount());
    }

    @Test
    void testMigration_movesLegacyCloudChainsToXliteDaemon(@TempDir Path tmp) throws IOException {
        // Arrange: create legacy CloudChains directory with a settings file
        String originalConfigDir = ConfigHelper.CONFIG_DIR;
        try {
            ConfigHelper.CONFIG_DIR = tmp.toString();
            Path oldDir = tmp.resolve("CloudChains");
            Path newDir = tmp.resolve("xlite-daemon");
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(newDir), "new dir must not exist before migration");
            Files.createDirectories(oldDir.resolve("settings"));
            Path legacyFile = oldDir.resolve("settings").resolve("config-legacy.json");
            String content = "{\"feeperbyte\":1}";
            Files.write(legacyFile, content.getBytes(StandardCharsets.UTF_8));

            // Act: trigger migration via getLocalDataDirectory
            String returned = ConfigHelper.getLocalDataDirectory();
            org.junit.jupiter.api.Assertions.assertTrue(returned.endsWith("xlite-daemon" + File.separator));

            // Assert: legacy content migrated to new location
            Path migratedFile = newDir.resolve("settings").resolve("config-legacy.json");
            org.junit.jupiter.api.Assertions.assertTrue(Files.exists(newDir), "xlite-daemon dir must exist after migration");
            org.junit.jupiter.api.Assertions.assertTrue(Files.exists(migratedFile), "legacy file must be migrated to new dir");
            assertEquals(content, new String(Files.readAllBytes(migratedFile), StandardCharsets.UTF_8));
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(oldDir), "old CloudChains dir must be moved (not remain)");
        } finally {
            ConfigHelper.CONFIG_DIR = originalConfigDir;
            // Cleanup temp-data dirs created via CONFIG_DIR override so commonCleanup().deleteDir
            // on the default "." path still succeeds; remove any leftover tmp subdirs eagerly.
            // JUnit @TempDir will delete tmp itself, but ensure CONFIG_DIR restored.
        }
    }

    @Test
    void testMigration_doesNotOverwriteExistingNewDir(@TempDir Path tmp) throws IOException {
        String originalConfigDir = ConfigHelper.CONFIG_DIR;
        try {
            ConfigHelper.CONFIG_DIR = tmp.toString();
            Path oldDir = tmp.resolve("CloudChains");
            Path newDir = tmp.resolve("xlite-daemon");
            Files.createDirectories(oldDir.resolve("settings"));
            Files.createDirectories(newDir.resolve("settings"));
            Path oldFile = oldDir.resolve("settings").resolve("config-old.json");
            Path newFile = newDir.resolve("settings").resolve("config-new.json");
            Files.write(oldFile, "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
            Files.write(newFile, "{\"b\":2}".getBytes(StandardCharsets.UTF_8));

            ConfigHelper.getLocalDataDirectory();

            // Fail-closed merge: disjoint legacy files are merged in, never
            // orphaned, and the legacy dir is archived (not left live).
            Path mergedFile = newDir.resolve("settings").resolve("config-old.json");
            org.junit.jupiter.api.Assertions.assertTrue(Files.exists(mergedFile),
                    "disjoint legacy file must be merged into new dir");
            assertEquals("{\"a\":1}", new String(Files.readAllBytes(mergedFile), StandardCharsets.UTF_8));
            org.junit.jupiter.api.Assertions.assertTrue(Files.exists(newFile), "existing new file must be kept");
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(oldDir), "legacy dir must be archived, not left live");
            org.junit.jupiter.api.Assertions.assertTrue(Files.exists(tmp.resolve("CloudChains.bak")),
                    "legacy dir must be preserved as backup");
        } finally {
            ConfigHelper.CONFIG_DIR = originalConfigDir;
        }
    }

    @Test
    void testMigration_resumesPartialNewDir(@TempDir Path tmp) throws IOException {
        String originalConfigDir = ConfigHelper.CONFIG_DIR;
        try {
            ConfigHelper.CONFIG_DIR = tmp.toString();
            Path oldDir = tmp.resolve("CloudChains");
            Path newDir = tmp.resolve("xlite-daemon");
            Files.createDirectories(oldDir.resolve("settings"));
            Files.write(oldDir.resolve("settings").resolve("config-a.json"),
                    "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
            Files.write(oldDir.resolve("settings").resolve("config-b.json"),
                    "{\"b\":2}".getBytes(StandardCharsets.UTF_8));
            // Simulate an interrupted migration: only one file made it over.
            Files.createDirectories(newDir.resolve("settings"));
            Files.write(newDir.resolve("settings").resolve("config-a.json"),
                    "{\"a\":1}".getBytes(StandardCharsets.UTF_8));

            ConfigHelper.getLocalDataDirectory();

            // Must complete the migration, never boot on the partial dir.
            Path resumed = newDir.resolve("settings").resolve("config-b.json");
            org.junit.jupiter.api.Assertions.assertTrue(Files.exists(resumed),
                    "interrupted migration must resume and complete");
            assertEquals("{\"b\":2}", new String(Files.readAllBytes(resumed), StandardCharsets.UTF_8));
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(oldDir), "legacy dir must be archived after resume");
        } finally {
            ConfigHelper.CONFIG_DIR = originalConfigDir;
        }
    }

    @Test
    void testMigration_conflictingFilesThrowFailClosed(@TempDir Path tmp) throws IOException {
        String originalConfigDir = ConfigHelper.CONFIG_DIR;
        try {
            ConfigHelper.CONFIG_DIR = tmp.toString();
            Path oldDir = tmp.resolve("CloudChains");
            Path newDir = tmp.resolve("xlite-daemon");
            Files.createDirectories(oldDir.resolve("settings"));
            Files.createDirectories(newDir.resolve("settings"));
            // Same relative path, different bytes: no safe automatic choice
            // (either side could be the real wallet), so refuse to boot.
            Files.write(oldDir.resolve("settings").resolve("config-c.json"),
                    "{\"c\":1}".getBytes(StandardCharsets.UTF_8));
            Files.write(newDir.resolve("settings").resolve("config-c.json"),
                    "{\"c\":2}".getBytes(StandardCharsets.UTF_8));
            // Plus a disjoint file: refusal must be side-effect-free, so even
            // mergeable files must NOT be copied before the throw.
            Files.write(oldDir.resolve("settings").resolve("config-d.json"),
                    "{\"d\":1}".getBytes(StandardCharsets.UTF_8));

            assertThrows(IllegalStateException.class, ConfigHelper::getLocalDataDirectory);
            org.junit.jupiter.api.Assertions.assertFalse(
                    Files.exists(newDir.resolve("settings").resolve("config-d.json")),
                    "refusal must leave the live dir untouched");
        } finally {
            ConfigHelper.CONFIG_DIR = originalConfigDir;
        }
    }

    @Test
    void testMigration_uncreatableDataDirThrowsFailClosed(@TempDir Path tmp) throws IOException {
        String originalConfigDir = ConfigHelper.CONFIG_DIR;
        try {
            // Base is a regular file: no directory can ever be created beneath it.
            Path blocker = tmp.resolve("blocker");
            Files.write(blocker, "x".getBytes(StandardCharsets.UTF_8));
            ConfigHelper.CONFIG_DIR = blocker.toString();

            // Must fail loudly, never return a bogus path and boot empty.
            assertThrows(IllegalStateException.class, ConfigHelper::getLocalDataDirectory);
        } finally {
            ConfigHelper.CONFIG_DIR = originalConfigDir;
        }
    }

    @Test
    void testMigration_blankBaseDirThrowsFailClosed(@TempDir Path tmp) {
        String originalConfigDir = ConfigHelper.CONFIG_DIR;
        try {
            // Whitespace-only override must fail fast. (A null CONFIG_DIR is
            // treated exactly like the default empty string and resolves via
            // App.getUserConfigDir, so it cannot be asserted hermetically.)
            ConfigHelper.CONFIG_DIR = "   ";

            assertThrows(IllegalStateException.class, ConfigHelper::getLocalDataDirectory);
        } finally {
            ConfigHelper.CONFIG_DIR = originalConfigDir;
        }
    }

    @Test
    void testMigration_existingBackupRefusesToBoot(@TempDir Path tmp) throws IOException {
        String originalConfigDir = ConfigHelper.CONFIG_DIR;
        try {
            ConfigHelper.CONFIG_DIR = tmp.toString();
            Path oldDir = tmp.resolve("CloudChains");
            Path newDir = tmp.resolve("xlite-daemon");
            Files.createDirectories(oldDir.resolve("settings"));
            Files.createDirectories(newDir.resolve("settings"));
            Files.write(oldDir.resolve("settings").resolve("config-old.json"),
                    "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
            Files.write(newDir.resolve("settings").resolve("config-new.json"),
                    "{\"b\":2}".getBytes(StandardCharsets.UTF_8));
            // A previous archival already parked a backup here: archiving
            // again would destroy rollback data, so refuse instead.
            Files.createDirectories(tmp.resolve("CloudChains.bak"));

            assertThrows(IllegalStateException.class, ConfigHelper::getLocalDataDirectory);
            // Nothing was archived over or deleted, and the refused merge
            // copied nothing: the legacy source is intact for manual recovery.
            org.junit.jupiter.api.Assertions.assertTrue(Files.exists(oldDir));
            org.junit.jupiter.api.Assertions.assertFalse(
                    Files.exists(newDir.resolve("settings").resolve("config-old.json")),
                    "refusal must leave the live dir untouched");
        } finally {
            ConfigHelper.CONFIG_DIR = originalConfigDir;
        }
    }

    @Test
    void testMigration_stagingLeftoverIsRedone(@TempDir Path tmp) throws IOException {
        String originalConfigDir = ConfigHelper.CONFIG_DIR;
        try {
            ConfigHelper.CONFIG_DIR = tmp.toString();
            Path oldDir = tmp.resolve("CloudChains");
            Path newDir = tmp.resolve("xlite-daemon");
            Path staging = tmp.resolve("xlite-daemon.migrating");
            Files.createDirectories(oldDir.resolve("settings"));
            Files.write(oldDir.resolve("settings").resolve("config-a.json"),
                    "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
            // Leftover of an interrupted copy-verify-rename: stale junk only.
            Files.createDirectories(staging);
            Files.write(staging.resolve("junk.tmp"), "stale".getBytes(StandardCharsets.UTF_8));

            ConfigHelper.getLocalDataDirectory();

            // Redone from the intact source: junk gone, content verified.
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(staging.resolve("junk.tmp")),
                    "stale staging content must not leak into the data dir");
            Path migrated = newDir.resolve("settings").resolve("config-a.json");
            org.junit.jupiter.api.Assertions.assertTrue(Files.exists(migrated));
            assertEquals("{\"a\":1}", new String(Files.readAllBytes(migrated), StandardCharsets.UTF_8));
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(oldDir), "legacy dir must be archived after redo");
        } finally {
            ConfigHelper.CONFIG_DIR = originalConfigDir;
        }
    }

    @Test
    void testSettingsAsFileFailsClosed(@TempDir Path tmp) throws IOException {
        String originalConfigDir = ConfigHelper.CONFIG_DIR;
        try {
            ConfigHelper.CONFIG_DIR = tmp.toString();
            // Poison the settings path with a regular file: every config
            // write below it would fail, so construction must refuse.
            Path dataDir = tmp.resolve("xlite-daemon");
            Files.createDirectories(dataDir);
            Files.write(dataDir.resolve("settings"), "not-a-dir".getBytes(StandardCharsets.UTF_8));

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new ConfigHelper("test"));
            org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("data directory unusable"),
                    "message must name the failure, got: " + e.getMessage());
        } finally {
            ConfigHelper.CONFIG_DIR = originalConfigDir;
        }
    }
}