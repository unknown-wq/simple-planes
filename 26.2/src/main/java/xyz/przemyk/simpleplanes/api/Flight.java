package xyz.przemyk.simpleplanes.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The flight a {@link FlightAwareAirspaceGuard} is being asked about: who is being flown, whether
 * they are actually aboard, and the two ends of the leg.
 *
 * <p>Everything here is <b>about the sortie</b> rather than about the point being probed, so one of
 * these is built once per route search and handed to every probe of it. A guard that has per-flight
 * work to do — resolving which claim contains the destination, say — can therefore do it once and
 * key it on the instance.
 *
 * <h2>Why this exists rather than three more parameters</h2>
 * {@link AirspaceGuard} asks one question about one point and that is still the whole of the
 * question. But the useful answers to it turned out to depend on facts about the <em>flight</em>
 * that a point cannot carry, and every one of them is a fact only this mod knows:
 *
 * <ul>
 *   <li><b>Is anybody actually in the aircraft?</b> A land-claim mod may reasonably want to route a
 *       manned aircraft round a border while letting an unmanned one — a mail run, a strike — fly
 *       straight, or the other way about. {@link #pilot} alone cannot answer that: it is also filled
 *       in for the player who <em>ordered</em> an empty flight.</li>
 *   <li><b>Where is this leg going, and where did it start?</b> Without those a guard cannot let an
 *       aircraft land at, or take off from, a field inside a claim it otherwise keeps out of — and
 *       an aircraft that cannot leave the ground it is standing on is not a routing preference, it
 *       is a trap.</li>
 * </ul>
 *
 * <p>They are gathered into a record rather than added to the interface's parameter list because the
 * list is what a foreign mod binds to, and every future fact would break it again. A record grows
 * without any existing guard noticing.
 *
 * @param level       the server level being flown in, never null.
 * @param craft       the aircraft, or null if there is none to name. Never assume a type.
 * @param pilot       the player the flight is flown on behalf of, or null if unknown. See
 *                    {@link AirspaceGuard} for why this can be null and what to do about it.
 * @param pilotAboard whether {@link #pilot} is sitting in {@link #craft} right now. False for an
 *                    unmanned flight — including one whose owner is known because they ordered it —
 *                    and false whenever there is no pilot at all.
 * @param departure   where this flight began: the parking spot, runway or point in the air the
 *                    autopilot took the aircraft from. Null for a flight that has forgotten (one
 *                    reloaded from disk by a build older than this field, say).
 * @param destination the point the autopilot is steering at now — the current waypoint, the strike
 *                    aimpoint, or the field it has committed to landing at once it has picked one.
 *                    Null when the flight has no target it can name. It moves through the flight,
 *                    as the aircraft's actual destination does; it is not a fixed final fix.
 */
public record Flight(ServerLevel level,
                     @Nullable Entity craft,
                     @Nullable Player pilot,
                     boolean pilotAboard,
                     @Nullable Vec3 departure,
                     @Nullable Vec3 destination) {
}
