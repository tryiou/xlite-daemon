package io.xlite.daemon.app.coinconfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Raw-byte write-through cache for remote coin-config sources. Mirrors the
 * remote layout ({@code manifest-latest.json} + {@code xbridge-confs/}) under
 * a per-source namespaced directory, so a cache directory doubles as a
 * local-directory source for offline fallback.
 *
 * <p>Bytes only: parsing, comparison and fetch policy belong to
 * {@link CoinConfigSource}. Every method fails open — cache I/O problems
 * are logged and the boot continues in-memory; a boot must never fail on
 * cache I/O.</p>
 */
public final class CoinConfigCache {

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    /** Sources retained alongside the current one on a source switch. */
    private static final int KEEP_SOURCES = 2;

    private final Path dir;

    private CoinConfigCache(Path dir) {
        this.dir = dir;
    }

    /**
     * Resolves (creating) the cache directory for a source, namespaced by
     * the sha16 of the resolved source string so flag/env/default switches
     * never read each other's bytes. Retains the current directory plus the
     * most recently created other one; older sources are pruned.
     */
    public static CoinConfigCache forSource(Path root, String source) {
        Path dir = root.resolve("src-" + sha16(source));
        try {
            Files.createDirectories(dir.resolve("xbridge-confs"));
            pruneOldSources(root, dir);
        } catch (Exception e) {
            LOGGER.warning("[coinconfig] cache dir unavailable (" + dir + "): " + e.getMessage()
                    + " — continuing without cache persistence");
        }
        return new CoinConfigCache(dir);
    }

    /** Cache directory; doubles as a local-directory source for fallback. */
    public Path dir() {
        return dir;
    }

    /** Cached manifest bytes, or empty on miss/unreadable (fail open). */
    public Optional<String> cachedManifest() {
        return readQuietly(dir.resolve("manifest-latest.json"));
    }

    /** Persists fresh manifest bytes; I/O failure logs and continues. */
    public void storeManifest(String json) {
        writeQuietly(dir.resolve("manifest-latest.json"), json);
    }

    /** Cached conf bytes for a validated file name, or empty (fail open). */
    public Optional<String> cachedConf(String fileName) {
        return readQuietly(dir.resolve("xbridge-confs").resolve(fileName));
    }

    /** Persists fresh conf bytes; I/O failure logs and continues. */
    public void storeConf(String fileName, String text) {
        writeQuietly(dir.resolve("xbridge-confs").resolve(fileName), text);
    }

    private static Optional<String> readQuietly(Path p) {
        try {
            if (!Files.isRegularFile(p))
                return Optional.empty();
            return Optional.of(Files.readString(p, StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOGGER.warning("[coinconfig] cache read failed (" + p + "): " + e.getMessage());
            return Optional.empty();
        }
    }

    private static void writeQuietly(Path p, String text) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, text, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warning("[coinconfig] cache write failed (" + p + "): " + e.getMessage());
        }
    }

    private static void pruneOldSources(Path root, Path current) {
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isDirectory).forEach(dirs::add);
        } catch (IOException e) {
            return;
        }
        dirs.sort(Comparator.comparing((Path d) -> {
            try {
                return Files.getLastModifiedTime(d);
            } catch (IOException e) {
                return FileTime.fromMillis(0);
            }
        }).reversed());
        Set<Path> keep = new LinkedHashSet<>();
        keep.add(current);
        for (Path d : dirs) {
            if (keep.size() >= KEEP_SOURCES)
                break;
            keep.add(d);
        }
        for (Path d : dirs) {
            if (!keep.contains(d))
                deleteQuietly(d);
        }
    }

    private static void deleteQuietly(Path d) {
        try (Stream<Path> walk = Files.walk(d)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOGGER.warning("[coinconfig] cache prune failed (" + p + "): " + e.getMessage());
                }
            });
            LOGGER.info("[coinconfig] pruned old cache source " + d.getFileName());
        } catch (IOException e) {
            LOGGER.warning("[coinconfig] cache prune failed (" + d + "): " + e.getMessage());
        }
    }

    private static String sha16(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest)
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
