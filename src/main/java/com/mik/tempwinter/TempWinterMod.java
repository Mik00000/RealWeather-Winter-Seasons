package com.mik.tempwinter;

import com.mik.tempwinter.client.WinterClientEvents;
import com.mik.tempwinter.compat.SeasonCompat;
import com.mik.tempwinter.compat.TemperatureModCompat;
import com.mik.tempwinter.network.WinterWeatherPayload;
import com.mik.tempwinter.weather.HeatSourceHelper;
import com.mik.tempwinter.weather.RealWeatherService;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Lightweight temporary winter mod: forces snowfall, uneven multi-block snowdrifts,
 * winter foliage tints, and real-world city weather sync without registering any blocks or items.
 */
@Mod(TempWinterMod.MODID)
public class TempWinterMod {
    public static final String MODID = "realweather";

    public TempWinterMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, WinterConfig.SPEC, "realweather-common.toml");
        modBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onLevelTickPre);
        NeoForge.EVENT_BUS.addListener(this::onLevelTickPost);
        NeoForge.EVENT_BUS.addListener(this::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            WinterClientEvents.register();
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(
                WinterWeatherPayload.TYPE,
                WinterWeatherPayload.STREAM_CODEC,
                WinterWeatherPayload::handleClient
        );
    }

    private void onServerStarting(ServerStartingEvent event) {
        RealWeatherService.onServerStart(event.getServer());
        TemperatureModCompat.init();
        SeasonCompat.syncRealSeasonIfNeeded(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        RealWeatherService.onServerStop();
    }

    private void onServerTick(ServerTickEvent.Post event) {
        RealWeatherService.tick(event.getServer());
    }

    private void onLevelTickPre(LevelTickEvent.Pre event) {
        if (!event.getLevel().isClientSide()) {
            RealWeatherService.setTickingOverworld(event.getLevel().dimension().equals(Level.OVERWORLD));
        }
    }

    private void onLevelTickPost(LevelTickEvent.Post event) {
        if (!event.getLevel().isClientSide()) {
            RealWeatherService.setTickingOverworld(true);
        }
    }

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(
                    player,
                    new WinterWeatherPayload(
                            WinterConfig.REAL_WEATHER_ENABLED.get() && !RealWeatherService.isVanillaWeatherMode(),
                            RealWeatherService.getIntensity(),
                            RealWeatherService.shouldUseRain(),
                            RealWeatherService.getCurrentTempC(),
                            RealWeatherService.getCurrentWindKmh(),
                            SeasonCompat.isWinterActive(player.level())
                    )
            );
        }
    }

    private void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !WinterConfig.ENABLED.get()
                || player.isSpectator()) {
            return;
        }
        Level level = player.level();
        if (!level.dimension().equals(Level.OVERWORLD)) {
            if (player.getTicksFrozen() > 0 && !player.isInPowderSnow) {
                player.setTicksFrozen(0);
            }
            return;
        }
        boolean outdoors = level.canSeeSky(player.blockPosition());
        double effectiveTempC = RealWeatherService.getEffectiveTempC(RealWeatherService.getCurrentTempC(), player.getBlockY());

        if (WinterConfig.FROST_OVERLAY.get() && !TemperatureModCompat.hasExternalTemperatureMod() && player.tickCount % 10 == 0) {
            boolean freezingOutdoors = effectiveTempC <= WinterConfig.FROST_OVERLAY_TEMP_C.get()
                    && outdoors
                    && !HeatSourceHelper.hasHeatNearby(level, player.blockPosition(), WinterConfig.HEAT_SOURCE_RADIUS.get() + 1)
                    && countLeatherArmor(player) < 2;
            int currentFrozen = player.getTicksFrozen();
            if (freezingOutdoors) {
                player.setTicksFrozen(Math.min(75, currentFrozen + 18));
            } else if (currentFrozen > 0 && !player.isInPowderSnow) {
                player.setTicksFrozen(Math.max(0, currentFrozen - 24));
            }
        }

        if (!player.onGround() || player.isCrouching() || player.tickCount % 4 != 0) {
            return;
        }

        if (WinterConfig.FRAGILE_ICE.get() && effectiveTempC > WinterConfig.FRAGILE_ICE_MAX_TEMP_C.get()) {
            BlockPos below = player.blockPosition().below();
            if (level.getBlockState(below).is(Blocks.ICE)
                    && level.getBlockState(below.below()).is(Blocks.WATER)
                    && !hasFrostWalker(level, player)) {
                float crackChance = player.fallDistance > 0.5F ? 0.55F : (player.isSprinting() ? 0.06F : 0.015F);
                if (player.getRandom().nextFloat() < crackChance) {
                    level.levelEvent(2001, below, Block.getId(Blocks.ICE.defaultBlockState()));
                    level.setBlockAndUpdate(below, Blocks.WATER.defaultBlockState());
                    level.playSound(null, below, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 0.55F, 1.2F);
                    return;
                }
            }
        }

        if (!WinterConfig.TRAMPLE_SNOW.get()) {
            return;
        }
        double dx = player.getX() - player.xo;
        double dz = player.getZ() - player.zo;
        if (dx * dx + dz * dz < 0.0025 || player.getRandom().nextFloat() >= 0.20F) {
            return;
        }
        BlockPos feet = player.blockPosition();
        BlockPos snowPos = level.getBlockState(feet).is(Blocks.SNOW)
                ? feet
                : (level.getBlockState(feet.below()).is(Blocks.SNOW) ? feet.below() : null);
        if (snowPos == null) {
            return;
        }
        BlockState state = level.getBlockState(snowPos);
        int layers = state.getValue(SnowLayerBlock.LAYERS);
        if (layers >= 2) {
            level.setBlockAndUpdate(snowPos, state.setValue(SnowLayerBlock.LAYERS, layers - 1));
            level.playSound(null, snowPos, SoundEvents.SNOW_STEP, SoundSource.BLOCKS, 0.35F, 0.9F);
            RealWeatherService.markColumnTrampled(snowPos.getX(), snowPos.getZ(), level.getGameTime());
        }
    }

    private static int countLeatherArmor(ServerPlayer player) {
        int count = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.is(Items.LEATHER_HELMET) || stack.is(Items.LEATHER_CHESTPLATE)
                    || stack.is(Items.LEATHER_LEGGINGS) || stack.is(Items.LEATHER_BOOTS)) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasFrostWalker(Level level, ServerPlayer player) {
        ItemStack boots = player.getItemBySlot(EquipmentSlot.FEET);
        if (boots.isEmpty()) {
            return false;
        }
        return level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .get(Enchantments.FROST_WALKER)
                .map(holder -> EnchantmentHelper.getItemEnchantmentLevel(holder, boots) > 0)
                .orElse(false);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("realweather")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("status")
                                .executes(ctx -> {
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal(RealWeatherService.getStatusSummary()),
                                            false
                                    );
                                    return 1;
                                }))
                        .then(Commands.literal("sync")
                                .executes(ctx -> {
                                    boolean started = RealWeatherService.forceSync();
                                    ctx.getSource().sendSuccess(
                                            () -> Component.translatable(started
                                                    ? "realweather.cmd.sync_started"
                                                    : "realweather.cmd.sync_busy"),
                                            true
                                    );
                                    return started ? 1 : 0;
                                }))
                        .then(Commands.literal("city")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                java.util.List.of("auto", "Kyiv", "Lviv", "Warsaw", "Reykjavik", "Zurich"), builder))
                                        .executes(ctx -> {
                                            String city = StringArgumentType.getString(ctx, "name").trim();
                                            RealWeatherService.setCityAndSync(city);
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.translatable("realweather.cmd.city_set", city),
                                                    true
                                            );
                                            return 1;
                                        })))
                        .then(Commands.literal("mode")
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                java.util.List.of("auto", "snow", "rain", "thunder", "clear", "vanilla"), builder))
                                        .executes(ctx -> executeMode(ctx.getSource(), StringArgumentType.getString(ctx, "type"), -1))
                                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                        java.util.List.of("5", "15", "30", "60"), builder))
                                                .executes(ctx -> executeMode(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "type"),
                                                        IntegerArgumentType.getInteger(ctx, "minutes")
                                                )))))
                        .then(Commands.literal("vanilla")
                                .executes(ctx -> executeMode(ctx.getSource(), "vanilla", -1))
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                java.util.List.of("5", "15", "30", "60"), builder))
                                        .executes(ctx -> executeMode(
                                                ctx.getSource(),
                                                "vanilla",
                                                IntegerArgumentType.getInteger(ctx, "minutes")
                                        ))))
                        .then(Commands.literal("offset")
                                .then(Commands.argument("hours", IntegerArgumentType.integer(-24, 24))
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                java.util.List.of("-6", "-3", "0", "3", "6"), builder))
                                        .executes(ctx -> {
                                            int hours = IntegerArgumentType.getInteger(ctx, "hours");
                                            RealWeatherService.setTimeOffsetAndSync(hours);
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.translatable("realweather.cmd.offset_set", hours),
                                                    true
                                            );
                                            return 1;
                                        })))
        );
    }

    private static int executeMode(net.minecraft.commands.CommandSourceStack source, String mode, int minutes) {
        boolean ok = RealWeatherService.setOverrideMode(source.getServer(), mode, minutes);
        if (!ok) {
            source.sendFailure(Component.translatable("realweather.cmd.mode_usage"));
            return 0;
        }
        source.sendSuccess(() -> minutes > 0
                ? Component.translatable("realweather.cmd.mode_set_timed", mode, minutes)
                : Component.translatable("realweather.cmd.mode_set", mode), true);
        return 1;
    }
}
