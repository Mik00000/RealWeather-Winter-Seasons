package com.mik.tempwinter.client;

import com.mik.tempwinter.WinterConfig;
import com.mik.tempwinter.weather.ClientWeatherState;
import com.mik.tempwinter.weather.RealWeatherService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.Locale;

/**
 * Client-side winter visuals: shader-aware blizzard fog, cold breath vapor, and tree-shake snow.
 */
public final class WinterClientEvents {
    private static int breathCooldownTicks = 30;
    private static int windSoundCooldownTicks = 60;
    private static long lastTreeShakeTick = -10L;

    private WinterClientEvents() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(WinterClientEvents::onClientTick);
        NeoForge.EVENT_BUS.addListener(WinterClientEvents::onRenderFog);
        NeoForge.EVENT_BUS.addListener(WinterClientEvents::onComputeFogColor);
        NeoForge.EVENT_BUS.addListener(WinterClientEvents::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(WinterClientEvents::onDebugText);
        NeoForge.EVENT_BUS.addListener(WinterClientEvents::onLoggingOut);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientWeatherState.reset();
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (!WinterConfig.ENABLED.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (mc.isPaused() || player == null || level == null || player.isSpectator()) {
            return;
        }
        boolean inOverworld = level.dimension().equals(Level.OVERWORLD);
        ClientWeatherState.setInOverworld(inOverworld);
        if (!inOverworld || (WinterConfig.REAL_WEATHER_ENABLED.get() && ClientWeatherState.isExplicitlyDisabled())) {
            return;
        }

        RandomSource rand = level.random;
        double baseTempC = ClientWeatherState.isActive() ? ClientWeatherState.getTempC() : -1.0;
        double tempC = RealWeatherService.getEffectiveTempC(baseTempC, player.getBlockY());
        double windKmh = ClientWeatherState.isActive() ? ClientWeatherState.getWindKmh() : 0.0;
        boolean isSnowingOutdoors = !ClientWeatherState.isRain()
                && level.getRainLevel(1.0F) > 0.2F
                && level.canSeeSky(player.blockPosition());

        double windThreshold = WinterConfig.BLIZZARD_WIND_THRESHOLD_KMH.get();
        if (isSnowingOutdoors && windKmh >= windThreshold && WinterConfig.GROUND_BLIZZARD_PARTICLES.get()
                && !ShaderAndModCompat.hasExternalWinterParticles()
                && !ShaderAndModCompat.hasParticleRain()) {
            int flakes = windKmh >= windThreshold + 15.0 ? 5 : 3;
            for (int i = 0; i < flakes; i++) {
                double px = player.getX() + (rand.nextDouble() - 0.5) * 18.0;
                double py = player.getY() + rand.nextDouble() * 3.0;
                double pz = player.getZ() + (rand.nextDouble() - 0.5) * 18.0;
                level.addParticle(
                        ParticleTypes.SNOWFLAKE,
                        px, py, pz,
                        0.08 + rand.nextDouble() * 0.06,
                        -0.02 - rand.nextDouble() * 0.02,
                        0.03 + (rand.nextDouble() - 0.5) * 0.04
                );
            }
        }

        if (isSnowingOutdoors && windKmh >= windThreshold && WinterConfig.BLIZZARD_WIND_SOUND.get()
                && !(WinterConfig.YIELD_TO_AMBIENT_SOUNDS.get() && ShaderAndModCompat.hasAmbientSounds())) {
            if (--windSoundCooldownTicks <= 0) {
                windSoundCooldownTicks = 55 + rand.nextInt(45);
                float vol = (float) Math.min(0.24, 0.10 + (windKmh - windThreshold) * 0.004);
                float pitch = 0.55F + rand.nextFloat() * 0.16F;
                level.playLocalSound(
                        player.getX() + (rand.nextDouble() - 0.5) * 6.0,
                        player.getY() + 1.5,
                        player.getZ() + (rand.nextDouble() - 0.5) * 6.0,
                        SoundEvents.BREEZE_IDLE_AIR,
                        SoundSource.WEATHER,
                        vol,
                        pitch,
                        false
                );
            }
        }

        if (WinterConfig.OVERHANG_SNOW_DRIP.get() && tempC <= WinterConfig.WET_SNOW_MAX_TEMPERATURE_C.get()
                && player.tickCount % 3 == 0) {
            BlockPos base = player.blockPosition();
            for (int i = 0; i < 2; i++) {
                BlockPos check = base.offset(
                        rand.nextInt(15) - 7,
                        1 + rand.nextInt(6),
                        rand.nextInt(15) - 7
                );
                BlockState selfState = level.getBlockState(check);
                if (selfState.isSolid() && level.getBlockState(check.below()).isAir()) {
                    BlockState above = level.getBlockState(check.above());
                    if (above.is(Blocks.SNOW) || above.is(Blocks.SNOW_BLOCK)) {
                        level.addParticle(
                                ParticleTypes.SNOWFLAKE,
                                check.getX() + rand.nextDouble(),
                                check.getY() - 0.05,
                                check.getZ() + rand.nextDouble(),
                                (rand.nextDouble() - 0.5) * 0.02,
                                -0.04,
                                (rand.nextDouble() - 0.5) * 0.02
                        );
                    }
                }
            }
        }

        if (!WinterConfig.COLD_BREATH_PARTICLES.get() || ShaderAndModCompat.hasExternalBreathMod() || player.isUnderWater()) {
            return;
        }
        if (tempC > WinterConfig.FREEZE_TEMPERATURE_C.get()) {
            return;
        }

        if (--breathCooldownTicks > 0) {
            return;
        }
        breathCooldownTicks = 35 + rand.nextInt(25);

        Vec3 look = player.getLookAngle();
        Vec3 mouth = player.getEyePosition().add(look.scale(0.32)).subtract(0.0, 0.14, 0.0);
        int count = tempC <= -5.0 ? 2 : 1;
        for (int i = 0; i < count; i++) {
            double ox = (rand.nextDouble() - 0.5) * 0.04;
            double oy = (rand.nextDouble() - 0.5) * 0.03;
            double oz = (rand.nextDouble() - 0.5) * 0.04;
            level.addParticle(
                    ParticleTypes.WHITE_SMOKE,
                    mouth.x + ox, mouth.y + oy, mouth.z + oz,
                    look.x * 0.015 + ox * 0.2,
                    0.006 + rand.nextDouble() * 0.006,
                    look.z * 0.015 + oz * 0.2
            );
        }
    }

    private static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getLevel().isClientSide() || !event.getLevel().dimension().equals(Level.OVERWORLD)
                || !WinterConfig.ENABLED.get() || !WinterConfig.TREE_SHAKE_SNOW.get()
                || ShaderAndModCompat.hasExternalWinterParticles()
                || (WinterConfig.REAL_WEATHER_ENABLED.get() && ClientWeatherState.isExplicitlyDisabled())) {
            return;
        }
        double tempC = ClientWeatherState.isActive() ? ClientWeatherState.getTempC() : -1.0;
        if (tempC > WinterConfig.WET_SNOW_MAX_TEMPERATURE_C.get()) {
            return;
        }

