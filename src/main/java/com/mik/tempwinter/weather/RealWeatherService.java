package com.mik.tempwinter.weather;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mik.tempwinter.WinterConfig;
import com.mik.tempwinter.client.ShaderAndModCompat;
import com.mik.tempwinter.compat.SeasonCompat;
import com.mik.tempwinter.network.WinterWeatherPayload;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Polls Open-Meteo asynchronously (with optional IP geolocation for cityName="auto")
 * and maps temperature + precipitation + thunderstorm codes into Minecraft snow/rain/thunder.
 */
public final class RealWeatherService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static volatile float currentIntensity = 0.5F;
    private static volatile double currentTempC = -1.0;
    private static volatile double currentWindKmh = 0.0;
    private static volatile double currentSnowDepthM = 0.0;
    private static volatile boolean currentHasRainOnly = false;
    private static volatile boolean currentThunder = false;
    private static volatile boolean fetchInFlight = false;
    private static volatile PendingUpdate pendingUpdate = null;
    private static volatile String overrideMode = "auto";
    private static volatile long overrideTicksRemaining = -1L;
    private static volatile boolean tickingOverworld = true;

    private static final int MAX_TRAMPLED_COLUMNS = 512;
    private static final long TRAMPLE_COOLDOWN_TICKS = 12000L;
    private static final Map<Long, Long> TRAMPLED_COLUMNS = new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
            return size() > MAX_TRAMPLED_COLUMNS;
        }
    };

    private static String cachedCityKey = null;
    private static String resolvedLabel = "Lviv";
    private static double resolvedLat = 49.8397;
    private static double resolvedLon = 24.0297;
    private static volatile int resolvedUtcOffsetSeconds = ZoneId.systemDefault().getRules().getOffset(Instant.now()).getTotalSeconds();
    private static long ticksUntilNextFetch = 20L;

    private record PendingUpdate(float intensity, double tempC, double windKmh, double snowDepthM, boolean rainOnly, boolean thunder, String summary) {
    }

    private RealWeatherService() {
    }

    public static float getIntensity() {
        return currentIntensity;
    }

    public static double getCurrentTempC() {
        return currentTempC;
    }

    public static double getCurrentWindKmh() {
        return currentWindKmh;
    }

    public static double getResolvedLatitude() {
        return resolvedLat;
    }

    public static double getEffectiveTempC(double baseTempC, int y) {
        if (!WinterConfig.ALTITUDE_COLD_ENABLED.get() || y <= 120) {
            return baseTempC;
        }
        return baseTempC - (y - 120) / 16.0;
    }

    public static boolean canMeltSnow() {
        return canMeltSnow(64);
    }

    public static boolean canMeltSnow(int y) {
        if (!WinterConfig.ENABLED.get() || !WinterConfig.REAL_WEATHER_ENABLED.get() || !WinterConfig.MELT_WHEN_WARM.get()) {
            return false;
        }
        double effectiveTempC = getEffectiveTempC(currentTempC, y);
        if (shouldUseRain(y) && effectiveTempC > WinterConfig.FREEZE_TEMPERATURE_C.get()) {
            return true;
        }
        return effectiveTempC > WinterConfig.MELT_TEMPERATURE_C.get();
    }

    public static double getSnowDepthMeters() {
        return currentSnowDepthM;
    }

    public static int getSnowDepthTargetLayers() {
        return getSnowDepthTargetLayers(64);
    }

    public static int getSnowDepthTargetLayers(int y) {
        if (!WinterConfig.ENABLED.get() || !WinterConfig.REAL_WEATHER_ENABLED.get()
                || !WinterConfig.SYNC_SNOW_DEPTH.get() || isVanillaWeatherMode() || shouldUseRain(y)) {
            return 0;
        }
        double effectiveTempC = getEffectiveTempC(currentTempC, y);
        if (currentSnowDepthM < 0.005 || effectiveTempC > WinterConfig.MELT_TEMPERATURE_C.get()) {
            return 0;
        }
        int maxLayers = WinterConfig.MAX_SNOW_BLOCKS.get() * 8;
        return Math.max(1, Math.min(maxLayers, (int) Math.round(currentSnowDepthM * 24.0)));
    }

    public static void markColumnTrampled(int x, int z, long gameTime) {
        long key = (((long) x) << 32) | (z & 0xFFFFFFFFL);
        synchronized (TRAMPLED_COLUMNS) {
            TRAMPLED_COLUMNS.put(key, gameTime + TRAMPLE_COOLDOWN_TICKS);
        }
    }

    public static boolean isColumnTrampled(int x, int z, long gameTime) {
        long key = (((long) x) << 32) | (z & 0xFFFFFFFFL);
        synchronized (TRAMPLED_COLUMNS) {
            Long until = TRAMPLED_COLUMNS.get(key);
            if (until == null) {
                return false;
            }
            if (gameTime >= until) {
                TRAMPLED_COLUMNS.remove(key);
                return false;
            }
            return true;
        }
    }

    public static boolean forceSync() {
        if (fetchInFlight) {
            return false;
        }
        cachedCityKey = null;
        ticksUntilNextFetch = WinterConfig.SYNC_INTERVAL_MINUTES.get() * 60L * 20L;
        startAsyncFetch();
        return true;
    }

    public static boolean isVanillaWeatherMode() {
        return "vanilla".equals(overrideMode);
    }

    public static void setTickingOverworld(boolean overworld) {
        tickingOverworld = overworld;
    }

    public static boolean isTickingOverworld() {
        return tickingOverworld;
    }

    public static boolean setCityAndSync(String city) {
        WinterConfig.CITY_NAME.set(city);
        WinterConfig.CITY_NAME.save();
        overrideMode = "auto";
        overrideTicksRemaining = -1L;
        cachedCityKey = null;
        return forceSync();
    }

    public static boolean setTimeOffsetAndSync(int offsetHours) {
        int clamped = Math.max(-24, Math.min(24, offsetHours));
        WinterConfig.WEATHER_TIME_OFFSET_HOURS.set(clamped);
        WinterConfig.WEATHER_TIME_OFFSET_HOURS.save();
        overrideMode = "auto";
        overrideTicksRemaining = -1L;
        return forceSync();
    }

    public static boolean setOverrideMode(MinecraftServer server, String mode, int durationMinutes) {
        String normalized = mode.toLowerCase(Locale.ROOT);
        if (!normalized.equals("auto") && !normalized.equals("snow") && !normalized.equals("rain")
                && !normalized.equals("thunder") && !normalized.equals("clear") && !normalized.equals("vanilla")) {
            return false;
        }
        overrideMode = normalized;
        overrideTicksRemaining = (!normalized.equals("auto") && durationMinutes > 0)
                ? durationMinutes * 60L * 20L
                : -1L;

        if (normalized.equals("auto")) {
            forceSync();
            return true;
        }

        int durationTicks = overrideTicksRemaining > 0
                ? (int) Math.min(Integer.MAX_VALUE, overrideTicksRemaining)
                : WinterConfig.SYNC_INTERVAL_MINUTES.get() * 60 * 20 + 1200;

        if (normalized.equals("vanilla")) {
            server.overworld().setWeatherParameters(6000, 0, false, false);
            PacketDistributor.sendToAllPlayers(
                    new WinterWeatherPayload(false, 1.0F, false, currentTempC, 0.0, false)
            );
            return true;
        }

        if (normalized.equals("snow")) {
            currentIntensity = 0.85F;
            currentTempC = -5.0;
            currentWindKmh = 25.0;
            currentHasRainOnly = false;
            currentThunder = false;
            server.overworld().setWeatherParameters(0, durationTicks, true, false);
        } else if (normalized.equals("rain")) {
            currentIntensity = 0.85F;
            currentTempC = 8.0;
            currentWindKmh = 18.0;
            currentHasRainOnly = true;
            currentThunder = false;
            server.overworld().setWeatherParameters(0, durationTicks, true, false);
        } else if (normalized.equals("thunder")) {
            currentIntensity = 1.0F;
            currentTempC = 12.0;
            currentWindKmh = 35.0;
            currentHasRainOnly = true;
            currentThunder = true;
            server.overworld().setWeatherParameters(0, durationTicks, true, true);
        } else {
            currentIntensity = 0.0F;
            currentHasRainOnly = false;
            currentThunder = false;
            server.overworld().setWeatherParameters(durationTicks, 0, false, false);
        }
        PacketDistributor.sendToAllPlayers(
                new WinterWeatherPayload(true, currentIntensity, shouldUseRain(), currentTempC, currentWindKmh, SeasonCompat.isWinterActive(server.overworld()))
        );
        return true;
    }

    public static String getStatusSummary() {
        String mode;
        if (isVanillaWeatherMode()) {
            mode = "VANILLA";
        } else if (currentIntensity <= 0.01F && !currentThunder) {
            mode = "CLEAR";
        } else if (shouldUseRain()) {
            mode = currentThunder ? "THUNDERSTORM" : "RAIN";
        } else if (!canPlaceSnowLayers()) {
            mode = "WET_SNOW";
        } else {
            mode = currentThunder ? "BLIZZARD_THUNDER" : "SNOW";
        }
        String timer = overrideTicksRemaining > 0
                ? String.format(Locale.ROOT, " (%dm left)", (overrideTicksRemaining + 1199L) / 1200L)
                : "";
        int offset = WinterConfig.WEATHER_TIME_OFFSET_HOURS.get();
        String offsetStr = offset != 0
                ? String.format(Locale.ROOT, " (%+dh)", offset)
                : "";
        return String.format(
                Locale.ROOT,
                "[RealWeather] %s%s | %.1f°C | wind %.1f km/h | snowDepth %.2fm | %s%s (%.0f%%) | freeze=%s, snowLayers=%s, melt=%s",
                resolvedLabel,
                offsetStr,
                currentTempC,
                currentWindKmh,
                currentSnowDepthM,
                mode,
                timer,
                currentIntensity * 100.0F,
                canFreezeWater(),
                canPlaceSnowLayers(),
                canMeltSnow()
        );
    }

    public static boolean shouldUseRain() {
        return shouldUseRain(64);
    }

    public static boolean shouldUseRain(int y) {
        if (!WinterConfig.REAL_WEATHER_ENABLED.get()) {
            return false;
        }
        return (currentHasRainOnly || currentThunder)
                && getEffectiveTempC(currentTempC, y) > WinterConfig.WET_SNOW_MAX_TEMPERATURE_C.get();
    }

    public static boolean canFreezeWater() {
        return canFreezeWater(64);
    }

    public static boolean canFreezeWater(int y) {
        if (!WinterConfig.REAL_WEATHER_ENABLED.get()) {
            return true;
        }
        return getEffectiveTempC(currentTempC, y) <= WinterConfig.FREEZE_TEMPERATURE_C.get();
    }

    public static boolean canPlaceSnowLayers() {
        return canPlaceSnowLayers(64);
    }

    public static boolean canPlaceSnowLayers(int y) {
        if (!WinterConfig.REAL_WEATHER_ENABLED.get()) {
            return true;
        }
        double effectiveTempC = getEffectiveTempC(currentTempC, y);
        if ((currentHasRainOnly || currentThunder) && effectiveTempC > WinterConfig.WET_SNOW_MAX_TEMPERATURE_C.get()) {
            return false;
        }
        return effectiveTempC <= WinterConfig.FREEZE_TEMPERATURE_C.get();
    }

    public static void onServerStart(MinecraftServer server) {
        currentIntensity = 0.0F;
        currentTempC = -1.0;
        currentWindKmh = 0.0;
        currentSnowDepthM = 0.0;
        currentHasRainOnly = false;
        currentThunder = false;
        cachedCityKey = null;
        resolvedUtcOffsetSeconds = ZoneId.systemDefault().getRules().getOffset(Instant.now()).getTotalSeconds();
        pendingUpdate = null;
        overrideMode = "auto";
        overrideTicksRemaining = -1L;
        tickingOverworld = true;
        ticksUntilNextFetch = 20L;
        synchronized (TRAMPLED_COLUMNS) {
            TRAMPLED_COLUMNS.clear();
        }
        SeasonCompat.resetCache();
        if (WinterConfig.isEnabled() && WinterConfig.REAL_WEATHER_ENABLED.get()) {
            server.overworld().setWeatherParameters(1200, 0, false, false);
        }
    }

    public static void onServerStop() {
        fetchInFlight = false;
        pendingUpdate = null;
    }

    public static void tick(MinecraftServer server) {
        if (!WinterConfig.isEnabled() || !WinterConfig.REAL_WEATHER_ENABLED.get()) {
            return;
        }

        if (overrideTicksRemaining > 0L && --overrideTicksRemaining <= 0L) {
            overrideTicksRemaining = -1L;
            overrideMode = "auto";
            LOGGER.info("[TempWinter] Timed weather override expired; returning to real-world weather.");
            forceSync();
        }

        if (WinterConfig.SYNC_REAL_TIME.get() && !isVanillaWeatherMode() && server.getTickCount() % 20 == 0) {
            ZoneOffset cityZone = ZoneOffset.ofTotalSeconds(resolvedUtcOffsetSeconds);
            LocalTime now = Instant.now().atOffset(cityZone).toLocalTime()
                    .plusHours(WinterConfig.WEATHER_TIME_OFFSET_HOURS.get());
            long sec = now.toSecondOfDay();
            long mcTimeOfDay = ((sec - 21600L + 86400L) % 86400L) * 24000L / 86400L;
            ServerLevel overworld = server.overworld();
            long dayBase = (overworld.getDayTime() / 24000L) * 24000L;
            overworld.setDayTime(dayBase + mcTimeOfDay);
        }

        PendingUpdate update = pendingUpdate;
        if (update != null) {
            pendingUpdate = null;
            if ("auto".equals(overrideMode)) {
                applyWeatherToServer(server, update);
            }
        }

        if (--ticksUntilNextFetch <= 0L && !fetchInFlight) {
            ticksUntilNextFetch = WinterConfig.SYNC_INTERVAL_MINUTES.get() * 60L * 20L;
            startAsyncFetch();
        }
    }

    private static void applyWeatherToServer(MinecraftServer server, PendingUpdate update) {
        currentIntensity = update.intensity();
        currentTempC = update.tempC();
        currentWindKmh = update.windKmh();
        currentSnowDepthM = update.snowDepthM();
        currentHasRainOnly = update.rainOnly();
        currentThunder = update.thunder();

        ServerLevel overworld = server.overworld();
        int durationTicks = WinterConfig.SYNC_INTERVAL_MINUTES.get() * 60 * 20 + 1200;
        boolean yieldWeather = WinterConfig.YIELD_TO_WEATHER_MODS.get() && ShaderAndModCompat.hasExternalWeatherMod();
        if (currentIntensity > 0.01F || currentThunder) {
            float effectiveIntensity = currentThunder && currentIntensity < 0.7F ? 0.85F : currentIntensity;
            currentIntensity = effectiveIntensity;
            if (!yieldWeather) {
                overworld.setWeatherParameters(0, durationTicks, true, currentThunder);
            }
        } else if (!yieldWeather) {
            overworld.setWeatherParameters(durationTicks, 0, false, false);
        }

        boolean useRain = shouldUseRain();
        boolean winterActive = SeasonCompat.isWinterActive(overworld);
        SeasonCompat.syncRealSeasonIfNeeded(server);
        PacketDistributor.sendToAllPlayers(new WinterWeatherPayload(true, currentIntensity, useRain, currentTempC, currentWindKmh, winterActive));
        LOGGER.info("[TempWinter] Weather sync ({}): intensity={}, temp={}C, mode={}, thunder={}, freeze={}, snowLayers={}",
                update.summary(), String.format(Locale.ROOT, "%.2f", currentIntensity),
                String.format(Locale.ROOT, "%.1f", currentTempC),
                useRain ? "RAIN" : "SNOW", currentThunder, canFreezeWater(), canPlaceSnowLayers());
    }

    private static void startAsyncFetch() {
        fetchInFlight = true;
        final String city = WinterConfig.CITY_NAME.get().trim();
        final double fallbackLat = WinterConfig.FALLBACK_LATITUDE.get();
        final double fallbackLon = WinterConfig.FALLBACK_LONGITUDE.get();
        final double overcastFlurry = WinterConfig.OVERCAST_FLURRY_INTENSITY.get();
        final boolean windBoost = WinterConfig.WIND_BOOST_ENABLED.get();

        CompletableFuture.runAsync(() -> {
            try {
                if (!city.equalsIgnoreCase(cachedCityKey)) {
                    resolveLocation(city, fallbackLat, fallbackLon);
                }

                PendingUpdate update = fetchFromOpenMeteo((float) overcastFlurry, windBoost);
                if (update == null) {
                    update = fetchFromWttrIn((float) overcastFlurry, windBoost);
                }
                if (update != null) {
                    pendingUpdate = update;
                }
            } catch (Exception e) {
                LOGGER.warn("[TempWinter] Real weather fetch failed (keeping previous state): {}", e.getMessage());
            } finally {
                fetchInFlight = false;
            }
        });
    }

    private static PendingUpdate fetchFromOpenMeteo(float overcastFlurry, boolean windBoost) {
        try {
            int offsetHours = WinterConfig.WEATHER_TIME_OFFSET_HOURS.get();
            String url = offsetHours == 0
                    ? String.format(
                            Locale.ROOT,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,precipitation,rain,snowfall,snow_depth,cloud_cover,weather_code,wind_speed_10m&timezone=auto",
                            resolvedLat, resolvedLon)
                    : String.format(
                            Locale.ROOT,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m&hourly=temperature_2m,precipitation,rain,snowfall,snow_depth,cloud_cover,weather_code,wind_speed_10m&past_days=1&forecast_days=2&timezone=auto",
                            resolvedLat, resolvedLon);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "TempWinter-MC/1.0")
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOGGER.warn("[TempWinter] Open-Meteo returned HTTP {}, trying fallback API", resp.statusCode());
                return null;
            }
            JsonObject root = GSON.fromJson(resp.body(), JsonObject.class);
            if (root.has("utc_offset_seconds") && !root.get("utc_offset_seconds").isJsonNull()) {
                resolvedUtcOffsetSeconds = root.get("utc_offset_seconds").getAsInt();
            }
            JsonObject cur = root.getAsJsonObject("current");
            double tempC;
            double precip;
            double rain;
            double snow;
            double snowDepthM;
            int clouds;
            int wmo;
            double windKmh;

            if (offsetHours != 0 && root.has("hourly") && cur != null && cur.has("time")) {
                JsonObject hourly = root.getAsJsonObject("hourly");
                int idx = findShiftedHourIndex(hourly.getAsJsonArray("time"), cur.get("time").getAsString(), offsetHours);
                if (idx < 0) {
                    return null;
                }
                tempC = getArrDouble(hourly, "temperature_2m", idx, -1.0);
                precip = getArrDouble(hourly, "precipitation", idx, 0.0);
                rain = getArrDouble(hourly, "rain", idx, 0.0);
                snow = getArrDouble(hourly, "snowfall", idx, 0.0);
                snowDepthM = getArrDouble(hourly, "snow_depth", idx, 0.0);
                clouds = (int) Math.round(getArrDouble(hourly, "cloud_cover", idx, 0.0));
                wmo = (int) Math.round(getArrDouble(hourly, "weather_code", idx, 0.0));
                windKmh = getArrDouble(hourly, "wind_speed_10m", idx, 0.0);
            } else {
                tempC = cur.has("temperature_2m") ? cur.get("temperature_2m").getAsDouble() : -1.0;
                precip = cur.has("precipitation") ? cur.get("precipitation").getAsDouble() : 0.0;
                rain = cur.has("rain") ? cur.get("rain").getAsDouble() : 0.0;
                snow = cur.has("snowfall") ? cur.get("snowfall").getAsDouble() : 0.0;
                snowDepthM = cur.has("snow_depth") && !cur.get("snow_depth").isJsonNull() ? cur.get("snow_depth").getAsDouble() : 0.0;
                clouds = cur.has("cloud_cover") ? cur.get("cloud_cover").getAsInt() : 0;
                wmo = cur.has("weather_code") ? cur.get("weather_code").getAsInt() : 0;
                windKmh = cur.has("wind_speed_10m") ? cur.get("wind_speed_10m").getAsDouble() : 0.0;
            }

            boolean thunder = (wmo == 95 || wmo == 96 || wmo == 99);
            boolean drizzleOrFlurryWmo = (wmo >= 51 && wmo <= 57) || wmo == 77 || wmo == 85 || wmo == 86;
            float intensity = computeIntensity(precip, snow, clouds, windKmh, thunder, drizzleOrFlurryWmo, overcastFlurry, windBoost);
            boolean rainOnly = (rain > 0.0 || (wmo >= 51 && wmo <= 67) || (wmo >= 80 && wmo <= 82)) && snow <= 0.0 && wmo != 77 && wmo != 85 && wmo != 86;
            String label = String.format(Locale.ROOT, "%s [open-meteo%s] wmo=%d precip=%.2f rain=%.2f snow=%.2f depth=%.2fm wind=%.1fkm/h clouds=%d%%",
                    resolvedLabel, offsetHours != 0 ? String.format(Locale.ROOT, " %+dh", offsetHours) : "", wmo, precip, rain, snow, snowDepthM, windKmh, clouds);
            return new PendingUpdate(intensity, tempC, windKmh, snowDepthM, rainOnly, thunder, label);
        } catch (Exception e) {
            LOGGER.warn("[TempWinter] Open-Meteo failed ({}), trying fallback API", e.getMessage());
            return null;
        }
    }

    private static int findShiftedHourIndex(JsonArray times, String currentIso, int offsetHours) {
        if (times == null || times.isEmpty()) {
            return -1;
        }
        LocalDateTime target = LocalDateTime.parse(currentIso).plusHours(offsetHours).plusMinutes(30).truncatedTo(ChronoUnit.HOURS);
        String targetStr = target.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"));
        for (int i = 0; i < times.size(); i++) {
            if (targetStr.equals(times.get(i).getAsString())) {
                return i;
            }
        }
        return -1;
    }

    private static double getArrDouble(JsonObject obj, String key, int idx, double def) {
        if (!obj.has(key)) {
            return def;
        }
        JsonArray arr = obj.getAsJsonArray(key);
        if (idx < 0 || idx >= arr.size() || arr.get(idx).isJsonNull()) {
            return def;
        }
        return arr.get(idx).getAsDouble();
    }

    private static PendingUpdate fetchFromWttrIn(float overcastFlurry, boolean windBoost) {
        try {
            String url = String.format(Locale.ROOT, "https://wttr.in/%.4f,%.4f?format=j1", resolvedLat, resolvedLon);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "TempWinter-MC/1.0")
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOGGER.warn("[TempWinter] Fallback wttr.in returned HTTP {}", resp.statusCode());
                return null;
            }
            JsonObject root = GSON.fromJson(resp.body(), JsonObject.class);
            JsonArray arr = root.getAsJsonArray("current_condition");
            if (arr == null || arr.isEmpty()) {
                return null;
            }
            JsonObject cur = arr.get(0).getAsJsonObject();
            double tempC = cur.has("temp_C") ? Double.parseDouble(cur.get("temp_C").getAsString()) : -1.0;
            double precip = cur.has("precipMM") ? Double.parseDouble(cur.get("precipMM").getAsString()) : 0.0;
            double windKmh = cur.has("windspeedKmph") ? Double.parseDouble(cur.get("windspeedKmph").getAsString()) : 0.0;
            int clouds = cur.has("cloudcover") ? Integer.parseInt(cur.get("cloudcover").getAsString()) : 0;
            int code = cur.has("weatherCode") ? Integer.parseInt(cur.get("weatherCode").getAsString()) : 0;

            boolean thunder = (code == 200 || code == 386 || code == 389 || code == 392 || code == 395);
            boolean isSnowCode = (code == 227 || code == 230 || (code >= 323 && code <= 338) || (code >= 368 && code <= 377) || code == 392 || code == 395);
            boolean isRainCode = ((code >= 176 && code <= 321 && !isSnowCode) || (code >= 353 && code <= 365) || code == 386 || code == 389);
            double snow = isSnowCode ? Math.max(0.2, precip * 0.5) : 0.0;
            boolean drizzleOrFlurry = isSnowCode || isRainCode;
            float intensity = computeIntensity(precip, snow, clouds, windKmh, thunder, drizzleOrFlurry, overcastFlurry, windBoost);
            boolean rainOnly = isRainCode && !isSnowCode;
            String label = String.format(Locale.ROOT, "%s [wttr.in] code=%d precip=%.2f wind=%.1fkm/h clouds=%d%%",
                    resolvedLabel, code, precip, windKmh, clouds);
            return new PendingUpdate(intensity, tempC, windKmh, isSnowCode ? Math.max(0.02, precip * 0.01) : 0.0, rainOnly, thunder, label);
        } catch (Exception e) {
            LOGGER.warn("[TempWinter] Fallback wttr.in failed: {}", e.getMessage());
            return null;
        }
    }

    private static void resolveLocation(String city, double fallbackLat, double fallbackLon) {
        if (city.equalsIgnoreCase("auto") || city.isEmpty()) {
            if (tryIpApi("https://ipapi.co/json/", false)
                    || tryIpApi("http://ip-api.com/json/?fields=status,city,lat,lon", true)) {
                cachedCityKey = city;
                return;
            }
        } else if (tryOpenMeteoGeocode(city)) {
            cachedCityKey = city;
            return;
        }
        resolvedLat = fallbackLat;
        resolvedLon = fallbackLon;
        resolvedLabel = String.format(Locale.ROOT, "%.2f,%.2f", fallbackLat, fallbackLon);
        cachedCityKey = city;
    }

    private static boolean tryIpApi(String url, boolean isIpApiCom) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "TempWinter-MC/1.0")
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return false;
            }
            JsonObject obj = GSON.fromJson(resp.body(), JsonObject.class);
            if (isIpApiCom) {
                if (!obj.has("status") || !"success".equalsIgnoreCase(obj.get("status").getAsString())) {
                    return false;
                }
                resolvedLat = obj.get("lat").getAsDouble();
                resolvedLon = obj.get("lon").getAsDouble();
                resolvedLabel = obj.has("city") ? obj.get("city").getAsString() + " (IP)" : "Auto-IP";
                return true;
            } else if (obj.has("latitude") && obj.has("longitude")) {
                resolvedLat = obj.get("latitude").getAsDouble();
                resolvedLon = obj.get("longitude").getAsDouble();
                resolvedLabel = obj.has("city") ? obj.get("city").getAsString() + " (IP)" : "Auto-IP";
                return true;
            }
        } catch (Exception e) {
            LOGGER.warn("[TempWinter] IP geolocation failed ({}): {}", url, e.getMessage());
        }
        return false;
    }

    private static boolean tryOpenMeteoGeocode(String city) {
        try {
            String encoded = URLEncoder.encode(city, StandardCharsets.UTF_8);
            URI uri = URI.create("https://geocoding-api.open-meteo.com/v1/search?name=" + encoded + "&count=1&language=en&format=json");
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "TempWinter-MC/1.0")
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonObject root = GSON.fromJson(resp.body(), JsonObject.class);
                JsonArray results = root.getAsJsonArray("results");
                if (results != null && !results.isEmpty()) {
                    JsonObject first = results.get(0).getAsJsonObject();
                    resolvedLat = first.get("latitude").getAsDouble();
                    resolvedLon = first.get("longitude").getAsDouble();
                    resolvedLabel = first.has("name") ? first.get("name").getAsString() : city;
                    return true;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[TempWinter] Failed to geocode '{}': {}", city, e.getMessage());
        }
        return false;
    }

    private static float computeIntensity(double precipMm, double snowCm, int cloudCoverPct, double windKmh,
                                          boolean thunder, boolean drizzleOrFlurryWmo, float overcastFlurry, boolean windBoost) {
        double windBonus = windBoost ? Math.max(0.0, Math.min(0.25, (windKmh - 12.0) / 80.0)) : 0.0;
        if (thunder || snowCm > 0.0 || precipMm > 0.0) {
            double raw = 0.45 + snowCm * 0.40 + precipMm * 0.25 + (thunder ? 0.30 : 0.0) + windBonus;
            return (float) Math.max(0.45, Math.min(1.0, raw));
        }
        if (drizzleOrFlurryWmo) {
            return (float) Math.min(1.0, 0.32 + windBonus * 0.5);
        }
        if (cloudCoverPct >= 65 && overcastFlurry > 0.0F) {
            return (float) Math.min(1.0, overcastFlurry + windBonus * 0.5);
        }
        return 0.0F;
    }
}
