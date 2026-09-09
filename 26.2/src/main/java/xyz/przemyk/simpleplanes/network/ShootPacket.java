package xyz.przemyk.simpleplanes.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.upgrades.shooter.ShooterUpgrade;

/**
 * "The pilot pulled the trigger." One shot per left click.
 *
 * <p>The shooter used to be fired from {@link PlaneEntity#hurtServer}, on the pilot's own attack
 * landing on the plane. That attack never arrives: vanilla's entity picking refuses to select the
 * vehicle you are riding, so a left click from the cockpit produces no entity hit result, no
 * attack packet and no shot ({@code ProjectileUtil.getEntityHitResult} skips any candidate whose
 * {@code getRootVehicle()} is the picker's own root vehicle). The trigger is therefore an input,
 * sent from the client that saw the click, and not a damage event.
 *
 * <p>The payload is empty on purpose: everything the server needs — who fired, from which aircraft,
 * in which direction — it already knows from the sender and the plane it is riding. Nothing the
 * client says about the shot is trusted.
 *
 * <p><b>Including how often it arrives.</b> An empty payload is free to send, and a client that has
 * been modified to send one every tick is not doing anything the protocol forbids. The two guards
 * below say who may shoot, not how fast; the rate is limited on the other side of them, by
 * {@link ShooterUpgrade}, which is where the cost being limited — a spawned projectile, or a
 * structure search — actually is, and which therefore also covers any future caller of
 * {@code use()}.
 */
public record ShootPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ShootPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "shoot"));

    public static final StreamCodec<ByteBuf, ShootPacket> STREAM_CODEC = StreamCodec.unit(new ShootPacket());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(ServerPlayer player) {
        Entity entity = player.getVehicle();
        // Same guard as DropPayloadPacket: only the pilot of the aircraft may use its equipment.
        if (entity instanceof PlaneEntity planeEntity && planeEntity.getControllingPassenger() == player) {
            ShooterUpgrade shooterUpgrade = planeEntity.getShooterUpgrade();
            if (shooterUpgrade != null) {
                shooterUpgrade.use(player);
            }
        }
    }
}
