package com.mik.tempwinter;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Configuration for temporary winter effects and real-world weather sync.
 */
public final class WinterConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue FORCE_SNOWFALL;
    public static final ModConfigSpec.BooleanValue WINTER_FOLIAGE_COLORS;
    public static final ModConfigSpec.BooleanValue PLACE_SNOW_BLOCKS;
    public static final ModConfigSpec.IntValue MAX_SNOW_BLOCKS;
    public static final ModConfigSpec.BooleanValue UNEVEN_DRIFTS;

    public static final ModConfigSpec.BooleanValue REAL_WEATHER_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> CITY_NAME;
    public static final ModConfigSpec.DoubleValue FALLBACK_LATITUDE;
    public static final ModConfigSpec.DoubleValue FALLBACK_LONGITUDE;
    public static final ModConfigSpec.IntValue SYNC_INTERVAL_MINUTES;
    public static final ModConfigSpec.IntValue WEATHER_TIME_OFFSET_HOURS;
    public static final ModConfigSpec.DoubleValue OVERCAST_FLURRY_INTENSITY;
    public static final ModConfigSpec.DoubleValue FREEZE_TEMPERATURE_C;
    public static final ModConfigSpec.DoubleValue WET_SNOW_MAX_TEMPERATURE_C;
    public static final ModConfigSpec.BooleanValue WIND_BOOST_ENABLED;
    public static final ModConfigSpec.BooleanValue MELT_WHEN_WARM;
    public static final ModConfigSpec.BooleanValue REDUCE_PATH_SNOW;
    public static final ModConfigSpec.BooleanValue TRAMPLE_SNOW;
    public static final ModConfigSpec.BooleanValue HEAT_MELTS_SNOW;
    public static final ModConfigSpec.IntValue HEAT_SOURCE_RADIUS;
    public static final ModConfigSpec.BooleanValue FRAGILE_ICE;
    public static final ModConfigSpec.DoubleValue FRAGILE_ICE_MAX_TEMP_C;
    public static final ModConfigSpec.BooleanValue FROST_OVERLAY;
    public static final ModConfigSpec.DoubleValue FROST_OVERLAY_TEMP_C;
    public static final ModConfigSpec.BooleanValue OVERHANG_SNOW_DRIP;
    public static final ModConfigSpec.DoubleValue BLIZZARD_WIND_THRESHOLD_KMH;
    public static final ModConfigSpec.BooleanValue COLD_SWEAT_INTEGRATION;
    public static final ModConfigSpec.BooleanValue TOUGH_AS_NAILS_INTEGRATION;
    public static final ModConfigSpec.BooleanValue EXTRA_TEMP_MODS_INTEGRATION;
    public static final ModConfigSpec.ConfigValue<String> SEASONS_BEHAVIOR;
    public static final ModConfigSpec.BooleanValue PROTECT_CREATE_MACHINERY;
    public static final ModConfigSpec.BooleanValue ALTITUDE_COLD_ENABLED;
    public static final ModConfigSpec.BooleanValue AMBIENT_BIOME_TEMPERATURE;
    public static final ModConfigSpec.BooleanValue SYNC_REAL_TIME;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CUSTOM_HEAT_KEYWORDS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CUSTOM_PROTECTED_KEYWORDS;
    public static final ModConfigSpec.BooleanValue YIELD_TO_AMBIENT_SOUNDS;
    public static final ModConfigSpec.BooleanValue YIELD_TO_WEATHER_MODS;
    public static final ModConfigSpec.BooleanValue YIELD_TO_SNOW_REAL_MAGIC;
    public static final ModConfigSpec.BooleanValue WINTER_WATER_COLOR;
    public static final ModConfigSpec.BooleanValue WINTER_FOG;
    public static final ModConfigSpec.BooleanValue FOG_WITH_SHADERS;
    public static final ModConfigSpec.BooleanValue COLD_BREATH_PARTICLES;
    public static final ModConfigSpec.BooleanValue TREE_SHAKE_SNOW;
    public static final ModConfigSpec.BooleanValue GROUND_BLIZZARD_PARTICLES;
    public static final ModConfigSpec.BooleanValue BLIZZARD_WIND_SOUND;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("winter");
        ENABLED = builder
                .comment("Master switch for temporary winter effects.")
                .define("enabled", true);
        FORCE_SNOWFALL = builder
                .comment("Make all biomes (including Savanna/Desert) have snowfall instead of rain/dry weather.")
                .define("forceSnowfall", true);
        WINTER_FOLIAGE_COLORS = builder
                .comment("Apply frosted winter colors to grass and leaves on the client.")
                .define("winterFoliageColors", true);
        PLACE_SNOW_BLOCKS = builder
                .comment("Allow snowfall to place snow layers and freeze water.")
                .define("placeSnowBlocks", true);
        MAX_SNOW_BLOCKS = builder
                .comment("Maximum vertical snow depth in full blocks (3 = up to 24 layers / 3.0 blocks).")
                .defineInRange("maxSnowBlocks", 3, 1, 8);
        UNEVEN_DRIFTS = builder
                .comment("Vary the maximum snow depth per column so drifts form naturally (e.g. 1.5 to 3.0 blocks).")
                .define("unevenDrifts", true);
        builder.pop();

        builder.push("real_weather");
        REAL_WEATHER_ENABLED = builder
                .comment("Sync Minecraft snowfall intensity, wet snow, and water freezing with real-world weather via Open-Meteo.")
                .define("realWeatherEnabled", true);
        CITY_NAME = builder
                .comment("City name (e.g. 'Kyiv', 'Lviv'), or 'auto' to detect the server's city dynamically by public IP.")
                .define("cityName", "auto");
        FALLBACK_LATITUDE = builder
                .comment("Fallback latitude if IP/city lookup fails.")
                .defineInRange("fallbackLatitude", 49.8397, -90.0, 90.0);
        FALLBACK_LONGITUDE = builder
                .comment("Fallback longitude if IP/city lookup fails.")
                .defineInRange("fallbackLongitude", 24.0297, -180.0, 180.0);
        SYNC_INTERVAL_MINUTES = builder
                .comment("How often (in minutes) the server polls Open-Meteo for current weather.")
                .defineInRange("syncIntervalMinutes", 10, 2, 120);
        WEATHER_TIME_OFFSET_HOURS = builder
                .comment("Shift weather (and real-time clock if syncRealTime=true) by N hours (-24 = 24h earlier, +24 = 24h forecast, 0 = live).")
                .defineInRange("weatherTimeOffsetHours", 0, -24, 24);
        OVERCAST_FLURRY_INTENSITY = builder
                .comment("Light snowfall intensity (0.0-1.0) when real weather is cloudy (>=65% cloud cover) without precipitation.")
                .defineInRange("overcastFlurryIntensity", 0.25, 0.0, 1.0);
        FREEZE_TEMPERATURE_C = builder
                .comment("Real temperature threshold (Celsius) at or below which water freezes into ice and snow layers accumulate.")
                .defineInRange("freezeTemperatureC", 0.0, -30.0, 15.0);
        WET_SNOW_MAX_TEMPERATURE_C = builder
                .comment("Above freezeTemperatureC and up to this temperature, precipitation falls as wet snow (particles only, no snow layers or ice).")
                .defineInRange("wetSnowMaxTemperatureC", 3.0, -10.0, 25.0);
        WIND_BOOST_ENABLED = builder
                .comment("Scale snowfall/rain density up when real-world wind_speed_10m is strong.")
                .define("windBoostEnabled", true);
        MELT_WHEN_WARM = builder
                .comment("Gradually melt accumulated snow layers and surface ice when real temperature rises above freezeTemperatureC.")
                .define("meltWhenWarm", true);
        REDUCE_PATH_SNOW = builder
                .comment("Keep paths, cobblestone, and stone bricks lightly dusted (max 3 layers) and keep rails/tracks clear.")
                .define("reducePathSnow", true);
        TRAMPLE_SNOW = builder
                .comment("Compress multi-layer snow down by 1 layer when players walk on it (leaves at least 1 layer).")
                .define("trampleSnow", true);
        HEAT_MELTS_SNOW = builder
                .comment("Prevent deep snow buildup and gently melt snow/ice near vanilla and modded heat sources.")
                .define("heatMeltsSnow", true);
        HEAT_SOURCE_RADIUS = builder
                .comment("Radius in blocks around campfires/furnaces/blaze burners/lanterns where snow buildup is blocked and ice melts.")
                .defineInRange("heatSourceRadius", 3, 1, 6);
        FRAGILE_ICE = builder
                .comment("Thin ice over water can crack under players when real temperature is above fragileIceMaxTempC (Frost Walker prevents cracking).")
                .define("fragileIce", true);
        FRAGILE_ICE_MAX_TEMP_C = builder
                .comment("Temperature (Celsius) above which thin ice over water becomes fragile.")
                .defineInRange("fragileIceMaxTempC", -5.0, -30.0, 15.0);
        FROST_OVERLAY = builder
                .comment("Show subtle non-damaging screen frost outdoors when <= frostOverlayTempC (prevented indoors, near heat, or wearing 2+ leather pieces).")
                .define("frostOverlay", true);
        FROST_OVERLAY_TEMP_C = builder
                .comment("Temperature (Celsius) at or below which outdoor frost screen overlay appears.")
                .defineInRange("frostOverlayTempC", -10.0, -40.0, 0.0);
        builder.pop();

        builder.push("visuals");
        WINTER_WATER_COLOR = builder
                .comment("Tint water and water fog to an icy winter blue on the client.")
                .define("winterWaterColor", true);
        WINTER_FOG = builder
                .comment("Add subtle winter mist fog during snowfall when no external fog mod or Iris shaderpack is active.")
                .define("winterFog", true);
        FOG_WITH_SHADERS = builder
                .comment("Force custom winter fog even when an Iris shaderpack is active (false = yield fog control to Iris/Complementary).")
                .define("fogWithShaders", false);
        COLD_BREATH_PARTICLES = builder
                .comment("Spawn subtle breath vapor particles when real temperature is at or below freezeTemperatureC (auto-disabled if Breath mod is loaded).")
                .define("coldBreathParticles", true);
        TREE_SHAKE_SNOW = builder
                .comment("Drop snowflake particles when hitting logs or leaves in winter (auto-disabled if WinterParticles mod is loaded).")
                .define("treeShakeSnow", true);
        GROUND_BLIZZARD_PARTICLES = builder
                .comment("Spawn drifting horizontal snowflake particles near the ground during windy snowfall (auto-disabled if WinterParticles is loaded).")
                .define("groundBlizzardParticles", true);
        BLIZZARD_WIND_SOUND = builder
                .comment("Play subtle low-pitched wind gusts outdoors during windy snowfall.")
                .define("blizzardWindSound", true);
        BLIZZARD_WIND_THRESHOLD_KMH = builder
                .comment("Real wind speed (km/h) required to trigger ground blizzard particles and wind gust sounds.")
                .defineInRange("blizzardWindThresholdKmh", 18.0, 0.0, 150.0);
        OVERHANG_SNOW_DRIP = builder
                .comment("Drop occasional snowflake particles from snow-covered roofs and overhangs.")
                .define("overhangSnowDrip", true);
        builder.pop();

        builder.push("compat");
        COLD_SWEAT_INTEGRATION = builder
                .comment("Feed real-world cold/blizzard temperature into Cold Sweat if installed (and yield frost screen overlay to Cold Sweat).")
                .define("coldSweatIntegration", true);
        TOUGH_AS_NAILS_INTEGRATION = builder
                .comment("Register a real-world weather temperature modifier for Tough As Nails if installed (and yield frost overlay to TAN).")
                .define("toughAsNailsIntegration", true);
        EXTRA_TEMP_MODS_INTEGRATION = builder
                .comment("Integrate with Legendary Survival Overhaul and Homeostatic if installed.")
                .define("extraTempModsIntegration", true);
        SEASONS_BEHAVIOR = builder
                .comment("How TempWinter interacts with season mods (Serene Seasons / Ecliptic Seasons): SYNC_REAL_DATE (default, syncs seasons to IRL date & hemisphere), RESPECT_SEASONS (only winter effects during mod winter), OVERRIDE (always force winter).")
                .define("seasonsBehavior", "SYNC_REAL_DATE");
        PROTECT_CREATE_MACHINERY = builder
                .comment("Prevent snow accumulation on Create belts, shafts, cogwheels, funnels, depots, bearings, and propellers.")
                .define("protectCreateMachinery", true);
        ALTITUDE_COLD_ENABLED = builder
                .comment("Lower effective temperature above y=120 (-1C per 16 blocks) so airships and high mountains get colder snow and wind.")
                .define("altitudeColdEnabled", true);
        AMBIENT_BIOME_TEMPERATURE = builder
                .comment("Map real-world temperature into Biome.getBaseTemperature() on main/render threads (never during worldgen) so Cold Sweat, Homeostatic, LSO, and TAN use real ambient temperature natively.")
                .define("ambientBiomeTemperature", true);
        SYNC_REAL_TIME = builder
                .comment("Sync Minecraft sun/moon time-of-day to the server's real local clock (false = normal Minecraft day/night cycle).")
                .define("syncRealTime", false);
        CUSTOM_HEAT_KEYWORDS = builder
                .comment("Block IDs or path substrings treated as heat sources (prevent snow buildup and melt ice within heatSourceRadius).")
                .defineListAllowEmpty(
                        "customHeatKeywords",
                        List.of("blaze_burner", "campfire", "stove", "heater", "fireplace", "hearth", "brazier", "boiler", "furnace"),
                        o -> o instanceof String s && !s.isBlank()
                );
        CUSTOM_PROTECTED_KEYWORDS = builder
                .comment("Block IDs or path substrings protected from snow layer accumulation (rails, Create belts/shafts/machinery, etc.).")
                .defineListAllowEmpty(
                        "customProtectedKeywords",
                        List.of("track", "belt", "shaft", "cogwheel", "funnel", "depot", "chute", "gearbox", "crank", "water_wheel", "windmill", "propeller", "bearing", "turntable", "encased_chain_drive"),
                        o -> o instanceof String s && !s.isBlank()
                );
        YIELD_TO_AMBIENT_SOUNDS = builder
                .comment("Disable TempWinter wind gust sounds if AmbientSounds is installed.")
                .define("yieldToAmbientSounds", true);
        YIELD_TO_WEATHER_MODS = builder
                .comment("Yield weather cycle control if Simple Clouds, Weather2, The Weather Update, or LocalizedWeather is installed.")
                .define("yieldToWeatherMods", true);
        YIELD_TO_SNOW_REAL_MAGIC = builder
                .comment("Yield snow layer placement cap to Snow! Real Magic! if installed (so slabs/stairs/fences accumulate snow via SRM).")
                .define("yieldToSnowRealMagic", true);
        builder.pop();

        SPEC = builder.build();
    }

    private WinterConfig() {
    }
}
