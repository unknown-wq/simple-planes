package xyz.przemyk.simpleplanes.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The list of {@link AirspaceGuard}s, and the one call that runs them.
 *
 * <h2>Why any-says-yes rather than a chain</h2>
 * {@link BlastGuards} chains its guards because a blast is a thing with a magnitude and two mods may
 * each want to reduce it. Airspace is not: the answer is a yes or a no, and two mods that both claim
 * a piece of sky are not in conflict — they simply both claim it. So the answer is the disjunction,
 * evaluated with a short circuit, and no guard ever sees what another one said. That also means no
 * guard can quietly <em>clear</em> a point another guard claimed, which a chain would have allowed
 * and which would be a protection bug rather than a feature.
 *
 * <h2>Why registration needs no lifecycle</h2>
 * Exactly as {@link BlastGuards}: the list is a plain static field, so a foreign mod may register
 * from its own initialiser whether that runs before or after this one. Fabric gives no ordering
 * guarantee between two mods' initialisers, and a hook another mod can miss by being early is a hook
 * that fails silently on somebody's machine.
 *
 * <h2>Thread safety and cost</h2>
 * {@link CopyOnWriteArrayList} because registration happens a handful of times at start-up and
 * iteration happens on the server thread inside a flight. With no guards registered — which is every
 * installation that has not gone looking for this — {@link #isActive} is one {@code isEmpty} test on
 * a field and {@link #isAvoided} is never reached at all, because the autopilot's own gate is
 * {@code isActive}. That is the property that makes this seam free: a server running this mod on its
 * own does not merely get the same answer, it does not run this code.
 *
 * <h2>The off switch</h2>
 * Avoidance can be turned off for a server with {@code /airspaceguard off} (see
 * {@link AirspaceGuardCommand}) and the state is remembered across restarts by
 * {@link AirspaceGuardSettings}. Off means off: {@link #isActive} answers false, the autopilot's
 * route planner reverts to the terrain-only search it ran before this existed, and no guard is
 * consulted. It is not a third mode.
 *
 * <p>The switch is checked after the {@code isEmpty} test, not before, so a server with no guards
 * registered never even looks at it.
 */
public final class AirspaceGuards {

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes");

    private static final List<AirspaceGuard> GUARDS = new CopyOnWriteArrayList<>();

    private AirspaceGuards() {}

    /**
     * Adds a guard. Order is irrelevant — the answer is a disjunction — but registrations are kept in
     * arrival order so that the short circuit is at least predictable when profiling.
     *
     * <p>Registering the same instance twice asks it twice; that is a caller's bug rather than
     * something worth paying an equality check for on a list this short.
     *
     * @param guard the guard to consult, never {@code null}.
     */
    public static void register(AirspaceGuard guard) {
        if (guard == null) {
            throw new IllegalArgumentException("airspace guard must not be null");
        }
        GUARDS.add(guard);
    }

    /** Whether anything at all is listening. */
    public static boolean isEmpty() {
        return GUARDS.isEmpty();
    }

    /** How many guards are registered, for {@code /airspaceguard status} to report honestly. */
    public static int count() {
        return GUARDS.size();
    }

    /**
     * Whether the route planner should ask about airspace at all on this level.
     *
     * <p>This is the gate the autopilot tests, and it is the whole of what a guard-free installation
     * pays: one {@code isEmpty} on a static field, per aircraft, per plan interval. The saved-data
     * read behind it only ever happens on a server that has a guard.
     *
     * @param level the level being flown in; a client level or a null level is never active.
     */
    public static boolean isActive(Level level) {
        if (GUARDS.isEmpty()) {
            return false;
        }
        return level instanceof ServerLevel serverLevel && AirspaceGuardSettings.isEnabled(serverLevel);
    }

    /**
     * Whether any guard claims this point for this pilot.
     *
     * <p>A guard that throws is logged and then treated as having answered {@code false}: a broken
     * third-party guard must not be able to divert an aircraft, and it must not be able to turn a
     * flight into a server crash. {@link Throwable} rather than {@link Exception} on purpose — a
     * guard whose own class fails to link throws an {@link Error}, and that is exactly the case
     * where carrying on matters most.
     *
     * <p>Callers are expected to have tested {@link #isActive} first; this repeats neither test,
     * because it is called once per probe and the gate is once per search.
     *
     * @param level the server level being flown in.
     * @param craft the aircraft, or {@code null}.
     * @param pilot the player the flight belongs to, or {@code null}.
     * @param at    the point being asked about.
     * @return true if the autopilot should prefer a route that avoids this point.
     */
    public static boolean isAvoided(ServerLevel level, Entity craft, Player pilot, Vec3 at) {
        return isAvoided(new Flight(level, craft, pilot, false, null, null), at);
    }

    /**
     * The same question, asked with the flight it is about — which is the form the route planner
     * uses, and the only form that can answer "is anybody aboard" or "is this where we are going".
     *
     * <p>A {@link FlightAwareAirspaceGuard} is asked the flight-aware question; every other guard is
     * asked the point-only one, with the flight's own level, aircraft and pilot, and so cannot tell
     * that this overload exists. The choice is one {@code instanceof} per guard per probe, which is
     * why the two kinds of guard are one list rather than two.
     *
     * @param flight what is being flown, by and for whom, and between where and where.
     * @param at     the point being asked about.
     * @return true if the autopilot should prefer a route that avoids this point.
     */
    public static boolean isAvoided(Flight flight, Vec3 at) {
        for (AirspaceGuard guard : GUARDS) {
            try {
                boolean avoided = guard instanceof FlightAwareAirspaceGuard aware
                    ? aware.isAirspaceAvoided(flight, at)
                    : guard.isAirspaceAvoided(flight.level(), flight.craft(), flight.pilot(), at);
                if (avoided) {
                    return true;
                }
            } catch (Throwable t) {
                LOGGER.error("Airspace guard {} threw; ignoring it for this point", guard.getClass().getName(), t);
            }
        }
        return false;
    }
}
