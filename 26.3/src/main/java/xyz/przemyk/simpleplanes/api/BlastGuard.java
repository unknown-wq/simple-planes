package xyz.przemyk.simpleplanes.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.autopilot.Blast;

/**
 * A veto or a modifier consulted immediately before an aircraft's warhead is applied to the world.
 *
 * <p>Every blast this mod produces goes through one line — {@code PlaneEntity#explode} — whatever
 * ordered it: the craftable strike tool, {@code /autopilot strike}, a gunship that ran out of sky,
 * or an ordinary plane a player flew into a hillside. A guard registered here sits on that line and
 * is therefore the complete list of this mod's explosions, with nothing to keep in sync.
 *
 * <h2>What a guard may decide</h2>
 * A guard is handed the blast as it currently stands and returns the blast to apply:
 * <ul>
 *   <li><b>abstain</b> — return the argument unchanged. This is what a guard does for the 99% of
 *       blasts it has no opinion about, and it must be cheap, because it runs on all of them.</li>
 *   <li><b>downgrade</b> — return a new {@link Blast} with less power, {@code breaksBlocks} off or
 *       {@code fire} off. {@code Blast} clamps its own power in the canonical constructor, so a
 *       guard cannot hand back a warhead larger than {@link Blast#MAX_POWER} even by accident. It
 *       <em>can</em> hand back a larger one than it was given, which is deliberate: a guard that
 *       wants to make a place more dangerous is as legitimate as one that wants to make it safer.</li>
 *   <li><b>suppress</b> — return {@code null}. No explosion happens at all: no block damage, no
 *       entity damage, no sound. The aircraft is still destroyed and still leaves its smoke; only
 *       the detonation is skipped.</li>
 * </ul>
 *
 * <h2>What a guard is told</h2>
 * Deliberately little, and all of it vanilla: the level, the aircraft (as a bare {@link Entity},
 * because nothing here should need to know what an aircraft is), the position the blast is centred
 * on, and the blast itself. A guard that wants to know more — who was flying, what the flight plan
 * was — can cast {@code source} and ask; a guard that only cares about <em>where</em> does not have
 * to.
 *
 * <p>This interface names no mod but this one, and it is meant to stay that way. It exists so that a
 * land-claim mod, a protection plugin or a server's own datapack glue can refuse a blast without
 * this mod having to grow a dependency on any of them, or even know they exist.
 *
 * @see BlastGuards
 */
@FunctionalInterface
public interface BlastGuard {

    /**
     * Decides what a blast about to go off should actually do.
     *
     * <p>Called on the server thread, inside the aircraft's own tick, immediately before
     * {@code Level#explode}. Implementations must be quick and must not throw; a guard that throws
     * is logged and skipped by {@link BlastGuards#filter}, and the blast carries on as if that
     * guard had abstained.
     *
     * @param level  the server level the blast is about to happen in, never {@code null}.
     * @param source the aircraft, or {@code null} if the blast has no entity behind it. Never
     *               assume a type: cast and test.
     * @param at     the centre of the blast, never {@code null}.
     * @param blast  the blast as it stands after every guard registered before this one, never
     *               {@code null}.
     * @return the blast to apply, or {@code null} to suppress the explosion entirely.
     */
    Blast guardBlast(ServerLevel level, Entity source, Vec3 at, Blast blast);
}
