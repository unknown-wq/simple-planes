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
 * Server -> client upgrade sync. The upgrade payload is serialised eagerly on the server into a
 * plain byte array (NeoForge's "write straight into the outgoing buffer" trick has no Fabric
 * equivalent and needed a ConnectionType which no longer exists).
 */
public record UpdateUpgradePacket(boolean newUpgrade, Identifier upgradeID, int planeEntityID, byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<UpdateUpgradePacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "update_upgrade"));

    public static final StreamCodec<ByteBuf, UpdateUpgradePacket> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.BOOL,
        UpdateUpgradePacket::newUpgrade,
        Identifier.STREAM_CODEC,
        UpdateUpgradePacket::upgradeID,
        ByteBufCodecs.VAR_INT,
        UpdateUpgradePacket::planeEntityID,
        ByteBufCodecs.BYTE_ARRAY,
        UpdateUpgradePacket::data,
        UpdateUpgradePacket::new
    );

    /**
     * Called on the server.
     */
    public static UpdateUpgradePacket create(boolean newUpgrade, Identifier upgradeID, PlaneEntity planeEntity) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), planeEntity.registryAccess());
        try {
            planeEntity.writeUpdateUpgradePacket(upgradeID, buffer);
            byte[] data = new byte[buffer.readableBytes()];
            buffer.readBytes(data);
            return new UpdateUpgradePacket(newUpgrade, upgradeID, planeEntity.getId(), data);
        } finally {
            buffer.release();
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
