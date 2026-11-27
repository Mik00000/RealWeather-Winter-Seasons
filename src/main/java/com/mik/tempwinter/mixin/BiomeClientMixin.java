package com.mik.tempwinter.mixin;

import com.mik.tempwinter.WinterConfig;
import com.mik.tempwinter.weather.ClientWeatherState;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Biome.class)
public class BiomeClientMixin {
    @Unique
    private static boolean tempwinter$canTintWinter() {
        if (!WinterConfig.ENABLED.get() || !ClientWeatherState.isInOverworld()) {
            return false;
        }
        if (!ClientWeatherState.hasReceivedSync()) {
            return true;
        }
        return ClientWeatherState.isWinterSeasonActive();
    }

    @Inject(method = "getGrassColor(DD)I", at = @At("HEAD"), cancellable = true, require = 0)
    private void tempwinter$grassColor(double x, double z, CallbackInfoReturnable<Integer> cir) {
        if (tempwinter$canTintWinter() && WinterConfig.WINTER_FOLIAGE_COLORS.get()) {
            cir.setReturnValue(0x9BB5AE);
        }
    }

    @Inject(method = "getFoliageColor()I", at = @At("HEAD"), cancellable = true, require = 0)
    private void tempwinter$foliageColor(CallbackInfoReturnable<Integer> cir) {
        if (tempwinter$canTintWinter() && WinterConfig.WINTER_FOLIAGE_COLORS.get()) {
            cir.setReturnValue(0x86A39D);
        }
    }

    @Inject(method = "getWaterColor()I", at = @At("HEAD"), cancellable = true, require = 0)
    private void tempwinter$waterColor(CallbackInfoReturnable<Integer> cir) {
        if (tempwinter$canTintWinter() && WinterConfig.WINTER_WATER_COLOR.get()) {
            cir.setReturnValue(0x3938C9);
        }
    }

    @Inject(method = "getWaterFogColor()I", at = @At("HEAD"), cancellable = true, require = 0)
    private void tempwinter$waterFogColor(CallbackInfoReturnable<Integer> cir) {
        if (tempwinter$canTintWinter() && WinterConfig.WINTER_WATER_COLOR.get()) {
            cir.setReturnValue(0x1E2952);
        }
    }
}
