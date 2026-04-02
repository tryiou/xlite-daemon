package io.cloudchains.app.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * Custom formatter for file output that produces detailed logs with timestamps and class information.
 * Format: yyyy-MM-dd HH:mm:ss class.method: message
 * Example: 2025-12-12 12:01:51 io.cloudchains.app.console.ConsoleMenu.init: Wallet initialized
 * 
 * This formatter:
 * - Uses English locale for consistent date formatting
 * - Includes full timestamp with seconds precision
 * - Includes fully qualified class name and method
 * - Provides detailed information for debugging and auditing
 */
public class FileFormatter extends Formatter {

    private static final String LINE_SEPARATOR = System.getProperty("line.separator");
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);

    @Override
    public String format(LogRecord record) {
        StringBuilder sb = new StringBuilder();

        // Format timestamp
        sb.append(dateFormat.format(new Date(record.getMillis())));
        sb.append(" ");

        // Format class and method information
        // Removed class and method information from log output

        // Format level and message
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