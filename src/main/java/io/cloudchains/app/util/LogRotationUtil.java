package io.cloudchains.app.util;

import io.cloudchains.app.App;

import java.io.File;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Utility class for log rotation management.
 * Provides a simple interface to perform log cleanup during application startup.
 */
public class LogRotationUtil {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);
    private static final int DEFAULT_LOG_RETENTION_DAYS = 30;
    private static final String LOG_RETENTION_ENV_VAR = "CLOUDCHAINS_LOG_RETENTION_DAYS";

    /**
     * Performs log rotation cleanup.
     * This method should be called during application startup.
     */
    public static void performLogRotation() {
        try {
            // Determine log directory path (same logic as App.java file handler creation)
            String userHomeDir = getUserConfigDirectory();
            String logDirectoryPath = userHomeDir + File.separator + "CloudChains";

            // Get retention days from environment variable or use default
            int retentionDays = getRetentionDaysFromEnvironment();

            // Perform log rotation
            LogRotationManager rotationManager = new LogRotationManager(logDirectoryPath, retentionDays);
            boolean success = rotationManager.rotateLogs();

            if (success) {
                // Log current log files after rotation
                List<LogRotationManager.LogFileInfo> logFiles = rotationManager.listLogFiles();
                LOGGER.log(Level.INFO, "[log-rotation] Current log files after rotation: {0}", logFiles.size());
                for (LogRotationManager.LogFileInfo fileInfo : logFiles) {
                    LOGGER.log(Level.FINE, "[log-rotation]   {0} ({1} bytes)",
                            new Object[]{fileInfo.getName(), fileInfo.getSize()});
                }
            } else {
                LOGGER.log(Level.WARNING, "[log-rotation] Log rotation completed with errors");
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[log-rotation] Failed to perform log rotation", e);
        }
    }

    /**
     * Gets the user configuration directory based on the operating system.
     * 
     * @return Path to user configuration directory
     */
    private static String getUserConfigDirectory() {
        String OS = (System.getProperty("os.name")).toLowerCase();

        if (OS.contains("win")) {
            return App.getEnv("AppData");
        } else if (OS.contains("nix") || OS.contains("nux") || OS.contains("aix")) {
            return System.getProperty("user.home") + File.separator + ".config";
        } else if (OS.contains("mac")) {
            return System.getProperty("user.home") + File.separator + "Library" + File.separator + "Application Support";
        } else {
            return System.getProperty("user.home") + File.separator + ".config";
        }
    }

    /**
     * Gets the log retention period from environment variable or returns default.
     * 
     * @return Number of days to retain logs
     */
    private static int getRetentionDaysFromEnvironment() {
        int retentionDays = DEFAULT_LOG_RETENTION_DAYS;
        String retentionEnv = App.getEnv(LOG_RETENTION_ENV_VAR);

        if (retentionEnv != null && !retentionEnv.trim().isEmpty()) {
            try {
                int envRetention = Integer.parseInt(retentionEnv.trim());
                if (envRetention > 0) {
                    retentionDays = envRetention;
                    LOGGER.log(Level.INFO, "[log-rotation] Using retention period from environment: {0} days", retentionDays);
                } else {
                    LOGGER.log(Level.WARNING, "[log-rotation] Invalid retention period from environment: {0}. Using default: {1} days",
                            new Object[]{retentionEnv, DEFAULT_LOG_RETENTION_DAYS});
                }
            } catch (NumberFormatException e) {
                LOGGER.log(Level.WARNING, "[log-rotation] Invalid retention period format from environment: {0}. Using default: {1} days",
                        new Object[]{retentionEnv, DEFAULT_LOG_RETENTION_DAYS});
            }
        } else {
            LOGGER.log(Level.INFO, "[log-rotation] Using default retention period: {0} days", retentionDays);
        }

        return retentionDays;
    }
}