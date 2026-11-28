package com.mik.tempwinter.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mik.tempwinter.WinterConfig;
import com.mik.tempwinter.client.ShaderAndModCompat;
import com.mik.tempwinter.compat.SableCompat;
import com.mik.tempwinter.compat.SeasonCompat;
import com.mik.tempwinter.weather.HeatSourceHelper;
import com.mik.tempwinter.weather.RealWeatherService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
                && WinterConfig.isEnabled() && WinterConfig.REAL_WEATHER_ENABLED.get()
                && !RealWeatherService.isVanillaWeatherMode()
                && !(WinterConfig.YIELD_TO_WEATHER_MODS.get() && ShaderAndModCompat.hasExternalWeatherMod())) {
            ci.cancel();
        }
    }

    @Inject(method = "resetWeatherCycle()V", at = @At("HEAD"), cancellable = true)
    private void tempwinter$disableSleepWeatherReset(CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (self.dimension().equals(Level.OVERWORLD)
                && WinterConfig.isEnabled() && WinterConfig.REAL_WEATHER_ENABLED.get()
                && !RealWeatherService.isVanillaWeatherMode()
                && !(WinterConfig.YIELD_TO_WEATHER_MODS.get() && ShaderAndModCompat.hasExternalWeatherMod())) {
            ci.cancel();
        }
    }

    @Inject(method = "tickPrecipitation(Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true)
    private void tempwinter$gatePrecipitationBlocks(BlockPos pos, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (!self.dimension().equals(Level.OVERWORLD) || !WinterConfig.isEnabled() || RealWeatherService.isVanillaWeatherMode()) {
            return;
        }
        if (!WinterConfig.PLACE_SNOW_BLOCKS.get()) {
            ci.cancel();
            return;
        }
        if (WinterConfig.REAL_WEATHER_ENABLED.get()) {
            BlockPos top = self.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
            int worldY = SableCompat.getEffectiveWorldY(self, top);
            if (!RealWeatherService.canPlaceSnowLayers(worldY) && !RealWeatherService.canFreezeWater(worldY)) {
                ci.cancel();
            }
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
        if (!WinterConfig.isLoaded()) {
            return original;
        }
        if (!WinterConfig.ENABLED.get() || !WinterConfig.PLACE_SNOW_BLOCKS.get()) {
            return WinterConfig.ENABLED.get() ? 0 : original;
        }
        ServerLevel self = (ServerLevel) (Object) this;
        if (!self.dimension().equals(Level.OVERWORLD)) {
            return original;
        }
        BlockPos top = self.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
        int worldY = SableCompat.getEffectiveWorldY(self, top);
        if (!RealWeatherService.canPlaceSnowLayers(worldY) || !SableCompat.canSnowStickOnDeck(self, top)) {
            return 0;
        }
        boolean yieldToSrm = WinterConfig.YIELD_TO_SNOW_REAL_MAGIC.get() && ShaderAndModCompat.hasSnowRealMagic();
        if (WinterConfig.REAL_WEATHER_ENABLED.get()) {
            float intensity = RealWeatherService.getIntensity();
            if (intensity <= 0.01F || (intensity < 1.0F && self.random.nextFloat() > intensity)) {
                return 0;
            }
        }
        BlockPos below = top.below();
        BlockState belowState = self.getBlockState(below);
        BlockPos placePos = (belowState.is(Blocks.SNOW) && belowState.getValue(SnowLayerBlock.LAYERS) < 8)
                ? below
                : top;
        int maxBlocks = WinterConfig.MAX_SNOW_BLOCKS.get();
        int snowBelow = 0;
        BlockPos cursor = placePos.below();
        while (snowBelow < maxBlocks && self.getBlockState(cursor).is(Blocks.SNOW)) {
            snowBelow++;
            cursor = cursor.below();
        }
        int capLayers = tempwinter$computeColumnCapLayers(self, placePos, self.getBlockState(cursor), maxBlocks);
        if (capLayers <= 0) {
            return 0;
        }
        BlockState atPlace = self.getBlockState(placePos);
        int curLayers = atPlace.is(Blocks.SNOW) ? atPlace.getValue(SnowLayerBlock.LAYERS) : 0;
        if (snowBelow * 8 + curLayers >= capLayers) {
            return 0;
        }
        if (WinterConfig.SMOOTH_DRIFTS.get() && (snowBelow > 0 || curLayers >= 3)
                && tempwinter$shouldWaitForNeighbors(self, placePos, curLayers)) {
            return 0;
        }
        int remaining = capLayers - snowBelow * 8;
        if (remaining <= 0) {
            return 0;
        }
        if (yieldToSrm) {
            return Math.min(original, remaining);
        }
        return Math.min(8, remaining);
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
        double noise01 = tempwinter$localDriftNoise01(top.getX(), top.getZ());
        int fallbackDrift = WinterConfig.UNEVEN_DRIFTS.get()
                ? tempwinter$localDriftCapLayers(noise01, maxBlocks)
                : maxBlocks * 8;
        int wallBonus = 0;
        if (WinterConfig.WALL_DRIFT_BONUS.get()) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockState adj = self.getBlockState(top.relative(dir));
                if (adj.isSolid() && !adj.is(Blocks.SNOW) && !adj.is(Blocks.SNOW_BLOCK) && !adj.is(BlockTags.DOORS)) {
                    wallBonus = 4;
                    break;
                }
            }
        }
        int worldY = SableCompat.getEffectiveWorldY(self, top);
        int capLayers = RealWeatherService.getColumnSnowCapLayers(worldY, noise01, wallBonus, fallbackDrift);
        if (capLayers <= 0) {
            return 0;
        }
        if (WinterConfig.REDUCE_PATH_SNOW.get() && (ground.is(Blocks.DIRT_PATH)
                || ground.is(Blocks.GRAVEL)
                || ground.is(Blocks.COBBLESTONE)
                || ground.is(Blocks.STONE_BRICKS)
                || ground.is(Blocks.MOSSY_COBBLESTONE)
                || ground.is(Blocks.SMOOTH_STONE))) {
            return Math.min(capLayers, 3);
        }
        return capLayers;
    }

    @Unique
    private static boolean tempwinter$shouldWaitForNeighbors(ServerLevel self, BlockPos pos, int curLayers) {
        int myHeight = pos.getY() * 8 + curLayers;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos nCol = pos.relative(dir);
            BlockPos nTop = self.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, nCol);
            if (Math.abs(nTop.getY() - pos.getY()) > 1) {
                continue;
            }
            BlockPos nSnow = self.getBlockState(nTop.below()).is(Blocks.SNOW)
                    && self.getBlockState(nTop.below()).getValue(SnowLayerBlock.LAYERS) < 8
                    ? nTop.below()
                    : nTop;
            BlockState nState = self.getBlockState(nSnow);
            int nLayers = nState.is(Blocks.SNOW) ? nState.getValue(SnowLayerBlock.LAYERS) : (nState.is(Blocks.SNOW_BLOCK) ? 8 : 0);
            if (nLayers == 0 && !nState.isAir()) {
                continue;
            }
            if (nLayers == 0 && !Blocks.SNOW.defaultBlockState().canSurvive(self, nSnow)) {
                continue;
            }
            int nHeight = nSnow.getY() * 8 + nLayers;
            if (myHeight - nHeight > 4) {
                return true;
            }
        }
        return false;
    }

    @Inject(method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V", at = @At("TAIL"))
    private void tempwinter$syncGroundSnowAndMelt(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (!self.dimension().equals(Level.OVERWORLD) || !WinterConfig.isEnabled() || RealWeatherService.isVanillaWeatherMode()) {
            return;
        }

        tempwinter$applySnowDepthCover(self, chunk);
        tempwinter$applyWindBlownRoofSnow(self, chunk);

        boolean warmMelt = RealWeatherService.canMeltSnow();
        boolean heatMelt = WinterConfig.HEAT_MELTS_SNOW.get();
        boolean offlineThaw = RealWeatherService.isOfflineThawActive();
        if (!warmMelt && !heatMelt && !offlineThaw) {
            return;
        }
        float meltChance;
        if (warmMelt) {
            double excessC = RealWeatherService.getCurrentTempC() - WinterConfig.MELT_TEMPERATURE_C.get();
            meltChance = (float) Math.min(0.25, 0.02 + Math.max(0.0, excessC) * 0.015 + (RealWeatherService.shouldUseRain() ? 0.08 : 0.0));
        } else if (offlineThaw) {
            meltChance = 0.15F;
        } else {
            meltChance = 0.08F;
        }
        if (self.random.nextFloat() >= meltChance) {
            return;
        }

        int x = chunk.getPos().getMinBlockX() + self.random.nextInt(16);
        int z = chunk.getPos().getMinBlockZ() + self.random.nextInt(16);
        BlockPos top = self.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z));
        int worldY = SableCompat.getEffectiveWorldY(self, top);
        boolean warmMeltAtTop = RealWeatherService.canMeltSnow(worldY);
        boolean offlineThawAtTop = RealWeatherService.hasOfflineThawAt(worldY);
        boolean nearHeat = heatMelt && HeatSourceHelper.hasHeatNearby(self, top, WinterConfig.HEAT_SOURCE_RADIUS.get());
        if (!warmMeltAtTop && !nearHeat && !offlineThawAtTop) {
            return;
        }

        BlockPos snowPos = tempwinter$findSnowBlockAt(self, top);
        if (snowPos == null && (WinterConfig.UNDER_TREE_SNOW.get() || WinterConfig.WIND_BLOWN_UNDER_ROOFS.get())) {
            BlockPos treeUnder = self.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, top);
            if (treeUnder.getY() < top.getY()) {
                snowPos = tempwinter$findSnowBlockAt(self, treeUnder);
            }
            if (snowPos == null && WinterConfig.WIND_BLOWN_UNDER_ROOFS.get()) {
                Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(self.random);
                BlockPos underRoof = top.relative(dir, 1 + self.random.nextInt(2));
                if (!self.canSeeSky(underRoof)) {
                    snowPos = tempwinter$findSnowBlockAt(self, underRoof);
                }
            }
        }
        if (snowPos != null) {
            BlockState state = self.getBlockState(snowPos);
            int layers = state.getValue(SnowLayerBlock.LAYERS);
            int snowWorldY = SableCompat.getEffectiveWorldY(self, snowPos);
            boolean canWarmMeltHere = RealWeatherService.canMeltSnow(snowWorldY);
            boolean offlineThawSnow = RealWeatherService.hasOfflineThawAt(snowWorldY);
            boolean nearHeatSnow = heatMelt && HeatSourceHelper.hasHeatNearby(self, snowPos, WinterConfig.HEAT_SOURCE_RADIUS.get());
            if (!canWarmMeltHere && !nearHeatSnow && !offlineThawSnow) {
                return;
            }
            double effectiveTempC = RealWeatherService.getEffectiveTempC(RealWeatherService.getCurrentTempC(), snowWorldY);
            boolean keepDepthFloor = !nearHeatSnow && ((offlineThawSnow && !canWarmMeltHere)
                    || (!RealWeatherService.shouldUseRain(snowWorldY) && effectiveTempC <= WinterConfig.MELT_TEMPERATURE_C.get() + 1.0));
            if (keepDepthFloor) {
                int floor = RealWeatherService.getColumnSnowCapLayers(
                        snowWorldY,
                        tempwinter$localDriftNoise01(snowPos.getX(), snowPos.getZ()),
                        0,
                        0
                );
                int snowBelow = 0;
                BlockPos cursor = snowPos.below();
                while (snowBelow < WinterConfig.MAX_SNOW_BLOCKS.get() && self.getBlockState(cursor).is(Blocks.SNOW)) {
                    snowBelow++;
                    cursor = cursor.below();
                }
                if (snowBelow * 8 + layers <= floor && (self.canSeeSky(snowPos) || floor > 0)) {
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
        if (self.getBlockState(icePos).is(Blocks.ICE) && self.canSeeSky(top)
                && (warmMeltAtTop || nearHeat || (offlineThawAtTop && RealWeatherService.getSnowDepthTargetLayers(worldY) == 0))) {
            self.setBlockAndUpdate(icePos, Blocks.WATER.defaultBlockState());
        }
    }

    @Unique
    private static void tempwinter$applySnowDepthCover(ServerLevel self, LevelChunk chunk) {
        if (self.random.nextInt(4) != 0) {
            return;
        }
        if (!WinterConfig.PLACE_SNOW_BLOCKS.get() || RealWeatherService.getSnowDepthTargetLayers(320) <= 0) {
            return;
        }
        boolean winterActive = SeasonCompat.isServerWinterActive();
        int maxBlocks = WinterConfig.MAX_SNOW_BLOCKS.get();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        long gameTime = self.getGameTime();
        for (int i = 0; i < 2; i++) {
            int x = minX + self.random.nextInt(16);
            int z = minZ + self.random.nextInt(16);
            if (RealWeatherService.isColumnTrampled(x, z, gameTime)) {
                continue;
            }
            BlockPos top = self.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z));
            if (!self.canSeeSky(top) || self.getBrightness(LightLayer.BLOCK, top) >= 10 || !SableCompat.canSnowStickOnDeck(self, top)) {
                continue;
            }
            int worldY = SableCompat.getEffectiveWorldY(self, top);
            int snowDepthTarget = RealWeatherService.getSnowDepthTargetLayers(worldY);
            if (snowDepthTarget <= 0) {
                continue;
            }
            if (!winterActive && RealWeatherService.getEffectiveTempC(RealWeatherService.getCurrentTempC(), worldY) > WinterConfig.MELT_TEMPERATURE_C.get()) {
                continue;
            }
            BlockPos below = top.below();
            BlockState belowState = self.getBlockState(below);
            if (belowState.is(Blocks.WATER) && self.getFluidState(below).isSource() && RealWeatherService.canFreezeWater(worldY)) {
                if (!WinterConfig.HEAT_MELTS_SNOW.get() || !HeatSourceHelper.hasHeatNearby(self, top, WinterConfig.HEAT_SOURCE_RADIUS.get())) {
                    self.setBlockAndUpdate(below, Blocks.ICE.defaultBlockState());
                }
                continue;
            }
            BlockPos placePos;
            if (belowState.is(Blocks.SNOW) && belowState.getValue(SnowLayerBlock.LAYERS) < 8) {
                placePos = below;
            } else if (self.getBlockState(top).is(Blocks.SNOW) || self.getBlockState(top).isAir()) {
                placePos = top;
            } else {
                continue;
            }
            int snowBelow = 0;
            BlockPos cursor = placePos.below();
            while (snowBelow < maxBlocks && self.getBlockState(cursor).is(Blocks.SNOW)) {
                snowBelow++;
                cursor = cursor.below();
            }
            int goal = tempwinter$computeColumnCapLayers(self, placePos, self.getBlockState(cursor), maxBlocks);
            if (goal <= 0) {
                continue;
            }
            BlockState atPlace = self.getBlockState(placePos);
            int curLayers = atPlace.is(Blocks.SNOW) ? atPlace.getValue(SnowLayerBlock.LAYERS) : 0;
            int totalExisting = snowBelow * 8 + curLayers;
            if (totalExisting >= goal) {
                continue;
            }
            if (WinterConfig.SMOOTH_DRIFTS.get() && (snowBelow > 0 || curLayers >= 3)
                    && tempwinter$shouldWaitForNeighbors(self, placePos, curLayers)) {
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

        if (WinterConfig.UNDER_TREE_SNOW.get() && self.random.nextInt(4) == 0) {
            int ux = minX + self.random.nextInt(16);
            int uz = minZ + self.random.nextInt(16);
            if (!RealWeatherService.isColumnTrampled(ux, uz, gameTime)) {
                BlockPos skyTop = self.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(ux, 0, uz));
                BlockPos underTop = self.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(ux, 0, uz));
                int underWorldY = SableCompat.getEffectiveWorldY(self, underTop);
                int underTarget = RealWeatherService.getSnowDepthTargetLayers(underWorldY);
                if (underTarget > 0
                        && underTop.getY() < skyTop.getY()
                        && SableCompat.canSnowStickOnDeck(self, underTop)
                        && (winterActive || RealWeatherService.getEffectiveTempC(RealWeatherService.getCurrentTempC(), underWorldY) <= WinterConfig.MELT_TEMPERATURE_C.get())
                        && self.getBrightness(LightLayer.BLOCK, underTop) < 10
                        && tempwinter$onlyLeavesAbove(self, underTop, skyTop)
                        && (!WinterConfig.HEAT_MELTS_SNOW.get() || !HeatSourceHelper.hasHeatNearby(self, underTop, WinterConfig.HEAT_SOURCE_RADIUS.get()))) {
                    tempwinter$placeShallowSnow(self, underTop, underTarget >= 2 ? 2 : 1);
                }
            }
        }
    }

    @Unique
    private static BlockPos tempwinter$findSnowBlockAt(ServerLevel self, BlockPos pos) {
        if (self.getBlockState(pos).is(Blocks.SNOW)) {
            return pos;
        }
        BlockPos below = pos.below();
        return self.getBlockState(below).is(Blocks.SNOW) ? below : null;
    }

    @Unique
    private static void tempwinter$applyWindBlownRoofSnow(ServerLevel self, LevelChunk chunk) {
        if (!WinterConfig.PLACE_SNOW_BLOCKS.get() || !WinterConfig.WIND_BLOWN_UNDER_ROOFS.get() || self.random.nextInt(4) != 0) {
            return;
        }
        double windKmh = RealWeatherService.getCurrentWindKmh();
        if (windKmh < 10.0) {
            return;
        }
        boolean liveWindySnow = self.isRaining()
                && RealWeatherService.canPlaceSnowLayers(320)
                && RealWeatherService.getIntensity() > 0.1F;
        if (!liveWindySnow && RealWeatherService.getSnowDepthTargetLayers(320) <= 0) {
            return;
        }
        int ox = chunk.getPos().getMinBlockX() + self.random.nextInt(16);
        int oz = chunk.getPos().getMinBlockZ() + self.random.nextInt(16);
        BlockPos outsideTop = self.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(ox, 0, oz));
        if (!self.canSeeSky(outsideTop)) {
            return;
        }
        Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(self.random);
        int maxReach = windKmh >= WinterConfig.BLIZZARD_WIND_THRESHOLD_KMH.get() ? 2 : 1;
        long gameTime = self.getGameTime();
        boolean winterActive = SeasonCompat.isServerWinterActive();
        double density = Math.min(0.75, 0.25 + (windKmh - 10.0) / 35.0);

        BlockPos walkPos = outsideTop;
        for (int step = 1; step <= maxReach; step++) {
            walkPos = walkPos.relative(dir);
            if (tempwinter$blocksWindFlow(self.getBlockState(walkPos.above()))) {
                break;
            }
            if (tempwinter$blocksWindFlow(self.getBlockState(walkPos))) {
                if (!tempwinter$blocksWindFlow(self.getBlockState(walkPos.above(2)))
                        && !tempwinter$blocksWindFlow(self.getBlockState(walkPos.relative(dir.getOpposite()).above()))) {
                    walkPos = walkPos.above();
                } else {
                    break;
                }
            } else if (self.getBlockState(walkPos).isAir() && self.getBlockState(walkPos.below()).isAir()
                    && !self.getBlockState(walkPos.below(2)).isAir()) {
                walkPos = walkPos.below();
            }
            if (self.canSeeSky(walkPos)) {
                continue;
            }
            if (RealWeatherService.isColumnTrampled(walkPos.getX(), walkPos.getZ(), gameTime)
                    || self.getBrightness(LightLayer.BLOCK, walkPos) >= 10
                    || !SableCompat.canSnowStickOnDeck(self, walkPos)) {
                continue;
            }
            int worldY = SableCompat.getEffectiveWorldY(self, walkPos);
            boolean activeForCol = RealWeatherService.getSnowDepthTargetLayers(worldY) > 0
                    || (self.isRaining() && RealWeatherService.canPlaceSnowLayers(worldY) && RealWeatherService.getIntensity() > 0.1F);
            if (!activeForCol) {
                continue;
            }
            if (!winterActive && RealWeatherService.getEffectiveTempC(RealWeatherService.getCurrentTempC(), worldY) > WinterConfig.MELT_TEMPERATURE_C.get()) {
                continue;
            }
            double noise = tempwinter$hash01(walkPos.getX() * 31 + 7, walkPos.getZ() * 17 - 3);
            if (noise > density) {
                continue;
            }
            int capLayers = tempwinter$computeColumnCapLayers(self, walkPos, self.getBlockState(walkPos.below()), WinterConfig.MAX_SNOW_BLOCKS.get());
            if (capLayers <= 0) {
                continue;
            }
            int desired = (windKmh >= WinterConfig.BLIZZARD_WIND_THRESHOLD_KMH.get() && noise < density * 0.45) ? 2 : 1;
            tempwinter$placeShallowSnow(self, walkPos, Math.min(capLayers, desired));
        }
    }

    @Unique
    private static void tempwinter$placeShallowSnow(ServerLevel self, BlockPos pos, int maxLayers) {
        if (maxLayers <= 0) {
            return;
        }
        BlockState current = self.getBlockState(pos);
        if (current.isAir() && Blocks.SNOW.defaultBlockState().canSurvive(self, pos)) {
            self.setBlockAndUpdate(pos, Blocks.SNOW.defaultBlockState());
        } else if (current.is(Blocks.SNOW) && current.getValue(SnowLayerBlock.LAYERS) < maxLayers) {
            self.setBlockAndUpdate(pos, current.setValue(SnowLayerBlock.LAYERS, current.getValue(SnowLayerBlock.LAYERS) + 1));
        }
    }

    @Unique
    private static boolean tempwinter$blocksWindFlow(BlockState state) {
        return !state.isAir() && !state.is(Blocks.SNOW) && !state.canBeReplaced();
    }

    @Unique
    private static boolean tempwinter$onlyLeavesAbove(ServerLevel self, BlockPos underTop, BlockPos skyTop) {
        boolean foundLeaves = false;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int maxY = Math.min(skyTop.getY(), underTop.getY() + 18);
        for (int y = underTop.getY(); y <= maxY; y++) {
            cursor.set(underTop.getX(), y, underTop.getZ());
            BlockState st = self.getBlockState(cursor);
            if (st.isAir() || st.is(Blocks.SNOW)) {
                continue;
            }
            if (st.is(BlockTags.LEAVES)) {
                foundLeaves = true;
                continue;
            }
            return false;
        }
        return foundLeaves;
    }

    @Unique
    private static double tempwinter$localDriftNoise01(int x, int z) {
        double broad = tempwinter$smoothNoise(x / 8.0, z / 8.0);
        double medium = tempwinter$smoothNoise(x / 3.5 + 31.7, z / 3.5 - 19.3);
        double fine = tempwinter$hash01(x, z);
        return Math.max(0.0, Math.min(1.0, broad * 0.70 + medium * 0.22 + fine * 0.08));
    }

    @Unique
    private static int tempwinter$localDriftCapLayers(double t, int maxBlocks) {
        int maxLayers = maxBlocks * 8;
        int minLayers = Math.min(maxLayers, Math.max(3, maxLayers / 6));
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
