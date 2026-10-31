package io.xlite.daemon.app.testing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import io.xlite.daemon.app.App;
import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.util.ConfigHelper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Test helper class providing shared utilities for all test files. This avoids code duplication
 * across test files.
 */
public abstract class TestHelper {

    /**
     * Per-class config sandbox. Never the working directory: a test that touches the config layer
     * must not write into the source tree.
     */
    protected static final Path TEST_SANDBOX = createSandbox();

    private static final Gson GSON = new GsonBuilder().create();
    protected static JsonObject testConfig;

    private static Path createSandbox() {
        try {
            return Files.createTempDirectory("xlite-daemon-test-");
        } catch (IOException e) {
            throw new RuntimeException("could not create the test config sandbox", e);
        }
    }

    static {
        try {
            loadTestConfig();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load test configuration", e);
        }
    }

    private static void loadTestConfig() throws IOException {
        try (InputStream is =
                TestHelper.class.getClassLoader().getResourceAsStream("test_config.json")) {
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

    /** A coin configuration for tests, with every value supplied explicitly. */
    protected static CoinConfig testCoinConfig(
            String ticker,
            String blockchain,
            int addr,
            int p2sh,
            int dpk,
            int port,
            long feePerByte,
            long minTxFee) {
        return testCoinConfig(
                ticker, blockchain, addr, p2sh, dpk, port, feePerByte, minTxFee, 100_000_000L);
    }

    /**
     * The coin factor is a parameter because a fixture that only ever uses 1e8 cannot detect a
     * divisor that is hardcoded to 1e8: every such calculation would agree with itself. Currencies
     * with fewer than eight decimals are real here, so a test that wants to catch that has to be
     * able to ask for one.
     */
    protected static CoinConfig testCoinConfig(
            String ticker,
            String blockchain,
            int addr,
            int p2sh,
            int dpk,
            int port,
            long feePerByte,
            long minTxFee,
            long coinFactor) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("AddressPrefix", Integer.toString(addr));
        e.put("ScriptPrefix", Integer.toString(p2sh));
        e.put("SecretPrefix", Integer.toString(dpk));
        e.put("COIN", Long.toString(coinFactor));
        e.put("FeePerByte", Long.toString(feePerByte));
        e.put("MinTxFee", Long.toString(minTxFee));
        e.put("Port", Integer.toString(port));
        e.put("DustAmount", "0");
        e.put("TxVersion", "1");
        e.put("Title", blockchain);
        return new CoinConfig(ticker, blockchain, ticker.toLowerCase() + "--test", e);
    }

    protected static int getAddressCount() {
        return testConfig.getAsJsonObject("test_parameters").get("address_count").getAsInt();
    }

    protected static int getAddressCountInitial() {
        return testConfig
                .getAsJsonObject("test_parameters")
                .get("address_count_initial")
                .getAsInt();
    }

    protected static String getPassword() {
        return testConfig.getAsJsonObject("test_parameters").get("password").getAsString();
    }

    protected static String getMnemonic() {
        return testConfig.getAsJsonObject("test_parameters").get("mnemonic").getAsString();
    }

    protected static ArrayList<String> getExpectedAddresses() {
        List<String> list =
                GSON.fromJson(
                        testConfig.get("expected_addresses"),
                        new TypeToken<List<String>>() {}.getType());
        return new ArrayList<>(list);
    }

    /**
     * Common setup method for all test files. Disables address discovery during tests to prevent
     * interference.
     *
     * <p>Every test gets a private sandbox: {@code CONFIG_DIR} used to be the working directory, so
     * a test that touched the config layer wrote {@code xlite-daemon/} and {@code .migration.lock}
     * into the source tree (and could delete a sibling checkout's data). A per-class temp dir is
     * deleted by JUnit, so nothing escapes.
     */
    @BeforeEach
    public void commonSetup() {
        ConfigHelper.setConfigDir(TEST_SANDBOX.toString());
        clean();
        // Disable address discovery during tests to prevent interference with deterministic address
        // generation
        CoinInstance.setAddressDiscoveryEnabled(false);
        // Ensure coin configs are available — simple in-memory set, no filesystem
        if (!CoinConfigRegistry.isLoaded()) {
            Map<String, CoinConfig> cfgs = new LinkedHashMap<>();
            // The prefixes and ports are the coins' real published values,
            // because address derivation depends on them. The fees are chosen
            // by the test and are not claims about current configuration, which
            // changes; nothing here should be updated when it does.
            cfgs.put("LTC", testCoinConfig("LTC", "Litecoin", 48, 50, 176, 9332, 10L, 5000L));
            cfgs.put("BLOCK", testCoinConfig("BLOCK", "Blocknet", 26, 28, 154, 41414, 20L, 10000L));
            CoinConfigRegistry.loadForTest(cfgs);
        }
    }

    /**
     * Common cleanup method for all test files. Pins CONFIG_DIR to the workdir sandbox: without
     * this, cleanup after a test that restored the default empty CONFIG_DIR would resolve (and
     * delete!) the real home data directory, depending on JUnit method order.
     */
    @AfterAll
    public static void commonCleanup() {
        ConfigHelper.setConfigDir(TEST_SANDBOX.toString());
        clean();
        deleteDir(TEST_SANDBOX.toFile());
    }

    /** Cleans up test data and resets CoinInstance state. */
    protected static void clean() {
        CoinInstance.getCoinInstances().clear();
        assertTrue(deleteDir(new File(ConfigHelper.getLocalDataDirectory())));
        String base =
                ConfigHelper.getConfigDir() == null || ConfigHelper.getConfigDir().trim().isEmpty()
                        ? App.getUserConfigDir()
                        : ConfigHelper.getConfigDir();
        File migrationLock = new File(base, ConfigHelper.MIGRATION_LOCK_FILE);
        if (migrationLock.exists()) {
            assertTrue(migrationLock.delete());
        }
        File staging = new File(base, ConfigHelper.MIGRATION_STAGING_NAME);
        if (staging.exists()) {
            assertTrue(deleteDir(staging));
        }
    }

    /**
     * Deletes a directory and all its contents recursively.
     *
     * @param path The directory to delete
     * @return true if deletion was successful
     */
    protected static boolean deleteDir(File path) {
        if (!path.exists()) {
            return true;
        }
        for (File subFile : Objects.requireNonNull(path.listFiles())) {
            if (subFile.isDirectory()) {
                deleteDir(subFile);
            } else {
                if (!subFile.delete()) {
                    return false;
                }
            }
        }
        return path.delete();
    }

    /**
     * Returns true if there's no duplicates in the list.
     *
     * @param list The list to check for duplicates
     * @return True if no duplicates
     */
    protected static boolean noDups(ArrayList<String> list) {
        HashSet<String> set = new HashSet<>(list);
        return list.size() == set.size();
    }

    /**
     * Asserts that a condition is true. This is a simple assertion method to avoid importing JUnit
     * in the helper.
     *
     * @param condition The condition to assert
     * @throws AssertionError if the condition is false
     */
    protected static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected true but was false");
        }
    }
}
