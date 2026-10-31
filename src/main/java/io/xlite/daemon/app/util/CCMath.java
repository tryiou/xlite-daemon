package io.xlite.daemon.app.util;

public final class CCMath {
    private CCMath() {}

    public static double roundToDecimalPlaces(double value, int decimalPlaces) {
        double scale = Math.pow(10, decimalPlaces);
        return Math.round(value * scale) / scale;
    }
}
