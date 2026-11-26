package com.mik.tempwinter.weather;

import com.mik.tempwinter.WinterConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Detects vanilla and modded heat sources (campfires, lit furnaces, blaze burners, stoves, lanterns, lava)
 * with a lightweight 1-second spatial cache to keep server TPS unaffected even at larger radii.
 */
public final class HeatSourceHelper {
    private static final int CACHE_SIZE = 128;

    private static final class Cache {
        final long[] keys = new long[CACHE_SIZE];
        final long[] time = new long[CACHE_SIZE];
        final boolean[] result = new boolean[CACHE_SIZE];
    }

    private static final ThreadLocal<Cache> THREAD_CACHE = ThreadLocal.withInitial(Cache::new);

    private HeatSourceHelper() {
    }

    public static boolean hasHeatNearby(Level level, BlockPos center, int radius) {
        long second = (level.getGameTime() / 20L) + 1L;
        int qx = center.getX() >> 1;
        int qy = center.getY() >> 1;
        int qz = center.getZ() >> 1;
        long key = BlockPos.asLong(qx, qy, qz) ^ (level.dimension().location().hashCode() * 31L) ^ radius;
        int slot = (int) (key ^ (key >>> 32)) & (CACHE_SIZE - 1);

        Cache cache = THREAD_CACHE.get();
        if (cache.keys[slot] == key && cache.time[slot] == second) {
            return cache.result[slot];
        }

        boolean found = scanHeatNearby(level, center, radius);
        cache.keys[slot] = key;
        cache.time[slot] = second;
        cache.result[slot] = found;
        return found;
    }

    private static boolean scanHeatNearby(Level level, BlockPos center, int radius) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    cursor.set(cx + dx, cy + dy, cz + dz);
                    if (isHeatSource(level, cursor, level.getBlockState(cursor))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean isHeatSource(Level level, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (state.hasProperty(BlockStateProperties.LIT) && !state.getValue(BlockStateProperties.LIT)) {
            return false;
        }
        if (state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER)
                || state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.LANTERN) || state.is(Blocks.SOUL_LANTERN)) {
            return true;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String fullId = id.toString();
        String path = id.getPath();
        for (String kw : WinterConfig.CUSTOM_HEAT_KEYWORDS.get()) {
            if (fullId.equals(kw) || path.contains(kw)) {
                return true;
            }
        }
        return state.getLightEmission(level, pos) >= 12;
    }
}
