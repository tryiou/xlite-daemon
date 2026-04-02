package io.cloudchains.app.util;

import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * Custom formatter for console output that produces clean, readable logs.
 * Format: LEVEL: message
 * Example: INFO: Wallet initialized successfully
 * 
 * This formatter:
 * - Uses English locale for consistent output
 * - Excludes timestamps and class/method information
 * - Provides clean, user-friendly console output
 */
public class ConsoleFormatter extends Formatter {

    private static final String LINE_SEPARATOR = System.getProperty("line.separator");

    @Override
    public String format(LogRecord record) {
        StringBuilder sb = new StringBuilder();

        // Format: LEVEL: message
        sb.append(record.getLevel().getName());
        sb.append(": ");
        sb.append(formatMessage(record));
        sb.append(LINE_SEPARATOR);

        // Include thrown exception if present
        if (record.getThrown() != null) {
            try {
                sb.append("Exception: ");
                sb.append(record.getThrown().toString());
                sb.append(LINE_SEPARATOR);

                // Add stack trace
                for (StackTraceElement element : record.getThrown().getStackTrace()) {
                    sb.append("\tat ");
                    sb.append(element.toString());
                    sb.append(LINE_SEPARATOR);
                }
            } catch (Exception ex) {
                // Ignore exceptions during exception formatting
            }
        }

        return sb.toString();
    }
}