package xyz.przemyk.simpleplanes.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import xyz.przemyk.simpleplanes.SimplePlanesMod;

public record JukeboxPacket(Identifier record, int planeEntityID) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<JukeboxPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "jukebox"));

    public static final StreamCodec<ByteBuf, JukeboxPacket> STREAM_CODEC = StreamCodec.composite(
        Identifier.STREAM_CODEC,
        JukeboxPacket::record,
        ByteBufCodecs.VAR_INT,
        JukeboxPacket::planeEntityID,
        JukeboxPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
