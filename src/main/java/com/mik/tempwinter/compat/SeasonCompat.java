package com.mik.tempwinter.compat;

import com.mik.tempwinter.WinterConfig;
import com.mik.tempwinter.weather.RealWeatherService;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Integrates with Serene Seasons and Ecliptic Seasons:
 * - SYNC_REAL_DATE (default): sets Serene Seasons to the real-world sub-season by date & hemisphere.
 * - RESPECT_SEASONS: reads the active season from Serene/Ecliptic Seasons and activates winter effects in winter.
 * - OVERRIDE: always forces winter effects regardless of season.
 */
public final class SeasonCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean SERENE_LOADED = isModLoaded("sereneseasons");
    private static final boolean ECLIPTIC_LOADED = isModLoaded("eclipticseasons");

    private static boolean isModLoaded(String modId) {
        ModList list = ModList.get();
        return list != null && list.isLoaded(modId);
    }

    private static volatile boolean clientIsWinter = true;
    private static volatile boolean serverIsWinter = true;
    private static String lastSyncedSubSeason = null;

    private static boolean sereneReflectionInit = false;
    private static Method sereneGetSeasonState = null;
    private static Method sereneGetSeason = null;
    private static Method sereneGetSubSeason = null;

    private static boolean eclipticReflectionInit = false;
    private static Method eclipticGetInstance = null;
    private static Method eclipticGetSeason = null;

    private SeasonCompat() {
    }

    public static boolean hasSeasonMod() {
        return SERENE_LOADED || ECLIPTIC_LOADED;
    }

    public static void setClientWinterActive(boolean isWinter) {
        clientIsWinter = isWinter;
    }

    public static boolean isClientWinterActive() {
        return clientIsWinter;
    }

    public static void resetCache() {
        lastSyncedSubSeason = null;
        serverIsWinter = isRealDateWinter();
    }

    public static boolean isServerWinterActive() {
        return serverIsWinter;
    }

    public static boolean isWinterActive(Level level) {
        String behavior = WinterConfig.SEASONS_BEHAVIOR.get().toUpperCase(Locale.ROOT);
        boolean active;
        if ("OVERRIDE".equals(behavior)) {
            active = true;
        } else if ("RESPECT_SEASONS".equals(behavior)) {
            String season = getModSeasonName(level);
            active = season != null ? "WINTER".equalsIgnoreCase(season) : isRealDateWinter();
        } else if (hasSeasonMod()) {
            String season = getModSeasonName(level);
            active = season != null ? "WINTER".equalsIgnoreCase(season) : isRealDateWinter();
        } else {
            active = isRealDateWinter();
        }
        serverIsWinter = active;
        return active;
    }

    public static void syncRealSeasonIfNeeded(MinecraftServer server) {
        if (!WinterConfig.ENABLED.get() || !"SYNC_REAL_DATE".equalsIgnoreCase(WinterConfig.SEASONS_BEHAVIOR.get())) {
            return;
        }
        if (!SERENE_LOADED) {
            return;
        }
        String targetSubSeason = getRealSubSeasonName();
        if (targetSubSeason.equalsIgnoreCase(lastSyncedSubSeason)) {
            return;
        }
        String current = getSereneSubSeasonName(server.overworld());
        if (targetSubSeason.equalsIgnoreCase(current)) {
            lastSyncedSubSeason = targetSubSeason;
            return;
        }
        try {
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput().withPermission(4),
                    "season set " + targetSubSeason.toLowerCase(Locale.ROOT)
            );
            lastSyncedSubSeason = targetSubSeason;
            LOGGER.info("[TempWinter] Synced Serene Seasons to IRL sub-season: {}", targetSubSeason);
        } catch (Throwable t) {
            LOGGER.warn("[TempWinter] Failed to sync Serene Seasons: {}", t.getMessage());
        }
    }

    public static boolean isRealDateWinter() {
        String sub = getRealSubSeasonName();
        return sub.endsWith("_WINTER");
    }

    public static String getRealSubSeasonName() {
        LocalDate now = LocalDate.now();
        int month = now.getMonthValue();
        int day = now.getDayOfMonth();
        boolean southern = RealWeatherService.getResolvedLatitude() < 0.0;
        if (southern) {
            month = ((month + 5) % 12) + 1;
        }
        String season = switch (month) {
            case 12, 1, 2 -> "WINTER";
            case 3, 4, 5 -> "SPRING";
            case 6, 7, 8 -> "SUMMER";
            default -> "AUTUMN";
        };
        String phase = switch (month) {
            case 12, 3, 6, 9 -> (day >= 15 ? "EARLY_" : "EARLY_");
            case 1, 4, 7, 10 -> "MID_";
            default -> "LATE_";
        };
        return phase + season;
    }

    public static String getModSeasonName(Level level) {
        if (level == null) {
            return null;
        }
        if (SERENE_LOADED) {
            String s = getSereneSeasonName(level);
            if (s != null) {
                return s;
            }
        }
        if (ECLIPTIC_LOADED) {
            return getEclipticSeasonName(level);
        }
        return null;
    }

    private static String getSereneSeasonName(Level level) {
        initSereneReflection();
        if (sereneGetSeasonState == null || sereneGetSeason == null) {
            return null;
        }
        try {
            Object state = sereneGetSeasonState.invoke(null, level);
            Object season = sereneGetSeason.invoke(state);
            return season != null ? season.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String getSereneSubSeasonName(Level level) {
        initSereneReflection();
        if (sereneGetSeasonState == null || sereneGetSubSeason == null) {
            return null;
        }
        try {
            Object state = sereneGetSeasonState.invoke(null, level);
            Object sub = sereneGetSubSeason.invoke(state);
            return sub != null ? sub.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void initSereneReflection() {
        if (sereneReflectionInit) {
            return;
        }
        sereneReflectionInit = true;
        try {
            Class<?> helper = Class.forName("sereneseasons.api.season.SeasonHelper");
            Class<?> state = Class.forName("sereneseasons.api.season.ISeasonState");
            sereneGetSeasonState = helper.getMethod("getSeasonState", Level.class);
            sereneGetSeason = state.getMethod("getSeason");
            sereneGetSubSeason = state.getMethod("getSubSeason");
        } catch (Throwable ignored) {
        }
    }

    private static String getEclipticSeasonName(Level level) {
        if (!eclipticReflectionInit) {
            eclipticReflectionInit = true;
            try {
                Class<?> api = Class.forName("com.teamtea.eclipticseasons.api.EclipticSeasonsApi");
                eclipticGetInstance = api.getMethod("getInstance");
                eclipticGetSeason = api.getMethod("getSeason", Level.class);
            } catch (Throwable ignored) {
            }
        }
        if (eclipticGetInstance == null || eclipticGetSeason == null) {
            return null;
        }
        try {
            Object inst = eclipticGetInstance.invoke(null);
            Object season = eclipticGetSeason.invoke(inst, level);
            return season != null ? season.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
