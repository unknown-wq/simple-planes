package xyz.przemyk.simpleplanes.autopilot;

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
     * Altitude of the {@value AutopilotConfig#GLIDE_SLOPE_DEGREES}-degree glide slope at a given
     * distance before the threshold.
     */
    public double glideSlopeAltitude(double distanceToThreshold) {
        return threshold.y + Math.tan(Math.toRadians(AutopilotConfig.GLIDE_SLOPE_DEGREES)) * Math.max(distanceToThreshold, 0);
    }

    /** Aiming point a little way down the runway, which is what the aircraft actually flies at. */
    public Vec3 aimPoint() {
        return AutopilotMath.pointAlong(threshold, landingHeading(), AutopilotConfig.TOUCHDOWN_AIM_OFFSET);
    }

    public RunwayEnd opposite() {
        return new RunwayEnd(airfield, farEnd, threshold);
    }
}
