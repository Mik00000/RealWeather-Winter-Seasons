package com.mik.tempwinter.compat;

import com.mik.tempwinter.weather.HeatSourceHelper;
import com.mik.tempwinter.weather.RealWeatherService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private static boolean frictionReflectionInit = false;
    private static boolean frictionReflectionOk = false;
    private static Method getAllSubLevelsMethod = null;
    private static Method isRemovedMethod = null;
    private static Method lastPoseMethod = null;
    private static Method boundingBoxMethod = null;
    private static Method subLevelGetPlotMethod = null;
    private static Method plotGetBoundingBoxMethod = null;
    private static Method plotGetBlockEntityActorsMethod = null;
    private static Method posePositionMethod = null;
    private static Method poseOrientationMethod = null;
    private static Method boxMinXMethod = null;
    private static Method boxMinYMethod = null;
    private static Method boxMinZMethod = null;
    private static Method boxMaxXMethod = null;
    private static Method boxMaxYMethod = null;
    private static Method boxMaxZMethod = null;
    private static Method intBoxContainsMethod = null;
    private static Field latestLinearVelocityField = null;
    private static Field latestAngularVelocityField = null;

    private static final Map<Class<?>, ActorAccessor> ACTOR_ACCESSORS = new ConcurrentHashMap<>();
    private static final ActorAccessor NO_ACTOR = new ActorAccessor(null, null, null, null);
    private static final int MAX_COLUMNS_PER_SUBLEVEL_TICK = 256;

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

    /**
     * Melts/clears Overworld snow when moving Sable contraptions, Aeronautics wheels,
     * or Tracks roll or scrape across snow layers.
     */
    public static void tickFrictionSnowMelt(ServerLevel level) {
        if (level == null || !ensureFrictionReflection()) {
            return;
        }
        try {
            Object container = getContainerMethod.invoke(null, level);
            if (container == null) {
                return;
            }
            Object allObj = getAllSubLevelsMethod.invoke(container);
            if (!(allObj instanceof List<?> subLevels) || subLevels.isEmpty()) {
                return;
            }
            for (Object subLevel : subLevels) {
                if (subLevel == null || Boolean.TRUE.equals(isRemovedMethod.invoke(subLevel))) {
                    continue;
                }
                processSubLevelFriction(level, subLevel);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean ensureFrictionReflection() {
        if (!ensureReflection()) {
            return false;
        }
        if (frictionReflectionInit) {
            return frictionReflectionOk;
        }
        frictionReflectionInit = true;
        try {
            Class<?> containerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            Class<?> subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
            Class<?> serverSubLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel");
            Class<?> subLevelAccessClass = Class.forName("dev.ryanhcode.sable.companion.SubLevelAccess");
            Class<?> levelPlotClass = Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot");
            Class<?> poseClass = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
            Class<?> boxClass = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3dc");
            Class<?> intBoxClass = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3ic");

            getAllSubLevelsMethod = containerClass.getMethod("getAllSubLevels");
            isRemovedMethod = subLevelClass.getMethod("isRemoved");
            lastPoseMethod = subLevelAccessClass.getMethod("lastPose");
            boundingBoxMethod = subLevelAccessClass.getMethod("boundingBox");
            subLevelGetPlotMethod = subLevelClass.getMethod("getPlot");
            plotGetBoundingBoxMethod = levelPlotClass.getMethod("getBoundingBox");
            plotGetBlockEntityActorsMethod = levelPlotClass.getMethod("getBlockEntityActors");
            posePositionMethod = poseClass.getMethod("position");
            poseOrientationMethod = poseClass.getMethod("orientation");
            boxMinXMethod = boxClass.getMethod("minX");
            boxMinYMethod = boxClass.getMethod("minY");
            boxMinZMethod = boxClass.getMethod("minZ");
            boxMaxXMethod = boxClass.getMethod("maxX");
            boxMaxYMethod = boxClass.getMethod("maxY");
            boxMaxZMethod = boxClass.getMethod("maxZ");
            intBoxContainsMethod = intBoxClass.getMethod("contains", int.class, int.class, int.class);
            latestLinearVelocityField = serverSubLevelClass.getField("latestLinearVelocity");
            latestAngularVelocityField = serverSubLevelClass.getField("latestAngularVelocity");
            frictionReflectionOk = true;
        } catch (Throwable ignored) {
            frictionReflectionOk = false;
        }
        return frictionReflectionOk;
    }

    private static void processSubLevelFriction(ServerLevel level, Object subLevel) throws Throwable {
        Object pose = logicalPoseMethod.invoke(subLevel);
        Object lastPose = lastPoseMethod.invoke(subLevel);
        Object globalBox = boundingBoxMethod.invoke(subLevel);
        Object plot = subLevelGetPlotMethod.invoke(subLevel);
        if (pose == null || globalBox == null || plot == null) {
            return;
        }

        Vector3dc posNow = (Vector3dc) posePositionMethod.invoke(pose);
        Vector3d linVel = computeLinearVelocity(subLevel, posNow, lastPose);
        Vector3d angVel = computeAngularVelocity(subLevel, pose, lastPose);

        double minX = ((Number) boxMinXMethod.invoke(globalBox)).doubleValue();
        double minY = ((Number) boxMinYMethod.invoke(globalBox)).doubleValue();
        double minZ = ((Number) boxMinZMethod.invoke(globalBox)).doubleValue();
        double maxX = ((Number) boxMaxXMethod.invoke(globalBox)).doubleValue();
        double maxY = ((Number) boxMaxYMethod.invoke(globalBox)).doubleValue();
        double maxZ = ((Number) boxMaxZMethod.invoke(globalBox)).doubleValue();

        double pivotX = posNow.x();
        double pivotY = posNow.y();
        double pivotZ = posNow.z();
        double halfDiag = 0.5 * Math.sqrt((maxX - minX) * (maxX - minX)
                + (maxY - minY) * (maxY - minY)
                + (maxZ - minZ) * (maxZ - minZ));

        double linearSpeed = linVel.length();
        double angularSpeed = angVel.length();
        double maxPointSpeed = linearSpeed + angularSpeed * halfDiag;

        // 1) Wheels (Aeronautics/Offroad) & Tracks (Sable Tracks)
        Object actorsObj = plotGetBlockEntityActorsMethod.invoke(plot);
        if (actorsObj instanceof Iterable<?> actors) {
            for (Object actor : actors) {
                if (actor instanceof BlockEntity be) {
                    processActorFriction(level, pose, be, linVel, angVel, pivotX, pivotY, pivotZ);
                }
            }
        }

        if (maxPointSpeed < 0.25) {
            return;
        }

        // 2) Solid contraption blocks scraping over terrain snow
        Object localBox = plotGetBoundingBoxMethod.invoke(plot);
        if (localBox == null) {
            return;
        }
        int gMinX = Mth.floor(minX);
        int gMaxX = Mth.floor(maxX);
        int gMinZ = Mth.floor(minZ);
        int gMaxZ = Mth.floor(maxZ);
        int widthX = gMaxX - gMinX + 1;
        int widthZ = gMaxZ - gMinZ + 1;
        if (widthX <= 0 || widthZ <= 0) {
            return;
        }

        int step = 1;
        if ((long) widthX * widthZ > MAX_COLUMNS_PER_SUBLEVEL_TICK) {
            step = Math.max(2, (int) Math.ceil(Math.sqrt((double) widthX * widthZ / MAX_COLUMNS_PER_SUBLEVEL_TICK)));
        }
        int phaseX = (int) ((level.getGameTime() / 2) % step);
        int phaseZ = (int) (((level.getGameTime() / 2) / step) % step);

        for (int gx = gMinX + phaseX; gx <= gMaxX; gx += step) {
            for (int gz = gMinZ + phaseZ; gz <= gMaxZ; gz += step) {
                if (!level.hasChunk(gx >> 4, gz >> 4)) {
                    continue;
                }
                int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, gx, gz);
                if (minY > topY + 0.6 || maxY < topY - 1.5) {
                    continue;
                }
                BlockPos snowPos = findSnowSurfaceAt(level, gx, topY, gz);
                if (snowPos == null) {
                    continue;
                }
                BlockState snowState = level.getBlockState(snowPos);
                double surfaceY = snowState.is(Blocks.SNOW)
                        ? snowPos.getY() + snowState.getValue(SnowLayerBlock.LAYERS) / 8.0
                        : snowPos.getY() + 1.0;

                if (!hasSolidContraptionBlockTouching(level, pose, localBox, gx + 0.5, surfaceY, gz + 0.5)) {
                    continue;
                }
                double pointSpeed = computePointSpeed(linVel, angVel, gx + 0.5 - pivotX, surfaceY - pivotY, gz + 0.5 - pivotZ);
                if (pointSpeed >= 0.25) {
                    meltSnowByFriction(level, snowPos, pointSpeed, false);
                }
            }
        }
    }

    private static void processActorFriction(
            ServerLevel level,
            Object pose,
            BlockEntity be,
            Vector3d linVel,
            Vector3d angVel,
            double centerX,
            double centerY,
            double centerZ
    ) {
        ActorAccessor accessor = getActorAccessor(be.getClass());
        if (accessor.getLerpedExtension == null) {
            return;
        }
        try {
            if (accessor.liftedUpField != null && Boolean.TRUE.equals(accessor.liftedUpField.get(be))) {
                return;
            }
            double ext = ((Number) accessor.getLerpedExtension.invoke(be, 1.0F)).doubleValue();
            if (ext <= 0.05 || ext >= 4.95) {
                return;
            }

            double spinSpeed = 0.0;
            if (accessor.angularVelocityField != null) {
                spinSpeed = Math.abs(((Number) accessor.angularVelocityField.get(be)).doubleValue()) * 0.5;
            } else if (accessor.getSpeedMethod != null) {
                spinSpeed = Math.abs(((Number) accessor.getSpeedMethod.invoke(be)).doubleValue()) * 0.04;
            }

            BlockState state = be.getBlockState();
            Vec3 hubLocal = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                    ? Vec3.atCenterOf(be.getBlockPos().relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING)))
                    : Vec3.atCenterOf(be.getBlockPos());
            Vec3 localContact = hubLocal.subtract(0.0, ext, 0.0);
            Vec3 worldContact = (Vec3) transformPositionMethod.invoke(pose, localContact);

            double worldSpeed = computePointSpeed(
                    linVel, angVel,
                    worldContact.x - centerX, worldContact.y - centerY, worldContact.z - centerZ
            );
            double effectiveSpeed = Math.max(worldSpeed, spinSpeed);
            if (effectiveSpeed < 0.35) {
                return;
            }

            BlockPos contactBlock = BlockPos.containing(worldContact);
            if (!level.hasChunk(contactBlock.getX() >> 4, contactBlock.getZ() >> 4)) {
                return;
            }
            BlockPos snowPos = findSnowNearContact(level, contactBlock);
            if (snowPos != null) {
                meltSnowByFriction(level, snowPos, effectiveSpeed, true);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasSolidContraptionBlockTouching(
            ServerLevel level,
            Object pose,
            Object localBox,
            double wx,
            double surfaceY,
            double wz
    ) throws Throwable {
        double[] probeYs = new double[]{surfaceY + 0.2, surfaceY - 0.15};
        for (double wy : probeYs) {
            Vec3 local = (Vec3) transformPositionInverseMethod.invoke(pose, new Vec3(wx, wy, wz));
            int lx = Mth.floor(local.x);
            int ly = Mth.floor(local.y);
            int lz = Mth.floor(local.z);
            if (!Boolean.TRUE.equals(intBoxContainsMethod.invoke(localBox, lx, ly, lz))) {
                continue;
            }
            if (!level.hasChunk(lx >> 4, lz >> 4)) {
                continue;
            }
            BlockPos localPos = new BlockPos(lx, ly, lz);
            BlockState state = level.getBlockState(localPos);
            if (!state.isAir() && !state.getCollisionShape(level, localPos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos findSnowSurfaceAt(ServerLevel level, int gx, int topY, int gz) {
        BlockPos top = new BlockPos(gx, topY, gz);
        BlockState atTop = level.getBlockState(top);
        if (atTop.is(Blocks.SNOW)) {
            return top;
        }
        BlockPos below = top.below();
        BlockState atBelow = level.getBlockState(below);
        if (atBelow.is(Blocks.SNOW) || atBelow.is(Blocks.SNOW_BLOCK)) {
            return below;
        }
        return null;
    }

    private static BlockPos findSnowNearContact(ServerLevel level, BlockPos contactBlock) {
        BlockState at = level.getBlockState(contactBlock);
        if (at.is(Blocks.SNOW) || at.is(Blocks.SNOW_BLOCK)) {
            return contactBlock;
        }
        BlockPos below = contactBlock.below();
        BlockState atBelow = level.getBlockState(below);
        if (atBelow.is(Blocks.SNOW) || atBelow.is(Blocks.SNOW_BLOCK)) {
            return below;
        }
        return null;
    }

    private static void meltSnowByFriction(ServerLevel level, BlockPos snowPos, double speed, boolean isWheelOrTrack) {
        RealWeatherService.markColumnTrampled(snowPos.getX(), snowPos.getZ(), level.getGameTime());

        double chance = isWheelOrTrack
                ? Math.min(1.0, 0.35 + speed * 0.14)
                : Math.min(0.95, 0.18 + speed * 0.08);
        if (chance < 1.0 && level.random.nextDouble() > chance) {
            return;
        }

        BlockState state = level.getBlockState(snowPos);
        if (state.is(Blocks.SNOW)) {
            int layers = state.getValue(SnowLayerBlock.LAYERS);
            int remove = (isWheelOrTrack && speed >= 4.0) ? 2 : 1;
            if (layers <= remove) {
                level.removeBlock(snowPos, false);
            } else {
                level.setBlockAndUpdate(snowPos, state.setValue(SnowLayerBlock.LAYERS, layers - remove));
            }
        } else if (state.is(Blocks.SNOW_BLOCK)) {
            int remaining = isWheelOrTrack ? 6 : 7;
            level.setBlockAndUpdate(snowPos, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, remaining));
        } else {
            return;
        }

        if (level.random.nextFloat() < (isWheelOrTrack ? 0.35F : 0.18F)) {
            level.sendParticles(
                    ParticleTypes.SNOWFLAKE,
                    snowPos.getX() + 0.5,
                    snowPos.getY() + 0.35,
                    snowPos.getZ() + 0.5,
                    isWheelOrTrack ? 2 : 1,
                    0.22, 0.1, 0.22,
                    0.02
            );
        }
    }

    private static Vector3d computeLinearVelocity(Object subLevel, Vector3dc posNow, Object lastPose) throws Throwable {
        Vector3d vel = new Vector3d();
        Object linObj = latestLinearVelocityField.get(subLevel);
        if (linObj instanceof Vector3dc lv) {
            vel.set(lv);
        }
        if (lastPose != null) {
            Vector3dc posLast = (Vector3dc) posePositionMethod.invoke(lastPose);
            double dx = (posNow.x() - posLast.x()) * 20.0;
            double dy = (posNow.y() - posLast.y()) * 20.0;
            double dz = (posNow.z() - posLast.z()) * 20.0;
            if (dx * dx + dy * dy + dz * dz > vel.lengthSquared()) {
                vel.set(dx, dy, dz);
            }
        }
        return vel;
    }

    private static Vector3d computeAngularVelocity(Object subLevel, Object pose, Object lastPose) throws Throwable {
        Vector3d ang = new Vector3d();
        Object angObj = latestAngularVelocityField.get(subLevel);
        if (angObj instanceof Vector3dc av) {
            ang.set(av);
        }
        if (lastPose != null && ang.lengthSquared() < 1e-6) {
            Quaterniondc qNow = (Quaterniondc) poseOrientationMethod.invoke(pose);
            Quaterniondc qLast = (Quaterniondc) poseOrientationMethod.invoke(lastPose);
            double dot = Math.abs(qNow.x() * qLast.x() + qNow.y() * qLast.y() + qNow.z() * qLast.z() + qNow.w() * qLast.w());
            dot = Math.min(1.0, dot);
            double angle = 2.0 * Math.acos(dot) * 20.0;
            if (angle > 0.01) {
                ang.set(0.0, angle, 0.0);
            }
        }
        return ang;
    }

    private static double computePointSpeed(Vector3dc linVel, Vector3dc angVel, double rx, double ry, double rz) {
        double vx = linVel.x() + (angVel.y() * rz - angVel.z() * ry);
        double vy = linVel.y() + (angVel.z() * rx - angVel.x() * rz);
        double vz = linVel.z() + (angVel.x() * ry - angVel.y() * rx);
        return Math.sqrt(vx * vx + vy * vy + vz * vz);
    }

    private static ActorAccessor getActorAccessor(Class<?> cls) {
        return ACTOR_ACCESSORS.computeIfAbsent(cls, c -> {
            Method getLerpedExtension = null;
            try {
                getLerpedExtension = c.getMethod("getLerpedExtension", float.class);
            } catch (Throwable ignored) {
                return NO_ACTOR;
            }
            Field angVel = findField(c, "angularVelocity");
            Field lifted = findField(c, "liftedUp");
            Method getSpeed = null;
            try {
                getSpeed = c.getMethod("getSpeed");
            } catch (Throwable ignored) {
            }
            return new ActorAccessor(getLerpedExtension, angVel, lifted, getSpeed);
        });
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> cur = cls; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private record ActorAccessor(
            Method getLerpedExtension,
            Field angularVelocityField,
            Field liftedUpField,
            Method getSpeedMethod
    ) {
    }
}
