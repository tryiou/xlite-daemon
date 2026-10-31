package io.xlite.daemon.app.util;

import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins which ports a stepping listener may not take: the master listener's
 * port, another coin's own daemon port, and another coin's derived listener
 * port.
 */
class RpcPortClaimsTest {

    private static final int MASTER_PORT = 9955;

    @AfterEach
    void reset() {
        CoinConfigRegistry.resetForTest();
    }

    private static CoinConfig config(String ticker, int port) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("AddressPrefix", "26");
        e.put("ScriptPrefix", "28");
        e.put("SecretPrefix", "154");
        e.put("COIN", "100000000");
        e.put("FeePerByte", "20");
        e.put("MinTxFee", "10000");
        e.put("Port", Integer.toString(port));
        e.put("DustAmount", "546");
        e.put("TxVersion", "1");
        e.put("Title", ticker);
        return new CoinConfig(ticker, ticker, ticker.toLowerCase() + "--test", e);
    }

    @Test
    void testClaimsIncludeOtherCoinsDaemonAndDerivedPorts() {
        Map<String, CoinConfig> cfgs = new LinkedHashMap<>();
        cfgs.put("BLOCK", config("BLOCK", 41414));
        cfgs.put("LTC", config("LTC", 9332));
        CoinConfigRegistry.loadForTest(cfgs);

        Set<Integer> claimed = RpcPortClaims.excluding("BLOCK", MASTER_PORT);

        assertTrue(claimed.contains(9332), "LTC's own daemon port");
        assertTrue(claimed.contains(9335), "LTC's derived listener port");
        assertFalse(claimed.contains(41414), "the asking coin's own daemon port");
        assertFalse(claimed.contains(41417), "the asking coin's own derived port");
    }

    @Test
    void testClaimsAlwaysIncludeTheMasterPort() {
        CoinConfigRegistry.loadForTest(Map.of());

        // The master port is the operator's configured value, not a constant:
        // a master pinned anywhere must still be off-limits to a coin.
        Set<Integer> claimed = RpcPortClaims.excluding("BLOCK", 9977);

        assertEquals(Set.of(9977), claimed);
    }

    @Test
    void testClaimsIncludeTheMasterPortAlongsideCoinPorts() {
        Map<String, CoinConfig> cfgs = new LinkedHashMap<>();
        cfgs.put("BLOCK", config("BLOCK", 41414));
        CoinConfigRegistry.loadForTest(cfgs);

        Set<Integer> claimed = RpcPortClaims.excluding("LTC", 9977);

        assertTrue(claimed.contains(9977), "the master listener's port");
        assertTrue(claimed.contains(41417), "BLOCK's derived listener port");
    }

    @Test
    void testKnownConfigPortsAreClaimedNotOnlyDerivedOnes() {
        // A coin that already has a config-file port keeps it; that exact port
        // must be off-limits to every other listener.
        Map<String, CoinConfig> cfgs = new LinkedHashMap<>();
        cfgs.put("BLOCK", config("BLOCK", 41414));
        CoinConfigRegistry.loadForTest(cfgs);

        // BLOCK claims 41414 and 41417; a sibling must avoid both.
        Set<Integer> claimed = RpcPortClaims.excluding("LTC", MASTER_PORT);
        assertTrue(claimed.contains(41414));
        assertTrue(claimed.contains(41417));
    }

    @Test
    void testCandidatesSkipExcludedPortsWithoutLeavingTheBaseNeighbourhood() {
        int base = 41417;
        Set<Integer> excluded = Set.of(base, base + 1, 9955);

        int[] candidates = RpcPorts.candidates(base, 10, excluded);

        assertEquals(base + 2, candidates[0]);
        for (int c : candidates) {
            assertFalse(excluded.contains(c));
            assertTrue(c >= base && c <= base + 20, "candidate " + c + " drifted");
        }
    }
}
