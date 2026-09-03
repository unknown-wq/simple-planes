package xyz.przemyk.simpleplanes.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

/**
 * Replaces NeoForge's {@code IEntityWithComplexSpawn}. Sent to every player that starts tracking a
 * plane, from {@code EntityTrackingEvents.START_TRACKING}.
 */
public record PlaneSpawnDataPacket(int planeEntityID, byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PlaneSpawnDataPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "plane_spawn_data"));

    public static final StreamCodec<ByteBuf, PlaneSpawnDataPacket> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT,
        PlaneSpawnDataPacket::planeEntityID,
        ByteBufCodecs.BYTE_ARRAY,
        PlaneSpawnDataPacket::data,
        PlaneSpawnDataPacket::new
    );

    /**
     * Called on the server.
     */
    public static PlaneSpawnDataPacket create(PlaneEntity planeEntity) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), planeEntity.registryAccess());
        try {
            planeEntity.writeSpawnData(buffer);
            byte[] data = new byte[buffer.readableBytes()];
            buffer.readBytes(data);
            return new PlaneSpawnDataPacket(planeEntity.getId(), data);
        } finally {
            buffer.release();
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
