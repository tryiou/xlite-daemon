package io.cloudchains.app.coinconfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;
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

    private final String base;

    /**
     * @param base the resolved config source: either a local checkout directory
     *             containing {@code manifest-latest.json} and {@code xbridge-confs/},
     *             or a RAW base URL under which those relative paths resolve
     *             (as produced by ConfigSourceResolver.resolve()).
     */
    public CoinConfigSource(String base) {
        this.base = base.replaceAll("/+$", "");
    }

    /** @return ticker -> config, ordered by manifest appearance */
    public Map<String, CoinConfig> loadAll() {
        String manifestJson = isLocal() ? readLocalFile(localManifest()) : fetchRemote(manifestUrl());
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
        Map<String, CoinConfig> out = new LinkedHashMap<>();
        int index = 0;
        int skipped = 0;
        for (JsonElement el : entries) {
            index++;
            JsonObject entry;
            try {
                entry = el.getAsJsonObject();
                final String ticker = requiredField(entry, "ticker", index);
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
                    sections = isLocal()
                            ? safeParseLocal(localXBridgeConf(xbridgeConf))
                            : XBridgeConfParser.parse(fetchRemote(xbridgeConfUrl(xbridgeConf)));
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
        if (skipped > 0) {
            LOGGER.warning("[coinconfig] skipped " + skipped + " manifest entries with missing/unreadable xbridge confs");
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("manifest: no loadable entries (all " + skipped + " skipped)");
        }
        return out;
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
