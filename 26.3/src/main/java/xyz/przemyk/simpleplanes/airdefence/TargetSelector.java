package xyz.przemyk.simpleplanes.airdefence;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.missile.MissileTier;
import xyz.przemyk.simpleplanes.missile.SiloStructure;

/**
 * Target choice for an air-defence silo: the nearest hostile {@link PlaneEntity} within the tier's detection
 * radius of the silo mouth that is not already engaged by {@link InterceptorSpec#MAX_PER_TARGET} others.
 * Players, mobs, friendly aircraft and anything that is not a {@code PlaneEntity} are never candidates.
 */
public final class TargetSelector {

    private TargetSelector() {}

    public static @Nullable PlaneEntity select(ServerLevel level, BlockPos silo, MissileTier tier, Engagements.@Nullable Engager self) {
        InterceptorSpec spec = InterceptorSpec.of(tier);
        Vec3 mouth = SiloStructure.mouth(silo, tier);
        long now = level.getGameTime();
        return AircraftRoster.nearestHostile(level, mouth, spec.detectionRadius(),
            p -> !Engagements.saturated(p.getUUID(), now, self));
    }

    /** Re-target for a missile in flight whose aircraft is gone: nearest free hostile within its seeker radius. */
    public static @Nullable PlaneEntity reacquire(ServerLevel level, Vec3 from, InterceptorSpec spec, Engagements.Engager self) {
        long now = level.getGameTime();
        return AircraftRoster.nearestHostile(level, from, spec.detectionRadius() * InterceptorSpec.RETARGET_FRACTION,
            p -> !Engagements.saturated(p.getUUID(), now, self));
    }
}
