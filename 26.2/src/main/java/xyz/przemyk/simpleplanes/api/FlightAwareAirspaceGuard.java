package xyz.przemyk.simpleplanes.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * An {@link AirspaceGuard} that is also told what flight it is answering for.
 *
 * <p>Register it exactly as any other guard — {@link AirspaceGuards#register} takes the base type —
 * and the route planner will notice and ask this method instead. Everything the note on
 * {@link AirspaceGuard} says still holds word for word: the answer is advice to a route planner and
 * not a prohibition, it is asked only while the autopilot is flying, and it is asked about points
 * rather than volumes.
 *
 * <h2>Why a second interface rather than another method on the first</h2>
 * Because a guard is a foreign mod's object, and this mod has no say in when that mod is rebuilt.
 * Adding a parameter to {@link AirspaceGuard#isAirspaceAvoided} would break every guard compiled
 * against the old shape at link time; adding a default method would break the ones that are
 * {@link java.lang.reflect.Proxy} instances, because a proxy routes <em>every</em> interface method
 * through its handler and a handler written before the method existed would answer it with null.
 * A separate interface is the only shape where an old guard keeps working untouched and a new one is
 * a compile-time — or, for a proxy, a {@code Class.forName} — opt in.
 *
 * <p>{@link AirspaceGuards#isAvoided(Flight, Vec3)} is where the choice is made, with one
 * {@code instanceof} per guard per probe.
 *
 * @see Flight
 */
public interface FlightAwareAirspaceGuard extends AirspaceGuard {

    /**
     * Whether this point is airspace the given flight should be routed around.
     *
     * <p>Same contract as {@link AirspaceGuard#isAirspaceAvoided}: server thread, inside the
     * aircraft's own tick, must be quick, must not throw, must not load chunks. The {@code flight}
     * is the same instance for every probe of one route search, so per-flight work may be cached on
     * it.
     *
     * @param flight what is being flown, by and for whom, and between where and where.
     * @param at     the point being asked about, never null. {@code y} is the altitude the candidate
     *               route would be flown at.
     * @return true if the autopilot should prefer a route that does not pass through here.
     */
    boolean isAirspaceAvoided(Flight flight, Vec3 at);

    /**
     * The base interface's question, answered from the flight-aware one with everything this mod
     * could not say filled in as "not known".
     *
     * <p>Nothing in this mod calls it on a guard that implements this interface — the planner
     * always has a {@link Flight} to hand and always prefers the method above. It exists so that
     * this type is a drop-in {@link AirspaceGuard} for any other caller, and so that a guard need
     * only write one method.
     */
    @Override
    default boolean isAirspaceAvoided(ServerLevel level, Entity craft, Player pilot, Vec3 at) {
        return isAirspaceAvoided(new Flight(level, craft, pilot, false, null, null), at);
    }
}
