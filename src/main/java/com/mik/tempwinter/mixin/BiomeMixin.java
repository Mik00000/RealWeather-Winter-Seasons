package com.mik.tempwinter.mixin;

import com.mik.tempwinter.WinterConfig;
import com.mik.tempwinter.client.ShaderAndModCompat;
import com.mik.tempwinter.compat.SeasonCompat;
import com.mik.tempwinter.compat.TemperatureModCompat;
import com.mik.tempwinter.weather.ClientWeatherState;
import com.mik.tempwinter.weather.RealWeatherService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Biome.class)
public class BiomeMixin {
    @Inject(method = "hasPrecipitation()Z", at = @At("HEAD"), cancellable = true)
    private void tempwinter$hasPrecipitation(CallbackInfoReturnable<Boolean> cir) {
        if (WinterConfig.isEnabled() && WinterConfig.FORCE_SNOWFALL.get()
                && tempwinter$isOverworld() && !tempwinter$isVanillaMode() && tempwinter$isWinterOrCold()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getPrecipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;", at = @At("HEAD"), cancellable = true)
    private void tempwinter$getPrecipitationAt(BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
        if (WinterConfig.isEnabled() && WinterConfig.FORCE_SNOWFALL.get()
                && tempwinter$isOverworld() && !tempwinter$isVanillaMode() && tempwinter$isWinterOrCold(pos.getY())) {
            cir.setReturnValue(tempwinter$isRainMode(pos.getY()) ? Biome.Precipitation.RAIN : Biome.Precipitation.SNOW);
        }
    }

    @Inject(method = "coldEnoughToSnow(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void tempwinter$coldEnoughToSnow(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (WinterConfig.isEnabled() && WinterConfig.FORCE_SNOWFALL.get()
                && tempwinter$isOverworld() && !tempwinter$isVanillaMode() && tempwinter$isWinterOrCold(pos.getY())) {
            cir.setReturnValue(!tempwinter$isRainMode(pos.getY()));
        }
    }

    @Inject(method = "shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Z", at = @At("HEAD"), cancellable = true)
    private void tempwinter$gateWaterFreeze(LevelReader level, BlockPos pos, boolean mustBeAtEdge, CallbackInfoReturnable<Boolean> cir) {
        if (level instanceof Level lvl && !lvl.dimension().equals(Level.OVERWORLD)) {
            return;
        }
        if (WinterConfig.isEnabled() && !tempwinter$isVanillaMode() && (!WinterConfig.PLACE_SNOW_BLOCKS.get() || !RealWeatherService.canFreezeWater())) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getBaseTemperature()F", at = @At("HEAD"), cancellable = true)
    private void tempwinter$ambientBaseTemperature(CallbackInfoReturnable<Float> cir) {
        if (!WinterConfig.isEnabled() || !WinterConfig.REAL_WEATHER_ENABLED.get()
                || !WinterConfig.AMBIENT_BIOME_TEMPERATURE.get() || !tempwinter$isOverworld() || tempwinter$isVanillaMode()) {
            return;
        }
        String thread = Thread.currentThread().getName();
        if (!"Server thread".equals(thread) && !"Render thread".equals(thread)) {
            return;
        }
        if (!TemperatureModCompat.hasExternalTemperatureMod()
                && !(ShaderAndModCompat.hasSnowRealMagic() && RealWeatherService.canMeltSnow())
                && !tempwinter$isWinterOrCold(64)) {
            return;
        }
        double tempC = (FMLEnvironment.dist == Dist.CLIENT && ClientWeatherState.isActive())
                ? ClientWeatherState.getTempC()
                : RealWeatherService.getCurrentTempC();
        float mcTemp = (float) Math.max(-0.8, Math.min(2.0, tempC / 25.0));
        cir.setReturnValue(mcTemp);
    }

    @Unique
    private static boolean tempwinter$isWinterOrCold() {
        return tempwinter$isWinterOrCold(64);
    }

    @Unique
    private static boolean tempwinter$isWinterOrCold(int y) {
        if ("OVERRIDE".equalsIgnoreCase(WinterConfig.SEASONS_BEHAVIOR.get())) {
            return true;
        }
        if (FMLEnvironment.dist == Dist.CLIENT && ClientWeatherState.isActive()) {
            return ClientWeatherState.isWinterSeasonActive()
                    || RealWeatherService.getEffectiveTempC(ClientWeatherState.getTempC(), y) <= WinterConfig.WET_SNOW_MAX_TEMPERATURE_C.get();
        }
        return SeasonCompat.isServerWinterActive()
                || RealWeatherService.getEffectiveTempC(RealWeatherService.getCurrentTempC(), y) <= WinterConfig.WET_SNOW_MAX_TEMPERATURE_C.get();
    }

    @Unique
    private static boolean tempwinter$isRainMode(int y) {
        if (FMLEnvironment.dist == Dist.CLIENT && ClientWeatherState.isActive()) {
            if (!ClientWeatherState.isRain()) {
                return false;
            }
            return RealWeatherService.getEffectiveTempC(ClientWeatherState.getTempC(), y) > WinterConfig.WET_SNOW_MAX_TEMPERATURE_C.get();
        }
        if (!RealWeatherService.shouldUseRain()) {
            return false;
        }
        return RealWeatherService.getEffectiveTempC(RealWeatherService.getCurrentTempC(), y) > WinterConfig.WET_SNOW_MAX_TEMPERATURE_C.get();
    }

    @Unique
    private static boolean tempwinter$isOverworld() {
        if ("Render thread".equals(Thread.currentThread().getName())) {
            return ClientWeatherState.isInOverworld();
        }
        return RealWeatherService.isTickingOverworld();
    }

    @Unique
    private static boolean tempwinter$isVanillaMode() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            return !ClientWeatherState.isActive();
        }
        return RealWeatherService.isVanillaWeatherMode();
    }
}
