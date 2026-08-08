package xyz.przemyk.simpleplanes.autopilot;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The live set of autopilot aircraft, and the server-tick heartbeat that keeps their chunks loaded.
 *
 * <h2>Why the count is derived and not counted</h2>
 * The active-autopilot count used to be a plain {@code static int} bumped in
 * {@code PlaneAutopilot#start} and decremented in {@code stop}. Every aircraft that went away
 * without its release path running — destroyed on impact, chunk-unloaded, world shut down — leaked a
 * slot permanently, and the leak accumulated on exactly the most common path (a crash). A live
 * server was observed reporting {@code 19/24 autopilot aircraft active, 2 in this dimension}: five
 * launches away from refusing every new flight, with nothing short of a restart to clear it.
 * <p>
 * It is now derived from the set below, which is pruned before every read, so an aircraft that
 * disappears for any reason at all stops being counted. Only server-side entities are ever
 * registered, so the shared client/server JVM of a single-player world cannot double-count either.
 *
 * <h2>Why the chunk ticket is renewed from here and not from the aircraft</h2>
 * An entity only ticks while its chunk is at {@link net.minecraft.server.level.ChunkLevel}
 * entity-ticking level, and the autopilot used to renew its own chunk ticket from
 * {@code PlaneAutopilot#tick}. That is circular: the moment an aircraft slips out of the
 * entity-ticking area it stops ticking, so it stops renewing the ticket that would have brought it
 * back, and it hangs in the air forever. Measured on the rig: a strike aircraft at 3.14 blocks/tick
 * froze permanently the instant it crossed out of the force-loaded region, keeping its velocity and
 * its position to the decimal for the rest of the run.
 * <p>
 * The renewal therefore runs from the server tick, over a set of strong references, so a frozen
 * aircraft is picked up and thawed rather than being lost.
 */
public final class AutopilotRegistry {

    /**
     * Identity set: two distinct aircraft must never collide, and {@code PlaneEntity} inherits
     * {@code Entity}'s identity-based equals anyway.
     */
    private static final Set<PlaneEntity> ACTIVE = Collections.newSetFromMap(new IdentityHashMap<>());

    private AutopilotRegistry() {}

    public static void init() {
        ServerTickEvents.END_LEVEL_TICK.register(AutopilotRegistry::onLevelTick);
    }

    /** Server-side only: a single-player client shares this JVM and must not add to the count. */
    public static void register(PlaneEntity plane) {
        if (!plane.level().isClientSide()) {
            ACTIVE.add(plane);
        }
    }

    public static void unregister(PlaneEntity plane) {
        ACTIVE.remove(plane);
    }

    /** Number of aircraft actually flying right now. Cannot leak: it is recomputed on every call. */
    public static int activeCount() {
        prune();
        return ACTIVE.size();
    }

    public static boolean canActivateAnother() {
        return activeCount() < AutopilotConfig.MAX_ACTIVE_AUTOPILOTS;
    }

    /**
     * The aircraft that are actually flying, pruned and copied.
     *
     * <p>A snapshot rather than the live set, because callers walk it and anything that touches an
     * aircraft can load a chunk and register another one. Cheaper and more precise than scanning the
     * level for entities: this is exactly the autopilot aircraft, with no world lookup at all.
     */
    public static List<PlaneEntity> active() {
        prune();
        return new ArrayList<>(ACTIVE);
    }

    private static void prune() {
        ACTIVE.removeIf(plane -> plane.isRemoved()
            || !plane.isAlive()
            || plane.getAutopilot() == null
            || !plane.getAutopilot().isActive());
    }

    /**
     * Keeps every live autopilot aircraft inside a chunk bubble it cannot outrun.
     *
     * <p>Runs off the level tick rather than the entity tick precisely so that an aircraft which has
     * already stopped ticking still gets a ticket and starts again.
     */
    private static void onLevelTick(ServerLevel level) {
        if (ACTIVE.isEmpty() || level.getGameTime() % AutopilotConfig.CHUNK_TICKET_INTERVAL != 0) {
            return;
        }
        prune();
        // Copied because keepChunksLoaded can load a chunk, which can load entities, which can
        // register another autopilot while this loop is running.
        List<PlaneEntity> snapshot = new ArrayList<>(ACTIVE);
        for (PlaneEntity plane : snapshot) {
            if (plane.level() == level) {
                PlaneAutopilot.keepChunksLoaded(level, plane);
            }
        }
    }
}
