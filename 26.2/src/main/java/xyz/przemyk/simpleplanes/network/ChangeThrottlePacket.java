package xyz.przemyk.simpleplanes.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

public record ChangeThrottlePacket(Direction direction) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ChangeThrottlePacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "throttle"));

    public static final StreamCodec<ByteBuf, ChangeThrottlePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ChangeThrottlePacket decode(ByteBuf pBuffer) {
            return new ChangeThrottlePacket(Direction.values()[pBuffer.readByte()]);
        }

        @Override
        public void encode(ByteBuf pBuffer, ChangeThrottlePacket pValue) {
            pBuffer.writeByte(pValue.direction.ordinal());
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(ServerPlayer player) {
        if (player.getVehicle() instanceof PlaneEntity planeEntity) {
            planeEntity.changeThrottle(direction);
        }
    }

    public enum Direction {
        UP,
        DOWN
    }
}
