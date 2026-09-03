package xyz.przemyk.simpleplanes.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import xyz.przemyk.simpleplanes.SimplePlanesMod;

public record CargoUpgradeRemovedPacket(byte index, int planeEntityID) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CargoUpgradeRemovedPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "cargo_removed"));

    public static final StreamCodec<ByteBuf, CargoUpgradeRemovedPacket> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.BYTE,
        CargoUpgradeRemovedPacket::index,
        ByteBufCodecs.VAR_INT,
        CargoUpgradeRemovedPacket::planeEntityID,
        CargoUpgradeRemovedPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
