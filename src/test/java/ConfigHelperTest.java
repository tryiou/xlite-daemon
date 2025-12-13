import io.cloudchains.app.util.ConfigHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

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
        assertEquals(0.0001, configHelper.getFee());
        assertTrue(configHelper.isFlatFee());
        assertFalse(configHelper.isRpcEnabled());
        assertEquals("", configHelper.getRpcUsername());
        assertEquals("", configHelper.getRpcPassword());
        assertEquals(-1000, configHelper.getRpcPort());
        assertEquals(0, configHelper.getAddressCount());
    }

    @Test
    void testSetAndGetFee() {
        double newFee = 0.001;
        configHelper.setFee(newFee);
        configHelper.writeConfig();

        ConfigHelper reloadedConfig = new ConfigHelper("test");
        assertEquals(newFee, reloadedConfig.getFee());
    }

    @Test
    void testSetAndGetFlatFee() {
        configHelper.setFlatFee(false);
        configHelper.writeConfig();

        ConfigHelper reloadedConfig = new ConfigHelper("test");
        assertFalse(reloadedConfig.isFlatFee());
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
        // Set some values
        configHelper.setFee(0.005);
        configHelper.setFlatFee(false);
        configHelper.setRpcEnabled(true);
        configHelper.setAddressCount(100);
        configHelper.writeConfig();

        // Create new instance and verify values persist
        ConfigHelper newConfig = new ConfigHelper("test");
        assertEquals(0.005, newConfig.getFee());
        assertFalse(newConfig.isFlatFee());
        assertTrue(newConfig.isRpcEnabled());
        assertEquals(100, newConfig.getAddressCount());
    }

    @Test
    void testLoadConfigFromFile() {
        // Write a config file manually
        String configContent = "{\n" +
                "    \"fee\": 0.002,\n" +
                "    \"feeFlat\": false,\n" +
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

        // Create new ConfigHelper instance to load from file
        ConfigHelper loadedConfig = new ConfigHelper("test");

        assertEquals(0.002, loadedConfig.getFee());
        assertFalse(loadedConfig.isFlatFee());
        assertTrue(loadedConfig.isRpcEnabled());
        assertEquals("testuser", loadedConfig.getRpcUsername());
        assertEquals("testpass", loadedConfig.getRpcPassword());
        assertEquals(9000, loadedConfig.getRpcPort());
        assertEquals(25, loadedConfig.getAddressCount());
    }
}