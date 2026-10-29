package io.xlite.daemon.app.coinconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Remote-path tests for {@link CoinConfigSource} via the stub fetch seam —
 * no network. Covers the supported-ticker filter: only enum-listed tickers
 * are ever fetched.
 */
class CoinConfigSourceRemoteTest {

    private static final String BASE = "https://example.invalid/bcf";

    private static class StubFetch implements java.util.function.Function<String, String> {
        final Map<String, String> bodies = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public String apply(String url) {
            calls.add(url);
            String body = bodies.get(url);
            if (body == null)
                throw new IllegalStateException("HTTP 404 fetching " + url);
            return body;
        }
    }

    private static StubFetch fourTickerStub() {
        StubFetch stub = new StubFetch();
        stub.bodies.put(BASE + "/manifest-latest.json",
                "[{\"blockchain\":\"Litecoin\",\"ticker\":\"LTC\",\"ver_id\":\"litecoin--v0.21.1\",\"xbridge_conf\":\"litecoin--v0.21.1.conf\"},"
                        + "{\"blockchain\":\"Dogecoin\",\"ticker\":\"DOGE\",\"ver_id\":\"doge--v1\",\"xbridge_conf\":\"doge--v1.conf\"},"
                        + "{\"blockchain\":\"Fake\",\"ticker\":\"FAKE\",\"ver_id\":\"fake--v1\",\"xbridge_conf\":\"fake--v1.conf\"},"
                        + "{\"blockchain\":\"BitcoinCash\",\"ticker\":\"BCH\",\"ver_id\":\"bch--v1\",\"xbridge_conf\":\"bch--v1.conf\"}]");
        stub.bodies.put(BASE + "/xbridge-confs/litecoin--v0.21.1.conf",
                "[LTC]\nTitle=Litecoin\nAddressPrefix=48\nScriptPrefix=50\nSecretPrefix=176\nCOIN=100000000\nFeePerByte=10\nMinTxFee=5000\nPort=9332\n");
        stub.bodies.put(BASE + "/xbridge-confs/doge--v1.conf",
                "[DOGE]\nTitle=Dogecoin\nAddressPrefix=30\nScriptPrefix=22\nSecretPrefix=158\nCOIN=100000000\nFeePerByte=10\nMinTxFee=1000\nPort=22555\n");
        return stub;
    }

