package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EntityBasedExplosionDamageCalculator;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.api.BlastBlockFilters;

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
 */
final class FilteredExplosion {

    private FilteredExplosion() {}

    static void explode(ServerLevel level, @Nullable Entity source, Vec3 at, Blast blast) {
        BiPredicate<BlockPos, BlockState> only = BlastBlockFilters.resolve(level, source, at, blast);
        if (only == null) {
            level.explode(source, at.x, at.y, at.z, blast.power(), blast.fire(), blast.interaction());
            return;
        }
        ExplosionDamageCalculator calculator = source == null ? new Plain(only) : new EntityBased(source, only);
        level.explode(source, Explosion.getDefaultDamageSource(level, source), calculator,
            at.x, at.y, at.z, blast.power(), blast.fire(), blast.interaction());
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
