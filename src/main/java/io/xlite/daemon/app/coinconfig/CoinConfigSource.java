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
import java.net.URI;
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
 * Loads every coin's {@link CoinConfig} from a blockchain-configuration-files source (local
 * directory or base URL), resolving once at daemon start.
 *
 * <p>Failure policy: manifest shape and required fields are fail-hard (missing
 * ticker/blockchain/xbridge_conf aborts the whole load). Per-entry xbridge-conf fetch/parse and
 * missing ticker sections are fail-open-warn-and-skip for remote sources (upstream may contain
 * stale entries like {@code oasis--v3.0.0.conf}) and fail-hard for local directories (developer
 * error). The daemon therefore starts even if a few remote entries are stale, but a broken local
 * checkout is caught early.
 */
public final class CoinConfigSource {

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);
    private static final Pattern XBRIDGE_CONF_PATTERN =
            Pattern.compile("^[A-Za-z0-9_\\-\\.]+\\.conf$");

    /** Parallel fetch width for remote conf deltas. */
    private static final int FETCH_POOL_SIZE = 10;

    /**
     * Overall cap for the remote conf-fetch phase; past it the boot proceeds with fetched + cached.
     */
    private static final long FETCH_PHASE_TIMEOUT_SECONDS = 120;

    private final String base;

    /**
     * Remote-fetch delegate, defaulting to {@link #fetchRemote(String)}. Tests inject a stub;
     * production never replaces it.
     */
    private Function<String, String> remoteFetch = CoinConfigSource::fetchRemote;

    /**
     * Write-through cache for remote sources; null disables caching (local sources and tests
     * without a cache behave exactly as before).
     */
    private CoinConfigCache cache = null;

    /** Test seam: overrides the fetch-phase cap. */
    private long fetchPhaseTimeoutSeconds = FETCH_PHASE_TIMEOUT_SECONDS;

    /**
     * @param base the resolved config source: either a local checkout directory containing {@code
     *     manifest-latest.json} and {@code xbridge-confs/}, or a RAW base URL under which those
     *     relative paths resolve (as produced by ConfigSourceResolver.resolve()).
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

    /**
     * Loads every coin the manifest lists.
     *
     * @return ticker -> config, ordered by manifest appearance
     */
    public Map<String, CoinConfig> loadAll() {
        String manifestJson =
                isLocal() ? readLocalFile(localManifest()) : remoteFetch.apply(manifestUrl());
        JsonArray entries = parseManifestEntries(manifestJson);
        // Snapshot the previous cached manifest BEFORE overwriting: the compare must run
        // old-vs-fresh, and a failed load must leave the last-good cache intact for offline
        // fallback.
        Optional<String> prevCachedManifest =
                (cache != null && !isLocal()) ? cache.cachedManifest() : Optional.empty();
        RemotePlan plan = isLocal() ? null : resolveRemoteTexts(entries, prevCachedManifest);
        LoadResult result = loadEntries(entries, plan);
        int skipped = result.skipped() + (plan == null ? 0 : plan.skipped);
        logLoadSummary(result, plan, skipped);
        if (result.byTicker().isEmpty()) {
            throw new IllegalStateException(
                    "manifest: no loadable entries ("
                            + skipped
                            + " skipped, "
                            + result.unsupported()
                            + " unsupported)");
        }
        if (!isLocal() && cache != null) {
            cache.storeManifest(manifestJson);
        }
        return result.byTicker();
    }

    private static void logLoadSummary(LoadResult result, RemotePlan plan, int skipped) {
        if (plan != null) {
            LOGGER.info(
                    "[coinconfig] loaded "
                            + result.byTicker().size()
                            + " ticker(s): "
                            + plan.fetched
                            + " fetched, "
                            + plan.cached
                            + " from cache");
        }
        if (skipped > 0) {
            LOGGER.warning(
                    "[coinconfig] skipped "
                            + skipped
                            + " manifest entries with missing/unreadable xbridge confs");
        }
        if (result.unsupported() > 0) {
            LOGGER.info(
                    "[coinconfig] skipped "
                            + result.unsupported()
                            + " manifest entries with tickers not in wallet enum");
        }
    }

    private LoadResult loadEntries(JsonArray entries, RemotePlan plan) {
        Map<String, CoinConfig> byTicker = new LinkedHashMap<>();
        int skipped = 0;
        int unsupported = 0;
        for (int index = 1; index <= entries.size(); index++) {
            EntryOutcome outcome = loadEntry(entries.get(index - 1), index, plan, byTicker);
            if (outcome.config() != null) {
                byTicker.put(outcome.ticker(), outcome.config());
            } else if (outcome.unsupported()) {
                unsupported++;
            } else {
                skipped++;
            }
        }
        return new LoadResult(byTicker, skipped, unsupported);
    }

    private EntryOutcome loadEntry(
            JsonElement element, int index, RemotePlan plan, Map<String, CoinConfig> byTicker) {
        try {
            return extractEntry(element.getAsJsonObject(), index, plan, byTicker);
        } catch (RuntimeException e) {
            // Required-field or JSON shape errors are still hard failures; per-entry
            // fetch/section issues for local are re-thrown above and will be wrapped here;
            // remote skips are already continued.
            if (e.getMessage() != null && e.getMessage().startsWith("manifest entry #")) {
                throw e;
            }
            throw new IllegalStateException("manifest entry #" + index + ": " + e.getMessage(), e);
        }
    }

    private EntryOutcome extractEntry(
            JsonObject entry, int index, RemotePlan plan, Map<String, CoinConfig> byTicker) {
        String ticker = requiredField(entry, "ticker", index);
        if (!isSupportedTicker(ticker)) {
            LOGGER.fine(
                    "[coinconfig] skipping ["
                            + ticker
                            + "] entry #"
                            + index
                            + ": ticker not in wallet enum");
            return EntryOutcome.ofUnsupported();
        }
        String blockchain = requiredField(entry, "blockchain", index);
        String verId = entry.has("ver_id") ? entry.get("ver_id").getAsString() : "";
        String xbridgeConf = requiredField(entry, "xbridge_conf", index);
        if (!XBRIDGE_CONF_PATTERN.matcher(xbridgeConf).matches() || xbridgeConf.contains("..")) {
            failEntry("xbridge_conf filename fails validation: " + xbridgeConf, index, ticker);
            return EntryOutcome.skipped();
        }
        if (byTicker.containsKey(ticker)) {
            LOGGER.warning(
                    "[coinconfig] duplicate ticker "
                            + ticker
                            + " entry #"
                            + index
                            + " overwriting previous (keeping last)");
        }
        Map<String, String> section = readSection(xbridgeConf, ticker, index, plan);
        if (section == null) {
            return EntryOutcome.skipped();
        }
        return EntryOutcome.loaded(ticker, new CoinConfig(ticker, blockchain, verId, section));
    }

    /**
     * A per-entry problem: fatal for a local source, a counted skip for a remote one.
     *
     * <p>The local source is a checkout the operator made, so a bad entry there is a mistake to
     * report; a remote manifest is trusted only entry by entry.
     */
    private void failEntry(String message, int index, String ticker) {
        if (isLocal()) {
            throw new IllegalStateException(message);
        }
        LOGGER.warning("[coinconfig] skipping [" + ticker + "] entry #" + index + ": " + message);
    }

    /** The ticker's section, or null when the entry is skipped. */
    private Map<String, String> readSection(
            String xbridgeConf, String ticker, int index, RemotePlan plan) {
        Map<String, Map<String, String>> sections;
        try {
            sections =
                    isLocal()
                            ? readLocalSections(xbridgeConf)
                            : readRemoteSections(plan, xbridgeConf);
        } catch (RuntimeException e) {
            if (isLocal()) {
                throw e;
            }
            LOGGER.warning(
                    "[coinconfig] skipping ["
                            + ticker
                            + "] entry #"
                            + index
                            + " ("
                            + xbridgeConf
                            + "): "
                            + e.getMessage());
            return null;
        }
        if (sections == null) {
            // A failed fetch was already warned and counted during the resolve phase.
            return null;
        }
        Map<String, String> section = sections.get(ticker);
        if (section != null) {
            return section;
        }
        failEntry(
                "xbridge conf " + xbridgeConf + " has no [" + ticker + "] section", index, ticker);
        return null;
    }

    private Map<String, Map<String, String>> readLocalSections(String xbridgeConf) {
        return safeParseLocal(localXBridgeConf(xbridgeConf));
    }

    /** The remote text resolved earlier, or null when its fetch already failed and was counted. */
    private static Map<String, Map<String, String>> readRemoteSections(
            RemotePlan plan, String xbridgeConf) {
        String text = plan.texts.get(xbridgeConf);
        return text == null ? null : XBridgeConfParser.parse(text);
    }

    /**
     * Parses a manifest document (bare array or {@code {"contracts": [...]}}). Malformed shape and
     * empty lists fail hard — the fresh manifest must be valid before it may overwrite the cache.
     */
    private static JsonArray parseManifestEntries(String manifestJson) {
        JsonArray entries;
        try {
            JsonElement root = JsonParser.parseString(manifestJson);
            if (root.isJsonArray()) {
                entries = root.getAsJsonArray();
            } else {
                entries = root.getAsJsonObject().getAsJsonArray("contracts");
                if (entries == null) {
                    throw new IllegalStateException("object manifest has no 'contracts' array");
                }
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("manifest: " + e.getMessage(), e);
        }
        if (entries.size() == 0) {
            throw new IllegalStateException("manifest lists no coins");
        }
        return entries;
    }

    /** Resolved conf texts for the remote path, keyed by conf file name. */
    private static final class RemotePlan {
        final Map<String, String> texts = new LinkedHashMap<>();
        final Map<String, String> cachedCompare;
        final List<String[]> toFetch = new ArrayList<>();
        int skipped;
        int cached;
        int fetched;

        RemotePlan(Map<String, String> cachedCompare) {
            this.cachedCompare = cachedCompare;
        }
    }

    /**
     * Resolves every eligible remote conf to its text: unchanged entries are served from the cache
     * (when attached), the remainder is fetched in parallel. Fetch failures are warned + counted
     * here so the main loop can skip quietly. Insertion order follows manifest appearance.
     *
     * @param prevCachedManifest snapshot of the cached manifest taken before this load (never the
     *     just-fetched one)
     */
    private RemotePlan resolveRemoteTexts(JsonArray entries, Optional<String> prevCachedManifest) {
        RemotePlan plan = new RemotePlan(readCachedCompare(prevCachedManifest));
        for (int index = 1; index <= entries.size(); index++) {
            planRemoteEntry(entries.get(index - 1).getAsJsonObject(), index, plan);
        }
        fetchParallel(plan);
        return plan;
    }

    private void planRemoteEntry(JsonObject entry, int index, RemotePlan plan) {
        String ticker = requiredField(entry, "ticker", index);
        if (!isSupportedTicker(ticker)) {
            return;
        }
        String verId = entry.has("ver_id") ? entry.get("ver_id").getAsString() : "";
        String xbridgeConf = requiredField(entry, "xbridge_conf", index);
        if (!XBRIDGE_CONF_PATTERN.matcher(xbridgeConf).matches() || xbridgeConf.contains("..")) {
            return;
        }
        if (plan.texts.containsKey(xbridgeConf) || containsFile(plan.toFetch, xbridgeConf)) {
            return;
        }
        if (useCachedConf(ticker, verId, xbridgeConf, plan)) {
            return;
        }
        plan.toFetch.add(new String[] {xbridgeConf, ticker, String.valueOf(index)});
    }

    /** Serves an unchanged entry from the cache, when its text still parses with the section. */
    private boolean useCachedConf(
            String ticker, String verId, String xbridgeConf, RemotePlan plan) {
        if (cache == null || !(verId + "\0" + xbridgeConf).equals(plan.cachedCompare.get(ticker))) {
            return false;
        }
        Optional<String> cachedText = cache.cachedConf(xbridgeConf);
        if (cachedText.isEmpty() || !parsesWithSection(cachedText.get(), ticker)) {
            return false;
        }
        plan.texts.put(xbridgeConf, cachedText.get());
        plan.cached++;
        return true;
    }

    /**
     * Compares the previous cached manifest against fresh entries: ticker to ver_id + xbridge_conf.
     * Unreadable cached manifests degrade to empty (full refetch), never to failure.
     */
    private Map<String, String> readCachedCompare(Optional<String> prevCachedManifest) {
        Map<String, String> compare = new LinkedHashMap<>();
        if (cache == null || prevCachedManifest.isEmpty()) {
            return compare;
        }
        JsonArray cachedEntries;
        try {
            cachedEntries = parseManifestEntries(prevCachedManifest.get());
        } catch (RuntimeException e) {
            LOGGER.warning(
                    "[coinconfig] cached manifest unreadable, refetching all: " + e.getMessage());
            return compare;
        }
        for (JsonElement element : cachedEntries) {
            Optional<Map.Entry<String, String>> entry = cachedCompareEntry(element);
            if (entry.isEmpty()) {
                return new LinkedHashMap<>();
            }
            compare.put(entry.get().getKey(), entry.get().getValue());
        }
        return compare;
    }

    private static Optional<Map.Entry<String, String>> cachedCompareEntry(JsonElement element) {
        try {
            JsonObject entry = element.getAsJsonObject();
            String ticker = requiredField(entry, "ticker", 0);
            String verId = entry.has("ver_id") ? entry.get("ver_id").getAsString() : "";
            String xbridgeConf = requiredField(entry, "xbridge_conf", 0);
            return Optional.of(Map.entry(ticker, verId + "\0" + xbridgeConf));
        } catch (RuntimeException e) {
            LOGGER.warning(
                    "[coinconfig] cached manifest entry unreadable, refetching all: "
                            + e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean containsFile(List<String[]> toFetch, String fileName) {
        for (String[] need : toFetch) {
            if (need[0].equals(fileName)) {
                return true;
            }
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
     * Fetches the needed conf files over a bounded pool, overall-capped: past the cap the boot
     * proceeds with fetched + cached. Every outcome (success, failure, timeout) lands
     * deterministically in the plan.
     */
    private void fetchParallel(RemotePlan plan) {
        if (plan.toFetch.isEmpty()) {
            return;
        }
        ExecutorService pool = newFetchPool(plan.toFetch.size());
        try {
            collectFetches(plan, invokeFetches(pool, plan.toFetch));
        } finally {
            pool.shutdown();
        }
    }

    private static ExecutorService newFetchPool(int taskCount) {
        // Daemon threads: a hung fetch (uninterruptible socket I/O) must never delay JVM
        // shutdown past the phase cap.
        return Executors.newFixedThreadPool(
                Math.min(FETCH_POOL_SIZE, taskCount),
                runnable -> {
                    Thread thread = new Thread(runnable, "coinconfig-fetch");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    private List<Future<String[]>> invokeFetches(ExecutorService pool, List<String[]> toFetch) {
        List<Callable<String[]>> tasks = new ArrayList<>();
        for (String[] need : toFetch) {
            tasks.add(fetchTask(need[0]));
        }
        try {
            return pool.invokeAll(tasks, fetchPhaseTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        }
    }

    private Callable<String[]> fetchTask(String fileName) {
        return () -> {
            String text = remoteFetch.apply(xbridgeConfUrl(fileName));
            if (cache != null) {
                cache.storeConf(fileName, text);
            }
            return new String[] {fileName, text};
        };
    }

    private void collectFetches(RemotePlan plan, List<Future<String[]>> futures) {
        for (int index = 0; index < plan.toFetch.size(); index++) {
            collectFetch(plan, plan.toFetch.get(index), futures, index);
        }
    }

    private void collectFetch(
            RemotePlan plan, String[] need, List<Future<String[]>> futures, int index) {
        Future<String[]> future = index < futures.size() ? futures.get(index) : null;
        if (future == null || future.isCancelled()) {
            warnFetchSkip(plan, need, "fetch timed out");
            return;
        }
        try {
            String[] done = future.get();
            plan.texts.put(done[0], done[1]);
            plan.fetched++;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            warnFetchSkip(plan, need, "fetch interrupted");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            warnFetchSkip(plan, need, cause.getMessage());
        }
    }

    private static void warnFetchSkip(RemotePlan plan, String[] need, String reason) {
        LOGGER.warning(
                "[coinconfig] skipping ["
                        + need[1]
                        + "] entry #"
                        + need[2]
                        + " ("
                        + need[0]
                        + "): "
                        + reason);
        plan.skipped++;
    }

    /** One entry's outcome: a loaded config, or one of the two counted skips. */
    private record EntryOutcome(String ticker, CoinConfig config, boolean unsupported) {

        static EntryOutcome loaded(String ticker, CoinConfig config) {
            return new EntryOutcome(ticker, config, false);
        }

        static EntryOutcome skipped() {
            return new EntryOutcome(null, null, false);
        }

        static EntryOutcome ofUnsupported() {
            return new EntryOutcome(null, null, true);
        }
    }

    /** Every coin loaded, plus the counts the summary reports. */
    private record LoadResult(Map<String, CoinConfig> byTicker, int skipped, int unsupported) {}

    /**
     * True when the wallet can serve this ticker: present in the string map and in the
     * supported-coins list. Unknown tickers (null mapping, e.g. BCH while its mapping stays
     * commented) and enum members the backend does not support are both skipped without any fetch.
     */
    private static boolean isSupportedTicker(String ticker) {
        CoinTicker t = CoinTickerUtils.stringToTicker(ticker);
        return t != null && CoinTicker.coins().contains(t);
    }

    private static String requiredField(JsonObject entry, String field, int index) {
        JsonElement el = entry.get(field);
        if (el == null || el.isJsonNull()) {
            throw new IllegalStateException(
                    "manifest entry #" + index + ": missing '" + field + "'");
        }
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
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("HTTP " + code + " fetching " + url);
            }
            try (InputStream in = conn.getInputStream();
                    Scanner s =
                            new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
                return s.hasNext() ? s.next() : "";
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot fetch " + url + ": " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
