package xyz.przemyk.simpleplanes.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.autopilot.Blast;

import java.util.function.BiPredicate;

/**
 * Narrows which blocks a blast may break, where a {@link BlastGuard} can only say whether it breaks any.
 *
 * <p>A guard decides what the blast <em>is</em>: its power, whether it breaks blocks at all, whether it
 * starts fires, whether it happens. That is all-or-nothing on the world side. A block filter is the
 * finer tool: "this blast goes off exactly as ordered, but it may break only the blocks for which this
 * predicate holds". A claim mod can use it to let a blast crater land outside a claim and leave the
 * inside alone, or to let it wreck buildings and spare the ground they stand on.
 *
 * <h2>Two stages, so the per-block part stays cheap</h2>
 * {@link #blockFilter} is called <b>once per detonation</b>, with the same arguments a guard gets and
 * after every guard has had its say. That is where the expensive work belongs: finding the claim the
 * blast is in, collecting the boxes that may break, deciding whether the pilot is exempt. It returns
 * either {@code null} — abstain, this filter has no opinion about this blast — or a predicate.
 *
 * <p>The predicate is then asked about <b>each block the blast would otherwise break</b>, possibly tens
 * of thousands of times for a large blast, on the server thread, inside vanilla's ray casting. It must
 * be a few comparisons and a lookup or two, never a world query that can load a chunk.
 *
 * <h2>Exactly what the predicate controls</h2>
 * The blast is applied through {@code Level#explode} with an {@code ExplosionDamageCalculator} whose
 * {@code shouldBlockExplode} is vanilla's answer <em>and</em> the predicate. Vanilla then uses the one
 * filtered list of positions for both block damage and fire (read in 26.3's {@code ServerExplosion}:
 * {@code interactWithBlocks} and {@code createFire} both take the list {@code calculateExplodedPositions}
 * built), so:
 * <ul>
 *   <li>a block the predicate rejects is not broken and does not drop;</li>
 *   <li>a fire can only start at a position the predicate accepted — an air block is asked about like any
 *       other, so a filter that rejects a position also keeps fire out of it;</li>
 *   <li>a rejected block still absorbs the blast as it always did: its explosion resistance is subtracted
 *       from the ray passing through it, so the blast does not reach further because something was
 *       spared;</li>
 *   <li>entity damage and knockback are untouched. They are the guard's business, not this one's.</li>
 * </ul>
 *
 * <h2>When it is not asked at all</h2>
 * A piercing blast, and a blast that neither breaks blocks nor starts fires, has nothing a filter could
 * narrow, so no filter is consulted for it. Nor is any filter consulted while {@code /blastguard off} is
 * in force: off means the explosion is exactly what the aircraft ordered.
 *
 * <p>Like {@link BlastGuard}, this names no mod but this one, takes and returns only vanilla types, the
 * JDK's {@link BiPredicate} and this mod's own {@link Blast}, and can therefore be implemented reflectively
 * (a {@code java.lang.reflect.Proxy} returning a plain lambda) by a mod that does not compile against
 * this one.
 *
 * <h2>Saying what it decided</h2>
 * Every blast a filter narrows leaves one {@code Filtered blast:} INFO line in the log, repeated by
 * {@code /blastguard status}: how many of the blocks the blast would have taken were allowed, solid and air
 * apart, followed by each filter's predicate's {@code toString}. A predicate that overrides it with what the
 * filter resolved for this blast (whose ground, which areas it spares) turns that line into a diagnosis; one
 * that does not is named by its filter's class.
 *
 * @see BlastBlockFilters
 * @see BlastGuard
 */
@FunctionalInterface
public interface BlastBlockFilter {

    /**
     * Decides, once per detonation, which blocks this blast may break.
     *
     * <p>Called on the server thread immediately before {@code Level#explode}, after the guards. Must not
     * throw; a filter that throws is logged and treated as having abstained, and so is a predicate that
     * throws (for the rest of that blast).
     *
     * @param level  the server level the blast is about to happen in, never {@code null}.
     * @param source the aircraft or missile, or {@code null} if the blast has no entity behind it.
     * @param at     the centre of the blast, never {@code null}.
     * @param blast  the blast as the guards left it, never {@code null}, never piercing.
     * @return {@code null} to abstain, or a predicate that is {@code true} for a block the blast may break.
     *         Several filters combine with a logical AND: a block breaks only if every predicate allows it.
     */
    @Nullable BiPredicate<BlockPos, BlockState> blockFilter(ServerLevel level, @Nullable Entity source, Vec3 at, Blast blast);
}
