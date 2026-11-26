package com.mik.tempwinter.weather;

/**
 * Holds client-side real-weather snowfall/rain intensity and type synced from the server.
 */
public final class ClientWeatherState {
    private static volatile boolean active = false;
    private static volatile float intensity = 1.0F;
    private static volatile boolean isRain = false;
    private static volatile double tempC = -1.0;
    private static volatile double windKmh = 0.0;
    private static volatile boolean winterSeasonActive = true;
    private static volatile boolean inOverworld = true;

    private ClientWeatherState() {
    }

    public static void update(boolean isActive, float newIntensity, boolean rain, double temperatureC, double windSpeedKmh, boolean winterSeason) {
        active = isActive;
        intensity = Math.max(0.0F, Math.min(1.0F, newIntensity));
        isRain = rain;
        tempC = temperatureC;
        windKmh = windSpeedKmh;
        winterSeasonActive = winterSeason;
    }

    public static boolean isActive() {
        return active;
    }

    public static float getIntensity() {
        return intensity;
    }

    public static boolean isRain() {
        return isRain;
    }

    public static double getTempC() {
        return tempC;
    }

    public static double getWindKmh() {
        return windKmh;
    }

    public static boolean isWinterSeasonActive() {
        return winterSeasonActive;
    }

    public static void setInOverworld(boolean overworld) {
        inOverworld = overworld;
    }

    public static boolean isInOverworld() {
        return inOverworld;
    }

    public static void reset() {
        active = false;
        intensity = 1.0F;
        isRain = false;
        tempC = -1.0;
        windKmh = 0.0;
        winterSeasonActive = true;
        inOverworld = true;
    }
}
