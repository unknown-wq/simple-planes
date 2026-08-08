package xyz.przemyk.simpleplanes.autopilot;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * How long each aircraft has been doing what it is doing about a runway — the one thing the tower
 * board needs that nothing in the autopilot records.
 *
 * <h2>Why this is sampled rather than stamped</h2>
 * {@link RunwayOccupancy} stores a reservation, not the tick it was made, and {@link PlaneAutopilot}
 * keeps its mode timer private. Neither is worth changing for a read-only readout: the reservation
 * logic is about to be replaced by a real dispatcher, and a board that writes into the thing it is
 * reporting on is a board that can be blamed for what it shows. So this class watches from the
 * outside, on the server tick, and never touches occupancy at all.
 *
 * <p>Roles are decided with the autopilot's own {@link PlaneAutopilot#holdsRunway} validation rather
 * than a second copy of it, so an aircraft this class calls an occupant is exactly the one
 * {@link RunwayOccupancy#holder} would return.
 *
 * <h2>Cost</h2>
 * One pass over {@link AutopilotRegistry#active()} — the aircraft that are flying, not the entities
 * in the world — every {@value #SAMPLE_INTERVAL} ticks. With nothing flying it is an empty loop.
 * The consequence of sampling is that every duration is accurate to half a second, which is well
 * inside the resolution the board prints.
 */
public final class TowerWatch {

    /** What an aircraft is doing about a runway, as far as the board is concerned. */
    public enum Role {
        /** Holds the reservation: cleared onto the runway and on the way down, or rolling out. */
        OCCUPYING,
        /** Orbiting the approach fix because the runway was busy when it asked. */
        HOLDING
    }

    /** Half a second. Fine enough for a readout printed as m:ss, cheap enough to ignore. */
    private static final int SAMPLE_INTERVAL = 10;

    private record Watched(Role role, String airfield, long since) {}

    /** Identity-keyed: two aircraft are never equal, and entity ids are reused across a restart. */
    private static final Map<PlaneEntity, Watched> WATCHED = new IdentityHashMap<>();

    private TowerWatch() {}

    public static void init() {
        ServerTickEvents.END_LEVEL_TICK.register(TowerWatch::onLevelTick);
    }

    private static void onLevelTick(ServerLevel level) {
        if (level.getGameTime() % SAMPLE_INTERVAL != 0) {
            return;
        }
        WATCHED.keySet().removeIf(plane -> plane.isRemoved()
            || !plane.isAlive()
            || plane.getAutopilot() == null
            || !plane.getAutopilot().isActive());

        long now = level.getGameTime();
        for (PlaneEntity plane : AutopilotRegistry.active()) {
            if (plane.level() != level) {
                continue;
            }
            String airfield = airfieldOf(plane);
            Role role = roleOf(plane, airfield);
            if (role == null || airfield == null) {
                WATCHED.remove(plane);
                continue;
            }
            Watched previous = WATCHED.get(plane);
            // Restamp on any change of role or field: a go-around that re-enters the hold, or a
            // diversion, starts a new wait rather than inheriting the old one.
            if (previous == null || previous.role() != role || !previous.airfield().equals(airfield)) {
                WATCHED.put(plane, new Watched(role, airfield, now));
            }
        }
    }

    /**
     * The airfield this aircraft is dealing with, as the flight plan records it.
     *
     * <p>{@code PlaneAutopilot.resolveLanding} writes the resolved name back into the plan before
     * the aircraft can ever occupy or hold, so this is the same name the reservation is keyed by —
     * including the {@code field-<id>} of an improvised landing.
     */
    public static @Nullable String airfieldOf(PlaneEntity plane) {
        PlaneAutopilot autopilot = plane.getAutopilot();
        if (autopilot == null || !autopilot.isActive() || autopilot.getPlan() == null) {
            return null;
        }
        return autopilot.getPlan().airfieldName();
    }

    /** The aircraft's role at {@code airfield}, or null when it is doing neither. */
    public static @Nullable Role roleOf(PlaneEntity plane, @Nullable String airfield) {
        PlaneAutopilot autopilot = plane.getAutopilot();
        if (airfield == null || autopilot == null || !autopilot.isActive()) {
            return null;
        }
        if (autopilot.getMode() == AutopilotMode.HOLD) {
            return Role.HOLDING;
        }
        // The autopilot's own test, not a copy of it: true exactly when the reservation is valid.
        return autopilot.holdsRunway(airfield) ? Role.OCCUPYING : null;
    }

    /**
     * Ticks this aircraft has been in its current role, or -1 when it has not been sampled yet
     * (the first half-second of a role, or a role entered while the server was not ticking).
     */
    public static long ticksInRole(PlaneEntity plane) {
        Watched watched = WATCHED.get(plane);
        if (watched == null) {
            return -1;
        }
        return Math.max(0, plane.level().getGameTime() - watched.since());
    }

    /** {@code m:ss}, or {@code ?} for a role that has not been sampled yet. */
    public static String elapsed(PlaneEntity plane) {
        long ticks = ticksInRole(plane);
        if (ticks < 0) {
            return "?";
        }
        long seconds = ticks / 20;
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }
}
