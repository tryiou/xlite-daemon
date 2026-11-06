package io.xlite.daemon.app.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Daily log discovery and retention.
 *
 * <p>{@link java.util.logging.FileHandler} appends a generation number when the pattern has no
 * {@code %g}, so the files on disk are {@code error-YYYY-MM-DD.log.0} and upward. The cleanup must
 * delete those, not only a plain {@code .log}, while leaving the handler's {@code .lck} file and
 * unrelated files alone.
 */
class LogRotationManagerTest {

    @TempDir Path tmp;

    @Test
    void deletesPlainAndRotatedDailyLogs() throws IOException {
        Path plain = create("error-2020-01-01.log");
        Path rotated = create("error-2020-01-01.log.0");
        Path last = create("error-2020-01-01.log.4");
        Path lock = create("error-2020-01-01.log.0.lck");
        Path unrelated = create("wallet.log");
        Path notADate = create("error-notadate.log");

        LogRotationManager manager = new LogRotationManager(tmp.toString(), 1);

        assertTrue(manager.rotateLogs());
        assertFalse(Files.exists(plain));
        assertFalse(Files.exists(rotated));
        assertFalse(Files.exists(last));
        assertTrue(Files.exists(lock));
        assertTrue(Files.exists(unrelated));
        assertTrue(Files.exists(notADate));
    }

    @Test
    void listsPlainAndRotatedDailyLogsOnly() throws IOException {
        create("error-2020-01-01.log");
        create("error-2020-01-01.log.0");
        create("error-2020-01-01.log.0.lck");
        create("wallet.log");

        LogRotationManager manager = new LogRotationManager(tmp.toString(), 1);

        List<String> names =
                manager.listLogFiles().stream()
                        .map(LogRotationManager.LogFileInfo::getName)
                        .toList();

        assertEquals(List.of("error-2020-01-01.log", "error-2020-01-01.log.0"), names);
    }

    private Path create(String name) throws IOException {
        Path file = tmp.resolve(name);
        Files.writeString(file, "x");
        return file;
    }
}
