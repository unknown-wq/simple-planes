package xyz.przemyk.simpleplanes.network;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import xyz.przemyk.simpleplanes.client.MovingSound;
import xyz.przemyk.simpleplanes.entities.CargoPlaneEntity;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

/**
 * Client-only packet receivers. Never class-loaded on a dedicated server: the only reference to
 * this class is inside {@link SimplePlanesNetworking#registerClient()}.
 */
public class SimplePlanesClientNetworking {

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(UpdateUpgradePacket.TYPE, (payload, context) -> {
            PlaneEntity planeEntity = getPlane(context.client(), payload.planeEntityID());
            if (planeEntity != null) {
                planeEntity.readUpdateUpgradePacket(payload.upgradeID(), wrap(context.client(), payload.data()), payload.newUpgrade());
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(SUpgradeRemovedPacket.TYPE, (payload, context) -> {
            PlaneEntity planeEntity = getPlane(context.client(), payload.planeEntityID());
            if (planeEntity != null) {
                planeEntity.removeUpgrade(payload.upgradeID());
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(NewCargoUpgradePacket.TYPE, (payload, context) -> {
            if (getPlane(context.client(), payload.planeEntityID()) instanceof CargoPlaneEntity cargoPlaneEntity) {
                cargoPlaneEntity.readNewCargoUpgradePacket(payload.upgradeID(), wrap(context.client(), payload.data()));
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(CargoUpgradeRemovedPacket.TYPE, (payload, context) -> {
            if (getPlane(context.client(), payload.planeEntityID()) instanceof CargoPlaneEntity cargoPlaneEntity) {
                cargoPlaneEntity.removeCargoUpgrade(payload.index());
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(PlaneSpawnDataPacket.TYPE, (payload, context) -> {
            PlaneEntity planeEntity = getPlane(context.client(), payload.planeEntityID());
            if (planeEntity != null) {
                planeEntity.readSpawnData(wrap(context.client(), payload.data()));
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(JukeboxPacket.TYPE, (payload, context) -> MovingSound.playRecord(payload));
    }

    public static void sendRotation(org.joml.Quaternionf quaternion) {
        ClientPlayNetworking.send(new RotationPacket(quaternion));
    }

    public static void sendDropPayload() {
        ClientPlayNetworking.send(new DropPayloadPacket());
    }

    private static PlaneEntity getPlane(Minecraft minecraft, int entityID) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            return null;
        }
        return level.getEntity(entityID) instanceof PlaneEntity planeEntity ? planeEntity : null;
    }

    private static RegistryFriendlyByteBuf wrap(Minecraft minecraft, byte[] data) {
        return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), minecraft.level.registryAccess());
    }
}
