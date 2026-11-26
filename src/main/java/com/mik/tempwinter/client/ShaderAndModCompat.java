package com.mik.tempwinter.client;

import net.neoforged.fml.ModList;

import java.lang.reflect.Method;

/**
 * Runtime compatibility checks for Iris shaderpacks and companion winter/weather mods.
 */
public final class ShaderAndModCompat {
    private static final boolean IRIS_LOADED = isModLoaded("iris") || isModLoaded("oculus");
    private static final boolean FOG_MOD_LOADED = isModLoaded("fog");
    private static final boolean WINTER_PARTICLES_LOADED = isModLoaded("winterparticles") || isModLoaded("snowparticles");
    private static final boolean BREATH_MOD_LOADED = isModLoaded("breath") || isModLoaded("coldbreath");
    private static final boolean SERENE_SEASONS_LOADED = isModLoaded("sereneseasons");
    private static final boolean AMBIENT_SOUNDS_LOADED = isModLoaded("ambientsounds");
    private static final boolean PARTICLE_RAIN_LOADED = isModLoaded("particlerain");
    private static final boolean SNOW_REAL_MAGIC_LOADED = isModLoaded("snowrealmagic");
    private static final boolean IMMERSIVE_WEATHERING_LOADED = isModLoaded("immersive_weathering") || isModLoaded("immersiveweathering");
    private static final boolean EXTERNAL_WEATHER_MOD_LOADED = isModLoaded("simpleclouds")
            || isModLoaded("weather2")
            || isModLoaded("weatherupdate")
            || isModLoaded("localizedweather");

    private static boolean isModLoaded(String modId) {
        ModList list = ModList.get();
        return list != null && list.isLoaded(modId);
    }

    private static boolean irisChecked = false;
    private static Method irisPackInUseMethod = null;

    private ShaderAndModCompat() {
    }

    public static boolean isIrisShaderActive() {
        if (!IRIS_LOADED) {
            return false;
        }
        if (!irisChecked) {
            irisChecked = true;
            try {
                Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
                irisPackInUseMethod = irisClass.getMethod("isPackInUseQuick");
            } catch (Throwable ignored) {
                irisPackInUseMethod = null;
            }
        }
        if (irisPackInUseMethod == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(irisPackInUseMethod.invoke(null));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasExternalFogMod() {
        return FOG_MOD_LOADED;
    }

    public static boolean hasExternalWinterParticles() {
        return WINTER_PARTICLES_LOADED;
    }

    public static boolean hasExternalBreathMod() {
        return BREATH_MOD_LOADED;
    }

    public static boolean hasSereneSeasons() {
        return SERENE_SEASONS_LOADED;
    }

    public static boolean hasAmbientSounds() {
        return AMBIENT_SOUNDS_LOADED;
    }

    public static boolean hasExternalWeatherMod() {
        return EXTERNAL_WEATHER_MOD_LOADED;
    }

    public static boolean hasParticleRain() {
        return PARTICLE_RAIN_LOADED;
    }

    public static boolean hasSnowRealMagic() {
        return SNOW_REAL_MAGIC_LOADED;
    }

    public static boolean hasImmersiveWeathering() {
        return IMMERSIVE_WEATHERING_LOADED;
    }
}
