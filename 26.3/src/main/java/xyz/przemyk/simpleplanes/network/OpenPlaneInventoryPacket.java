package xyz.przemyk.simpleplanes.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

public record OpenPlaneInventoryPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OpenPlaneInventoryPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "open_inv"));

    public static final StreamCodec<ByteBuf, OpenPlaneInventoryPacket> STREAM_CODEC = StreamCodec.unit(new OpenPlaneInventoryPacket());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(ServerPlayer player) {
        Entity entity = player.getVehicle();
        if (entity instanceof PlaneEntity planeEntity) {
            planeEntity.openContainer(player, 0);
        }
    }
}
