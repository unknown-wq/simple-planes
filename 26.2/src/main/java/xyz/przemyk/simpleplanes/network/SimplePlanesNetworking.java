package xyz.przemyk.simpleplanes.network;

import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

/**
 * Payload registration + server-side receivers (C3).
 * <p>
 * {@link #register()} is called from the common initializer, {@link #registerClient()} from the
 * client initializer. Everything that touches client-only classes lives in
 * {@link SimplePlanesClientNetworking}, which is only ever class-loaded from {@link #registerClient()}.
 */
public class SimplePlanesNetworking {

    public static void register() {
        // ---- serverbound ----
        PayloadTypeRegistry.serverboundPlay().register(RotationPacket.TYPE, RotationPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MoveHeliUpPacket.TYPE, MoveHeliUpPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HeliCyclicPacket.TYPE, HeliCyclicPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(OpenPlaneInventoryPacket.TYPE, OpenPlaneInventoryPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CycleItemsPacket.TYPE, CycleItemsPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DropPayloadPacket.TYPE, DropPayloadPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ChangeThrottlePacket.TYPE, ChangeThrottlePacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PitchPacket.TYPE, PitchPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(YawPacket.TYPE, YawPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CyclePlaneInventoryPacket.TYPE, CyclePlaneInventoryPacket.STREAM_CODEC);

        // ---- clientbound ----
        PayloadTypeRegistry.clientboundPlay().register(UpdateUpgradePacket.TYPE, UpdateUpgradePacket.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SUpgradeRemovedPacket.TYPE, SUpgradeRemovedPacket.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(JukeboxPacket.TYPE, JukeboxPacket.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(NewCargoUpgradePacket.TYPE, NewCargoUpgradePacket.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CargoUpgradeRemovedPacket.TYPE, CargoUpgradeRemovedPacket.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PlaneSpawnDataPacket.TYPE, PlaneSpawnDataPacket.STREAM_CODEC);

        // ---- server receivers ----
        ServerPlayNetworking.registerGlobalReceiver(RotationPacket.TYPE, (payload, context) -> payload.handle(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(MoveHeliUpPacket.TYPE, (payload, context) -> payload.handle(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(HeliCyclicPacket.TYPE, (payload, context) -> payload.handle(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(OpenPlaneInventoryPacket.TYPE, (payload, context) -> payload.handle(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(CycleItemsPacket.TYPE, (payload, context) -> payload.handle(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(DropPayloadPacket.TYPE, (payload, context) -> payload.handle(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(ChangeThrottlePacket.TYPE, (payload, context) -> payload.handle(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(PitchPacket.TYPE, (payload, context) -> payload.handle(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(YawPacket.TYPE, (payload, context) -> payload.handle(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(CyclePlaneInventoryPacket.TYPE, (payload, context) -> payload.handle(context.player()));

        // replacement for NeoForge's IEntityWithComplexSpawn
        EntityTrackingEvents.START_TRACKING.register((trackedEntity, player) -> {
            if (trackedEntity instanceof PlaneEntity planeEntity) {
                ServerPlayNetworking.send(player, PlaneSpawnDataPacket.create(planeEntity));
            }
        });
    }

    public static void registerClient() {
        SimplePlanesClientNetworking.register();
    }

    // ---- helpers replacing NeoForge's PacketDistributor ----

    public static void sendToPlayersTrackingEntity(Entity entity, CustomPacketPayload payload) {
        for (ServerPlayer player : PlayerLookup.tracking(entity)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }
}
