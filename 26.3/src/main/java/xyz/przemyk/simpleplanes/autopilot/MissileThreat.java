package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.airdefence.AircraftRoster;
import xyz.przemyk.simpleplanes.airdefence.Engagements;
import xyz.przemyk.simpleplanes.airdefence.Interceptor;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.missile.MissileEntity;
import xyz.przemyk.simpleplanes.missile.MissileTracker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * One air-defence missile inbound on one aircraft, as a missile approach warner sees it: where it is, how fast
 * the range is closing, the time to impact and how much motor it has left. Read-only: nothing here touches the
 * missile or its guidance. Shared by {@link MissileEvasion} (autopilot fighters) and {@link MissileWarning}
 * (pilots). See {@code design/FIGHTER-EVASION.md}.
 *
 * @param range     nose to the aircraft's box centre, blocks
 * @param closing   rate the range is shrinking, blocks per tick; negative when opening
 * @param tti       range over closing speed, ticks; {@link Double#POSITIVE_INFINITY} when not closing
 * @param remaining motor path still to fly before the missile gives up, blocks (a lower bound)
 * @param speed     the missile's speed now, blocks per tick
 * @param planeSpeed the aircraft's speed now, blocks per tick
 */
public record MissileThreat(MissileEntity missile, int tier, Vec3 at, double range, double closing, double tti,
                            double remaining, double speed, double planeSpeed) {

    /** Range at which a launch is seen. Above every tier's detection radius (T4: 350). */
    public static final double WARNING_RANGE = 400.0;

    /**
     * AD missiles in flight whose engagement claim is on {@code plane}, nearest impact first. A missile still in
     * its tube is not seen yet: the warner keys on the motor plume above the ground.
     */
    public static List<MissileThreat> inbound(PlaneEntity plane) {
        if (!(plane.level() instanceof ServerLevel level)) {
            return List.of();
        }
        List<MissileThreat> out = new ArrayList<>(2);
        UUID self = plane.getUUID();
        long now = level.getGameTime();
        Vec3 centre = AircraftRoster.aimPoint(plane);
        for (MissileEntity m : MissileTracker.active()) {
            Interceptor interceptor = m.interceptor();
            if (interceptor == null || m.isFinished() || m.isRemoved() || m.level() != level
                || m.phase() == MissileEntity.Phase.TUBE) {
                continue;
            }
            if (!self.equals(Engagements.targetOf(new Engagements.MissileEngager(m.getId()), now))) {
                continue;
            }
            Vec3 nose = m.nose();
            Vec3 los = centre.subtract(nose);
            double range = los.length();
            if (range > WARNING_RANGE) {
                continue;
            }
            Vec3 rel = m.direction().scale(m.speed()).subtract(plane.getDeltaMovement());
            double closing = range < 1.0E-6 ? 0.0 : rel.dot(los) / range;
            double tti = closing > 0.05 ? range / closing : Double.POSITIVE_INFINITY;
            double remaining = Math.max(0.0, interceptor.spec.range - m.pathLength());
            out.add(new MissileThreat(m, m.tier().tier, nose, range, closing, tti, remaining, m.speed(),
                plane.getDeltaMovement().length()));
        }
        out.sort(Comparator.comparingDouble(MissileThreat::tti).thenComparingDouble(MissileThreat::range));
        return out;
    }

    /**
     * False once the motor path left cannot reach the aircraft even if it turned straight at the missile, when
     * the two would meet after the missile flew {@code range * speed / (speed + planeSpeed)}.
     */
    public boolean canReach() {
        double meet = range * speed / Math.max(speed + planeSpeed, 1.0E-3);
        return remaining + interceptorFuse() >= meet;
    }

    private double interceptorFuse() {
        Interceptor interceptor = missile.interceptor();
        return interceptor == null ? 0.0 : interceptor.spec.fuseRadius;
    }

    /** Horizontal direction the missile is coming from, as a Minecraft yaw seen from {@code plane}. */
    public double bearingFrom(PlaneEntity plane) {
        return AutopilotMath.headingTo(plane.position(), at);
    }

    /** Clock position of the missile relative to the nose, 12 dead ahead, 6 behind. */
    public int clock(PlaneEntity plane) {
        double rel = AutopilotMath.angleDelta(plane.getYRot(), bearingFrom(plane));
        int hour = Mth.floor((rel + 360.0 + 15.0) / 30.0) % 12;
        return hour == 0 ? 12 : hour;
    }
}
