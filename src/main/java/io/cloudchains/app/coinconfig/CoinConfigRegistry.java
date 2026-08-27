package io.cloudchains.app.coinconfig;

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
        String resolved = source.replaceAll("/+$", "");
        Map<String, CoinConfig> all = new CoinConfigSource(resolved).loadAll();
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
