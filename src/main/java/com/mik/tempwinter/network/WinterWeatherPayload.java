package com.mik.tempwinter.network;

import com.mik.tempwinter.TempWinterMod;
import com.mik.tempwinter.weather.ClientWeatherState;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Syncs real-world snowfall/rain intensity and precipitation type to clients.
 */
public record WinterWeatherPayload(boolean active, float intensity, boolean isRain, double tempC, double windKmh, boolean winterSeasonActive) implements CustomPacketPayload {
    public static final Type<WinterWeatherPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TempWinterMod.MODID, "weather_sync"));

    public static final StreamCodec<ByteBuf, WinterWeatherPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, WinterWeatherPayload::active,
            ByteBufCodecs.FLOAT, WinterWeatherPayload::intensity,
            ByteBufCodecs.BOOL, WinterWeatherPayload::isRain,
            ByteBufCodecs.DOUBLE, WinterWeatherPayload::tempC,
            ByteBufCodecs.DOUBLE, WinterWeatherPayload::windKmh,
            ByteBufCodecs.BOOL, WinterWeatherPayload::winterSeasonActive,
            WinterWeatherPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(WinterWeatherPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientWeatherState.update(
                payload.active(), payload.intensity(), payload.isRain(),
                payload.tempC(), payload.windKmh(), payload.winterSeasonActive()
        ));
    }
}
