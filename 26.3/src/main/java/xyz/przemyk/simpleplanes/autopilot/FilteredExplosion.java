package xyz.przemyk.simpleplanes.autopilot;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EntityBasedExplosionDamageCalculator;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.api.BlastBlockFilters;

import java.util.Locale;
import java.util.function.BiPredicate;

/**
 * The non-piercing half of {@link Blast#detonate}: a vanilla explosion, narrowed by the registered
 * {@link xyz.przemyk.simpleplanes.api.BlastBlockFilter}s when any of them has an opinion.
 *
 * <p>With no filter narrowing the blast this is the very same {@code Level#explode} call {@link Blast} always
 * made. With one, it is the fuller overload that takes an {@link ExplosionDamageCalculator}, given the
 * calculator vanilla would have built itself ({@code ServerExplosion#makeDamageCalculator}: entity-based
 * with a source, the plain default without) with {@code shouldBlockExplode} narrowed by the predicate, and
 * the damage source the short overload would have passed. Everything else — resistance, entity damage,
 * knockback, particles, sound — is vanilla's.
 *
 * <p>Every blast a filter narrowed leaves one INFO line in the log, {@code Filtered blast: ...}: the power, the
 * centre, the source, how many of the blocks the unfiltered blast would have taken the filters allowed (split
 * into solid blocks, which break, and air, where fire may start) and each filter's own description of what it
 * decided. The last one is also shown by {@code /blastguard status}.
 */
public final class FilteredExplosion {

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes");

    private static volatile @Nullable String lastFiltered;

    private FilteredExplosion() {}

    static void explode(ServerLevel level, @Nullable Entity source, Vec3 at, Blast blast) {
        BiPredicate<BlockPos, BlockState> only = BlastBlockFilters.resolve(level, source, at, blast);
        if (only == null) {
            level.explode(source, at.x, at.y, at.z, blast.power(), blast.fire(), blast.interaction());
            return;
        }
        Tally tally = new Tally(only);
        ExplosionDamageCalculator calculator = source == null ? new Plain(tally) : new EntityBased(source, tally);
        level.explode(source, Explosion.getDefaultDamageSource(level, source), calculator,
            at.x, at.y, at.z, blast.power(), blast.fire(), blast.interaction());
        String summary = String.format(Locale.ROOT,
            "power %.1f%s%s at %.1f %.1f %.1f by %s: %d of %d blocks the blast would have taken were allowed (%d solid, %d air); %s",
            blast.power(), blast.breaksBlocks() ? ",blocks" : "", blast.fire() ? ",fire" : "", at.x, at.y, at.z,
            source == null ? "no entity" : EntityType.getKey(source.getType()).toString(),
            tally.allowedSolid + tally.allowedAir, tally.seen.size(), tally.allowedSolid, tally.allowedAir, only);
        lastFiltered = summary;
        LOGGER.info("Filtered blast: {}", summary);
    }

    /**
     * The summary of the last blast a filter narrowed on this server, for {@code /blastguard status}; null if
     * none has been since start-up.
     */
    public static @Nullable String lastFiltered() {
        return lastFiltered;
    }

    /**
     * Counts, once per position, what the blast would have taken without the filters and how much of it the
     * filters let through. It sits behind vanilla's own {@code shouldBlockExplode}, so a position reaches it
     * only if the unfiltered blast would have taken it; the filters' verdict for a position does not change
     * within one blast, so the first answer is the one counted. Air counts too: an allowed air position is
     * where the blast may start a fire, which is how a blast whose filters allow no solid block at all still
     * leaves fire behind.
     */
    private static final class Tally implements BiPredicate<BlockPos, BlockState> {
        private final BiPredicate<BlockPos, BlockState> only;
        private final LongOpenHashSet seen = new LongOpenHashSet();
        private int allowedSolid;
        private int allowedAir;

        Tally(BiPredicate<BlockPos, BlockState> only) {
            this.only = only;
        }

        @Override
        public boolean test(BlockPos pos, BlockState state) {
            boolean allowed = only.test(pos, state);
            if (seen.add(pos.asLong()) && allowed) {
                if (state.isAir()) {
                    allowedAir++;
                } else {
                    allowedSolid++;
                }
            }
            return allowed;
        }
    }

    /** Vanilla's default calculator, as used for a blast with no source entity, narrowed. */
    private static final class Plain extends ExplosionDamageCalculator {
        private final BiPredicate<BlockPos, BlockState> only;

        Plain(BiPredicate<BlockPos, BlockState> only) {
            this.only = only;
        }

        @Override
        public boolean shouldBlockExplode(Explosion explosion, BlockGetter level, BlockPos pos, BlockState state, float power) {
            return super.shouldBlockExplode(explosion, level, pos, state, power) && only.test(pos, state);
        }
    }

    /** Vanilla's entity-based calculator, as used for a blast with a source entity, narrowed. */
    private static final class EntityBased extends EntityBasedExplosionDamageCalculator {
        private final BiPredicate<BlockPos, BlockState> only;

        EntityBased(Entity source, BiPredicate<BlockPos, BlockState> only) {
            super(source);
            this.only = only;
        }

        @Override
        public boolean shouldBlockExplode(Explosion explosion, BlockGetter level, BlockPos pos, BlockState state, float power) {
            return super.shouldBlockExplode(explosion, level, pos, state, power) && only.test(pos, state);
        }
    }
}
