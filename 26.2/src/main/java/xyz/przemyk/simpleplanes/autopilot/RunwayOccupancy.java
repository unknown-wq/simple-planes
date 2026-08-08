package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Who is currently using which runway, and how many autopilots are airborne at all.
 *
 * <p>This is deliberately runtime-only state: a reservation is meaningless across a restart,
 * because the aircraft holding it is re-evaluated from scratch when it loads. Reservations are
 * never timed out — they are validated against the holder, so a plane that is removed, lands or
 * has its autopilot switched off releases the runway implicitly.
 *
 * <p>All access is from the server thread (entity ticking and item use), so a plain map is fine.
 */
public final class RunwayOccupancy {

    private record Key(ResourceKey<Level> dimension, String airfield) {}

    private static final Map<Key, PlaneEntity> RESERVATIONS = new HashMap<>();

    private RunwayOccupancy() {}

    private static boolean stillHolding(PlaneEntity plane, String airfield) {
        if (plane == null || plane.isRemoved() || !plane.isAlive()) {
            return false;
        }
        PlaneAutopilot autopilot = plane.getAutopilot();
        return autopilot != null && autopilot.isActive() && autopilot.holdsRunway(airfield);
    }

    /**
     * Tries to reserve a runway. Returns true if this aircraft now owns it — including the case
     * where it already did.
     */
    public static boolean tryOccupy(Level level, String airfield, PlaneEntity plane) {
        Key key = new Key(level.dimension(), airfield);
        PlaneEntity holder = RESERVATIONS.get(key);
        if (holder == plane) {
            return true;
        }
        if (holder != null && stillHolding(holder, airfield)) {
            return false;
        }
        RESERVATIONS.put(key, plane);
        return true;
    }

    public static void release(Level level, String airfield, PlaneEntity plane) {
        Key key = new Key(level.dimension(), airfield);
        if (RESERVATIONS.get(key) == plane) {
            RESERVATIONS.remove(key);
        }
    }

    /** Releases every reservation held by this aircraft, whatever the airfield. */
    public static void releaseAll(PlaneEntity plane) {
        RESERVATIONS.values().removeIf(holder -> holder == plane);
    }

    public static boolean isFree(Level level, String airfield, PlaneEntity asker) {
        Key key = new Key(level.dimension(), airfield);
        PlaneEntity holder = RESERVATIONS.get(key);
        return holder == null || holder == asker || !stillHolding(holder, airfield);
    }

    /**
     * Number of live autopilots, used to enforce {@link AutopilotConfig#MAX_ACTIVE_AUTOPILOTS}.
     *
     * <p>Derived from {@link AutopilotRegistry}, which recounts the live aircraft on every call.
     * This used to be a {@code static int} incremented on activation and decremented on release, and
     * it leaked a slot for every aircraft that went away without running its release path — which is
     * what happens on every crash. See {@link AutopilotRegistry} for the measured failure.
     */
    public static int activeCount() {
        return AutopilotRegistry.activeCount();
    }

    public static boolean canActivateAnother() {
        return AutopilotRegistry.canActivateAnother();
    }

    /** Drops reservations whose holder has gone away; called occasionally, not every tick. */
    public static void prune() {
        Iterator<Map.Entry<Key, PlaneEntity>> iterator = RESERVATIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, PlaneEntity> entry = iterator.next();
            if (!stillHolding(entry.getValue(), entry.getKey().airfield())) {
                iterator.remove();
            }
        }
    }
}
