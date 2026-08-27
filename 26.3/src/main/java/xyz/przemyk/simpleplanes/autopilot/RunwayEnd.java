package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * One landing direction of an {@link Airfield}. Landing on this end means crossing {@code threshold}
 * and rolling out towards {@code farEnd}, so the landing heading points from the threshold to the
 * far end and the approach is flown from beyond the threshold.
 */
public record RunwayEnd(Airfield airfield, Vec3 threshold, Vec3 farEnd) {

    /** Minecraft yaw the aircraft must be on at touchdown. */
    public double landingHeading() {
        return AutopilotMath.headingTo(threshold, farEnd);
    }

    /** Runway designator of this landing direction, e.g. "09". */
    public String designator() {
        return AutopilotMath.designator(landingHeading());
    }

    public double length() {
        return AutopilotMath.horizontalDistance(threshold, farEnd);
    }

    /** Threshold crossing height. */
    public double elevation() {
        return threshold.y;
    }

    /**
     * A point on the extended runway centreline, {@code distance} blocks before the threshold, at
     * {@code height} blocks above the threshold elevation.
     */
    public Vec3 approachPoint(double distance, double height) {
        Vec3 back = AutopilotMath.pointAlong(threshold, landingHeading() + 180.0, distance);
        return new Vec3(back.x, threshold.y + height, back.z);
    }

    /**
     * How far down this runway the touchdown is aimed, in blocks from the threshold. Derived from
     * the runway's own length — see {@link AutopilotConfig#touchdownAimOffset}.
     */
    public double aimOffset() {
        return AutopilotConfig.touchdownAimOffset(length());
    }

    /**
     * Elevation of the runway surface at the aim point, which is the datum the whole arrival is
     * flown to.
     *
     * <p>Interpolated rather than taken from the threshold, because a surveyed runway is allowed to
     * slope and the aim point is now far enough in for that to matter: 40 blocks along a 5-degree
     * strip is 3.5 blocks of elevation, which is most of the {@link AutopilotConfig#FLARE_HEIGHT}
     * the flare is triggered on.
     */
    public double touchdownElevation() {
        double fraction = Mth.clamp(aimOffset() / Math.max(length(), 1.0E-3), 0.0, 1.0);
        return Mth.lerp(fraction, threshold.y, farEnd.y);
    }

    /**
     * Altitude of the {@value AutopilotConfig#GLIDE_SLOPE_DEGREES}-degree glide slope at a given
     * distance before the threshold.
     *
     * <p>The slope ends on the <em>aim point</em>, not on the threshold. That is the whole
     * difference between a runway that is aimed at and one that is merely crossed: with the bottom
     * of the slope on the threshold, the commanded altitude at the threshold was the threshold's own
     * elevation, so the aircraft arrived there in ground effect and put the wheels down on the first
     * few blocks of the strip whatever {@code TOUCHDOWN_AIM_OFFSET} claimed. Moving the endpoint
     * {@link #aimOffset()} blocks down the runway moves the touchdown by the same amount and lifts
     * the whole approach by {@code tan(8 deg) * aimOffset} — measured on the rig as a threshold
     * crossing height of 0.3 blocks before and 6.9 after, on the same 183-block field, with the
     * touchdown moving from 1.4 blocks in to 41.8.
     *
     * <p>Flat past the aim point rather than continuing down, so an aircraft that is still airborne
     * there is not commanded into the runway.
     */
    public double glideSlopeAltitude(double distanceToThreshold) {
        double toAim = distanceToThreshold + aimOffset();
        return touchdownElevation()
            + Math.tan(Math.toRadians(AutopilotConfig.GLIDE_SLOPE_DEGREES)) * Math.max(toAim, 0);
    }

    /** Aiming point down the runway, which is what the aircraft actually flies at. */
    public Vec3 aimPoint() {
        Vec3 along = AutopilotMath.pointAlong(threshold, landingHeading(), aimOffset());
        return new Vec3(along.x, touchdownElevation(), along.z);
    }

    public RunwayEnd opposite() {
        return new RunwayEnd(airfield, farEnd, threshold);
    }
}
