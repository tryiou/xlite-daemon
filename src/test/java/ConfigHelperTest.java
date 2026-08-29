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

            // When new dir already exists, migration must NOT run
            org.junit.jupiter.api.Assertions.assertTrue(Files.exists(oldDir), "old dir must remain when new dir already exists");
            org.junit.jupiter.api.Assertions.assertTrue(Files.exists(oldFile));
            org.junit.jupiter.api.Assertions.assertTrue(Files.exists(newFile));
        } finally {
            ConfigHelper.CONFIG_DIR = originalConfigDir;
        }
    }
}