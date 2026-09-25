package io.xlite.daemon.app.coinconfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Loads every coin's {@link CoinConfig} from a blockchain-configuration-files
 * source (local directory or base URL), resolving once at daemon start.
 *
 * <p>Failure policy: manifest shape and required fields are fail-hard (missing
 * ticker/blockchain/xbridge_conf aborts the whole load). Per-entry
 * xbridge-conf fetch/parse and missing ticker sections are
 * fail-open-warn-and-skip for remote sources (upstream may contain stale
 * entries like {@code oasis--v3.0.0.conf}) and fail-hard for local
 * directories (developer error). The daemon therefore starts even if a few
 * remote entries are stale, but a broken local checkout is caught early.</p>
 */
public final class CoinConfigSource {

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);
    private static final Pattern XBRIDGE_CONF_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-\\.]+\\.conf$");

    /** Parallel fetch width for remote conf deltas. */
    private static final int FETCH_POOL_SIZE = 10;
    /** Overall cap for the remote conf-fetch phase; past it the boot proceeds with fetched + cached. */
    private static final long FETCH_PHASE_TIMEOUT_SECONDS = 120;

    private final String base;

    /**
     * Remote-fetch delegate, defaulting to {@link #fetchRemote(String)}.
     * Tests inject a stub; production never replaces it.
     */
    private Function<String, String> remoteFetch = CoinConfigSource::fetchRemote;

    /**
     * Write-through cache for remote sources; null disables caching (local
     * sources and tests without a cache behave exactly as before).
     */
    private CoinConfigCache cache = null;

    /** Test seam: overrides the fetch-phase cap. */
    private long fetchPhaseTimeoutSeconds = FETCH_PHASE_TIMEOUT_SECONDS;

    /**
     * @param base the resolved config source: either a local checkout directory
     *             containing {@code manifest-latest.json} and {@code xbridge-confs/},
     *             or a RAW base URL under which those relative paths resolve
     *             (as produced by ConfigSourceResolver.resolve()).
     */
    public CoinConfigSource(String base) {
        this.base = base.replaceAll("/+$", "");
    }

    /** Test seam: replaces the HTTP fetch delegate with a stub. */
    void setRemoteFetch(Function<String, String> remoteFetch) {
        this.remoteFetch = remoteFetch;
    }

    /** Attaches a write-through cache (remote sources only). */
    void setCache(CoinConfigCache cache) {
        this.cache = cache;
    }

    /** Test seam: overrides the fetch-phase cap. */
    void setFetchPhaseTimeoutForTest(long seconds) {
        this.fetchPhaseTimeoutSeconds = seconds;
    }

    /** @return ticker -> config, ordered by manifest appearance */
    public Map<String, CoinConfig> loadAll() {
        String manifestJson = isLocal() ? readLocalFile(localManifest()) : remoteFetch.apply(manifestUrl());
        JsonArray entries = parseManifestEntries(manifestJson);
        // Snapshot the previous cached manifest BEFORE overwriting: the
        // compare must run old-vs-fresh, and a failed load must leave the
        // last-good cache intact for offline fallback.
        Optional<String> prevCachedManifest =
                (cache != null && !isLocal()) ? cache.cachedManifest() : Optional.empty();
        RemoteTexts remoteTexts = isLocal() ? null : resolveRemoteTexts(entries, prevCachedManifest);
        Map<String, CoinConfig> out = new LinkedHashMap<>();
        int index = 0;
        int skipped = 0;
        int unsupported = 0;
        for (JsonElement el : entries) {
            index++;
            JsonObject entry;
            try {
                entry = el.getAsJsonObject();
                final String ticker = requiredField(entry, "ticker", index);
                if (!isSupportedTicker(ticker)) {
                    LOGGER.fine("[coinconfig] skipping [" + ticker + "] entry #" + index
                            + ": ticker not in wallet enum");
                    unsupported++;
                    continue;
                }
                final String blockchain = requiredField(entry, "blockchain", index);
                final String verId = entry.has("ver_id") ? entry.get("ver_id").getAsString() : "";
                final String xbridgeConf = requiredField(entry, "xbridge_conf", index);
                if (!XBRIDGE_CONF_PATTERN.matcher(xbridgeConf).matches() || xbridgeConf.contains("..")) {
                    String msg = "xbridge_conf filename fails validation: " + xbridgeConf;
                    if (isLocal()) {
                        throw new IllegalStateException(msg);
                    } else {
                        LOGGER.warning("[coinconfig] skipping [" + ticker + "] entry #" + index + ": " + msg);
                        skipped++;
                        continue;
                    }
                }
                if (out.containsKey(ticker)) {
                    LOGGER.warning("[coinconfig] duplicate ticker " + ticker + " entry #" + index
                            + " overwriting previous (keeping last)");
                }
                Map<String, Map<String, String>> sections;
                try {
                    if (isLocal()) {
                        sections = safeParseLocal(localXBridgeConf(xbridgeConf));
                    } else {
                        String text = remoteTexts.get(xbridgeConf);
                        if (text == null) {
                            // Fetch already failed during the resolve phase and
                            // was warned + counted there; skip quietly here.
                            continue;
                        }
                        sections = XBridgeConfParser.parse(text);
                    }
                } catch (RuntimeException fe) {
                    if (isLocal()) {
                        throw fe;
                    } else {
                        LOGGER.warning("[coinconfig] skipping [" + ticker + "] entry #" + index
                                + " (" + xbridgeConf + "): " + fe.getMessage());
                        skipped++;
                        continue;
                    }
                }
                Map<String, String> section = sections.get(ticker);
                if (section == null) {
                    String msg = "xbridge conf " + xbridgeConf + " has no [" + ticker + "] section";
                    if (isLocal()) {
                        throw new IllegalStateException(msg);
                    } else {
                        LOGGER.warning("[coinconfig] skipping [" + ticker + "] entry #" + index + ": " + msg);
                        skipped++;
                        continue;
                    }
                }
                out.put(ticker, new CoinConfig(ticker, blockchain, verId, section));
            } catch (RuntimeException e) {
                // Required-field or JSON shape errors are still hard failures;
                // per-entry fetch/section issues for local are re-thrown above
                // and will be wrapped here; remote skips are already continued.
                if (e.getMessage() != null && e.getMessage().startsWith("manifest entry #")) {
                    throw e;
                }
                throw new IllegalStateException("manifest entry #" + index + ": " + e.getMessage(), e);
            }
        }
        if (!isLocal()) {
            skipped += remoteTexts.skipped;
        }
        if (skipped > 0) {
            LOGGER.warning("[coinconfig] skipped " + skipped + " manifest entries with missing/unreadable xbridge confs");
        }
        if (unsupported > 0) {
            LOGGER.info("[coinconfig] skipped " + unsupported + " manifest entries with tickers not in wallet enum");
        }
        if (!isLocal()) {
            LOGGER.info("[coinconfig] loaded " + out.size() + " ticker(s): "
                    + remoteTexts.fetched + " fetched, " + remoteTexts.cached + " from cache");
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("manifest: no loadable entries (" + skipped
                    + " skipped, " + unsupported + " unsupported)");
        }
        if (!isLocal() && cache != null)
            cache.storeManifest(manifestJson);
        return out;
    }

    /**
     * Parses a manifest document (bare array or {@code {"contracts": [...]}}).
     * Malformed shape and empty lists fail hard — the fresh manifest must be
     * valid before it may overwrite the cache.
     */
    private static JsonArray parseManifestEntries(String manifestJson) {
        JsonArray entries;
        try {
            JsonElement root = JsonParser.parseString(manifestJson);
            if (root.isJsonArray()) {
                entries = root.getAsJsonArray();
            } else {
                entries = root.getAsJsonObject().getAsJsonArray("contracts");
                if (entries == null)
                    throw new IllegalStateException("object manifest has no 'contracts' array");
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("manifest: " + e.getMessage(), e);
        }
        if (entries.size() == 0)
            throw new IllegalStateException("manifest lists no coins");
        return entries;
    }

    /** Resolved conf texts for the remote path, keyed by conf file name. */
    private static final class RemoteTexts {
        final Map<String, String> texts = new LinkedHashMap<>();
        int skipped;
        int cached;
        int fetched;

        String get(String fileName) {
            return texts.get(fileName);
        }
    }

    /**
     * Resolves every eligible remote conf to its text: unchanged entries are
     * served from the cache (when attached), the remainder is fetched in
     * parallel. Fetch failures are warned + counted here so the main loop
     * can skip quietly. Insertion order follows manifest appearance.
     *
     * @param prevCachedManifest snapshot of the cached manifest taken before
     *                           this load (never the just-fetched one)
     */
    private RemoteTexts resolveRemoteTexts(JsonArray entries, Optional<String> prevCachedManifest) {
        RemoteTexts plan = new RemoteTexts();
        Map<String, String> cachedCompare = readCachedCompare(prevCachedManifest);
        List<String[]> toFetch = new ArrayList<>();
        int index = 0;
        for (JsonElement el : entries) {
            index++;
            JsonObject entry = el.getAsJsonObject();
            String ticker = requiredField(entry, "ticker", index);
            if (!isSupportedTicker(ticker))
                continue;
            String verId = entry.has("ver_id") ? entry.get("ver_id").getAsString() : "";
            String xbridgeConf = requiredField(entry, "xbridge_conf", index);
            if (!XBRIDGE_CONF_PATTERN.matcher(xbridgeConf).matches() || xbridgeConf.contains(".."))
                continue;
            if (plan.texts.containsKey(xbridgeConf) || containsFile(toFetch, xbridgeConf))
                continue;
            String want = verId + "\0" + xbridgeConf;
            String have = cachedCompare.get(ticker);
            if (cache != null && want.equals(have)) {
                Optional<String> cachedText = cache.cachedConf(xbridgeConf);
                if (cachedText.isPresent() && parsesWithSection(cachedText.get(), ticker)) {
                    plan.texts.put(xbridgeConf, cachedText.get());
                    plan.cached++;
                    continue;
                }
            }
            toFetch.add(new String[]{xbridgeConf, ticker, String.valueOf(index)});
        }
        fetchParallel(toFetch, plan);
        return plan;
    }

    /**
     * Compares the previous cached manifest against fresh entries: ticker to
     * ver_id + xbridge_conf. Unreadable cached manifests degrade to empty
     * (full refetch), never to failure.
     */
    private Map<String, String> readCachedCompare(Optional<String> prevCachedManifest) {
        Map<String, String> compare = new LinkedHashMap<>();
        if (cache == null || !prevCachedManifest.isPresent())
            return compare;
        JsonArray cachedEntries;
        try {
            cachedEntries = parseManifestEntries(prevCachedManifest.get());
        } catch (RuntimeException e) {
            LOGGER.warning("[coinconfig] cached manifest unreadable, refetching all: " + e.getMessage());
            return compare;
        }
        for (JsonElement el : cachedEntries) {
            try {
                JsonObject entry = el.getAsJsonObject();
                String ticker = requiredField(entry, "ticker", 0);
                String verId = entry.has("ver_id") ? entry.get("ver_id").getAsString() : "";
                String xbridgeConf = requiredField(entry, "xbridge_conf", 0);
                compare.put(ticker, verId + "\0" + xbridgeConf);
            } catch (RuntimeException e) {
                LOGGER.warning("[coinconfig] cached manifest entry unreadable, refetching all: " + e.getMessage());
                return new LinkedHashMap<>();
            }
        }
        return compare;
    }

    private static boolean containsFile(List<String[]> toFetch, String fileName) {
        for (String[] need : toFetch) {
            if (need[0].equals(fileName))
                return true;
        }
        return false;
    }

    private static boolean parsesWithSection(String text, String ticker) {
        try {
            return XBridgeConfParser.parse(text).get(ticker) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Fetches the needed conf files over a bounded pool, overall-capped:
     * past the cap the boot proceeds with fetched + cached. Every outcome
     * (success, failure, timeout) lands deterministically in the plan.
     */
    private void fetchParallel(List<String[]> toFetch, RemoteTexts plan) {
        if (toFetch.isEmpty())
            return;
        // Daemon threads: a hung fetch (uninterruptible socket I/O) must
        // never delay JVM shutdown past the phase cap.
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(FETCH_POOL_SIZE, toFetch.size()), r -> {
            Thread t = new Thread(r, "coinconfig-fetch");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Callable<String[]>> tasks = new ArrayList<>();
            for (String[] need : toFetch) {
                final String fileName = need[0];
                final String url = xbridgeConfUrl(fileName);
                tasks.add(() -> {
                    String text = remoteFetch.apply(url);
                    if (cache != null)
                        cache.storeConf(fileName, text);
                    return new String[]{fileName, text};
                });
            }
            List<Future<String[]>> futures;
            boolean poolInterrupted = false;
            try {
                futures = pool.invokeAll(tasks, fetchPhaseTimeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures = new ArrayList<>();
                poolInterrupted = true;
            }
            for (int i = 0; i < toFetch.size(); i++) {
                String[] need = toFetch.get(i);
                String fileName = need[0];
                String ticker = need[1];
                String entryNo = need[2];
                if (poolInterrupted || i >= futures.size() || futures.get(i).isCancelled()) {
                    warnFetchSkip(plan, ticker, entryNo, fileName,
                            poolInterrupted ? "fetch interrupted" : "fetch timed out");
                    continue;
                }
                try {
                    String[] done = futures.get(i).get();
                    plan.texts.put(done[0], done[1]);
                    plan.fetched++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    warnFetchSkip(plan, ticker, entryNo, fileName, "fetch interrupted");
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    warnFetchSkip(plan, ticker, entryNo, fileName, cause.getMessage());
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    private static void warnFetchSkip(RemoteTexts plan, String ticker, String entryNo,
            String fileName, String reason) {
        LOGGER.warning("[coinconfig] skipping [" + ticker + "] entry #" + entryNo
                + " (" + fileName + "): " + reason);
        plan.skipped++;
    }

    /**
     * True when the wallet can serve this ticker: present in the string map
     * and in the supported-coins list. Unknown tickers (null mapping, e.g.
     * BCH while its mapping stays commented) and enum members the backend
     * does not support are both skipped without any fetch.
     */
    private static boolean isSupportedTicker(String ticker) {
        CoinTicker t = CoinTickerUtils.stringToTicker(ticker);
        return t != null && CoinTicker.coins().contains(t);
    }

    private static String requiredField(JsonObject entry, String field, int index) {
        JsonElement el = entry.get(field);
        if (el == null || el.isJsonNull())
            throw new IllegalStateException("manifest entry #" + index + ": missing '" + field + "'");
        return el.getAsString();
    }

    private boolean isLocal() {
        return ConfigSourceResolver.isLocalDirectory(base);
    }

    private Path localManifest() {
        return Paths.get(base, "manifest-latest.json");
    }

    private Path localXBridgeConf(String fileName) {
        return Paths.get(base, "xbridge-confs", fileName);
    }

    private String manifestUrl() {
        return base + "/manifest-latest.json";
    }

    private String xbridgeConfUrl(String fileName) {
        return base + "/xbridge-confs/" + fileName;
    }

    private static String readLocalFile(Path p) {
        try {
            byte[] b = Files.readAllBytes(p);
            return new String(b, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + p + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, Map<String, String>> safeParseLocal(Path p) {
        try {
            return XBridgeConfParser.parseFile(p);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + p + ": " + e.getMessage(), e);
        }
    }

    private static String fetchRemote(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            int code = conn.getResponseCode();
            if (code != 200)
                throw new IllegalStateException("HTTP " + code + " fetching " + url);
            try (InputStream in = conn.getInputStream(); Scanner s =
                    new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
                return s.hasNext() ? s.next() : "";
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot fetch " + url + ": " + e.getMessage(), e);
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }
}
