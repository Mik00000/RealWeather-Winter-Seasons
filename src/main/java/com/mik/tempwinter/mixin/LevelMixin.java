package com.mik.tempwinter.mixin;

import com.mik.tempwinter.WinterConfig;
import com.mik.tempwinter.weather.ClientWeatherState;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public class LevelMixin {
    @Inject(method = "getRainLevel(F)F", at = @At("RETURN"), cancellable = true, require = 0)
    private void tempwinter$scaleSnowfallDensity(float partialTick, CallbackInfoReturnable<Float> cir) {
        Level self = (Level) (Object) this;
        if (!self.isClientSide || !self.dimension().equals(Level.OVERWORLD) || !WinterConfig.isEnabled()) {
            return;
        }
        if (WinterConfig.REAL_WEATHER_ENABLED.get() && ClientWeatherState.isActive()) {
            float base = cir.getReturnValueF();
            if (base > 0.0F) {
                cir.setReturnValue(base * ClientWeatherState.getIntensity());
            }
        }
    }
}
