package xyz.przemyk.simpleplanes.network;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.autopilot.Airfield;
import xyz.przemyk.simpleplanes.autopilot.AirfieldBrowser;
import xyz.przemyk.simpleplanes.autopilot.AutopilotSavedData;
import xyz.przemyk.simpleplanes.autopilot.Helipad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends each player the registered fields around them, so the world overlay can draw what already
 * exists.
 *
 * <h2>When it sends, and why it is worked out rather than announced</h2>
 * The obvious design is for every place that changes an airfield to call a "tell the clients" method:
 * the survey, {@code /autopilot airfields rename}, {@code remove}, {@code park}, {@code unpark}, the
 * runway tool's parking mode and whatever else grows later. That is six call sites today across four
 * classes owned by other work, every one of which is a place a future change can forget, and a
 * forgotten one leaves a marker on screen for an airfield that no longer exists — the failure that is
 * hardest to notice and worst to trust.
 *
 * <p>So nothing announces anything. Once a second this rebuilds what each player <em>should</em> have
 * and sends it only if that differs, by value, from what they were last sent. Creating, renaming,
 * removing, marking a stand, removing a stand and an aircraft parking on one all change the rebuilt
 * payload, so all of them are covered by one rule that cannot be forgotten; and a world where nothing
 * has changed sends nothing at all, which is the normal case. The cost of the rule is one rebuild per
 * player per second over the fields within {@link #MARKER_RADIUS} — a handful of records and, for
 * each marked stand, the same occupancy question the ground handling already asks every time it picks
 * one.
 *
 * <p>A player is also sent the fields when they join, before any comparison, because an empty client
 * cache and an empty payload are indistinguishable by value and a joining player must be told either
 * way.
 */
public final class AirfieldMarkerSync {

    private AirfieldMarkerSync() {}

    /**
     * How far from a player a field is worth drawing, in blocks.
     *
     * <p>Comfortably past the far render distance, so a marker is on the client before the ground
     * under it is, and small enough that a world with airfields scattered over thousands of blocks
     * sends each player only their own corner of it.
     */
    private static final double MARKER_RADIUS = 512.0;

    /** How often the payload is rebuilt and compared, in ticks. */
    private static final int POLL_TICKS = 20;

    /** What each player was last sent, so an unchanged rebuild sends nothing. */
    private record Sent(ResourceKey<Level> dimension, AirfieldMarkersPacket payload) {}

    private static final Map<UUID, Sent> SENT = new HashMap<>();

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            SENT.remove(handler.player.getUUID());
            send(handler.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            SENT.remove(handler.player.getUUID()));
        ServerTickEvents.END_LEVEL_TICK.register(AirfieldMarkerSync::tick);
    }

    private static void tick(ServerLevel level) {
        if (level.getServer().getTickCount() % POLL_TICKS != 0 || level.players().isEmpty()) {
            return;
        }
        for (ServerPlayer player : List.copyOf(level.players())) {
            send(player);
        }
    }

    /** Rebuilds this player's markers and sends them if they are not what the player already has. */
    private static void send(ServerPlayer player) {
        AirfieldMarkersPacket payload = build(player);
        Sent sent = SENT.get(player.getUUID());
        if (sent != null && sent.dimension().equals(player.level().dimension())
            && sent.payload().equals(payload)) {
            return;
        }
        SENT.put(player.getUUID(), new Sent(player.level().dimension(), payload));
        SimplePlanesNetworking.sendToPlayer(player, payload);
    }

    private static AirfieldMarkersPacket build(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return new AirfieldMarkersPacket(List.of(), List.of());
        }
        // Deliberately the non-creating read. computeIfAbsent would write an empty airfields.dat into
        // every dimension a player has ever stood in, once a second, for a feature that only draws.
        AutopilotSavedData data = level.getDataStorage().get(AutopilotSavedData.TYPE);
        if (data == null) {
            return new AirfieldMarkersPacket(List.of(), List.of());
        }
        double x = player.getX();
        double z = player.getZ();
        List<AirfieldMarkersPacket.Runway> runways = new ArrayList<>();
        for (Airfield airfield : data.airfieldList()) {
            if (near(airfield.centre().x, airfield.centre().z, x, z)) {
                runways.add(marker(level, airfield));
            }
        }
        List<AirfieldMarkersPacket.Pad> pads = new ArrayList<>();
        for (Helipad pad : data.helipadList()) {
            if (near(pad.centre().getX() + 0.5, pad.centre().getZ() + 0.5, x, z)) {
                pads.add(new AirfieldMarkersPacket.Pad(pad.name(), pad.centre(), pad.radius()));
            }
        }
        return new AirfieldMarkersPacket(List.copyOf(runways), List.copyOf(pads));
    }

    private static boolean near(double fieldX, double fieldZ, double playerX, double playerZ) {
        double dx = fieldX - playerX;
        double dz = fieldZ - playerZ;
        return dx * dx + dz * dz <= MARKER_RADIUS * MARKER_RADIUS;
    }

    private static AirfieldMarkersPacket.Runway marker(ServerLevel level, Airfield airfield) {
        List<BlockPos> stands = airfield.parkingSpots();
        int occupied = 0;
        for (int i = 0; i < stands.size(); i++) {
            BlockPos spot = stands.get(i);
            // The same question the ground handling asks when it picks a stand, rather than a
            // second opinion of our own: a marker that disagrees with where an aircraft will
            // actually be sent is worse than no marker.
            Vec3 position = new Vec3(spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5);
            if (!Airfield.standFree(level, airfield, position, spot, null)) {
                occupied |= 1 << i;
            }
        }
        return new AirfieldMarkersPacket.Runway(airfield.name(), airfield.thresholdA(),
            airfield.thresholdB(), airfield.width(), stands, occupied,
            AirfieldBrowser.isUsable(airfield));
    }
}