    @Test
    void testOnlySupportedTickersFetched() {
        StubFetch stub = fourTickerStub();
        CoinConfigSource src = new CoinConfigSource(BASE);
        src.setRemoteFetch(stub);
        Map<String, CoinConfig> all = src.loadAll();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("LTC"));
        assertTrue(all.containsKey("DOGE"));
        assertEquals("litecoin--v0.21.1", all.get("LTC").getVerId());
        for (String url : stub.calls) {
            assertFalse(url.contains("fake--v1.conf"), "FAKE must never be fetched: " + url);
            assertFalse(url.contains("bch--v1.conf"), "BCH must never be fetched: " + url);
        }
        assertEquals(3, stub.calls.size(), "1 manifest + 2 supported confs, got " + stub.calls);
    }

    @Test
    void testAllUnsupportedManifestFailsHard() {
        StubFetch stub = new StubFetch();
        stub.bodies.put(BASE + "/manifest-latest.json",
                "[{\"blockchain\":\"Fake\",\"ticker\":\"FAKE\",\"xbridge_conf\":\"fake--v1.conf\"}]");
        CoinConfigSource src = new CoinConfigSource(BASE);
        src.setRemoteFetch(stub);
        IllegalStateException e = assertThrows(IllegalStateException.class, src::loadAll);
        assertTrue(e.getMessage().contains("no loadable entries"));
        assertEquals(1, stub.calls.size(), "only the manifest is fetched, got " + stub.calls);
    }

    @Test
    void testManifestFetchFailurePropagates() {
        StubFetch stub = new StubFetch();
        CoinConfigSource src = new CoinConfigSource(BASE);
        src.setRemoteFetch(stub);
        assertThrows(IllegalStateException.class, src::loadAll);
    }

    // --- cache / compare / parallel ---

    private static final String LTC_CONF_V1 =
            "[LTC]\nTitle=Litecoin\nAddressPrefix=48\nScriptPrefix=50\nSecretPrefix=176\nCOIN=100000000\nFeePerByte=10\nMinTxFee=5000\nPort=9332\n";
    private static final String DOGE_CONF =
            "[DOGE]\nTitle=Dogecoin\nAddressPrefix=30\nScriptPrefix=22\nSecretPrefix=158\nCOIN=100000000\nFeePerByte=10\nMinTxFee=1000\nPort=22555\n";

    private static String manifestOf(String... entries) {
        return "[" + String.join(",", entries) + "]";
    }

    private static String manifestEntry(String blockchain, String ticker, String verId, String conf) {
        return "{\"blockchain\":\"" + blockchain + "\",\"ticker\":\"" + ticker
                + "\",\"ver_id\":\"" + verId + "\",\"xbridge_conf\":\"" + conf + "\"}";
    }

    private CoinConfigSource cachedSource(Path root, String manifestJson, Map<String, String> confs,
            StubFetch stub) {
        CoinConfigCache cache = CoinConfigCache.forSource(root, BASE);
        cache.storeManifest(manifestJson);
        for (Map.Entry<String, String> e : confs.entrySet())
            cache.storeConf(e.getKey(), e.getValue());
        CoinConfigSource src = new CoinConfigSource(BASE);
        src.setRemoteFetch(stub);
        src.setCache(cache);
        return src;
    }

    @Test
    void testFullCacheHitFetchesNoConfs(@TempDir Path root) {
        String manifest = manifestOf(
                manifestEntry("Litecoin", "LTC", "litecoin--v1", "litecoin--v1.conf"),
                manifestEntry("Dogecoin", "DOGE", "doge--v1", "doge--v1.conf"));
        Map<String, String> confs = new LinkedHashMap<>();
        confs.put("litecoin--v1.conf", LTC_CONF_V1);
        confs.put("doge--v1.conf", DOGE_CONF);
        StubFetch stub = new StubFetch();
        stub.bodies.put(BASE + "/manifest-latest.json", manifest);
        stub.bodies.put(BASE + "/xbridge-confs/litecoin--v1.conf", LTC_CONF_V1);
        stub.bodies.put(BASE + "/xbridge-confs/doge--v1.conf", DOGE_CONF);
        Map<String, CoinConfig> all = cachedSource(root, manifest, confs, stub).loadAll();
        assertEquals(2, all.size());
        assertEquals(10L, all.get("LTC").feePerByte());
        assertEquals(1000L, all.get("DOGE").minTxFee());
        assertEquals(1, stub.calls.size(), "only the manifest is fetched, got " + stub.calls);
        assertTrue(stub.calls.get(0).endsWith("manifest-latest.json"));
    }

    @Test
    void testSingleVerIdBumpFetchesExactlyOneConf(@TempDir Path root) {
        String oldManifest = manifestOf(
                manifestEntry("Litecoin", "LTC", "litecoin--vOLD", "litecoin--vOLD.conf"),
                manifestEntry("Dogecoin", "DOGE", "doge--v1", "doge--v1.conf"));
        Map<String, String> oldConfs = new LinkedHashMap<>();
        oldConfs.put("litecoin--vOLD.conf", LTC_CONF_V1);
        oldConfs.put("doge--v1.conf", DOGE_CONF);
        String newManifest = manifestOf(
                manifestEntry("Litecoin", "LTC", "litecoin--vNEW", "litecoin--vNEW.conf"),
                manifestEntry("Dogecoin", "DOGE", "doge--v1", "doge--v1.conf"));
        String newLtc = LTC_CONF_V1.replace("FeePerByte=10", "FeePerByte=11");
        StubFetch stub = new StubFetch();
        stub.bodies.put(BASE + "/manifest-latest.json", newManifest);
        stub.bodies.put(BASE + "/xbridge-confs/litecoin--vNEW.conf", newLtc);
        Map<String, CoinConfig> all = cachedSource(root, oldManifest, oldConfs, stub).loadAll();
        assertEquals(11L, all.get("LTC").feePerByte(), "fresh conf wins");
        assertEquals(1000L, all.get("DOGE").minTxFee(), "unchanged coin served from cache");
        assertEquals(2, stub.calls.size(), "manifest + 1 delta, got " + stub.calls);
    }

    @Test
    void testRemovedTickerDroppedAndNeverFetched(@TempDir Path root) {
        String oldManifest = manifestOf(
                manifestEntry("Litecoin", "LTC", "litecoin--v1", "litecoin--v1.conf"),
                manifestEntry("Dogecoin", "DOGE", "doge--v1", "doge--v1.conf"));
        Map<String, String> oldConfs = new LinkedHashMap<>();
        oldConfs.put("litecoin--v1.conf", LTC_CONF_V1);
        oldConfs.put("doge--v1.conf", "[DOGE]\nTitle=Dogecoin\n");
        String newManifest = manifestOf(
                manifestEntry("Litecoin", "LTC", "litecoin--v1", "litecoin--v1.conf"));
        StubFetch stub = new StubFetch();
        stub.bodies.put(BASE + "/manifest-latest.json", newManifest);
        Map<String, CoinConfig> all = cachedSource(root, oldManifest, oldConfs, stub).loadAll();
        assertEquals(1, all.size());
        assertTrue(all.containsKey("LTC"));
        for (String url : stub.calls)
            assertFalse(url.contains("doge--v1.conf"), "removed ticker must never be fetched");
    }

    @Test
    void testCorruptCachedConfRefetched(@TempDir Path root) {
        String manifest = manifestOf(
                manifestEntry("Litecoin", "LTC", "litecoin--v1", "litecoin--v1.conf"));
        Map<String, String> corrupt = new LinkedHashMap<>();
        corrupt.put("litecoin--v1.conf", "garbage ((( no sections here");
        StubFetch stub = new StubFetch();
        stub.bodies.put(BASE + "/manifest-latest.json", manifest);
        stub.bodies.put(BASE + "/xbridge-confs/litecoin--v1.conf", LTC_CONF_V1);
        Map<String, CoinConfig> all = cachedSource(root, manifest, corrupt, stub).loadAll();
        assertEquals(10L, all.get("LTC").feePerByte(), "refetched conf wins over corrupt cache");
        assertEquals(2, stub.calls.size(), "manifest + refetch, got " + stub.calls);
    }

    @Test
    void testParallelMatchesSerialDeterministically() {
        String[] tickers = {"BLOCK", "DOGE", "BTC", "LTC", "DASH", "DOGE", "SYS", "PIVX", "DGB", "RVN", "UNO", "PKOIN"};
        List<String> entries = new ArrayList<>();
        final Map<String, String> confBodies = new LinkedHashMap<>();
        for (String t : tickers) {
            entries.add(manifestEntry("Chain" + t, t, "v1", t.toLowerCase() + ".conf"));
            confBodies.put(BASE + "/xbridge-confs/" + t.toLowerCase() + ".conf", "[" + t + "]\nTitle=" + t + "\n");
        }
        String manifest = manifestOf(entries.toArray(new String[0]));
        Function<String, String> jittered = url -> {
            if (url.endsWith("manifest-latest.json"))
                return manifest;
            try {
                Thread.sleep(ThreadLocalRandom.current().nextInt(15));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String body = confBodies.get(url);
            if (body == null)
                throw new IllegalStateException("HTTP 404 fetching " + url);
            return body;
        };
        CoinConfigSource first = new CoinConfigSource(BASE);
        first.setRemoteFetch(jittered);
        CoinConfigSource second = new CoinConfigSource(BASE);
        second.setRemoteFetch(jittered);
        Map<String, CoinConfig> a = first.loadAll();
        Map<String, CoinConfig> b = second.loadAll();
        assertEquals(11, a.size());
        assertEquals(a, b, "parallel fetch must be deterministic across runs");
        assertEquals(new ArrayList<>(a.keySet()), new ArrayList<>(b.keySet()), "manifest order preserved");
    }

    @Test
    void testFetchCapFallsBackToCache(@TempDir Path root) {
        String manifest = manifestOf(
                manifestEntry("Litecoin", "LTC", "litecoin--v1", "litecoin--v1.conf"),
                manifestEntry("Bitcoin", "BTC", "bitcoin--v1", "bitcoin--v1.conf"));
        Map<String, String> partial = new LinkedHashMap<>();
        partial.put("litecoin--v1.conf", LTC_CONF_V1);
        CoinConfigCache cache = CoinConfigCache.forSource(root, BASE);
        cache.storeManifest(manifest);
        for (Map.Entry<String, String> e : partial.entrySet())
            cache.storeConf(e.getKey(), e.getValue());
        final List<String> calls = new ArrayList<>();
        Function<String, String> hanging = url -> {
            calls.add(url);
            if (url.endsWith("manifest-latest.json"))
                return manifest;
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("fetch should have been capped");
        };
        CoinConfigSource src = new CoinConfigSource(BASE);
        src.setRemoteFetch(hanging);
        src.setCache(cache);
        src.setFetchPhaseTimeoutForTest(1);
        long start = System.currentTimeMillis();
        Map<String, CoinConfig> all = src.loadAll();
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(all.containsKey("LTC"), "cached coin served despite cap");
        assertFalse(all.containsKey("BTC"), "uncached coin skipped past the cap");
        assertTrue(calls.stream().anyMatch(u -> u.endsWith("bitcoin--v1.conf")), "fetch was attempted");
        assertTrue(elapsed < 30_000, "cap bounds the phase, took " + elapsed + "ms");
    }

    @Test
    void testSameFilenameContentChangeRefetched(@TempDir Path root) {
        String file = "litecoin.conf";
        String oldManifest = manifestOf(
                manifestEntry("Litecoin", "LTC", "litecoin--v1", file));
        Map<String, String> oldConfs = new LinkedHashMap<>();
        oldConfs.put(file, LTC_CONF_V1);
        String newManifest = manifestOf(
                manifestEntry("Litecoin", "LTC", "litecoin--v2", file));
        String newConf = LTC_CONF_V1.replace("FeePerByte=10", "FeePerByte=12");
        StubFetch stub = new StubFetch();
        stub.bodies.put(BASE + "/manifest-latest.json", newManifest);
        stub.bodies.put(BASE + "/xbridge-confs/" + file, newConf);
        Map<String, CoinConfig> all = cachedSource(root, oldManifest, oldConfs, stub).loadAll();
        assertEquals(12L, all.get("LTC").feePerByte(), "same-filename change must refetch, not serve stale");
        assertEquals(2, stub.calls.size(), "manifest + refetch, got " + stub.calls);
    }

    @Test
    void testFailedLoadPreservesLastGoodCache(@TempDir Path root) {
        String oldManifest = manifestOf(
                manifestEntry("Litecoin", "LTC", "litecoin--v1", "litecoin--v1.conf"));
        Map<String, String> oldConfs = new LinkedHashMap<>();
        oldConfs.put("litecoin--v1.conf", LTC_CONF_V1);
        String newManifest = manifestOf(
                manifestEntry("Litecoin", "LTC", "litecoin--v2", "litecoin--v2.conf"));
        StubFetch stub = new StubFetch();
        stub.bodies.put(BASE + "/manifest-latest.json", newManifest);
        CoinConfigCache cache = CoinConfigCache.forSource(root, BASE);
        cache.storeManifest(oldManifest);
        for (Map.Entry<String, String> e : oldConfs.entrySet())
            cache.storeConf(e.getKey(), e.getValue());
        CoinConfigSource src = new CoinConfigSource(BASE);
        src.setRemoteFetch(stub);
        src.setCache(cache);
        IllegalStateException e = assertThrows(IllegalStateException.class, src::loadAll);
        assertTrue(e.getMessage().contains("no loadable entries"), e.getMessage());
        assertEquals(oldManifest, cache.cachedManifest().orElseThrow(AssertionError::new),
                "failed load must leave the last-good manifest for fallback");
        assertEquals(LTC_CONF_V1, cache.cachedConf("litecoin--v1.conf").orElseThrow(AssertionError::new));
    }

    @Test
    void testSourceSwitchRefetchesFully(@TempDir Path root) {
        String base2 = "https://example.invalid/bcf2";
        String manifest = manifestOf(
                manifestEntry("Litecoin", "LTC", "litecoin--v1", "litecoin--v1.conf"));
        StubFetch stub = new StubFetch();
        stub.bodies.put(BASE + "/manifest-latest.json", manifest);
        stub.bodies.put(BASE + "/xbridge-confs/litecoin--v1.conf", LTC_CONF_V1);
        stub.bodies.put(base2 + "/manifest-latest.json", manifest);
        stub.bodies.put(base2 + "/xbridge-confs/litecoin--v1.conf", LTC_CONF_V1);
        CoinConfigSource first = new CoinConfigSource(BASE);
        first.setRemoteFetch(stub);
        first.setCache(CoinConfigCache.forSource(root, BASE));
        assertEquals(1, first.loadAll().size());
        int callsAfterFirst = stub.calls.size();
        CoinConfigSource second = new CoinConfigSource(base2);
        second.setRemoteFetch(stub);
        second.setCache(CoinConfigCache.forSource(root, base2));
        assertEquals(1, second.loadAll().size());
        assertEquals(callsAfterFirst + 2, stub.calls.size(),
                "switched source refetches manifest + conf, got " + stub.calls);
    }
}
