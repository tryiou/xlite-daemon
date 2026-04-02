package io.cloudchains.app.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Manages log file rotation and cleanup to prevent disk space issues.
 * 
 * Features:
 * - Configurable log retention period (default: 7 days)
 * - Automatic cleanup of old log files during startup
 * - Support for both .log and .log.1 files (rotated by FileHandler)
 * - Graceful handling of missing directories and files
 * - Detailed logging of cleanup operations
 * 
 * Log file naming pattern: error-YYYY-MM-DD.log[.1]
 * Example: error-2025-12-12.log, error-2025-12-12.log.1
 */
public class LogRotationManager {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);
    private static final String LOG_PREFIX = "error-";
    private static final String LOG_SUFFIX = ".log";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Path logDirectory;
    private final int retentionDays;

    /**
     * Creates a new LogRotationManager.
     * 
     * @param logDirectoryPath Path to the directory containing log files
     * @param retentionDays Number of days to keep log files (default: 7)
     */
    public LogRotationManager(String logDirectoryPath, int retentionDays) {
        this.logDirectory = Paths.get(logDirectoryPath);
        this.retentionDays = Math.max(1, retentionDays); // Ensure at least 1 day retention
    }

    /**
     * Creates a LogRotationManager with default 7-day retention.
     * 
     * @param logDirectoryPath Path to the directory containing log files
     */
    public LogRotationManager(String logDirectoryPath) {
        this(logDirectoryPath, 7);
    }

    /**
     * Performs log rotation cleanup by removing files older than the retention period.
     * 
     * @return true if cleanup completed successfully, false otherwise
     */
    public boolean rotateLogs() {
        try {
            if (!ensureLogDirectoryExists()) {
                return false;
            }

            List<File> oldLogFiles = findOldLogFiles();

            if (oldLogFiles.isEmpty()) {
                LOGGER.log(Level.INFO, "[log-rotation] No old log files found. Current retention: {0} days", retentionDays);
                return true;
            }

            LOGGER.log(Level.INFO, "[log-rotation] Found {0} old log files to clean up", oldLogFiles.size());

            long totalSize = 0;
            int deletedCount = 0;

            for (File file : oldLogFiles) {
                try {
                    long fileSize = file.length();
                    if (file.delete()) {
                        totalSize += fileSize;
                        deletedCount++;
                        LOGGER.log(Level.FINE, "[log-rotation] Deleted: {0} ({1} bytes)",
                                new Object[]{file.getName(), fileSize});
                    } else {
                        LOGGER.log(Level.WARNING, "[log-rotation] Failed to delete: {0}", file.getName());
                    }
                } catch (SecurityException e) {
                    LOGGER.log(Level.SEVERE, "[log-rotation] Security exception deleting file: " + file.getName(), e);
                }
            }

            LOGGER.log(Level.INFO, "[log-rotation] Cleanup completed: {0}/{1} files deleted, {2} bytes freed",
                    new Object[]{deletedCount, oldLogFiles.size(), totalSize});

            return true;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[log-rotation] Error during log rotation", e);
            return false;
        }
    }

    /**
     * Lists all log files in the directory with their details.
     * 
     * @return List of log file details
     */
    public List<LogFileInfo> listLogFiles() {
        List<LogFileInfo> files = new ArrayList<>();

        try {
            if (!Files.exists(logDirectory)) {
                return files;
            }

            Files.list(logDirectory)
                    .filter(path -> isLogFile(path.toFile()))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .forEach(path -> {
                        File file = path.toFile();
                        files.add(new LogFileInfo(
                                file.getName(),
                                file.length(),
                                file.lastModified()
                        ));
                    });

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[log-rotation] Error listing log files", e);
        }

        return files;
    }

    /**
     * Gets the current retention period in days.
     * 
     * @return Number of days logs are retained
     */
    public int getRetentionDays() {
        return retentionDays;
    }

    /**
     * Checks if a file is a log file based on naming pattern.
     * 
     * @param file File to check
     * @return true if it's a log file, false otherwise
     */
    private boolean isLogFile(File file) {
        String name = file.getName();
        return name.startsWith(LOG_PREFIX) &&
                name.endsWith(LOG_SUFFIX) &&
                extractDateFromFileName(name) != null;
    }

    /**
     * Extracts date from log file name.
     * 
     * @param fileName Name of the log file
     * @return LocalDate if valid, null otherwise
     */
    private LocalDate extractDateFromFileName(String fileName) {
        try {
            // Remove prefix and suffix to get date part
            String datePart = fileName
                    .replaceFirst("^" + LOG_PREFIX, "")
                    .replaceFirst("\\.log.*$", "");

            return LocalDate.parse(datePart, DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            LOGGER.log(Level.FINE, "[log-rotation] Could not parse date from filename: " + fileName);
            return null;
        }
    }

    /**
     * Finds all log files older than the retention period.
     * 
     * @return List of old log files
     */
    private List<File> findOldLogFiles() {
        List<File> oldFiles = new ArrayList<>();
        LocalDate cutoffDate = LocalDate.now().minusDays(retentionDays);

        try {
            if (!Files.exists(logDirectory)) {
                return oldFiles;
            }

            Files.list(logDirectory)
                    .filter(path -> isLogFile(path.toFile()))
                    .forEach(path -> {
                        File file = path.toFile();
                        LocalDate fileDate = extractDateFromFileName(file.getName());

                        if (fileDate != null && fileDate.isBefore(cutoffDate)) {
                            oldFiles.add(file);
                        }
                    });

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[log-rotation] Error finding old log files", e);
        }

        return oldFiles;
    }

    /**
     * Ensures the log directory exists, creating it if necessary.
     * 
     * @return true if directory exists or was created successfully
     */
    private boolean ensureLogDirectoryExists() {
        try {
            if (!Files.exists(logDirectory)) {
                Files.createDirectories(logDirectory);
                LOGGER.log(Level.INFO, "[log-rotation] Created log directory: {0}", logDirectory);
            }
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "[log-rotation] Failed to create log directory: " + logDirectory, e);
            return false;
        }
    }

    /**
     * Immutable class representing log file information.
     */
    public static class LogFileInfo {
        private final String name;
        private final long size;
        private final long lastModified;

        public LogFileInfo(String name, long size, long lastModified) {
            this.name = name;
            this.size = size;
            this.lastModified = lastModified;
        }

        public String getName() {
            return name;
        }

        public long getSize() {
            return size;
        }

        public long getLastModified() {
            return lastModified;
        }

        @Override
        public String toString() {
            return String.format("LogFileInfo{name='%s', size=%d bytes, lastModified=%d}", name, size, lastModified);
        }
    }
}