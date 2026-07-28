package xyz.przemyk.simpleplanes.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.container.CycleableContainer;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

public record CyclePlaneInventoryPacket(Direction direction) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CyclePlaneInventoryPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "cycle_inventory"));

    public static final StreamCodec<ByteBuf, CyclePlaneInventoryPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public CyclePlaneInventoryPacket decode(ByteBuf pBuffer) {
            return new CyclePlaneInventoryPacket(Direction.values()[pBuffer.readByte()]);
        }

        @Override
        public void encode(ByteBuf pBuffer, CyclePlaneInventoryPacket pValue) {
            pBuffer.writeByte(pValue.direction.ordinal());
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(ServerPlayer sender) {
        if (sender.containerMenu instanceof CycleableContainer container && sender.getVehicle() instanceof PlaneEntity planeEntity) {
            planeEntity.openContainer(sender, direction == Direction.LEFT ? container.cycleableContainerID() + 1 : container.cycleableContainerID() - 1);
        }
    }

    public enum Direction {
        LEFT,
        RIGHT
    }
}
