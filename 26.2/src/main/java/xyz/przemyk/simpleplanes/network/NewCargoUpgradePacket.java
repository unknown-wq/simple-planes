package xyz.przemyk.simpleplanes.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;

public record NewCargoUpgradePacket(Identifier upgradeID, int planeEntityID, byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<NewCargoUpgradePacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "new_cargo"));

    public static final StreamCodec<ByteBuf, NewCargoUpgradePacket> STREAM_CODEC = StreamCodec.composite(
        Identifier.STREAM_CODEC,
        NewCargoUpgradePacket::upgradeID,
        ByteBufCodecs.VAR_INT,
        NewCargoUpgradePacket::planeEntityID,
        ByteBufCodecs.BYTE_ARRAY,
        NewCargoUpgradePacket::data,
        NewCargoUpgradePacket::new
    );

    /**
     * Called on the server.
     */
    public static NewCargoUpgradePacket create(Identifier upgradeID, int planeEntityID, Upgrade upgrade) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), upgrade.getPlaneEntity().registryAccess());
        try {
            upgrade.writePacket(buffer);
            byte[] data = new byte[buffer.readableBytes()];
            buffer.readBytes(data);
            return new NewCargoUpgradePacket(upgradeID, planeEntityID, data);
        } finally {
            buffer.release();
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
