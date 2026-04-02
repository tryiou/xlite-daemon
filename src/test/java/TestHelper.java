import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.util.ConfigHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Test helper class providing shared utilities for all test files.
 * This avoids code duplication across TestCoinInstance, TestConfigHelper, and TestLoginUtils.
 */
public class TestHelper {

    private static final Gson GSON = new GsonBuilder().create();
    protected static JsonObject testConfig;

    static {
        try {
            loadTestConfig();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load test configuration", e);
        }
    }

    private static void loadTestConfig() throws IOException {
        try (InputStream is = TestHelper.class.getClassLoader().getResourceAsStream("test_config.json")) {
            if (is == null) {
                throw new IOException("Could not find test_config.json in classpath");
            }
            InputStreamReader reader = new InputStreamReader(is);
            testConfig = GSON.fromJson(reader, JsonObject.class);
        }
    }

    // Test parameters - shared across all test files
    protected static final int ADDRESS_COUNT_INITIAL = getAddressCountInitial();
    protected static final int ADDRESS_COUNT = getAddressCount();
    protected static final String MNEMONIC = getMnemonic();
    protected static final String PASSWORD = getPassword();
    protected static final String COIN_TICKER = "LITECOIN";

    protected static int getAddressCount() {
        return testConfig.getAsJsonObject("test_parameters").get("address_count").getAsInt();
    }

    protected static int getAddressCountInitial() {
        return testConfig.getAsJsonObject("test_parameters").get("address_count_initial").getAsInt();
    }

    protected static String getPassword() {
        return testConfig.getAsJsonObject("test_parameters").get("password").getAsString();
    }

    protected static String getMnemonic() {
        return testConfig.getAsJsonObject("test_parameters").get("mnemonic").getAsString();
    }

    protected static ArrayList<String> getExpectedAddresses() {
        List<String> list = GSON.fromJson(testConfig.get("expected_addresses"), new TypeToken<List<String>>(){
        }.getType());
        return new ArrayList<>(list);
    }

    /**
     * Common setup method for all test files.
     * Disables address discovery during tests to prevent interference.
     */
    @BeforeEach
    public void commonSetup() {
        ConfigHelper.CONFIG_DIR = ".";
        clean();
        // Disable address discovery during tests to prevent interference with deterministic address generation
        CoinInstance.setAddressDiscoveryEnabled(false);
    }

    /**
     * Common cleanup method for all test files.
     */
    @AfterAll
    public static void commonCleanup() {
        clean();
    }

    /**
     * Cleans up test data and resets CoinInstance state.
     */
    protected static void clean() {
        CoinInstance.getCoinInstances().clear();
        assertTrue(deleteDir(new File(ConfigHelper.getLocalDataDirectory())));
    }

    /**
     * Deletes a directory and all its contents recursively.
     * @param path The directory to delete
     * @return true if deletion was successful
     */
    protected static boolean deleteDir(File path) {
        if (!path.exists())
            return true;
        for (File subFile : Objects.requireNonNull(path.listFiles())) {
            if (subFile.isDirectory()) {
                deleteDir(subFile);
            } else {
                if (!subFile.delete())
                    return false;
            }
        }
        return path.delete();
    }

    /**
     * Returns true if there's no duplicates in the list.
     * @param list The list to check for duplicates
     * @return True if no duplicates
     */
    protected static boolean noDups(ArrayList<String> list) {
        HashSet<String> set = new HashSet<>(list);
        return list.size() == set.size();
    }

    /**
     * Asserts that a condition is true.
     * This is a simple assertion method to avoid importing JUnit in the helper.
     * @param condition The condition to assert
     * @throws AssertionError if the condition is false
     */
    protected static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected true but was false");
        }
    }
}