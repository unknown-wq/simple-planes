package xyz.przemyk.simpleplanes.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.autopilot.Blast;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The list of {@link BlastGuard}s, and the one call that runs them.
 *
 * <h2>Why a list and not a single hook</h2>
 * Two mods may both have an opinion about the same blast — a land-claim mod that wants no craters
 * inside a claim and a server's own glue that wants no fire anywhere — and neither should have to
 * know about the other. Guards are therefore <em>chained</em>: each one is handed what the previous
 * one returned, so downgrades compose and the strictest answer wins without anybody arbitrating.
 * The first guard to return {@code null} ends the chain; there is nothing left to decide once the
 * blast is gone.
 *
 * <h2>Why registration needs no lifecycle</h2>
 * There is no {@code init()} and nothing to call from {@link xyz.przemyk.simpleplanes.SimplePlanesMod}.
 * The list is a plain static field, so a foreign mod may register from its own initialiser whether
 * that runs before or after this one — class-loading this class is the whole of the setup. That
 * matters because Fabric gives no ordering guarantee between two mods' initialisers, and a hook
 * another mod can miss by being early is a hook that fails silently on somebody's machine.
 *
 * <h2>Thread safety and cost</h2>
 * {@link CopyOnWriteArrayList} because registration happens a handful of times at start-up and
 * iteration happens on the server thread on every crash: reads must be free, writes may be dear.
 * With no guards registered — which is every installation that has not gone looking for this —
 * {@link #filter} is one {@code isEmpty} test on a field.
 *
 * <h2>The off switch</h2>
 * Guarding can be turned off for a server with {@code /blastguard off} (see
 * {@link BlastGuardCommand}), and the state is remembered across restarts by
 * {@link BlastGuardSettings}. Off means <em>off</em>: {@link #filter} returns the blast it was given
 * without consulting anybody, so the explosion is precisely the one the aircraft ordered and the
 * behaviour is what this mod did before guards existed. It is not a third mode and it does not
 * half-apply anything.
 *
 * <p>The switch is checked after the {@code isEmpty} test, not before, so a server with no guards
 * registered never even looks at it.
 */
public final class BlastGuards {

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes");

    private static final List<BlastGuard> GUARDS = new CopyOnWriteArrayList<>();

    private BlastGuards() {}

    /**
     * Adds a guard. Registrations are kept in the order they arrive and each guard sees the output
     * of the ones before it, so a mod that wants the last word should register last.
     *
     * <p>Registering the same instance twice runs it twice; that is a caller's bug rather than
     * something worth paying an equality check for on a list this short.
     *
     * @param guard the guard to consult, never {@code null}.
     */
    public static void register(BlastGuard guard) {
        if (guard == null) {
            throw new IllegalArgumentException("blast guard must not be null");
        }
        GUARDS.add(guard);
    }

    /** Whether anything at all is listening. Public so a caller can skip work it would waste. */
    public static boolean isEmpty() {
        return GUARDS.isEmpty();
    }

    /** How many guards are registered, for {@code /blastguard status} to report honestly. */
    public static int count() {
        return GUARDS.size();
    }

    /**
     * Runs {@code blast} past every registered guard in turn.
     *
     * <p>A guard that throws is logged once per throw and then treated as having abstained: a
     * broken third-party guard must not be able to stop an aircraft from being destroyed, and it
     * must not be able to turn a crash into a server crash. {@link Throwable} rather than
     * {@link Exception} on purpose — a guard whose own class fails to link throws an
     * {@link Error}, and that is exactly the case where carrying on matters most.
     *
     * <p>Returns {@code blast} untouched, consulting nobody, when guarding is switched off for this
     * server — see {@link BlastGuardSettings}. That check sits behind the {@code isEmpty} one so that
     * a server with no guards pays nothing for the existence of a switch it will never use.
     *
     * @param level  the server level the blast is about to happen in.
     * @param source the aircraft, or {@code null}.
     * @param at     the centre of the blast.
     * @param blast  the blast as ordered.
     * @return the blast to apply, or {@code null} if a guard suppressed it entirely.
     */
    public static Blast filter(ServerLevel level, Entity source, Vec3 at, Blast blast) {
        if (GUARDS.isEmpty()) {
            return blast;
        }
        if (!BlastGuardSettings.isEnabled(level)) {
            return blast;
        }
        Blast current = blast;
        for (BlastGuard guard : GUARDS) {
            final Blast verdict;
            try {
                verdict = guard.guardBlast(level, source, at, current);
            } catch (Throwable t) {
                LOGGER.error("Blast guard {} threw; ignoring it for this blast", guard.getClass().getName(), t);
                continue;
            }
            if (verdict == null) {
                return null;
            }
            current = verdict;
        }
        return current;
    }
}
