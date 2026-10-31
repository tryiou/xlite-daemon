package io.xlite.daemon.app.util;

public class DetectOS {
    public static boolean isUnix = false;
    public static boolean isOSX = false;
    public static boolean isWindows = false;

    static {
        String OS = System.getProperty("os.name").toLowerCase();

        if (OS.contains("win")) isWindows = true;
        else if (OS.contains("mac")) isOSX = true;
        else if (OS.contains("nix") || OS.contains("nux") || OS.contains("aix")) isUnix = true;
    }
}
