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

/**
 * Loads every coin's {@link CoinConfig} from a blockchain-configuration-files
 * source (local directory or base URL), resolving once at daemon start.
 *
 * <p>Failure policy is fail-hard: any unreadable manifest, missing conf file,
 * missing conf section or unparsable value throws; the daemon refuses to
 * start rather than guess coin parameters.</p>
 */
public final class CoinConfigSource {

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
        for (JsonElement el : entries) {
            index++;
            JsonObject entry;
            try {
                entry = el.getAsJsonObject();
                final String ticker = requiredField(entry, "ticker", index);
                final String blockchain = requiredField(entry, "blockchain", index);
                final String verId = entry.has("ver_id") ? entry.get("ver_id").getAsString() : "";
                final String xbridgeConf = requiredField(entry, "xbridge_conf", index);

                CoinConfig prev = out.get(ticker);
                if (prev != null)
                    throw new IllegalStateException("duplicate manifest ticker " + ticker);

                Map<String, Map<String, String>> sections = isLocal()
                        ? safeParseLocal(localXBridgeConf(xbridgeConf))
                        : XBridgeConfParser.parse(fetchRemote(xbridgeConfUrl(xbridgeConf)));
                Map<String, String> section = sections.get(ticker);
                if (section == null)
                    throw new IllegalStateException("xbridge conf " + xbridgeConf
                            + " has no [" + ticker + "] section");
                out.put(ticker, new CoinConfig(ticker, blockchain, verId, section));
            } catch (RuntimeException e) {
                throw new IllegalStateException("manifest entry #" + index + ": " + e.getMessage(), e);
            }
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
