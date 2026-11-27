package com.mik.tempwinter.compat;

import com.mik.tempwinter.weather.HeatSourceHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reflection bridge for Sable sublevels (ships/airships):
 * resolves plot-local blocks to global world altitude and deck orientation,
 * and maps player world positions onto intersecting ship decks for heat & trampling.
 */
public final class SableCompat {
    private static final boolean SABLE_LOADED = isModLoaded("sable");
    private static final Vec3 LOCAL_UP = new Vec3(0.0, 1.0, 0.0);

    private static boolean reflectionInit = false;
    private static Method getContainerMethod = null;
    private static Method inBoundsMethod = null;
    private static Method getPlotMethod = null;
    private static Method queryIntersectingMethod = null;
    private static Method getSubLevelMethod = null;
    private static Method logicalPoseMethod = null;
    private static Method transformPositionMethod = null;
    private static Method transformPositionInverseMethod = null;
    private static Method transformNormalMethod = null;
    private static Constructor<?> boundingBoxAabbCtor = null;

    private SableCompat() {
    }

    private static boolean isModLoaded(String modId) {
        ModList list = ModList.get();
        return list != null && list.isLoaded(modId);
    }

    private static boolean ensureReflection() {
        if (!SABLE_LOADED) {
            return false;
        }
        if (reflectionInit) {
            return getContainerMethod != null;
        }
        reflectionInit = true;
        try {
            Class<?> containerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            Class<?> plotClass = Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot");
            Class<?> subLevelAccessClass = Class.forName("dev.ryanhcode.sable.companion.SubLevelAccess");
            Class<?> poseClass = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
            Class<?> boxInterfaceClass = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3dc");
            Class<?> boxImplClass = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3d");

            getContainerMethod = containerClass.getMethod("getContainer", Level.class);
            inBoundsMethod = containerClass.getMethod("inBounds", ChunkPos.class);
            getPlotMethod = containerClass.getMethod("getPlot", ChunkPos.class);
            queryIntersectingMethod = containerClass.getMethod("queryIntersecting", boxInterfaceClass);
            getSubLevelMethod = plotClass.getMethod("getSubLevel");
            logicalPoseMethod = subLevelAccessClass.getMethod("logicalPose");
            transformPositionMethod = poseClass.getMethod("transformPosition", Vec3.class);
            transformPositionInverseMethod = poseClass.getMethod("transformPositionInverse", Vec3.class);
            transformNormalMethod = poseClass.getMethod("transformNormal", Vec3.class);
            boundingBoxAabbCtor = boxImplClass.getConstructor(AABB.class);
            return true;
        } catch (Throwable ignored) {
            getContainerMethod = null;
            return false;
        }
    }

    private static Object getPoseForPlot(Level level, BlockPos pos) {
        if (!ensureReflection() || level == null || pos == null) {
            return null;
        }
        try {
            Object container = getContainerMethod.invoke(null, level);
            if (container == null) {
                return null;
            }
            ChunkPos chunkPos = new ChunkPos(pos);
            if (!Boolean.TRUE.equals(inBoundsMethod.invoke(container, chunkPos))) {
                return null;
            }
            Object plot = getPlotMethod.invoke(container, chunkPos);
            if (plot == null) {
                return null;
            }
            Object subLevel = getSubLevelMethod.invoke(plot);
            if (subLevel == null) {
                return null;
            }
            return logicalPoseMethod.invoke(subLevel);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Returns real global world Y for a Sable plot block (e.g. a high-flying airship),
     * or {@code pos.getY()} for normal terrain blocks.
     */
    public static int getEffectiveWorldY(Level level, BlockPos pos) {
        if (pos == null) {
            return 64;
        }
        Object pose = getPoseForPlot(level, pos);
        if (pose == null) {
            return pos.getY();
        }
        try {
            Vec3 worldPos = (Vec3) transformPositionMethod.invoke(pose, Vec3.atCenterOf(pos));
            return (int) Math.round(worldPos.y);
        } catch (Throwable ignored) {
            return pos.getY();
        }
    }

    /**
     * Returns true unless {@code pos} belongs to a Sable construction tilted beyond ~63 degrees or upside down.
     */
    public static boolean canSnowStickOnDeck(Level level, BlockPos pos) {
        Object pose = getPoseForPlot(level, pos);
        if (pose == null) {
            return true;
        }
        try {
            Vec3 worldUp = (Vec3) transformNormalMethod.invoke(pose, LOCAL_UP);
            return worldUp.y >= 0.45;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Maps a global world point onto plot-local {@link BlockPos} coordinates for every nearby Sable ship.
     */
    public static List<BlockPos> getIntersectingLocalPositions(Level level, Vec3 worldPoint, double inflateRadius) {
        if (!ensureReflection() || level == null || worldPoint == null) {
            return Collections.emptyList();
        }
        try {
            Object container = getContainerMethod.invoke(null, level);
            if (container == null) {
                return Collections.emptyList();
            }
            AABB aabb = new AABB(worldPoint, worldPoint).inflate(inflateRadius);
            Object box = boundingBoxAabbCtor.newInstance(aabb);
            Object iterableObj = queryIntersectingMethod.invoke(container, box);
            if (!(iterableObj instanceof Iterable<?> subLevels)) {
                return Collections.emptyList();
            }
            List<BlockPos> result = null;
            for (Object subLevel : subLevels) {
                if (subLevel == null) {
                    continue;
                }
                Object pose = logicalPoseMethod.invoke(subLevel);
                if (pose == null) {
                    continue;
                }
                Vec3 local = (Vec3) transformPositionInverseMethod.invoke(pose, worldPoint);
                if (result == null) {
                    result = new ArrayList<>(2);
                }
                result.add(BlockPos.containing(local));
            }
            return result != null ? result : Collections.emptyList();
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    public static boolean hasHeatOnNearbySubLevel(Level level, Vec3 worldPoint, int radius) {
        for (BlockPos localPos : getIntersectingLocalPositions(level, worldPoint, radius + 1.0)) {
            if (HeatSourceHelper.hasHeatNearby(level, localPos, radius)) {
                return true;
            }
        }
        return false;
    }

    public static BlockPos findBlockOnIntersectingShip(Level level, Vec3 feetWorldPos, boolean snowNotIce) {
        for (BlockPos localFeet : getIntersectingLocalPositions(level, feetWorldPos, 1.0)) {
            if (snowNotIce) {
                if (level.getBlockState(localFeet).is(Blocks.SNOW)) {
                    return localFeet;
                }
                BlockPos below = localFeet.below();
                if (level.getBlockState(below).is(Blocks.SNOW)) {
                    return below;
                }
            } else {
                BlockPos below = localFeet.below();
                BlockState belowState = level.getBlockState(below);
                if (belowState.is(Blocks.ICE) && level.getBlockState(below.below()).is(Blocks.WATER)) {
                    return below;
                }
            }
        }
        return null;
    }
}
