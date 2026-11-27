package com.mik.tempwinter.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mik.tempwinter.WinterConfig;
import com.mik.tempwinter.client.ShaderAndModCompat;
import com.mik.tempwinter.compat.SeasonCompat;
import com.mik.tempwinter.weather.HeatSourceHelper;
import com.mik.tempwinter.weather.RealWeatherService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public class ServerLevelMixin {
    @Inject(method = "advanceWeatherCycle()V", at = @At("HEAD"), cancellable = true)
    private void tempwinter$disableVanillaWeatherCycle(CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (self.dimension().equals(Level.OVERWORLD)
                && WinterConfig.ENABLED.get() && WinterConfig.REAL_WEATHER_ENABLED.get()
                && !RealWeatherService.isVanillaWeatherMode()
                && !(WinterConfig.YIELD_TO_WEATHER_MODS.get() && ShaderAndModCompat.hasExternalWeatherMod())) {
            ci.cancel();
        }
    }

    @Inject(method = "resetWeatherCycle()V", at = @At("HEAD"), cancellable = true)
    private void tempwinter$disableSleepWeatherReset(CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (self.dimension().equals(Level.OVERWORLD)
                && WinterConfig.ENABLED.get() && WinterConfig.REAL_WEATHER_ENABLED.get()
                && !RealWeatherService.isVanillaWeatherMode()
                && !(WinterConfig.YIELD_TO_WEATHER_MODS.get() && ShaderAndModCompat.hasExternalWeatherMod())) {
            ci.cancel();
        }
    }

    @Inject(method = "tickPrecipitation(Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true)
    private void tempwinter$gatePrecipitationBlocks(BlockPos pos, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (!self.dimension().equals(Level.OVERWORLD) || !WinterConfig.ENABLED.get() || RealWeatherService.isVanillaWeatherMode()) {
            return;
        }
        if (!WinterConfig.PLACE_SNOW_BLOCKS.get()) {
            ci.cancel();
            return;
        }
        if (WinterConfig.REAL_WEATHER_ENABLED.get()
                && !RealWeatherService.canPlaceSnowLayers()
                && !RealWeatherService.canFreezeWater()) {
            ci.cancel();
        }
    }

    @ModifyExpressionValue(
        method = "tickPrecipitation(Lnet/minecraft/core/BlockPos;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/GameRules;getInt(Lnet/minecraft/world/level/GameRules$Key;)I"
        ),
        require = 0
    )
    private int tempwinter$unevenSnowCap(int original, BlockPos pos) {
        if (!WinterConfig.ENABLED.get() || !WinterConfig.PLACE_SNOW_BLOCKS.get() || !RealWeatherService.canPlaceSnowLayers()) {
            return WinterConfig.ENABLED.get() && !RealWeatherService.canPlaceSnowLayers() ? 0 : original;
        }
        ServerLevel self = (ServerLevel) (Object) this;
        if (!self.dimension().equals(Level.OVERWORLD)) {
            return original;
        }
        if (WinterConfig.YIELD_TO_SNOW_REAL_MAGIC.get() && ShaderAndModCompat.hasSnowRealMagic()) {
            return original;
        }
        if (WinterConfig.REAL_WEATHER_ENABLED.get()) {
            float intensity = RealWeatherService.getIntensity();
            if (intensity <= 0.01F || (intensity < 1.0F && self.random.nextFloat() > intensity)) {
                return 0;
            }
        }
        BlockPos top = self.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
        int maxBlocks = WinterConfig.MAX_SNOW_BLOCKS.get();
        int snowBelow = 0;
        BlockPos cursor = top.below();
        while (snowBelow < maxBlocks && self.getBlockState(cursor).is(Blocks.SNOW)) {
            snowBelow++;
            cursor = cursor.below();
        }
        int capLayers = tempwinter$computeColumnCapLayers(self, top, self.getBlockState(cursor), maxBlocks);
        if (capLayers <= 0) {
            return 0;
        }
        int remaining = capLayers - snowBelow * 8;
        return remaining <= 0 ? 0 : Math.min(8, remaining);
    }

    @Unique
    private static int tempwinter$computeColumnCapLayers(ServerLevel self, BlockPos top, BlockState ground, int maxBlocks) {
        var groundId = BuiltInRegistries.BLOCK.getKey(ground.getBlock());
        String fullGroundId = groundId.toString();
        String groundPath = groundId.getPath();
        if (ground.is(BlockTags.RAILS) || groundPath.contains("track")
                || (ShaderAndModCompat.hasImmersiveWeathering() && groundPath.contains("icicle"))) {
            return 0;
        }
        if (WinterConfig.PROTECT_CREATE_MACHINERY.get()) {
            for (String kw : WinterConfig.CUSTOM_PROTECTED_KEYWORDS.get()) {
                if (fullGroundId.equals(kw) || groundPath.contains(kw)) {
                    return 0;
                }
            }
        }
        if (WinterConfig.HEAT_MELTS_SNOW.get() && HeatSourceHelper.hasHeatNearby(self, top, WinterConfig.HEAT_SOURCE_RADIUS.get())) {
            return 0;
        }
        int capLayers = WinterConfig.UNEVEN_DRIFTS.get()
                ? tempwinter$localDriftCapLayers(top.getX(), top.getZ(), maxBlocks)
                : maxBlocks * 8;
        if (WinterConfig.REDUCE_PATH_SNOW.get() && (ground.is(Blocks.DIRT_PATH)
                || ground.is(Blocks.GRAVEL)
                || ground.is(Blocks.COBBLESTONE)
                || ground.is(Blocks.STONE_BRICKS)
                || ground.is(Blocks.MOSSY_COBBLESTONE)
                || ground.is(Blocks.SMOOTH_STONE))) {
            capLayers = Math.min(capLayers, 3);
        }
        return capLayers;
    }

    @Inject(method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V", at = @At("TAIL"))
    private void tempwinter$syncGroundSnowAndMelt(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (!self.dimension().equals(Level.OVERWORLD) || !WinterConfig.ENABLED.get() || RealWeatherService.isVanillaWeatherMode()) {
            return;
        }

        tempwinter$applySnowDepthCover(self, chunk);

        boolean warmMelt = RealWeatherService.canMeltSnow();
        boolean heatMelt = WinterConfig.HEAT_MELTS_SNOW.get();
        if (!warmMelt && !heatMelt) {
            return;
        }
        float meltChance;
        if (warmMelt) {
            double excessC = RealWeatherService.getCurrentTempC() - WinterConfig.MELT_TEMPERATURE_C.get();
            meltChance = (float) Math.min(0.25, 0.02 + Math.max(0.0, excessC) * 0.015 + (RealWeatherService.shouldUseRain() ? 0.08 : 0.0));
        } else {
            meltChance = 0.08F;
        }
        if (self.random.nextFloat() >= meltChance) {
            return;
        }

        int x = chunk.getPos().getMinBlockX() + self.random.nextInt(16);
        int z = chunk.getPos().getMinBlockZ() + self.random.nextInt(16);
        BlockPos top = self.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z));
        boolean nearHeat = heatMelt && HeatSourceHelper.hasHeatNearby(self, top, WinterConfig.HEAT_SOURCE_RADIUS.get());
        if (!warmMelt && !nearHeat) {
            return;
        }

        BlockPos snowPos = self.getBlockState(top).is(Blocks.SNOW)
                ? top
                : (self.getBlockState(top.below()).is(Blocks.SNOW) ? top.below() : null);
        if (snowPos != null) {
            BlockState state = self.getBlockState(snowPos);
            int layers = state.getValue(SnowLayerBlock.LAYERS);
            if (!nearHeat && !RealWeatherService.shouldUseRain() && RealWeatherService.getCurrentTempC() <= 2.5) {
                int floor = RealWeatherService.getSnowDepthTargetLayers();
                if (layers <= floor) {
                    return;
                }
            }
            if (layers <= 1) {
                self.removeBlock(snowPos, false);
            } else {
                self.setBlockAndUpdate(snowPos, state.setValue(SnowLayerBlock.LAYERS, layers - 1));
            }
            return;
        }

        BlockPos icePos = top.below();
        if (self.getBlockState(icePos).is(Blocks.ICE) && self.canSeeSky(top)) {
            self.setBlockAndUpdate(icePos, Blocks.WATER.defaultBlockState());
        }
    }

    @Unique
    private static void tempwinter$applySnowDepthCover(ServerLevel self, LevelChunk chunk) {
        int snowDepthTarget = RealWeatherService.getSnowDepthTargetLayers();
        if (snowDepthTarget <= 0 || !WinterConfig.PLACE_SNOW_BLOCKS.get()) {
            return;
        }
        if (WinterConfig.YIELD_TO_SNOW_REAL_MAGIC.get() && ShaderAndModCompat.hasSnowRealMagic()) {
            return;
        }
        if (!SeasonCompat.isServerWinterActive() && RealWeatherService.getCurrentTempC() > WinterConfig.MELT_TEMPERATURE_C.get()) {
            return;
        }
        int maxBlocks = WinterConfig.MAX_SNOW_BLOCKS.get();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < 4; i++) {
            int x = minX + self.random.nextInt(16);
            int z = minZ + self.random.nextInt(16);
            BlockPos top = self.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z));
            if (!self.canSeeSky(top) || self.getBrightness(LightLayer.BLOCK, top) >= 10) {
                continue;
            }
            BlockPos below = top.below();
            BlockState belowState = self.getBlockState(below);
            if (belowState.is(Blocks.WATER) && self.getFluidState(below).isSource() && RealWeatherService.canFreezeWater()) {
                if (!WinterConfig.HEAT_MELTS_SNOW.get() || !HeatSourceHelper.hasHeatNearby(self, top, WinterConfig.HEAT_SOURCE_RADIUS.get())) {
                    self.setBlockAndUpdate(below, Blocks.ICE.defaultBlockState());
                }
                continue;
            }
            BlockPos placePos = self.getBlockState(top).is(Blocks.SNOW) ? top : (self.getBlockState(top).isAir() ? top : null);
            if (placePos == null) {
                continue;
            }
            int snowBelow = 0;
            BlockPos cursor = placePos.below();
            while (snowBelow < maxBlocks && self.getBlockState(cursor).is(Blocks.SNOW)) {
                snowBelow++;
                cursor = cursor.below();
            }
            int capLayers = tempwinter$computeColumnCapLayers(self, placePos, self.getBlockState(cursor), maxBlocks);
            int goal = Math.min(capLayers, snowDepthTarget);
            if (goal <= 0) {
                continue;
            }
            BlockState atPlace = self.getBlockState(placePos);
            int curLayers = atPlace.is(Blocks.SNOW) ? atPlace.getValue(SnowLayerBlock.LAYERS) : 0;
            int totalExisting = snowBelow * 8 + curLayers;
            if (totalExisting >= goal) {
                continue;
            }
            if (curLayers == 0) {
                BlockState snow = Blocks.SNOW.defaultBlockState();
                if (snow.canSurvive(self, placePos)) {
                    self.setBlockAndUpdate(placePos, snow);
                }
            } else if (curLayers < 8) {
                self.setBlockAndUpdate(placePos, atPlace.setValue(SnowLayerBlock.LAYERS, curLayers + 1));
            } else {
                BlockPos above = placePos.above();
                if (self.getBlockState(above).isAir()) {
                    BlockState snow = Blocks.SNOW.defaultBlockState();
                    if (snow.canSurvive(self, above)) {
                        self.setBlockAndUpdate(above, snow);
                    }
                }
            }
        }
    }

    @Unique
    private static int tempwinter$localDriftCapLayers(int x, int z, int maxBlocks) {
        int maxLayers = maxBlocks * 8;
        int minLayers = Math.max(4, maxLayers / 2);
        double n = tempwinter$smoothNoise(x / 6.0, z / 6.0);
        double fine = tempwinter$hash01(x, z) * 0.12;
        double t = Math.max(0.0, Math.min(1.0, n * 0.88 + fine));
        return minLayers + (int) Math.round(t * (maxLayers - minLayers));
    }

    @Unique
    private static double tempwinter$smoothNoise(double x, double z) {
        int xi = (int) Math.floor(x);
        int zi = (int) Math.floor(z);
        double fx = x - xi;
        double fz = z - zi;
        fx = fx * fx * (3.0 - 2.0 * fx);
        fz = fz * fz * (3.0 - 2.0 * fz);
        double a = tempwinter$hash01(xi, zi);
        double b = tempwinter$hash01(xi + 1, zi);
        double c = tempwinter$hash01(xi, zi + 1);
        double d = tempwinter$hash01(xi + 1, zi + 1);
        double ab = a + (b - a) * fx;
        double cd = c + (d - c) * fx;
        return ab + (cd - ab) * fz;
    }

    @Unique
    private static double tempwinter$hash01(int x, int z) {
        long h = x * 341873128712L + z * 132897987541L;
        h = (h ^ (h >>> 33)) * 0xff51afd7ed558ccdL;
        h = (h ^ (h >>> 33)) * 0xc4ceb9fe1a85ec53L;
        h = h ^ (h >>> 33);
        return ((h >>> 11) & 0x1FFFFFL) / (double) 0x200000L;
    }
}
