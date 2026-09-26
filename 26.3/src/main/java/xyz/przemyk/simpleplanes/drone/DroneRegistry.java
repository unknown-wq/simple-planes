package xyz.przemyk.simpleplanes.drone;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side roster of patrol drones: the loaded ones by UUID, and the saved roster ({@link DroneSavedData}) of
 * all of them. Renews chunk tickets from the level tick, not from the drone, so a drone that stopped ticking is
 * loaded again; after a restart the saved position of every airborne drone is ticketed until the drone is back.
 * A drone whose chunk is entity-loaded for {@link #WRITE_OFF_TICKS} without it appearing is written off.
 */
public final class DroneRegistry {

    public static final int TICKET_RADIUS = 2;
    public static final int TICKET_INTERVAL = 5;
    public static final int TICKET_LEAD_TICKS = 20;
    /** How often a loaded drone's position is copied into the saved roster. */
    public static final int SAVE_INTERVAL = 20;
    /** Ticks a chunk may be loaded without the drone that should be in it before the drone is written off. */
    public static final int WRITE_OFF_TICKS = 400;
    /** How long {@link #summon} keeps the last known chunk loaded. */
    public static final int SUMMON_TICKS = 200;

    private static final Map<UUID, PatrolDroneEntity> LIVE = new HashMap<>();
    private static final Map<UUID, Integer> ABSENT = new HashMap<>();
    private static final Map<UUID, Long> SUMMONED = new HashMap<>();
    private static boolean initialised;

    private DroneRegistry() {}

    public static void init() {
        if (initialised) {
            return;
        }
        initialised = true;
        ServerTickEvents.END_LEVEL_TICK.register(DroneRegistry::onLevelTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (PatrolDroneEntity drone : new ArrayList<>(LIVE.values())) {
                if (!drone.isRemoved() && drone.level() instanceof ServerLevel level) {
                    save(drone, level);
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LIVE.clear();
            ABSENT.clear();
            SUMMONED.clear();
        });
    }

    /** Called by the drone every server tick. */
    static void track(PatrolDroneEntity drone, ServerLevel level) {
        LIVE.put(drone.getUUID(), drone);
        ABSENT.remove(drone.getUUID());
        SUMMONED.remove(drone.getUUID());
        if (level.getGameTime() % SAVE_INTERVAL == drone.getId() % SAVE_INTERVAL) {
            save(drone, level);
        }
    }

    /** Copies the drone's position and state into the saved roster now. */
    static void save(PatrolDroneEntity drone, ServerLevel level) {
        DroneSavedData data = DroneSavedData.get(level);
        DroneSavedData.Entry old = data.get(drone.getUUID());
        Vec3 pos = drone.position();
        boolean airborne = drone.state().airborne();
        if (old != null && old.airborne() == airborne && old.controller().equals(drone.controllerKey())
            && old.pos().distanceToSqr(pos) < 4.0) {
            return;
        }
        data.put(new DroneSavedData.Entry(drone.getUUID(), pos, airborne, drone.controllerKey()));
    }

    /** The drone is gone for good (destroyed, stowed, picked up, packed). */
    static void forget(PatrolDroneEntity drone, ServerLevel level) {
        LIVE.remove(drone.getUUID());
        ABSENT.remove(drone.getUUID());
        SUMMONED.remove(drone.getUUID());
        DroneSavedData.get(level).remove(drone.getUUID());
    }

    public static @Nullable PatrolDroneEntity find(ServerLevel level, UUID id) {
        PatrolDroneEntity d = LIVE.get(id);
        if (d == null || d.isRemoved() || d.level() != level) {
            return null;
        }
        return d;
    }

    public static List<PatrolDroneEntity> all(ServerLevel level) {
        LIVE.values().removeIf(d -> d.isRemoved() && d.getRemovalReason() != null);
        List<PatrolDroneEntity> out = new ArrayList<>();
        for (PatrolDroneEntity d : LIVE.values()) {
            if (d.level() == level && !d.isRemoved()) {
                out.add(d);
            }
        }
        out.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        return out;
    }

    public static boolean isKnown(ServerLevel level, UUID id) {
        return find(level, id) != null || DroneSavedData.get(level).get(id) != null;
    }

    public static @Nullable Vec3 lastKnown(ServerLevel level, UUID id) {
        PatrolDroneEntity d = find(level, id);
        if (d != null) {
            return d.position();
        }
        DroneSavedData.Entry e = DroneSavedData.get(level).get(id);
        return e == null ? null : e.pos();
    }

    public static void summon(ServerLevel level, UUID id) {
        if (find(level, id) == null && DroneSavedData.get(level).get(id) != null) {
            SUMMONED.put(id, level.getGameTime() + SUMMON_TICKS);
        }
    }

    private static void onLevelTick(ServerLevel level) {
        if (level.getGameTime() % TICKET_INTERVAL != 0) {
            return;
        }
        LIVE.values().removeIf(PatrolDroneEntity::isRemoved);
        for (PatrolDroneEntity drone : new ArrayList<>(LIVE.values())) {
            if (drone.level() == level && drone.state().airborne()) {
                ticket(level, drone.position(), drone.getDeltaMovement());
            }
        }
        DroneSavedData data = level.getDataStorage().get(DroneSavedData.TYPE);
        if (data == null || data.entries().isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        for (DroneSavedData.Entry e : new ArrayList<>(data.entries().values())) {
            if (LIVE.containsKey(e.id())) {
                continue;
            }
            Long until = SUMMONED.get(e.id());
            boolean summoned = until != null && until > now;
            if (!e.airborne() && !summoned) {
                continue;
            }
            // loaded but not ticking (it was saved a chunk away from its roster position): ticket where it is
            if (level.getEntity(e.id()) instanceof PatrolDroneEntity found && !found.isRemoved()) {
                ticket(level, found.position(), Vec3.ZERO);
                data.put(new DroneSavedData.Entry(e.id(), found.position(), e.airborne(), e.controller()));
                ABSENT.remove(e.id());
                continue;
            }
            ticket(level, e.pos(), Vec3.ZERO);
            if (entitiesLoadedAround(level, e.pos())) {
                int absent = ABSENT.merge(e.id(), TICKET_INTERVAL, Integer::sum);
                if (absent >= WRITE_OFF_TICKS) {
                    DroneFeedback.LOGGER.warn("Patrol drone {} written off: its chunk at {} loaded without it", e.id(), e.pos());
                    data.remove(e.id());
                    ABSENT.remove(e.id());
                    SUMMONED.remove(e.id());
                }
            }
        }
    }

    /** The chunk at {@code pos} and its eight neighbours all have their entities loaded. */
    private static boolean entitiesLoadedAround(ServerLevel level, Vec3 pos) {
        int cx = Mth.floor(pos.x) >> 4, cz = Mth.floor(pos.z) >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!level.areEntitiesLoaded(ChunkPos.pack(cx + dx, cz + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void ticket(ServerLevel level, Vec3 pos, Vec3 velocity) {
        ChunkPos here = new ChunkPos(Mth.floor(pos.x) >> 4, Mth.floor(pos.z) >> 4);
        level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, here, TICKET_RADIUS);
        Vec3 ahead = pos.add(velocity.scale(TICKET_LEAD_TICKS));
        ChunkPos next = new ChunkPos(Mth.floor(ahead.x) >> 4, Mth.floor(ahead.z) >> 4);
        if (!next.equals(here)) {
            level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, next, TICKET_RADIUS);
        }
    }
}
