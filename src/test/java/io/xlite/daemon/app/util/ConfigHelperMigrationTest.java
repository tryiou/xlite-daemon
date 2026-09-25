package io.xlite.daemon.app.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct coverage for migration steps the public path cannot force on a
 * single test filesystem: the staged copy-verify-rename fallback (only
 * taken when an atomic move is unsupported) and the constructor's
 * fail-closed propagation.
 */
class ConfigHelperMigrationTest {

    @Test
    void testCopyVerifyRenameFallback(@TempDir Path tmp) throws IOException {
        Path oldDir = tmp.resolve("CloudChains");
        Path newDir = tmp.resolve("xlite-daemon");
        Path staging = tmp.resolve("xlite-daemon.migrating");
        Files.createDirectories(oldDir.resolve("settings"));
        Files.write(oldDir.resolve("settings").resolve("config-a.json"),
                "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        Files.write(oldDir.resolve("key.dat"), new byte[]{1, 2, 3, 4});

        ConfigHelper.copyVerifyRename(oldDir.toFile(), newDir.toFile(), staging.toFile());

        // Verified content landed via staging; staging renamed away (gone).
        assertEquals("{\"a\":1}", new String(
                Files.readAllBytes(newDir.resolve("settings").resolve("config-a.json")),
                StandardCharsets.UTF_8));
        assertTrue(Files.exists(newDir.resolve("key.dat")));
        assertFalse(Files.exists(staging), "staging must be renamed to the data dir, not left behind");
        // Legacy source untouched throughout: archiving is the caller's job.
        assertTrue(Files.exists(oldDir.resolve("settings").resolve("config-a.json")));
    }

    @Test
    void testCopyVerifyRenameFailureThrows(@TempDir Path tmp) throws IOException {
        Path oldDir = tmp.resolve("CloudChains");
        Path newDir = tmp.resolve("xlite-daemon");
        Files.createDirectories(oldDir);
        // Staging parent is a regular file: nothing can be copied there.
        Path blocker = tmp.resolve("blocker");
        Files.write(blocker, "x".getBytes(StandardCharsets.UTF_8));
        Path staging = blocker.resolve("xlite-daemon.migrating");

        assertThrows(IllegalStateException.class,
                () -> ConfigHelper.copyVerifyRename(oldDir.toFile(), newDir.toFile(), staging.toFile()));
        assertFalse(Files.exists(newDir), "failed fallback must not leave a partial data dir");
    }

    @Test
    void testConstructorPropagatesUnusableDataDir(@TempDir Path tmp) throws IOException {
        String saved = ConfigHelper.CONFIG_DIR;
        try {
            Path blocker = tmp.resolve("blocker");
            Files.write(blocker, "x".getBytes(StandardCharsets.UTF_8));
            ConfigHelper.CONFIG_DIR = blocker.toString();

            // Must propagate fail-closed, never boot on defaults with no wallet.
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new ConfigHelper("test"));
            assertTrue(e.getMessage().contains("data directory unusable"),
                    "message must name the failure, got: " + e.getMessage());
        } finally {
            ConfigHelper.CONFIG_DIR = saved;
        }
    }
}
