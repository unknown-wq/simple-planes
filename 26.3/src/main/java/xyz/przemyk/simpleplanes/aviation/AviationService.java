package xyz.przemyk.simpleplanes.aviation;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.airdefence.Allegiance;
import xyz.przemyk.simpleplanes.api.map.AviationMap;
import xyz.przemyk.simpleplanes.api.map.AviationSnapshot;
import xyz.przemyk.simpleplanes.api.map.LaunchResult;
import xyz.przemyk.simpleplanes.autopilot.Airfield;
import xyz.przemyk.simpleplanes.autopilot.AirfieldBrowser;
import xyz.przemyk.simpleplanes.autopilot.AutopilotRegistry;
import xyz.przemyk.simpleplanes.autopilot.AutopilotSavedData;
import xyz.przemyk.simpleplanes.autopilot.FlightPlan;
import xyz.przemyk.simpleplanes.autopilot.Helipad;
import xyz.przemyk.simpleplanes.autopilot.PlaneAutopilot;
import xyz.przemyk.simpleplanes.autopilot.Shuttle;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.missile.LaunchSiloBlockEntity;
import xyz.przemyk.simpleplanes.missile.MissileTier;
import xyz.przemyk.simpleplanes.missile.SiloStructure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Server side of the aviation map: payload registration, the silo index upkeep, snapshot building and the
 * launch request handler. Documented in {@code MISSILES.md} ("Launching from the map") and {@code AUTOPILOT.md}.
 *
 * <p>Every launch goes through {@link #handleLaunch}, whether it came from a client or from
 * {@code /aviation test launch}. The client's view of a silo is never used: the handler resolves the silo from
 * the world and ends in {@link LaunchSiloBlockEntity#launch}, the same call {@code /missile launch} makes.
 */
public final class AviationService {

    private AviationService() {}

    public static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes-aviation");

    /**
     * How close (3D, feet to silo mouth) a player must be to launch. 24 blocks is "standing at the silo":
     * the silo is in view, and it is always within the player's own simulation distance (minimum 2 chunks =
     * 32 blocks), so its chunk is block-ticking and the hatch sequence runs.
     */
    public static final int NEAR_RADIUS = 24;
    /** Horizontal radius the snapshot lists are cut to: the tier 4 range plus margin. */
    public static final int SNAPSHOT_RADIUS = 12_000;
    /**
     * Minimum milliseconds between two launch requests from one player (refused ones count). Wall clock, not
     * server ticks: a paused single-player server still handles packets but does not count ticks.
     */
    public static final long LAUNCH_INTERVAL_MS = 1000;
    /** Minimum milliseconds between two snapshot requests from one player; extra requests are dropped. */
    public static final long SNAPSHOT_INTERVAL_MS = 500;
    /** How often loaded silos are re-read into the index. */
    private static final int SWEEP_TICKS = 20;

    private static final Map<UUID, Long> LAST_LAUNCH = new HashMap<>();
    private static final Map<UUID, Long> LAST_SNAPSHOT = new HashMap<>();

    private record Pending(ResourceKey<Level> dimension, @Nullable BlockPos silo, @Nullable ChunkPos chunk) {}

    /** Events can arrive mid-tick (chunk promotion); the index is touched only from the level tick. */
    private static final ConcurrentLinkedQueue<Pending> PENDING = new ConcurrentLinkedQueue<>();

    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(AviationPayloads.SnapshotRequest.TYPE, AviationPayloads.SnapshotRequest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AviationPayloads.LaunchRequest.TYPE, AviationPayloads.LaunchRequest.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(AviationPayloads.Snapshot.TYPE, AviationPayloads.Snapshot.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(AviationPayloads.LaunchReply.TYPE, AviationPayloads.LaunchReply.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(AviationPayloads.SnapshotRequest.TYPE,
            (payload, context) -> onSnapshotRequest(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(AviationPayloads.LaunchRequest.TYPE,
            (payload, context) -> reply(context.player(), handleLaunch(context.player(), payload)));

        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((be, level) -> {
            if (be instanceof LaunchSiloBlockEntity) PENDING.add(new Pending(level.dimension(), be.getBlockPos().immutable(), null));
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) ->
            PENDING.add(new Pending(level.dimension(), null, chunk.getPos())));
        // The last state before a chunk goes is what the map shows for it until it loads again.
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (be instanceof LaunchSiloBlockEntity silo) SiloIndex.get(level).update(silo, level.getGameTime());
            }
        });
        ServerTickEvents.END_LEVEL_TICK.register(AviationService::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (ServerLevel level : server.getAllLevels()) sweep(level);
            PENDING.clear();
            LAST_LAUNCH.clear();
            LAST_SNAPSHOT.clear();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            LAST_LAUNCH.remove(handler.player.getUUID());
            LAST_SNAPSHOT.remove(handler.player.getUUID());
        });

        AviationCommand.register();
    }

    // ------------------------------------------------------------------ silo index upkeep

    private static void tick(ServerLevel level) {
        if (!PENDING.isEmpty()) {
            List<Pending> later = new ArrayList<>();
            Pending p;
            while ((p = PENDING.poll()) != null) {
                if (p.dimension() != level.dimension()) {
                    later.add(p);
                    continue;
                }
                if (p.silo() != null) {
                    if (level.getBlockEntity(p.silo()) instanceof LaunchSiloBlockEntity silo) {
                        SiloIndex.get(level).update(silo, level.getGameTime());
                    }
                } else if (p.chunk() != null) {
                    verifyChunk(level, p.chunk());
                }
            }
            PENDING.addAll(later);
        }
        if (level.getGameTime() % SWEEP_TICKS == 0) sweep(level);
    }

    /** A chunk came back from disk: every indexed silo in it must still be there. */
    private static void verifyChunk(ServerLevel level, ChunkPos chunk) {
        SiloIndex index = SiloIndex.peek(level);
        if (index == null || index.size() == 0) return;
        for (SiloIndex.Entry e : index.inChunk(chunk)) refresh(level, index, e.pos(), "chunk loaded without it");
    }

    /** Re-reads every indexed silo whose chunk is loaded; drops the ones that are gone. Returns the number dropped. */
    public static int sweep(ServerLevel level) {
        SiloIndex index = SiloIndex.peek(level);
        if (index == null || index.size() == 0) return 0;
        int dropped = 0;
        for (SiloIndex.Entry e : index.entries()) {
            if (level.isLoaded(e.pos()) && !refresh(level, index, e.pos(), "sweep")) dropped++;
        }
        return dropped;
    }

    /** @return false when the silo was gone and its entry dropped. The chunk must be loaded. */
    private static boolean refresh(ServerLevel level, SiloIndex index, BlockPos pos, String why) {
        if (level.getBlockEntity(pos) instanceof LaunchSiloBlockEntity silo) {
            index.update(silo, level.getGameTime());
            return true;
        }
        if (index.remove(pos)) LOGGER.info("[aviation] silo index: {} is gone, entry dropped ({})", pos.toShortString(), why);
        return false;
    }

    // ------------------------------------------------------------------ snapshot

    private static void onSnapshotRequest(ServerPlayer player) {
        long now = Util.getMillis();
        Long last = LAST_SNAPSHOT.get(player.getUUID());
        if (last != null && now - last < SNAPSHOT_INTERVAL_MS) return;
        LAST_SNAPSHOT.put(player.getUUID(), now);
        if (!ServerPlayNetworking.canSend(player, AviationPayloads.Snapshot.TYPE)) return;
        ServerPlayNetworking.send(player, new AviationPayloads.Snapshot(snapshot(player)));
    }

    public static boolean permitted(ServerPlayer player) {
        return Commands.LEVEL_GAMEMASTERS.check(player.permissions());
    }

    /** What {@code player} may see of the airspace in their dimension. */
    public static AviationSnapshot snapshot(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        double px = player.getX();
        double pz = player.getZ();
        boolean permitted = permitted(player);

        List<AviationSnapshot.Airfield> airfields = new ArrayList<>();
        List<AviationSnapshot.Helipad> helipads = new ArrayList<>();
        List<AviationSnapshot.Route> routes = new ArrayList<>();
        AutopilotSavedData data = level.getDataStorage().get(AutopilotSavedData.TYPE);
        if (data != null) {
            List<Airfield> fields = data.airfieldList();
            fields.removeIf(a -> !within(a.centre().x, a.centre().z, px, pz));
            fields.sort(Comparator.comparingDouble(a -> distSq(a.centre().x, a.centre().z, px, pz)));
            for (Airfield a : cap(fields, AviationPayloads.MAX_AIRFIELDS)) {
                airfields.add(new AviationSnapshot.Airfield(clip(a.name()), a.thresholdA(), a.thresholdB(), a.width(),
                    clip(a.endA().designator(), 8), clip(a.endB().designator(), 8), AirfieldBrowser.isUsable(a)));
            }
            List<Helipad> pads = data.helipadList();
            pads.removeIf(h -> !within(h.centre().getX() + 0.5, h.centre().getZ() + 0.5, px, pz));
            pads.sort(Comparator.comparingDouble(h -> distSq(h.centre().getX() + 0.5, h.centre().getZ() + 0.5, px, pz)));
            for (Helipad h : cap(pads, AviationPayloads.MAX_HELIPADS)) {
                helipads.add(new AviationSnapshot.Helipad(clip(h.name()), h.centre(), h.radius()));
            }
            for (Shuttle s : data.shuttles()) {
                if (routes.size() >= AviationPayloads.MAX_ROUTES) break;
                Airfield a = data.get(s.fieldA());
                Airfield b = data.get(s.fieldB());
                if (a == null || b == null) continue;
                if (!within(a.centre().x, a.centre().z, px, pz) && !within(b.centre().x, b.centre().z, px, pz)) continue;
                routes.add(new AviationSnapshot.Route(s.id(), clip(s.fieldA()), clip(s.fieldB()),
                    BlockPos.containing(a.centre()), BlockPos.containing(b.centre()), s.state().getSerializedName(),
                    s.type().getSerializedName(), s.allegiance() == Allegiance.HOSTILE, s.legs(), clip(s.note())));
            }
        }

        List<AviationSnapshot.Flight> flights = new ArrayList<>();
        for (PlaneEntity plane : AutopilotRegistry.active()) {
            if (flights.size() >= AviationPayloads.MAX_FLIGHTS) break;
            if (plane.level() != level || !within(plane.getX(), plane.getZ(), px, pz)) continue;
            PlaneAutopilot autopilot = plane.getAutopilot();
            FlightPlan plan = autopilot == null ? null : autopilot.getPlan();
            if (plan == null) continue;
            BlockPos dest = plan.kind() == FlightPlan.Kind.STRIKE ? plan.strikeTarget()
                : plan.hasWaypoints() ? plan.waypoints().get(plan.waypoints().size() - 1) : null;
            String kind = plan.kind().name().toLowerCase(Locale.ROOT);
            String name = plan.airfieldName() == null ? "" : plan.airfieldName();
            flights.add(new AviationSnapshot.Flight(plane.getId(),
                BuiltInRegistries.ENTITY_TYPE.getKey(plane.getType()).getPath(), plane.isHostile(),
                plane.getX(), plane.getY(), plane.getZ(), kind, autopilot.getMode().getName(),
                dest != null, dest == null ? 0 : dest.getX() + 0.5, dest == null ? 0 : dest.getZ() + 0.5, clip(name)));
        }

        List<AviationSnapshot.Silo> silos = new ArrayList<>();
        SiloIndex index = SiloIndex.peek(level);
        if (index != null) {
            List<SiloIndex.Entry> entries = index.entries();
            entries.removeIf(e -> !within(e.pos().getX() + 0.5, e.pos().getZ() + 0.5, px, pz));
            entries.sort(Comparator.comparingDouble(e -> distSq(e.pos().getX() + 0.5, e.pos().getZ() + 0.5, px, pz)));
            for (SiloIndex.Entry e : entries) {
                if (silos.size() >= AviationPayloads.MAX_SILOS) break;
                AviationSnapshot.Silo view = siloView(level, index, player, e);
                if (view != null) silos.add(view);
            }
        }

        return new AviationSnapshot(level.dimension().identifier().toString(), level.getGameTime(), NEAR_RADIUS,
            permitted, SNAPSHOT_RADIUS, airfields, helipads, routes, flights, silos);
    }

    private static AviationSnapshot.@Nullable Silo siloView(ServerLevel level, SiloIndex index, ServerPlayer player,
                                                            SiloIndex.Entry e) {
        BlockPos pos = e.pos();
        boolean chunkLoaded = level.isLoaded(pos);
        LaunchSiloBlockEntity be = null;
        if (chunkLoaded) {
            if (!refresh(level, index, pos, "snapshot")) return null;
            be = (LaunchSiloBlockEntity) level.getBlockEntity(pos);
        }
        MissileTier tier = be != null ? be.tier() : MissileTier.of(Mth.clamp(e.tier(), 1, 4));
        boolean strike = be != null ? be.mode() == LaunchSiloBlockEntity.Mode.MANUAL : e.strike();
        boolean loaded = be != null ? be.isLoaded() : e.loaded();
        String phase = be != null ? be.phase().name().toLowerCase(Locale.ROOT) : "unknown";
        Vec3 mouth = SiloStructure.mouth(pos, tier);
        double distance = player.position().distanceTo(mouth);
        Component problem = siloProblem(level, be, tier, strike, distance, chunkLoaded);
        return new AviationSnapshot.Silo(pos, tier.tier, strike, loaded, phase, chunkLoaded, mouth.x, mouth.z,
            (int) tier.minRange, (int) tier.maxRange, distance, problem == null,
            problem == null ? AviationPayloads.text("status.ready", "ready") : problem);
    }

    /**
     * Why this silo cannot launch for a player at {@code distance}, target aside; null when it can. The
     * permission check is separate ({@link #permitted}), so non-operators still see what the silo is doing.
     */
    private static @Nullable Component siloProblem(ServerLevel level, @Nullable LaunchSiloBlockEntity be, MissileTier tier,
                                                   boolean strike, double distance, boolean chunkLoaded) {
        if (distance > NEAR_RADIUS) {
            return AviationPayloads.text("refuse.far", "too far away (%s blocks; you must be within %s)",
                (int) Math.round(distance), NEAR_RADIUS);
        }
        if (!chunkLoaded || be == null) return AviationPayloads.text("refuse.unloaded", "the silo's chunk is not loaded");
        if (!strike) return AviationPayloads.text("refuse.air_defence", "the silo is in air-defence mode");
        String readiness = be.readiness(level, tier);
        return readiness == null ? null : Component.literal(readiness);
    }

    // ------------------------------------------------------------------ launch

    /**
     * The one launch path for the map. Checks, in order: operator permission, rate limit, target sanity,
     * player near the silo, silo chunk loaded, silo present, then everything {@code /missile launch} checks
     * ({@link LaunchSiloBlockEntity#launch}: mode, idle, loaded, intact, hatch clear, range, height).
     */
    public static LaunchResult handleLaunch(ServerPlayer player, AviationPayloads.LaunchRequest request) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos requested = request.silo().immutable();
        if (!permitted(player)) {
            return refused(player, requested, AviationPayloads.text("refuse.permission",
                "operator permission is required to launch (the same as /missile launch)"));
        }
        long now = Util.getMillis();
        Long last = LAST_LAUNCH.get(player.getUUID());
        LAST_LAUNCH.put(player.getUUID(), now);
        if (last != null && now - last < LAUNCH_INTERVAL_MS) {
            return refused(player, requested, AviationPayloads.text("refuse.rate", "too many launch requests; wait a second"));
        }
        if (!level.getWorldBorder().isWithinBounds(request.x() + 0.5, request.z() + 0.5)) {
            return refused(player, requested, AviationPayloads.text("refuse.border", "the target is outside the world border"));
        }
        if (!level.isInWorldBounds(requested) || player.position().distanceTo(Vec3.atCenterOf(requested)) > NEAR_RADIUS + 8) {
            return refused(player, requested, AviationPayloads.text("refuse.far", "too far away (%s blocks; you must be within %s)",
                (int) Math.round(player.position().distanceTo(Vec3.atCenterOf(requested))), NEAR_RADIUS));
        }
        if (!level.isLoaded(requested)) {
            return refused(player, requested, AviationPayloads.text("refuse.unloaded", "the silo's chunk is not loaded"));
        }
        BlockPos master = SiloStructure.masterOf(level, requested);
        if (master == null || !(level.getBlockEntity(master) instanceof LaunchSiloBlockEntity be)) {
            SiloIndex index = SiloIndex.peek(level);
            if (index != null) refresh(level, index, requested, "launch request");
            return refused(player, requested, AviationPayloads.text("refuse.no_silo", "there is no silo at %s", requested.toShortString()));
        }
        MissileTier tier = be.tier();
        Vec3 mouth = SiloStructure.mouth(master, tier);
        double distance = player.position().distanceTo(mouth);
        if (distance > NEAR_RADIUS) {
            return refused(player, master, AviationPayloads.text("refuse.far", "too far away (%s blocks; you must be within %s)",
                (int) Math.round(distance), NEAR_RADIUS));
        }
        Vec3 target = new Vec3(request.x() + 0.5, targetY(level, request.x(), request.z(), request.y()), request.z() + 0.5);
        String problem = be.launch(level, target);
        SiloIndex.get(level).update(be, level.getGameTime());
        if (problem != null) {
            return refused(player, master, AviationPayloads.text("refuse.silo", "silo at %s cannot launch: %s",
                master.toShortString(), problem));
        }
        double range = Math.hypot(target.x - mouth.x, target.z - mouth.z);
        LOGGER.info("[aviation] {} launched silo {} T{} from the map at {} {} {} ({} blocks)", player.getName().getString(),
            master.toShortString(), tier.tier, fmt(target.x), fmt(target.y), fmt(target.z), fmt(range));
        Component message = AviationPayloads.text("launched", "Launch: tier %s missile from %s to %s %s %s (%s blocks)",
            tier.tier, master.toShortString(), fmt(target.x), fmt(target.y), fmt(target.z), (int) Math.round(range));
        player.sendOverlayMessage(message.copy().withStyle(ChatFormatting.GREEN));
        return new LaunchResult(master, true, message, target.x, target.y, target.z);
    }

    /**
     * Target height. A loaded target column uses the server's own heightmap (first free block above the
     * surface, like {@code /missile launch x -19 z} on the test world). An unloaded one uses the client's value
     * when it sent one (the map's recorded surface + 1), else the generator's surface estimate, which reads
     * noise only and loads nothing.
     */
    static double targetY(ServerLevel level, int x, int z, int clientY) {
        if (level.hasChunk(x >> 4, z >> 4)) return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        if (clientY != AviationMap.SURFACE) return Mth.clamp(clientY, level.getMinY(), level.getMaxY());
        return level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level,
            level.getChunkSource().randomState());
    }

    private static LaunchResult refused(ServerPlayer player, BlockPos silo, Component reason) {
        Component message = AviationPayloads.text("refused", "Launch refused: %s", reason);
        player.sendOverlayMessage(message.copy().withStyle(ChatFormatting.RED));
        LOGGER.info("[aviation] launch request by {} for silo {} refused: {}", player.getName().getString(),
            silo.toShortString(), reason.getString());
        return new LaunchResult(silo, false, message, 0, 0, 0);
    }

    private static void reply(ServerPlayer player, LaunchResult result) {
        if (player instanceof FakePlayer || !ServerPlayNetworking.canSend(player, AviationPayloads.LaunchReply.TYPE)) return;
        ServerPlayNetworking.send(player, new AviationPayloads.LaunchReply(result));
        // A fresh snapshot, so the map shows the hatch opening without waiting for its next poll.
        if (ServerPlayNetworking.canSend(player, AviationPayloads.Snapshot.TYPE)) {
            ServerPlayNetworking.send(player, new AviationPayloads.Snapshot(snapshot(player)));
        }
    }

    /** For {@code /aviation test resetlimits}. */
    static void resetLimits() {
        LAST_LAUNCH.clear();
        LAST_SNAPSHOT.clear();
    }

    // ------------------------------------------------------------------ helpers

    private static boolean within(double x, double z, double px, double pz) {
        return distSq(x, z, px, pz) <= (double) SNAPSHOT_RADIUS * SNAPSHOT_RADIUS;
    }

    private static double distSq(double x, double z, double px, double pz) {
        double dx = x - px;
        double dz = z - pz;
        return dx * dx + dz * dz;
    }

    private static <T> List<T> cap(List<T> list, int max) {
        return list.size() <= max ? list : list.subList(0, max);
    }

    private static String clip(@Nullable String s) {
        return clip(s, 200);
    }

    private static String clip(@Nullable String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
