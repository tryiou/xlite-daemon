package io.cloudchains.app.util;

import java.io.*;

public class CCLogger {
    private static boolean isLogging;

    public static boolean isLoggingEnabled() {
        return isLogging;
    }

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%4$s] %5$s %n");
    }

    public static void setLogging(boolean isEnabled) {
//        System.setErr(new PrintStream(new OutputStream() {
//            public void write(int b) {
//            }
//        }));

        isLogging = isEnabled;
    }
}
