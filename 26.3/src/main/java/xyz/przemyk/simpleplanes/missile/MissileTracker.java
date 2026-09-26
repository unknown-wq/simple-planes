package xyz.przemyk.simpleplanes.missile;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Keeps missiles in flight, and silos in a launch sequence, loaded and ticking; collects flight reports.
 *
 * <p>Chunk tickets are {@code TicketType.ENDER_PEARL} (40-tick timeout, simulates, keeps the dimension active),
 * renewed from the level tick over strong references, so a missile that has stopped ticking is still found and
 * thawed. Radius 3 makes the 3x3 chunks around the centre entity-ticking and 7x7 resident. Each missile holds
 * one ticket on its own chunk and two ahead along its velocity, so the ground in front is loaded before the
 * missile gets there. A tick in which a tracked missile did not run is counted as a stall.
 */
public final class MissileTracker {

    public static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes-missile");

    private static final int TICKET_RADIUS = 3;
    private static final int[] LEAD_TICKS = {10, 20};
    private static final int SILO_TICKET_INTERVAL = 10;
    private static final int MAX_REPORTS = 64;

    private static final Map<Integer, MissileEntity> ACTIVE = new LinkedHashMap<>();
    private static final Map<ResourceKey<Level>, Set<BlockPos>> SILO_HOLDS = new HashMap<>();
    private static final Deque<Report> REPORTS = new ArrayDeque<>();
    private static int telemetryInterval;

    public record Report(int id, int tier, MissileEntity.Outcome outcome, Vec3 at, Vec3 target, double miss, double closest,
                         int flightTicks, long totalTicks, double pathLength, double launchRange, double maxAltitude,
                         int stalls, BlockPos silo) {
        public String line() {
            return String.format(Locale.ROOT,
                "#%d T%d %s at %.2f,%.2f,%.2f target %.2f,%.2f,%.2f miss=%.2f closest=%.2f flight=%dt total=%dt (%.1fs)"
                    + " flown=%.1f range=%.1f max_y=%.1f stalls=%d silo=%s",
                id, tier, outcome, at.x, at.y, at.z, target.x, target.y, target.z, miss, closest, flightTicks, totalTicks,
                totalTicks / 20.0, pathLength, launchRange, maxAltitude, stalls, silo.toShortString());
        }
    }

    private MissileTracker() {}

    static void init() {
        ServerTickEvents.START_LEVEL_TICK.register(MissileTracker::onLevelTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (MissileEntity m : List.copyOf(ACTIVE.values())) {
                LOGGER.info("[missile] #{} discarded at shutdown (flights do not survive a restart)", m.getId());
                m.discard();
            }
            ACTIVE.clear();
            SILO_HOLDS.clear();
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ACTIVE.clear();
            SILO_HOLDS.clear();
            REPORTS.clear();
        });
    }

    static void track(MissileEntity missile) {
        ACTIVE.put(missile.getId(), missile);
        if (missile.level() instanceof ServerLevel level) keepLoaded(level, missile);
        LOGGER.info("[missile] #{} T{} launched from silo {} at {}", missile.getId(), missile.tier().tier,
            missile.silo().toShortString(), fmt(missile.target()));
    }

    public static List<MissileEntity> active() {
        return List.copyOf(ACTIVE.values());
    }

    public static MissileEntity byId(int id) {
        return ACTIVE.get(id);
    }

    public static List<Report> reports() {
        return new ArrayList<>(REPORTS);
    }

    public static void setTelemetry(int interval) {
        telemetryInterval = Math.max(0, interval);
    }

    public static int telemetryInterval() {
        return telemetryInterval;
    }

    static void telemetry(MissileEntity missile) {
        if (telemetryInterval > 0 && missile.flightTicks() % telemetryInterval == 0) {
            LOGGER.info("[missile-tm] {}", missile.telemetryLine());
        }
    }

    static void report(MissileEntity missile, MissileEntity.Outcome outcome, Vec3 at) {
        ACTIVE.remove(missile.getId());
        long now = missile.level().getGameTime();
        Report r = new Report(missile.getId(), missile.tier().tier, outcome, at, missile.target(), at.distanceTo(missile.target()),
            Math.min(missile.closest(), at.distanceTo(missile.target())), missile.flightTicks(), now - missile.launchCommandTime(),
            missile.pathLength(), missile.launchRange(), missile.maxAltitude(), missile.stalls(), missile.silo());
        REPORTS.addLast(r);
        while (REPORTS.size() > MAX_REPORTS) REPORTS.removeFirst();
        LOGGER.info("[missile] {}", r.line());
    }

    public static void holdSilo(ServerLevel level, BlockPos pos) {
        SILO_HOLDS.computeIfAbsent(level.dimension(), k -> new HashSet<>()).add(pos.immutable());
        ticket(level, pos.getX(), pos.getZ());
    }

    public static void releaseSilo(ServerLevel level, BlockPos pos) {
        Set<BlockPos> set = SILO_HOLDS.get(level.dimension());
        if (set != null) set.remove(pos);
    }

    private static void onLevelTick(ServerLevel level) {
        Set<BlockPos> holds = SILO_HOLDS.get(level.dimension());
        if (holds != null && !holds.isEmpty() && level.getGameTime() % SILO_TICKET_INTERVAL == 0) {
            for (BlockPos p : holds) ticket(level, p.getX(), p.getZ());
        }
        if (ACTIVE.isEmpty()) return;
        long now = level.getGameTime();
        for (MissileEntity m : List.copyOf(ACTIVE.values())) {
            if (m.level() != level) continue;
            if (m.isRemoved()) {
                if (!m.isFinished()) {
                    ACTIVE.remove(m.getId());
                    report(m, MissileEntity.Outcome.REMOVED, m.centre());
                }
                continue;
            }
            if (m.lastTickTime() >= 0 && m.lastTickTime() < now) m.addStall();
            keepLoaded(level, m);
        }
    }

    private static void keepLoaded(ServerLevel level, MissileEntity m) {
        Vec3 c = m.centre();
        ticket(level, Mth.floor(c.x), Mth.floor(c.z));
        Vec3 v = m.direction().scale(Math.max(m.speed(), m.tier().cruiseSpeed * 0.5));
        for (int lead : LEAD_TICKS) {
            ticket(level, Mth.floor(c.x + v.x * lead), Mth.floor(c.z + v.z * lead));
        }
    }

    private static void ticket(ServerLevel level, int blockX, int blockZ) {
        level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(blockX >> 4, blockZ >> 4), TICKET_RADIUS);
    }

    static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", v.x, v.y, v.z);
    }
}
