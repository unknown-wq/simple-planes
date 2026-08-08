package xyz.przemyk.simpleplanes.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.entities.HelicopterEntity;

/**
 * The helicopter's cyclic stick: the two translation inputs, sent together because they change
 * together and because they are meaningless apart.
 *
 * <p>Why this exists rather than reusing {@link PitchPacket} and the strafe input: neither of those
 * arrives at a helicopter in a usable form. {@code PitchPacket} carries a fixed-wing elevator
 * command whose sign convention is the opposite of a cyclic ("+1 = nose up" against "+1 = go
 * forward"), and the strafe axis is not a packet at all — {@code Player.xxa} is only ever written
 * by {@code LocalPlayer.aiStep} and reads zero on the server for every riding player. The old
 * helicopter read both from {@code TempMotionVars}, which is why its translation controls existed
 * only on the flying client and could not be driven by anything else.
 *
 * <p>Both fields are latching: sent when a key goes down or up, not every tick.
 *
 * @param forward percent of full deflection: -100 back, 0 neutral, +100 forward
 * @param right   percent of full deflection: -100 bank left, 0 wings level, +100 bank right
 */
public record HeliCyclicPacket(byte forward, byte right) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<HeliCyclicPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "heli_cyclic"));

    public static final StreamCodec<ByteBuf, HeliCyclicPacket> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.BYTE,
        HeliCyclicPacket::forward,
        ByteBufCodecs.BYTE,
        HeliCyclicPacket::right,
        HeliCyclicPacket::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(ServerPlayer player) {
        if (player.getVehicle() instanceof HelicopterEntity helicopter && helicopter.getControllingPassenger() == player) {
            helicopter.setCyclicForward(forward);
            helicopter.setCyclicRight(right);
        }
    }
}
