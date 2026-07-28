package xyz.przemyk.simpleplanes.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import xyz.przemyk.simpleplanes.SimplePlanesMod;

public record SUpgradeRemovedPacket(Identifier upgradeID, int planeEntityID) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SUpgradeRemovedPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "upgrade_removed"));

    public static final StreamCodec<ByteBuf, SUpgradeRemovedPacket> STREAM_CODEC = StreamCodec.composite(
        Identifier.STREAM_CODEC,
        SUpgradeRemovedPacket::upgradeID,
        ByteBufCodecs.VAR_INT,
        SUpgradeRemovedPacket::planeEntityID,
        SUpgradeRemovedPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
