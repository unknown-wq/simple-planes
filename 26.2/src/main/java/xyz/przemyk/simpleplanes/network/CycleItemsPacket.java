package xyz.przemyk.simpleplanes.network;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.container.PlaneWorkbenchContainer;

public record CycleItemsPacket(Direction direction) implements CustomPacketPayload {

    /** Cached: the wire carries an ordinal, so decoding has to bounds-check against this. */
    private static final Direction[] DIRECTIONS = Direction.values();

    public static final CustomPacketPayload.Type<CycleItemsPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "cycle_items"));

    public static final StreamCodec<ByteBuf, CycleItemsPacket> STREAM_CODEC = new StreamCodec<ByteBuf, CycleItemsPacket>() {
        @Override
        public CycleItemsPacket decode(ByteBuf pBuffer) {
            byte ordinal = pBuffer.readByte();
            if (ordinal < 0 || ordinal >= DIRECTIONS.length) {
                throw new DecoderException("Unknown cycle direction: " + ordinal);
            }
            return new CycleItemsPacket(DIRECTIONS[ordinal]);
        }

        @Override
        public void encode(ByteBuf pBuffer, CycleItemsPacket pValue) {
            pBuffer.writeByte(pValue.direction.ordinal());
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(ServerPlayer player) {
        if (player.containerMenu instanceof PlaneWorkbenchContainer workbenchContainer) {
            workbenchContainer.cycleItems(direction);
        }
    }

    public enum Direction {
        CRAFTING_LEFT,
        CRAFTING_RIGHT
    }
}
