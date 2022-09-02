package io.cloudchains.app.util;

public class DetectOS {
    static public boolean isUnix = false;
    static public boolean isOSX = false;
    static public boolean isWindows = false;

    static {
        String OS = System.getProperty("os.name").toLowerCase();

        if (OS.contains("win"))
            isWindows = true;
        else if (OS.contains("mac"))
            isOSX = true;
        else if (OS.contains("nix") || OS.contains("nux") || OS.contains("aix"))
            isUnix = true;
    }
}
