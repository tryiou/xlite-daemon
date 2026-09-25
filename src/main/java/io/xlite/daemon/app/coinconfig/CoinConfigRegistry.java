package io.xlite.daemon.app.coinconfig;

import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Process-wide holder for the loaded {@link CoinConfig} map. Initialized once
 * at startup from the resolved blockchain-configuration-files source; tests
 * may reload with a different source between cases.
 */
public final class CoinConfigRegistry {

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private static volatile Map<String, CoinConfig> loaded = null;
    private static volatile String loadedSource = null;

    private CoinConfigRegistry() {
    }

    /**
     * Load (or reload) all configs from the given source. On success the map
     * replaces any prior load — startup calls once; tests may call repeatedly.
     * Gate-rejected entries are logged and omitted from the map when the user
     * chose warn-and-continue semantics; the registry retains every valid
     * ticker.
     */
    public static synchronized void load(String source) {
        load(source, null);
    }

    /**
     * Load with offline fallback: when the upstream load fails and the cache
     * holds a manifest, serve the stale cache instead of starting coin-less.
     * Cold cache (or a cache that itself fails to load) propagates the
     * original failure — first-ever boot with no network still fails
     * honestly. Staleness is always logged loudly.
     */
    public static synchronized void loadWithFallback(String source, CoinConfigCache cache) {
        try {
            load(source, cache);
            return;
        } catch (RuntimeException first) {
            if (cache == null)
                throw first;
            String warmed = cache.dir().toString();
            if (!Files.isRegularFile(cache.dir().resolve("manifest-latest.json")))
                throw first;
            try {
                load(warmed);
            } catch (RuntimeException fallbackFailure) {
                throw new IllegalStateException("upstream (" + first.getMessage()
                        + "); cache fallback also failed: " + fallbackFailure.getMessage(), first);
            }
            LOGGER.log(Level.WARNING,
                    "[coinconfig] upstream unreachable (" + first.getMessage()
                            + ") — serving STALE cached configs from " + warmed
                            + " (" + cacheAge(cache) + ")");
        }
    }

    /**
     * Load with an optional write-through cache (remote sources). A null
     * cache behaves exactly like {@link #load(String)}.
     */
    public static synchronized void load(String source, CoinConfigCache cache) {
        String resolved = source.replaceAll("/+$", "");
        CoinConfigSource src = new CoinConfigSource(resolved);
        if (cache != null)
            src.setCache(cache);
        Map<String, CoinConfig> all = src.loadAll();
        LinkedHashMap<String, CoinConfig> filtered = new LinkedHashMap<>();
        int rejected = 0;
        for (Map.Entry<String, CoinConfig> e : all.entrySet()) {
            try {
                CoinConfigGate.validate(e.getValue());
                filtered.put(e.getKey(), e.getValue());
            } catch (IllegalStateException ex) {
                LOGGER.log(Level.WARNING,
                        "[coinconfig] rejected [" + e.getKey() + "]: " + ex.getMessage());
                rejected++;
            }
        }
        if (rejected > 0) {
            LOGGER.log(Level.WARNING,
                    "[coinconfig] " + rejected + " ticker(s) rejected by gate; continuing with "
                            + filtered.size() + " valid");
        }
        loaded = Collections.unmodifiableMap(filtered);
        loadedSource = resolved;
        LOGGER.info("[coinconfig] loaded " + loaded.size() + " ticker(s) from " + resolved);
    }

    private static String cacheAge(CoinConfigCache cache) {
        try {
            FileTime mtime = Files.getLastModifiedTime(cache.dir().resolve("manifest-latest.json"));
            long hours = (System.currentTimeMillis() - mtime.toMillis()) / 3_600_000L;
            if (hours < 1)
                return "under an hour old";
            if (hours < 48)
                return hours + "h old";
            return (hours / 24) + "d old";
        } catch (Exception e) {
            return "age unknown";
        }
    }

    public static boolean isLoaded() {
        return loaded != null;
    }

    public static String loadedSource() {
        return loadedSource;
    }

    /** Snapshot of loaded configs. Empty when not loaded. Defensive copy under lock. */
    public static synchronized Map<String, CoinConfig> list() {
        Map<String, CoinConfig> m = loaded;
        if (m == null)
            return Collections.emptyMap();
        return Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }

    /** Alias for list() — preferred name. */
    public static synchronized Map<String, CoinConfig> snapshot() {
        return list();
    }

    /** Fail-fast when registry has not been loaded yet. */
    public static CoinConfig get(String ticker) {
        Map<String, CoinConfig> m = loaded;
        if (m == null)
            throw new IllegalStateException(
                    "CoinConfigRegistry not loaded — App.initCoinConfigs() must run before coin init");
        CoinConfig cfg = m.get(ticker);
        if (cfg == null)
            throw new IllegalStateException(
                    "[" + ticker + "] not present in loaded coin configs from " + loadedSource);
        return cfg;
    }

    /** Visible for tests only — public for cross-package default-package TestHelper. */
    public static synchronized void loadForTest(Map<String, CoinConfig> cfgs) {
        LinkedHashMap<String, CoinConfig> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, CoinConfig> e : cfgs.entrySet()) {
            CoinConfigGate.validate(e.getValue());
            filtered.put(e.getKey(), e.getValue());
        }
        loaded = Collections.unmodifiableMap(filtered);
        loadedSource = "test-fixture";
        LOGGER.info("[coinconfig] loaded " + loaded.size() + " ticker(s) from test-fixture");
    }

    /** Visible for tests only — public for cross-package tests. */
    public static synchronized void resetForTest() {
        loaded = null;
        loadedSource = null;
    }
}
