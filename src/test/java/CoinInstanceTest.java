import io.cloudchains.app.crypto.LoginUtils;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.util.AddressBalance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for CoinInstance functionality.
 * Tests address generation, wallet initialization, and deterministic address creation.
 */
class CoinInstanceTest extends TestHelper {

    @Test
    void deterministicAddresses_fromMnemonic() {
        for (int runCount = 0; runCount < 10; runCount++) {
            CoinInstance coin = CoinInstance.getInstance(CoinTicker.BLOCKNET);
            assertNotNull(coin);
            coin.getConfigHelper().setAddressCount(getAddressCount());
            assertNull(coin.init(LoginUtils.loginToEntropy(getPassword()), getMnemonic(), false));

            List<AddressBalance> addresses = coin.getAddressKeyPairs();
            ArrayList<String> actual = new ArrayList<>();
            for (AddressBalance address : addresses) {
                actual.add(address.getAddress().toBase58());
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
            CoinInstance coin = CoinInstance.getInstance(CoinTicker.BLOCKNET);
            assertNotNull(coin);
            coin.getConfigHelper().setAddressCount(getAddressCountInitial());
            assertNull(coin.init(LoginUtils.loginToEntropy(getPassword()), getMnemonic(), false));

            final int total = getAddressCount() - getAddressCountInitial() - 1;
            for (int i = 0; i < total; i++)
                coin.generateAddress(false);
            coin.generateAddress(true); // last one

            List<AddressBalance> addresses = coin.getAddressKeyPairs();
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
            CoinInstance coin = CoinInstance.getInstance(CoinTicker.BLOCKNET);
            assertNotNull(coin);
            coin.getConfigHelper().setAddressCount(getAddressCountInitial());
            assertNull(coin.init(LoginUtils.loginToEntropy(getPassword()), getMnemonic(), false));

            // Reinit which triggers generate forward addresses
            coin.getConfigHelper().setAddressCount(getAddressCount());
            assertNull(coin.init(LoginUtils.loginToEntropy(getPassword()), null, false));

            List<AddressBalance> addresses = coin.getAddressKeyPairs();
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
        CoinInstance coin = CoinInstance.getInstance(CoinTicker.BLOCKNET);
        assertNotNull(coin);
        coin.getConfigHelper().setAddressCount(getAddressCountInitial());
        assertNull(coin.init(LoginUtils.loginToEntropy(getPassword()), getMnemonic(), false));

        for (int idx = getAddressCountInitial() * 2; idx < getAddressCount(); idx += getAddressCountInitial()) {
            // ReloadConfig with new address count triggers generate forward addresses
            coin.getConfigHelper().setAddressCount(idx);
            coin.getConfigHelper().writeConfig();
            coin.reloadConfig();
            List<AddressBalance> addresses = coin.getAddressKeyPairs();
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
    void setup() {
        commonSetup();
    }

    @AfterAll
    static void cleanup() {
        commonCleanup();
    }
}