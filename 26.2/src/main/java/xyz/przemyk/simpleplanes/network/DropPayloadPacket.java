package xyz.przemyk.simpleplanes.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

public record DropPayloadPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DropPayloadPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "drop_payload"));

    public static final StreamCodec<ByteBuf, DropPayloadPacket> STREAM_CODEC = StreamCodec.unit(new DropPayloadPacket());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(ServerPlayer player) {
        Entity entity = player.getVehicle();
        if (entity instanceof PlaneEntity planeEntity) {
            planeEntity.dropPayload();
        }
    }
}
