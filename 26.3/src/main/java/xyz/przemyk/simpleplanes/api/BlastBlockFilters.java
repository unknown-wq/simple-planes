package xyz.przemyk.simpleplanes.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.autopilot.Blast;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;

/**
 * The list of {@link BlastBlockFilter}s, and the one call that resolves them for a blast.
 *
 * <p>Built the way {@link BlastGuards} is and for the same reasons: a plain static
 * {@link CopyOnWriteArrayList} with no lifecycle, so a foreign mod may register from its own initialiser in
 * either load order; a filter that throws is logged and ignored rather than taking the explosion with it;
 * and with nothing registered {@link #resolve} is one {@code isEmpty} test, after which the blast goes
 * through the very same {@code Level#explode} overload it always did.
 *
 * <p>Filters combine with a logical AND — a block breaks only if every filter that has an opinion allows it —
 * so two mods that each protect something never have to know about each other.
 *
 * <p>{@code /blastguard off} switches filters off together with guards (see {@link BlastGuardSettings}).
 */
public final class BlastBlockFilters {

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes");

    private static final List<BlastBlockFilter> FILTERS = new CopyOnWriteArrayList<>();

    private BlastBlockFilters() {}

    /**
     * Adds a filter. Order does not matter: the predicates are combined with AND.
     *
     * @param filter the filter to consult, never {@code null}.
     */
    public static void register(BlastBlockFilter filter) {
        if (filter == null) {
            throw new IllegalArgumentException("blast block filter must not be null");
        }
        FILTERS.add(filter);
    }

    /** Whether anything at all is listening. */
    public static boolean isEmpty() {
        return FILTERS.isEmpty();
    }

    /** How many filters are registered, for {@code /blastguard status}. */
    public static int count() {
        return FILTERS.size();
    }

    /**
     * Asks every registered filter about one blast and combines the answers.
     *
     * @param level  the server level the blast is about to happen in.
     * @param source the aircraft, or {@code null}.
     * @param at     the centre of the blast.
     * @param blast  the blast as the guards left it.
     * @return {@code null} if no filter narrows this blast — the caller then explodes exactly as it would
     *         have without this class — or the combined per-block predicate.
     */
    public static @Nullable BiPredicate<BlockPos, BlockState> resolve(ServerLevel level, @Nullable Entity source, Vec3 at, Blast blast) {
        if (FILTERS.isEmpty()) {
            return null;
        }
        if (blast.pierce() || (!blast.breaksBlocks() && !blast.fire())) {
            // Nothing on the world side for a filter to narrow.
            return null;
        }
        if (!BlastGuardSettings.isEnabled(level)) {
            return null;
        }
        BiPredicate<BlockPos, BlockState> combined = null;
        for (BlastBlockFilter filter : FILTERS) {
            final BiPredicate<BlockPos, BlockState> only;
            try {
                only = filter.blockFilter(level, source, at, blast);
            } catch (Throwable t) {
                LOGGER.error("Blast block filter {} threw; ignoring it for this blast", filter.getClass().getName(), t);
                continue;
            }
            if (only == null) {
                continue;
            }
            BiPredicate<BlockPos, BlockState> checked = new Checked(filter, only);
            combined = combined == null ? checked : combined.and(checked);
        }
        return combined;
    }

    /**
     * One filter's predicate, isolated: if it throws, that is logged once and the filter abstains (allows
     * everything) for the rest of the blast, so a broken filter cannot turn a crash into a server crash and
     * cannot flood the log with one line per block.
     */
    private static final class Checked implements BiPredicate<BlockPos, BlockState> {
        private final BlastBlockFilter filter;
        private final BiPredicate<BlockPos, BlockState> only;
        private boolean broken;

        Checked(BlastBlockFilter filter, BiPredicate<BlockPos, BlockState> only) {
            this.filter = filter;
            this.only = only;
        }

        @Override
        public boolean test(BlockPos pos, BlockState state) {
            if (broken) {
                return true;
            }
            try {
                return only.test(pos, state);
            } catch (Throwable t) {
                broken = true;
                LOGGER.error("Blast block filter {} threw on a block; ignoring it for the rest of this blast", filter.getClass().getName(), t);
                return true;
            }
        }
    }
}
