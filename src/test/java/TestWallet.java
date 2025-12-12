import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.cloudchains.app.crypto.LoginUtils;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.util.AddressBalance;
import io.cloudchains.app.util.ConfigHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class TestWallet {

    private static final Gson GSON = new GsonBuilder().create();
    private static JsonObject testConfig;

    static {
        try {
            loadTestConfig();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load test configuration", e);
        }
    }

    private static void loadTestConfig() throws IOException {
        try (InputStream is = TestWallet.class.getClassLoader().getResourceAsStream("test_config.json")) {
            if (is == null) {
                throw new IOException("Could not find test_config.json in classpath");
            }
            InputStreamReader reader = new InputStreamReader(is);
            testConfig = GSON.fromJson(reader, JsonObject.class);
        }
    }

    // Test parameters - same interface as TestWalletConfig
    private static final int ADDRESS_COUNT_INITIAL = getAddressCountInitial();
    private static final int ADDRESS_COUNT = getAddressCount();
    private static final String MNEMONIC = getMnemonic();
    private static final String PASSWORD = getPassword();
    private static final String COIN_TICKER = "LITECOIN";

    private static int getAddressCount() {
        return testConfig.getAsJsonObject("test_parameters").get("address_count").getAsInt();
    }

    private static int getAddressCountInitial() {
        return testConfig.getAsJsonObject("test_parameters").get("address_count_initial").getAsInt();
    }

    private static String getPassword() {
        return testConfig.getAsJsonObject("test_parameters").get("password").getAsString();
    }

    private static String getMnemonic() {
        return testConfig.getAsJsonObject("test_parameters").get("mnemonic").getAsString();
    }

    private static ArrayList<String> getExpectedAddresses() {
        List<String> list = GSON.fromJson(testConfig.get("expected_addresses"), List.class);
        return new ArrayList<>(list);
    }

    @Test
    void deterministicAddresses_fromMnemonic() {
        for (int runCount = 0; runCount < 10; runCount++) {
            CoinInstance coin = CoinInstance.getInstance(CoinTicker.LITECOIN);
            assertNotNull(coin);
            coin.getConfigHelper().setAddressCount(getAddressCount());
            assertNull(coin.init(LoginUtils.loginToEntropy(getPassword()), getMnemonic(), false));

            ArrayList<AddressBalance> addresses = coin.getAddressKeyPairs();
            ArrayList<String> actual = new ArrayList<>();
            for (AddressBalance address : addresses) {
                actual.add(address.getAddress().toBase58());
//                System.out.println("\""+actual.get(actual.size()-1)+"\",");
            }
            assertEquals(getAddressCount(), actual.size());
            assertTrue(noDups(actual));

            List<String> expected = getExpectedAddresses();
            assertTrue(noDups(new ArrayList<>(expected)));
            for (int i = 0; i < expected.size(); i++)
                assertEquals(expected.get(i), actual.get(i));
            assertEquals(expected.size(), actual.size());

            coin.deinit();
            clean();
        }
    }

    @Test
    void deterministicAddresses_generateAddress() {
        for (int runCount = 0; runCount < 10; runCount++) {
            CoinInstance coin = CoinInstance.getInstance(CoinTicker.LITECOIN);
            assertNotNull(coin);
            coin.getConfigHelper().setAddressCount(getAddressCountInitial());
            assertNull(coin.init(LoginUtils.loginToEntropy(getPassword()), getMnemonic(), false));

            final int total = getAddressCount() - getAddressCountInitial() - 1;
            for (int i = 0; i < total; i++)
                coin.generateAddress(false);
            coin.generateAddress(true); // last one

            ArrayList<AddressBalance> addresses = coin.getAddressKeyPairs();
            ArrayList<String> actual = new ArrayList<>();
            for (AddressBalance address : addresses)
                actual.add(address.getAddress().toBase58());
            assertEquals(getAddressCount(), actual.size());
            assertTrue(noDups(actual));

            List<String> expected = getExpectedAddresses();
            assertTrue(noDups(new ArrayList<>(expected)));
            for (int i = 0; i < expected.size(); i++)
                assertEquals(expected.get(i), actual.get(i));
            assertEquals(expected.size(), actual.size());

            coin.deinit();
            clean();
        }
    }

    @Test
    void deterministicAddresses_generateForwardAddresses() {
        for (int runCount = 0; runCount < 10; runCount++) {
            CoinInstance coin = CoinInstance.getInstance(CoinTicker.LITECOIN);
            assertNotNull(coin);
            coin.getConfigHelper().setAddressCount(getAddressCountInitial());
            assertNull(coin.init(LoginUtils.loginToEntropy(getPassword()), getMnemonic(), false));

            // Reinit which triggers generate forward addresses
            coin.getConfigHelper().setAddressCount(getAddressCount());
            assertNull(coin.init(LoginUtils.loginToEntropy(getPassword()), null, false));

            ArrayList<AddressBalance> addresses = coin.getAddressKeyPairs();
            ArrayList<String> actual = new ArrayList<>();
            for (AddressBalance address : addresses)
                actual.add(address.getAddress().toBase58());
            assertEquals(getAddressCount(), actual.size());
            assertTrue(noDups(actual));

            List<String> expected = getExpectedAddresses();
            assertTrue(noDups(new ArrayList<>(expected)));
            for (int i = 0; i < expected.size(); i++)
                assertEquals(expected.get(i), actual.get(i));
            assertEquals(expected.size(), actual.size());

            coin.deinit();
            clean();
        }
    }

    @Test
    void deterministicAddresses_generateForwardAddressesReloadConfig() {
        CoinInstance coin = CoinInstance.getInstance(CoinTicker.LITECOIN);
        assertNotNull(coin);
        coin.getConfigHelper().setAddressCount(getAddressCountInitial());
        assertNull(coin.init(LoginUtils.loginToEntropy(getPassword()), getMnemonic(), false));

        for (int idx = getAddressCountInitial() * 2; idx < getAddressCount(); idx += getAddressCountInitial()) {
            // ReloadConfig with new address count triggers generate forward addresses
            coin.getConfigHelper().setAddressCount(idx);
            coin.getConfigHelper().writeConfig();
            coin.reloadConfig();
            ArrayList<AddressBalance> addresses = coin.getAddressKeyPairs();
            ArrayList<String> actual = new ArrayList<>();
            for (AddressBalance address : addresses)
                actual.add(address.getAddress().toBase58());
            assertEquals(idx, actual.size());
            assertTrue(noDups(actual));

            List<String> expected = getExpectedAddresses();
            assertTrue(noDups(new ArrayList<>(expected)));
            for (int i = 0; i < idx; i++)
                assertEquals(expected.get(i), actual.get(i));
        }


        coin.deinit();
        clean();
    }

    @BeforeEach
    void beforeEach() {
        ConfigHelper.CONFIG_DIR = ".";
        clean();
        // Disable address discovery during tests to prevent interference with deterministic address generation
        CoinInstance.setAddressDiscoveryEnabled(false);
    }

    @AfterAll
    static void afterAll() {
        clean();
    }


    static void clean() {
        CoinInstance.getCoinInstances().clear();
        assertTrue(deleteDir(new File(ConfigHelper.getLocalDataDirectory())));
    }

    static boolean deleteDir(File path) {
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
    * @param list
     * @return True if no duplicates
    */
    static boolean noDups(ArrayList<String> list) {
        HashSet<String> set = new HashSet<>(list);
        return list.size() == set.size();
    }

}
