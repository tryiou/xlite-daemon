package io.xlite.daemon.app.util;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.xlite.daemon.app.App;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class ConfigHelper {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private String tickerStr;
    private File file;

    private long feePerByte;
    private long minTxFee;
    private boolean rpcEnabled;
    private String rpcUsername;
    private String rpcPassword;
    private int rpcPort;
    private int addressCount;

    // Override specific configuration directory (useful in unit tests)
    public static String CONFIG_DIR = ""; // Must not end with [/], e.g. /home/user/.config, not /home/user/.config/

    // Data directory names under the platform config root.
    public static final String DATA_DIR_NAME = "xlite-daemon";
    private static final String LEGACY_DIR_NAME = "CloudChains";
    private static final String LEGACY_BACKUP_SUFFIX = ".bak";
    private static final String MIGRATION_STAGING_SUFFIX = ".migrating";
    // Staging directory name, derived from the data dir name. Public (like the
    // lock file below) so the test harness can clean sandboxed leftovers.
    public static final String MIGRATION_STAGING_NAME = DATA_DIR_NAME + MIGRATION_STAGING_SUFFIX;
    // On-disk inter-process migration lock. Public so the test harness can
    // clean it alongside the sandboxed data dir. Intentionally never deleted
    // in production: an empty sentinel file is harmless, while deleting a
    // lock another process may be opening is not.
    public static final String MIGRATION_LOCK_FILE = ".migration.lock";
    private static final Object MIGRATION_LOCK = new Object();

    public ConfigHelper(String tickerStr) {
        this.tickerStr = Preconditions.checkNotNull(tickerStr, "tickerStr must not be null");

        try {
            file = Preconditions.checkNotNull(this.getFile(), "getFile() returned null for " + tickerStr);
            loadConfig();
        } catch (Exception e) {
            // Fail-closed: a null file means the data directory itself is
            // unusable (migration or creation failed). Swallowing that here
            // would boot the daemon on defaults with no wallet on disk.
            if (file == null) {
                throw new IllegalStateException(
                        "[config] Failed to initialize config for " + tickerStr + ": data directory unusable: "
                                + e.getMessage(), e);
            }
            LOGGER.warning("[config] Failed to initialize config for " + tickerStr + ", " + e.getMessage());
        }
    }

    public synchronized void loadConfig() {
        try {
            String rawConfig = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (rawConfig.isEmpty()) {
                rpcEnabled = false;
                rpcUsername = "";
                rpcPassword = "";
                rpcPort = defaultRpcPort();
                addressCount = 0;

                writeConfig();
                return;
            }

            JSONObject config = new JSONObject(rawConfig);

            final String[] configKeys = new String[]{
                    "feeperbyte",
                    "mintxfee",
                    "rpcEnabled",
                    "rpcUsername",
                    "rpcPassword",
                    "rpcPort",
                    "addressCount"
            };

            for (String configKey : configKeys) {
                if (!config.has(configKey)) {
                    LOGGER.finer("[config] Missing config key '" + configKey + "' for " + tickerStr + ", will use default");
                }
            }

            boolean needsWrite = false;

            if (!config.has("feeperbyte")) {
                needsWrite = true;
            } else {
                feePerByte = config.getLong("feeperbyte");
            }

            if (!config.has("mintxfee")) {
                needsWrite = true;
            } else {
                minTxFee = config.getLong("mintxfee");
            }

            if (!config.has("rpcEnabled")) {
                rpcEnabled = false;
                needsWrite = true;
            } else {
                rpcEnabled = config.getBoolean("rpcEnabled");
            }

            if (!config.has("rpcUsername")) {
                rpcUsername = "";
                needsWrite = true;
            } else {
                rpcUsername = config.getString("rpcUsername");
            }

            if (!config.has("rpcPassword")) {
                rpcPassword = "";
                needsWrite = true;
            } else {
                rpcPassword = config.getString("rpcPassword");
            }

            if (!config.has("rpcPort")) {
                rpcPort = defaultRpcPort();
                needsWrite = true;
            } else {
                rpcPort = config.getInt("rpcPort");
                if (rpcPort == 0) {
                    rpcPort = -1000;
                    needsWrite = true;
                }
            }

            if (!config.has("addressCount")) {
                setAddressCount(0);
                needsWrite = true;
            } else {
                addressCount = config.getInt("addressCount");
            }

            if (needsWrite) {
                writeConfig();
            }
        } catch (Exception e) {
            LOGGER.warning("[config] Error reading config file for " + tickerStr + ", " + e.getMessage());
        }
    }

    private File getFile() {
        String userHome = getLocalDataDirectory();
        Preconditions.checkNotNull(userHome);

        File home = new File(userHome);
        File settingsDirectory = new File(home, "settings");
        if (!settingsDirectory.exists()) {
            if (!settingsDirectory.isDirectory() && !settingsDirectory.mkdirs()
                    && !settingsDirectory.isDirectory()) {
                LOGGER.finer("[config] ERROR: Could not create base/settings directory!");
                return null;
            }
        }
        // A regular file (or anything non-directory) at settings/ would make
        // every config write below fail: refuse instead of booting on defaults.
        if (!settingsDirectory.isDirectory()) {
            LOGGER.warning("[config] settings path is not a directory: " + settingsDirectory);
            return null;
        }

        File configFile = new File(settingsDirectory, "config-" + tickerStr + ".json");
        try {
            if (!configFile.createNewFile() && !configFile.exists())
                return null;
        } catch (IOException e) {
            LOGGER.warning("[config] IOException creating config file for " + tickerStr + ", " + e.getMessage());
            return null;
        }

        return configFile;
    }

    public synchronized void setFeePerByte(long feePerByte) {
        this.feePerByte = feePerByte;
    }

    public synchronized void setMinTxFee(long minTxFee) {
        this.minTxFee = minTxFee;
    }

    public synchronized void setRpcEnabled(boolean isEnabled) {
        this.rpcEnabled = isEnabled;
    }

    public synchronized void setRpcUsername(String user) {
        this.rpcUsername = user;
    }

    public synchronized void setRpcPassword(String pass) {
        this.rpcPassword = pass;
    }

    public synchronized boolean setRpcPort(int rpcPort) {
        if (rpcPort < 1 || rpcPort > 65535) {
            LOGGER.warning("[config] Invalid port " + rpcPort + ", must be 1-65535");
            return false;
        }
        int maxAttempts = 100;
        for (int i = 0; i < maxAttempts && rpcPort + i <= 65535; i++) {
            if (PortCheck.available(rpcPort + i)) {
                this.rpcPort = rpcPort + i;
                return true;
            }
        }
        LOGGER.warning("[config] No available port in range " + rpcPort + "-" + Math.min(rpcPort + maxAttempts - 1, 65535));
        return false;
    }

    public synchronized void setAddressCount(int addressCount) {
        this.addressCount = addressCount;
    }

    public synchronized long getFeePerByte() {
        return feePerByte;
    }

    public synchronized long getMinTxFee() {
        return minTxFee;
    }

    public synchronized boolean isRpcEnabled() {
        return rpcEnabled;
    }

    public synchronized String getRpcUsername() {
        return rpcUsername;
    }

    public synchronized String getRpcPassword() {
        return rpcPassword;
    }

    private int defaultRpcPort() {
        return this.tickerStr.equalsIgnoreCase("master") ? 9955 : -1000;
    }

    public synchronized int getMasterRpcPort() {
        if (rpcPort == -1000) {
            return 9955;
        }
        return rpcPort;
    }

    public synchronized int getRpcPort() {
        return rpcPort;
    }

    public synchronized int getAddressCount() {
        return addressCount;
    }

    private JSONObject toConfigJson() {
        JSONObject config = new JSONObject();
        config.put("feeperbyte", feePerByte);
        config.put("mintxfee", minTxFee);
        config.put("rpcEnabled", rpcEnabled);
        config.put("rpcUsername", rpcUsername);
        config.put("rpcPassword", rpcPassword);
        config.put("rpcPort", rpcPort);
        config.put("addressCount", addressCount);
        return config;
    }

    public synchronized boolean validAuth() {
        return rpcUsername != null && !rpcUsername.isEmpty() && rpcPassword != null && !rpcPassword.isEmpty();
    }

    public synchronized void writeConfig() {
        try {
            String newContent = toConfigJson().toString(4);

            if (file.exists()) {
                String existingContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                if (existingContent.equals(newContent)) {
                    return;
                }
            }

            try (FileWriter fw = new FileWriter(file, false)) {
                fw.write(newContent);
            }
        } catch (IOException e) {
            LOGGER.warning("[config] IOException writing config for " + tickerStr + ", " + e.getMessage());
        }
    }

    public static String getLocalDataDirectory() {
        String baseDir;
        if (CONFIG_DIR == null || CONFIG_DIR.isEmpty()) {
            baseDir = App.getUserConfigDir();
        } else {
            baseDir = CONFIG_DIR;
        }
        // Fail fast on a null/blank base (e.g. missing Windows AppData)
        // instead of stringifying to a bogus "null/xlite-daemon/" dir.
        if (baseDir == null || baseDir.trim().isEmpty()) {
            throw new IllegalStateException(
                    "[config] Cannot resolve data directory: base config dir is null or blank. "
                            + "Set XLITE_DATA_HOME to an explicit path.");
        }

        File newDir = new File(baseDir, DATA_DIR_NAME);
        File oldDir = new File(baseDir, LEGACY_DIR_NAME);
        // Intra-JVM mutual exclusion plus an inter-process file lock: supervisors
        // restart daemons, and two processes racing on the shared staging dir
        // would otherwise wipe each other's in-progress copy. The loser of a
        // real race blocks here, then finds the source already reconciled.
        synchronized (MIGRATION_LOCK) {
            File base = new File(baseDir);
            // Re-check after mkdirs: a concurrent creator winning the race
            // makes mkdirs return false for an existing dir (no failure).
            if (!base.isDirectory() && !base.mkdirs() && !base.isDirectory()) {
                throw new IllegalStateException(
                        "[config] Cannot create base config directory: " + base
                                + ". Check permissions or set XLITE_DATA_HOME.");
            }
            try (RandomAccessFile lockRaf = new RandomAccessFile(new File(base, MIGRATION_LOCK_FILE), "rw");
                 FileLock migrationLock = acquireMigrationLock(lockRaf, base)) {
                // Blocking acquire is intentional: the loser waits, then finds
                // the source already reconciled. A second daemon only ever
                // blocks here during an in-progress large copy.
                migrateLegacyDirectory(baseDir, oldDir, newDir);
                if (!newDir.isDirectory() && !newDir.mkdirs() && !newDir.isDirectory()) {
                    throw new IllegalStateException(
                            "[config] Cannot create data directory: " + newDir
                                    + ". Check permissions or set XLITE_DATA_HOME.");
                }
            } catch (OverlappingFileLockException e) {
                // Unreachable through our own paths (same-JVM overlap is
                // excluded by MIGRATION_LOCK and the file is ours alone),
                // but wrap precisely rather than leak an undecorated throw.
                throw new IllegalStateException(
                        "[config] Data directory migration lock failed for " + base + ": overlapping lock", e);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "[config] Data directory migration lock failed for " + base + ": " + e.getMessage(), e);
            }
        }

        return newDir.getPath() + File.separator;
    }

    /**
     * Blocking inter-process acquire with diagnosis logging. Blocking (not
     * tryLock-with-timeout) is intentional: an OS-managed wait cannot
     * spuriously fail a healthy startup; the loser always wakes to find the
     * source already reconciled.
     */
    private static FileLock acquireMigrationLock(RandomAccessFile lockRaf, File base) throws IOException {
        LOGGER.fine("[migrate] waiting for data directory migration lock for " + base);
        long startNanos = System.nanoTime();
        FileLock lock = lockRaf.getChannel().lock();
        long waitedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        // Operator-visible only when actually contended: uncontended startup stays quiet.
        if (waitedMillis > 1000) {
            LOGGER.info("[migrate] waited " + waitedMillis + " ms for data directory migration lock for " + base);
        } else {
            LOGGER.fine("[migrate] holding data directory migration lock for " + base);
        }
        return lock;
    }
    /**
     * Reconciles the legacy {@code CloudChains/} data directory with the new
     * {@code xlite-daemon/} one. Fail-closed: any unverifiable state throws
     * instead of booting on a partial or empty directory.
     *
     * <p>States handled:
     * <ul>
     *   <li>No legacy dir: nothing to do (fresh install or already migrated).</li>
     *   <li>Legacy dir, no new dir: same-filesystem rename, else staged
     *   copy + byte-verify + rename, then archive the legacy dir.</li>
     *   <li>Both dirs: merge-verify (idempotent; byte-identical files are
     *   fine), then archive the legacy dir. Byte-divergent files throw:
     *   either side could be the real wallet, so there is no safe choice.</li>
     * </ul>
     *
     * <p>The legacy source is never deleted until its content is verified
     * present at the destination; it is archived (never deleted) after that.
     * Our own staging leftover is wiped and redone from the intact source.
     */
    private static void migrateLegacyDirectory(String baseDir, File oldDir, File newDir) {
        File staging = new File(baseDir, MIGRATION_STAGING_NAME);
        if (staging.exists()) {
            deleteDirectoryRecursively(staging);
        }
        if (!oldDir.exists()) {
            return;
        }
        if (!newDir.exists()) {
            try {
                Files.move(oldDir.toPath(), newDir.toPath(), StandardCopyOption.ATOMIC_MOVE);
                LOGGER.info("[migrate] moved legacy data directory from " + oldDir + " to " + newDir);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                LOGGER.warning("[migrate] atomic move unsupported (" + e.getMessage() + "), copy-verify-archive fallback");
            } catch (IOException e) {
                LOGGER.warning("[migrate] move failed (" + e.getMessage() + "), copy-verify-archive fallback");
            }
            copyVerifyRename(oldDir, newDir, staging);
            archiveLegacyDir(baseDir, oldDir);
            return;
        }
        // Both exist: refuse early when archiving is impossible, so the
        // refusal below is side-effect-free (mergeVerify mutates newDir).
        if (new File(baseDir, LEGACY_DIR_NAME + LEGACY_BACKUP_SUFFIX).exists()) {
            throw new IllegalStateException(
                    "[migrate] refusing to boot: legacy backup already exists and legacy dir "
                            + oldDir + " is still present. Resolve manually.");
        }
        mergeVerify(oldDir, newDir);
        archiveLegacyDir(baseDir, oldDir);
    }

    /**
     * Copies {@code oldDir} to {@code staging}, byte-verifies every legacy
     * file landed identically, then renames staging to {@code newDir}.
     * Throws on any failure; the legacy source is untouched throughout.
     *
     * <p>Visible for testing: the cross-filesystem fallback cannot be forced
     * through the public path on a single test filesystem, so the suite
     * drives this step directly with a hand-built staging dir.
     */
    @VisibleForTesting
    static void copyVerifyRename(File oldDir, File newDir, File staging) {
        try {
            copyDirectoryRecursively(oldDir, staging);
            verifyTreeContains(staging, oldDir);
            Files.move(staging.toPath(), newDir.toPath());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "[migrate] copy-verify-rename failed for " + oldDir + " -> " + newDir + ": " + e.getMessage(), e);
        }
        verifyTreeContains(newDir, oldDir);
        LOGGER.info("[migrate] copied legacy data directory from " + oldDir + " to " + newDir);
    }

    /**
     * Merges legacy files missing from the new dir (never overwrites), then
     * verifies every legacy file is present with identical bytes. Byte-
     * divergent files throw: no automatic choice is safe for wallet data.
     *
     * <p>Conflict detection runs as a pure verification pass before any copy,
     * so a refusal leaves the live dir untouched (fail-closed, side-effect-free).
     */
    private static void mergeVerify(File oldDir, File newDir) {
        List<String> conflicts = detectConflicts(oldDir, newDir);
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException(
                    "[migrate] refusing to boot: legacy and new data dirs diverge on "
                            + conflicts.size() + " file(s) " + conflicts
                            + ". Resolve manually (either side could be the real wallet).");
        }
        try (Stream<Path> walk = Files.walk(oldDir.toPath())) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path relative = oldDir.toPath().relativize(source);
                Path target = newDir.toPath().resolve(relative);
                if (Files.isDirectory(source)) {
                    if (!Files.exists(target)) {
                        Files.createDirectories(target);
                    }
                } else if (Files.isRegularFile(source)) {
                    if (!Files.exists(target)) {
                        Files.copy(source, target);
                    }
                } else {
                    throw new IllegalStateException(
                            "[migrate] unsupported legacy entry (not a file or directory): " + source);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "[migrate] merge failed for " + oldDir + " -> " + newDir + ": " + e.getMessage(), e);
        }
        // Symmetric with the copy path: re-verify after writing, before the
        // only other good copy is archived away.
        verifyTreeContains(newDir, oldDir);
    }

    /**
     * Pure verification pass: returns relative paths of byte-divergent files
     * present on both sides. Copies nothing.
     */
    private static List<String> detectConflicts(File oldDir, File newDir) {
        List<String> conflicts = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(oldDir.toPath())) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(source)) {
                    continue;
                }
                Path relative = oldDir.toPath().relativize(source);
                Path target = newDir.toPath().resolve(relative);
                if (Files.isRegularFile(target) && Files.mismatch(source, target) != -1) {
                    conflicts.add(relative.toString());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "[migrate] conflict scan failed for " + oldDir + " vs " + newDir + ": " + e.getMessage(), e);
        }
        return conflicts;
    }

    /**
     * Every regular file under {@code required} must exist under
     * {@code container} with identical bytes. One-directional: files created
     * fresh in the container (e.g. logs) are fine.
     */
    private static void verifyTreeContains(File container, File required) {
        try (Stream<Path> walk = Files.walk(required.toPath())) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path relative = required.toPath().relativize(source);
                Path target = container.toPath().resolve(relative);
                if (Files.isDirectory(source)) {
                    if (!Files.isDirectory(target)) {
                        throw new IllegalStateException("[migrate] verification failed: missing directory " + relative);
                    }
                } else if (Files.isRegularFile(source)) {
                    if (!Files.isRegularFile(target) || Files.mismatch(source, target) != -1) {
                        throw new IllegalStateException("[migrate] verification failed: " + relative + " missing or divergent");
                    }
                } else {
                    throw new IllegalStateException("[migrate] verification failed: unsupported entry " + source);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "[migrate] verification failed for " + required + " in " + container + ": " + e.getMessage(), e);
        }
    }

    /**
     * Moves the reconciled legacy dir aside for rollback. Never deletes user
     * data, never overwrites an existing backup: both throw fail-closed.
     */
    private static void archiveLegacyDir(String baseDir, File oldDir) {
        File backup = new File(baseDir, LEGACY_DIR_NAME + LEGACY_BACKUP_SUFFIX);
        if (backup.exists()) {
            throw new IllegalStateException(
                    "[migrate] refusing to boot: legacy backup already exists at " + backup
                            + " and legacy dir " + oldDir + " is still present. Resolve manually.");
        }
        try {
            Files.move(oldDir.toPath(), backup.toPath());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "[migrate] failed to archive legacy dir " + oldDir + " to " + backup + ": " + e.getMessage(), e);
        }
        LOGGER.info("[migrate] archived legacy data directory from " + oldDir + " to " + backup);
    }

    private static void deleteDirectoryRecursively(File dir) {
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            for (Path path : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new IllegalStateException("[migrate] failed to clean staging dir " + dir + ": " + e.getMessage(), e);
        }
    }

    private static void copyDirectoryRecursively(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs() && !target.exists()) {
                throw new IOException("Failed to create directory " + target);
            }
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyDirectoryRecursively(child, new File(target, child.getName()));
                }
            }
        } else {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                throw new IOException("Failed to create directory " + parent);
            }
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