        Level level = event.getLevel();
        long now = level.getGameTime();
        if (now - lastTreeShakeTick < 5L) {
            return;
        }

        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)) {
            return;
        }

        lastTreeShakeTick = now;
        RandomSource rand = level.random;
        level.playLocalSound(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                SoundEvents.SNOW_STEP, SoundSource.BLOCKS, 0.45F, 1.25F, false
        );
        for (int i = 0; i < 12; i++) {
            double px = pos.getX() + 0.5 + (rand.nextDouble() - 0.5) * 2.0;
            double py = pos.getY() + 1.0 + rand.nextDouble() * 1.8;
            double pz = pos.getZ() + 0.5 + (rand.nextDouble() - 0.5) * 2.0;
            double vx = (rand.nextDouble() - 0.5) * 0.05;
            double vy = -0.06 - rand.nextDouble() * 0.08;
            double vz = (rand.nextDouble() - 0.5) * 0.05;
            level.addParticle(ParticleTypes.SNOWFLAKE, px, py, pz, vx, vy, vz);
        }
    }

    private static boolean shouldApplyWinterFog(ViewportEvent event) {
        if (!WinterConfig.ENABLED.get() || !WinterConfig.WINTER_FOG.get()
                || (WinterConfig.REAL_WEATHER_ENABLED.get() && ClientWeatherState.isExplicitlyDisabled())
                || ShaderAndModCompat.hasExternalFogMod() || ShaderAndModCompat.hasParticleRain()) {
            return false;
        }
        if (!WinterConfig.FOG_WITH_SHADERS.get() && ShaderAndModCompat.isIrisShaderActive()) {
            return false;
        }
        if (event.getCamera().getFluidInCamera() != FogType.NONE) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD) || ClientWeatherState.isRain()) {
            return false;
        }
        return mc.level.getRainLevel((float) event.getPartialTick()) > 0.08F;
    }

    private static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!shouldApplyWinterFog(event)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float rain = mc.level.getRainLevel((float) event.getPartialTick());
        float intensity = ClientWeatherState.isActive() ? ClientWeatherState.getIntensity() : 0.6F;
        float strength = Math.min(1.0F, rain * Math.max(0.35F, intensity));

        float vanillaFar = event.getFarPlaneDistance();
        float targetFar = Math.min(vanillaFar, 128.0F - strength * 64.0F);
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), Math.max(2.0F, 12.0F - strength * 8.0F)));
        event.setFarPlaneDistance(targetFar);
        event.setCanceled(true);
    }

    private static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        if (!shouldApplyWinterFog(event)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float rain = mc.level.getRainLevel((float) event.getPartialTick());
        float blend = Math.min(0.45F, rain * 0.35F);
        event.setRed(event.getRed() * (1.0F - blend) + 0.80F * blend);
        event.setGreen(event.getGreen() * (1.0F - blend) + 0.85F * blend);
        event.setBlue(event.getBlue() * (1.0F - blend) + 0.92F * blend);
    }

    private static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (!WinterConfig.ENABLED.get() || !ClientWeatherState.isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!mc.getDebugOverlay().showDebugScreen() || mc.player == null || !mc.player.level().dimension().equals(Level.OVERWORLD)) {
            return;
        }
        double base = ClientWeatherState.getTempC();
        double eff = RealWeatherService.getEffectiveTempC(base, mc.player.getBlockY());
        String mode = ClientWeatherState.isRain() ? "RAIN" : (eff <= WinterConfig.FREEZE_TEMPERATURE_C.get() ? "SNOW" : "WET_SNOW");
        event.getRight().add(String.format(
                Locale.ROOT,
                "[RealWeather] %.1fC (eff %.1fC) | Wind %.1f km/h | %s",
                base, eff, ClientWeatherState.getWindKmh(), mode
        ));
    }
}
