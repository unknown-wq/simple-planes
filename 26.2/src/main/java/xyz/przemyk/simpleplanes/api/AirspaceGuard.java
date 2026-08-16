package xyz.przemyk.simpleplanes.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * A claim on a piece of sky, consulted by the autopilot while it decides which way to fly.
 *
 * <p>The sibling of {@link BlastGuard} and built the same way: an interface that names no mod but
 * this one, plus a registry another mod adds itself to. It exists so that a land-claim mod, a
 * protection plugin or a server's own datapack glue can tell an unmanned or auto-flown aircraft
 * "not over here, not for this pilot" without this mod having to grow a dependency on any of them,
 * or even know they exist.
 *
 * <h2>What a guard is asked, and what it is not</h2>
 * It is asked one question — <b>would this pilot rather not be over this point?</b> — and it answers
 * yes or no. That is deliberately weaker than a permission check, and the weakness is the whole
 * design:
 *
 * <ul>
 *   <li>A {@code true} is <b>advice to the route planner</b>, not a prohibition. It is folded into
 *       the same cost comparison that already decides between climbing over a ridge and flying
 *       round it, and the planner may still fly through avoided airspace when every alternative is
 *       worse — most importantly when the aircraft is already inside it. See
 *       {@link xyz.przemyk.simpleplanes.autopilot.RoutePlanner}.</li>
 *   <li>It is asked <b>only while the autopilot is flying</b>. A player with their hands on the
 *       controls is never routed by this and never stopped by it: this mod does not implement
 *       no-fly zones and this seam cannot be used to build one, because nothing consults it on the
 *       manual flight path.</li>
 *   <li>It is asked about <b>points, not about volumes</b>. The planner probes a handful of columns
 *       along each candidate heading and asks about each; a guard therefore never has to describe
 *       the shape of its claim, only to answer for a position.</li>
 * </ul>
 *
 * <h2>What a guard is told</h2>
 * The level, the aircraft (as a bare {@link Entity}, because nothing here should need to know what
 * an aircraft is), the player the flight is being flown on behalf of, and the point in question.
 *
 * <p>The pilot is passed explicitly rather than left to be dug out of the aircraft, because for an
 * autopilot flight it frequently <em>cannot</em> be dug out: a plane launched by {@code /autopilot
 * flight} carries nobody, and the player the flight belongs to is the one who ordered it. Working
 * that out is this mod's business, not a guard's. It may still be {@code null} — a flight reloaded
 * from disk after a restart has forgotten who ordered it, and a strike aircraft never had an owner
 * — and a guard that has nothing to say about an anonymous flight should answer {@code false}.
 *
 * @see AirspaceGuards
 */
@FunctionalInterface
public interface AirspaceGuard {

    /**
     * Whether this point is airspace the given pilot should be routed around.
     *
     * <p>Called on the server thread, inside the aircraft's own tick, up to
     * {@code ROUTE_PLAN_SAMPLES} times per candidate heading and at most once every
     * {@code ROUTE_PLAN_INTERVAL} ticks per aircraft. Implementations must be quick and must not
     * throw; a guard that throws is logged and skipped by {@link AirspaceGuards#isAvoided}, and the
     * point is treated as clear.
     *
     * <p><b>Must not load chunks.</b> The planner only ever probes ground it can already see, so a
     * guard is only ever asked about resident chunks in practice — but a guard that reaches for
     * world state should check for itself and answer {@code false} rather than generate terrain from
     * inside an aircraft's tick.
     *
     * @param level  the server level being flown in, never {@code null}.
     * @param craft  the aircraft, or {@code null} if there is none to name. Never assume a type.
     * @param pilot  the player the flight is flown on behalf of, or {@code null} if unknown.
     * @param at     the point being asked about, never {@code null}. {@code y} is the altitude the
     *               candidate route would be flown at.
     * @return true if the autopilot should prefer a route that does not pass through here.
     */
    boolean isAirspaceAvoided(ServerLevel level, Entity craft, Player pilot, Vec3 at);
}
