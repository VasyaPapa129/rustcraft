package dev.rustcraft;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Сервер → клиент: жажда (0..100) и температура тела (°C) для худа. */
public record StatsPayload(int thirst, int temperature) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StatsPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RustCraft.MOD_ID, "stats"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StatsPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StatsPayload::thirst,
            ByteBufCodecs.VAR_INT, StatsPayload::temperature,
            StatsPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
