package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.misc.MathUtil;

/**
 * Geometry helpers shared by the autopilot, the runway survey tool and the route wand.
 *
 * <p><b>Angle conventions.</b> Everything internal uses Minecraft yaw (degrees, 0 = +Z / south,
 * increasing clockwise seen from above, i.e. increasing yaw is a right turn). This matches
 * {@link MathUtil#getYaw(Vec3)} and {@link net.minecraft.world.entity.Entity#getYRot()}.
 * Compass headings (0 = north) are only produced for display, by {@link #compassHeading(double)}.
 */
public final class AutopilotMath {

    private AutopilotMath() {}

    /** Minecraft yaw, in degrees, of the horizontal direction from {@code from} to {@code to}. */
    public static double headingTo(Vec3 from, Vec3 to) {
        return MathUtil.getYaw(new Vec3(to.x - from.x, 0, to.z - from.z));
    }

    /** Point at {@code distance} blocks from {@code origin} along Minecraft yaw {@code heading}. */
    public static Vec3 pointAlong(Vec3 origin, double heading, double distance) {
        Vec3 dir = MathUtil.rotationToVector(heading, 0);
        return new Vec3(origin.x + dir.x * distance, origin.y, origin.z + dir.z * distance);
    }

    /** Signed smallest difference {@code target - current}, in (-180, 180]. */
    public static double angleDelta(double current, double target) {
        return Mth.wrapDegrees(target - current);
    }

    /** Horizontal distance between two points. */
    public static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Aviation compass heading (0 = north, 90 = east) for a Minecraft yaw. */
    public static double compassHeading(double minecraftYaw) {
        double compass = (minecraftYaw + 180.0) % 360.0;
        return compass < 0 ? compass + 360.0 : compass;
    }

    /** Runway designator ("09", "27", "36") for a Minecraft yaw. */
    public static String designator(double minecraftYaw) {
        int tens = (int) Math.round(compassHeading(minecraftYaw) / 10.0);
        if (tens <= 0 || tens > 36) {
            tens = tens <= 0 ? 36 : tens - 36;
        }
        return String.format("%02d", tens);
    }

    /**
     * Signed lateral offset of {@code point} from the line through {@code origin} running along
     * {@code heading}. Positive means the point is to the right of the centreline.
     */
    public static double lateralOffset(Vec3 origin, double heading, Vec3 point) {
        // "Right" is heading + 90 degrees, because increasing Minecraft yaw is a right turn.
        Vec3 right = MathUtil.rotationToVector(heading + 90.0, 0);
        double dx = point.x - origin.x;
        double dz = point.z - origin.z;
        return dx * right.x + dz * right.z;
    }

    /**
     * Distance of {@code point} measured along {@code heading} from {@code origin}. Negative means
     * the point is behind the origin.
     */
    public static double alongTrack(Vec3 origin, double heading, Vec3 point) {
        Vec3 dir = MathUtil.rotationToVector(heading, 0);
        return (point.x - origin.x) * dir.x + (point.z - origin.z) * dir.z;
    }

    /**
     * Minimum-time bang-bang command for a double integrator: the angular "stopping distance" at the
     * current rate is subtracted from the error, so the controller starts braking early instead of
     * overshooting and oscillating. Returns -1, 0 or +1.
     *
     * @param error     remaining angle to the target, degrees
     * @param rate      current angular rate, degrees per tick
     * @param accel     angular acceleration the control surface produces, degrees per tick squared
     * @param deadband  error below which no input is given
     */
    public static byte bangBang(double error, double rate, double accel, double deadband) {
        double stoppingDistance = rate * Math.abs(rate) / (2.0 * Math.max(accel, 1.0E-4));
        double command = error - stoppingDistance;
        if (command > deadband) {
            return 1;
        }
        if (command < -deadband) {
            return -1;
        }
        return 0;
    }
}
