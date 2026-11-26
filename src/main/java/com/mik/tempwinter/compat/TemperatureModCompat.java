package com.mik.tempwinter.compat;

import com.mik.tempwinter.WinterConfig;
import com.mik.tempwinter.weather.RealWeatherService;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Optional runtime integration with temperature survival mods (Cold Sweat, Tough As Nails,
 * Legendary Survival Overhaul, Homeostatic). Ambient temperature is provided via Biome.getBaseTemperature()
 * and Tough As Nails' positional modifier so each temperature mod handles armor, shelter, and heat itself.
 */
public final class TemperatureModCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean COLD_SWEAT_LOADED = isModLoaded("coldsweat");
    private static final boolean TAN_LOADED = isModLoaded("toughasnails");
    private static final boolean LSO_LOADED = isModLoaded("legendarysurvivaloverhaul");
    private static final boolean HOMEOSTATIC_LOADED = isModLoaded("homeostatic");

    private static boolean isModLoaded(String modId) {
        ModList list = ModList.get();
        return list != null && list.isLoaded(modId);
    }

    private static boolean tanInitialized = false;

    private TemperatureModCompat() {
    }

    public static boolean hasExternalTemperatureMod() {
        return COLD_SWEAT_LOADED || TAN_LOADED || LSO_LOADED || HOMEOSTATIC_LOADED;
    }

    public static void init() {
        if (TAN_LOADED && WinterConfig.TOUGH_AS_NAILS_INTEGRATION.get() && !tanInitialized) {
            tanInitialized = true;
            registerToughAsNailsModifier();
        }
        if (COLD_SWEAT_LOADED && WinterConfig.COLD_SWEAT_INTEGRATION.get()) {
            LOGGER.info("[TempWinter] Cold Sweat detected; providing ambient temperature via Biome.getBaseTemperature().");
        }
        if (LSO_LOADED && WinterConfig.EXTRA_TEMP_MODS_INTEGRATION.get()) {
            LOGGER.info("[TempWinter] Legendary Survival Overhaul detected; providing ambient temperature via Biome.getBaseTemperature().");
        }
        if (HOMEOSTATIC_LOADED && WinterConfig.EXTRA_TEMP_MODS_INTEGRATION.get()) {
            LOGGER.info("[TempWinter] Homeostatic detected; providing ambient temperature via Biome.getBaseTemperature().");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerToughAsNailsModifier() {
        try {
            Class<?> helperClass = Class.forName("toughasnails.api.temperature.TemperatureHelper");
            Class<?> modInterface = Class.forName("toughasnails.api.temperature.IPositionalTemperatureModifier");
            Class<?> levelEnum = Class.forName("toughasnails.api.temperature.TemperatureLevel");
            Object icy = Enum.valueOf((Class<Enum>) levelEnum, "ICY");
            Object cold = Enum.valueOf((Class<Enum>) levelEnum, "COLD");
            Object warm = Enum.valueOf((Class<Enum>) levelEnum, "WARM");

            Object proxy = Proxy.newProxyInstance(
                    TemperatureModCompat.class.getClassLoader(),
                    new Class<?>[]{modInterface},
                    (p, method, args) -> {
                        if (!"modify".equals(method.getName()) || args == null || args.length < 3) {
                            return args != null && args.length >= 3 ? args[2] : null;
                        }
                        Level level = (Level) args[0];
                        BlockPos pos = (BlockPos) args[1];
                        Object current = args[2];
                        if (!WinterConfig.ENABLED.get()
                                || !WinterConfig.TOUGH_AS_NAILS_INTEGRATION.get()
                                || RealWeatherService.isVanillaWeatherMode()
                                || !level.canSeeSky(pos)) {
                            return current;
                        }
                        double tempC = RealWeatherService.getEffectiveTempC(RealWeatherService.getCurrentTempC(), pos.getY());
                        if (tempC <= -5.0) {
                            return icy;
                        }
                        if (tempC <= WinterConfig.WET_SNOW_MAX_TEMPERATURE_C.get()) {
                            return cold;
                        }
                        if (tempC >= 26.0) {
                            return warm;
                        }
                        return current;
                    }
            );

            Method reg = helperClass.getMethod("registerPositionalTemperatureModifier", modInterface);
            reg.invoke(null, proxy);
            LOGGER.info("[TempWinter] Tough As Nails positional temperature modifier registered.");
        } catch (Throwable t) {
            LOGGER.warn("[TempWinter] Could not hook Tough As Nails API: {}", t.getMessage());
        }
    }
}
