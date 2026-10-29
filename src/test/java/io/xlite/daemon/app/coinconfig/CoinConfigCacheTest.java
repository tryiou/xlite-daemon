package io.xlite.daemon.app.coinconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Byte-level tests for {@link CoinConfigCache} — no network, no parsing.
 */
class CoinConfigCacheTest {

    @Test
    void testRoundTrip(@TempDir Path root) {
        CoinConfigCache cache = CoinConfigCache.forSource(root, "https://example.invalid/a");
        assertTrue(cache.cachedManifest().isEmpty());
        assertTrue(cache.cachedConf("ltc.conf").isEmpty());
        cache.storeManifest("[{\"ticker\":\"LTC\"}]");
        cache.storeConf("ltc.conf", "[LTC]\nTitle=Litecoin\n");
        assertEquals("[{\"ticker\":\"LTC\"}]", cache.cachedManifest().orElseThrow(AssertionError::new));
        assertEquals("[LTC]\nTitle=Litecoin\n", cache.cachedConf("ltc.conf").orElseThrow(AssertionError::new));
        assertEquals("manifest-latest.json", cache.dir().resolve("manifest-latest.json").getFileName().toString());
    }

    @Test
    void testSourcesAreNamespaced(@TempDir Path root) {
        Path a = CoinConfigCache.forSource(root, "https://example.invalid/a").dir();
        Path b = CoinConfigCache.forSource(root, "https://example.invalid/b").dir();
        assertNotEquals(a, b);
        assertEquals(a, CoinConfigCache.forSource(root, "https://example.invalid/a").dir());
    }

    @Test
    void testPruneKeepsCurrentPlusMostRecent(@TempDir Path root) throws Exception {
        Path oldA = root.resolve("src-old-a");
        Path oldB = root.resolve("src-old-b");
        Files.createDirectories(oldA);
        Files.createDirectories(oldB);
        Files.setLastModifiedTime(oldA, FileTime.fromMillis(1000));
        Files.setLastModifiedTime(oldB, FileTime.fromMillis(2000));
        CoinConfigCache current = CoinConfigCache.forSource(root, "https://example.invalid/new");
        assertTrue(Files.isDirectory(current.dir()));
        assertTrue(Files.isDirectory(oldB), "most recent other source kept");
        assertFalse(Files.exists(oldA), "oldest source pruned");
    }
}
