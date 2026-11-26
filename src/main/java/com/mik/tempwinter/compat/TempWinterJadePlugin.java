package com.mik.tempwinter.compat;

import com.mik.tempwinter.TempWinterMod;
import com.mik.tempwinter.WinterConfig;
import com.mik.tempwinter.weather.ClientWeatherState;
import com.mik.tempwinter.weather.RealWeatherService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

import java.util.Locale;

/**
 * Optional Jade plugin showing snow/ice temperature state and fragile ice warnings.
 */
@WailaPlugin
public class TempWinterJadePlugin implements IWailaPlugin {
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(TempWinterMod.MODID, "winter_info");

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        IBlockComponentProvider provider = new IBlockComponentProvider() {
            @Override
            public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
                try {
                    if (!WinterConfig.ENABLED.get() || !ClientWeatherState.isActive()
                            || !accessor.getLevel().dimension().equals(Level.OVERWORLD)) {
                        return;
                    }
                    BlockPos pos = accessor.getPosition();
                    BlockState state = accessor.getBlockState();
                    double effTemp = RealWeatherService.getEffectiveTempC(ClientWeatherState.getTempC(), pos.getY());
                    String tempStr = String.format(Locale.ROOT, "%.1f", effTemp);

                    if (state.is(Blocks.SNOW) && state.hasProperty(SnowLayerBlock.LAYERS)) {
                        boolean melting = effTemp > WinterConfig.FREEZE_TEMPERATURE_C.get();
                        tooltip.add(Component.translatable(
                                melting ? "temp_winter.jade.snow_melting" : "temp_winter.jade.snow_freezing",
                                tempStr
                        ).withStyle(melting ? ChatFormatting.YELLOW : ChatFormatting.AQUA));
                    } else if (state.is(Blocks.ICE)) {
                        boolean overWater = accessor.getLevel().getBlockState(pos.below()).is(Blocks.WATER);
                        if (overWater && WinterConfig.FRAGILE_ICE.get() && effTemp > WinterConfig.FRAGILE_ICE_MAX_TEMP_C.get()) {
                            tooltip.add(Component.translatable("temp_winter.jade.ice_fragile").withStyle(ChatFormatting.RED));
                        } else {
                            tooltip.add(Component.translatable("temp_winter.jade.ice_solid", tempStr).withStyle(ChatFormatting.AQUA));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            @Override
            public ResourceLocation getUid() {
                return UID;
            }
        };
        registration.registerBlockComponent(provider, SnowLayerBlock.class);
        registration.registerBlockComponent(provider, IceBlock.class);
    }
}
