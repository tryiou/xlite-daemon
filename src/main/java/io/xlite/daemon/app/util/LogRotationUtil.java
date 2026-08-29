package io.xlite.daemon.app.util;

import io.xlite.daemon.app.App;

import java.io.File;
import java.util.List;
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
    private static final String LOG_RETENTION_ENV_VAR = "XLITE_DAEMON_LOG_RETENTION_DAYS";

    /**
     * Performs log rotation cleanup.
     * This method should be called during application startup.
     */
    public static void performLogRotation() {
        try {
            String userHomeDir = App.getUserConfigDir();
            String logDirectoryPath = userHomeDir + File.separator + "xlite-daemon";

            // Get retention days from environment variable or use default
            int retentionDays = getRetentionDaysFromEnvironment();

            // Perform log rotation
            LogRotationManager rotationManager = new LogRotationManager(logDirectoryPath, retentionDays);
            boolean success = rotationManager.rotateLogs();

            if (success) {
                // Log current log files after rotation
                List<LogRotationManager.LogFileInfo> logFiles = rotationManager.listLogFiles();
                LOGGER.info("[log-rotation] Current log files after rotation: " + logFiles.size());
                for (LogRotationManager.LogFileInfo fileInfo : logFiles) {
                    LOGGER.fine("[log-rotation]   " + fileInfo.getName() + " (" + fileInfo.getSize() + " bytes)");
                }
            } else {
                LOGGER.warning("[log-rotation] Log rotation completed with errors");
            }

        } catch (Exception e) {
            LOGGER.severe("[log-rotation] Failed to perform log rotation: " + e.getMessage());
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
                    LOGGER.info("[log-rotation] Using retention period from environment: " + retentionDays + " days");
                } else {
                    LOGGER.warning("[log-rotation] Invalid retention period from environment: " + retentionEnv + ". Using default: " + DEFAULT_LOG_RETENTION_DAYS + " days");
                }
            } catch (NumberFormatException e) {
                LOGGER.warning("[log-rotation] Invalid retention period format from environment: " + retentionEnv + ". Using default: " + DEFAULT_LOG_RETENTION_DAYS + " days");
            }
        } else {
            LOGGER.info("[log-rotation] Using default retention period: " + retentionDays + " days");
        }

        return retentionDays;
    }
}
