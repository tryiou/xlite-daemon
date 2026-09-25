package io.xlite.daemon.app.coinconfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline-fallback tests for {@link CoinConfigRegistry#loadWithFallback}.
 * Upstream failure is simulated with a closed localhost port (fast
 * refusal, no network dependency).
 */
class CoinConfigRegistryFallbackTest {

    private static final String DEAD_UPSTREAM = "http://127.0.0.1:9/bcf";

    private static final String MANIFEST =
            "[{\"blockchain\":\"Litecoin\",\"ticker\":\"LTC\",\"ver_id\":\"litecoin--v1\",\"xbridge_conf\":\"litecoin--v1.conf\"},"
                    + "{\"blockchain\":\"Dogecoin\",\"ticker\":\"DOGE\",\"ver_id\":\"doge--v1\",\"xbridge_conf\":\"doge--v1.conf\"}]";
    private static final String LTC_CONF =
            "[LTC]\nTitle=Litecoin\nAddressPrefix=48\nScriptPrefix=50\nSecretPrefix=176\nCOIN=100000000\nFeePerByte=10\nMinTxFee=5000\nPort=9332\n";
    private static final String DOGE_CONF =
            "[DOGE]\nTitle=Dogecoin\nAddressPrefix=30\nScriptPrefix=22\nSecretPrefix=158\nCOIN=100000000\nFeePerByte=10\nMinTxFee=1000\nPort=22555\n";

    @BeforeEach
    void reset() {
        CoinConfigRegistry.resetForTest();
    }

    @AfterEach
    void resetAfter() {
        CoinConfigRegistry.resetForTest();
    }

    private static CoinConfigCache warmCache(Path root, String source) {
        CoinConfigCache cache = CoinConfigCache.forSource(root, source);
        cache.storeManifest(MANIFEST);
        cache.storeConf("litecoin--v1.conf", LTC_CONF);
        cache.storeConf("doge--v1.conf", DOGE_CONF);
        return cache;
    }

    @Test
    void testWarmCacheServedWhenUpstreamDead(@TempDir Path root) {
        CoinConfigCache cache = warmCache(root, DEAD_UPSTREAM);
        CoinConfigRegistry.loadWithFallback(DEAD_UPSTREAM, cache);
        Map<String, CoinConfig> all = CoinConfigRegistry.list();
        assertEquals(2, all.size());
        assertEquals(48, all.get("LTC").addressPrefix());
        assertEquals(cache.dir().toString(), CoinConfigRegistry.loadedSource());
    }

    @Test
    void testColdCachePropagatesOriginalFailure(@TempDir Path root) {
        CoinConfigCache cache = CoinConfigCache.forSource(root, DEAD_UPSTREAM);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CoinConfigRegistry.loadWithFallback(DEAD_UPSTREAM, cache));
        assertTrue(e.getMessage().contains("Cannot fetch"), e.getMessage());
        assertTrue(CoinConfigRegistry.list().isEmpty());
    }

    @Test
    void testNullCachePropagatesFailure(@TempDir Path root) {
        assertThrows(IllegalStateException.class,
                () -> CoinConfigRegistry.loadWithFallback(DEAD_UPSTREAM, null));
    }

    @Test
    void testHealthyLoadNeedsNoFallback(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("xbridge-confs"));
        Files.writeString(dir.resolve("manifest-latest.json"), MANIFEST);
        Files.writeString(dir.resolve("xbridge-confs").resolve("litecoin--v1.conf"), LTC_CONF);
        Files.writeString(dir.resolve("xbridge-confs").resolve("doge--v1.conf"), DOGE_CONF);
        CoinConfigRegistry.loadWithFallback(dir.toString(), null);
        assertEquals(2, CoinConfigRegistry.list().size());
    }
}
