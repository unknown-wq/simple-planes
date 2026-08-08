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

    /**
     * Whole-degree compass heading for a readout. Rounding {@link #compassHeading} on its own prints
     * north as 360, which is not a heading anyone writes.
     */
    public static int compassDisplay(double minecraftYaw) {
        return (int) Math.round(compassHeading(minecraftYaw)) % 360;
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

    // ------------------------------------------------------------------ deceleration schedule

    /*
     * How far it takes to slow down, and therefore when to start.
     *
     * A fast cruise is only useful if the aircraft can still land at the end of it, and the approach
     * is tuned around arriving at APPROACH_SPEED. Clamping the commanded speed at the moment the mode
     * changes does not achieve that: the aircraft is still doing cruise speed when the glide slope
     * starts, so it floats down the slope high and fast and either goes around or arrives too hot to
     * flare. The energy has to be shed *before* the descent, over however many blocks the drag curve
     * actually needs.
     *
     * This is the same shape as the strike's dive point, which is derived from the height still to be
     * lost rather than being a fixed distance. Here the derivation is from the speed still to be lost.
     *
     * The model is PlaneEntity#tickMotion exactly:
     *
     *     speed -= (dragQuad*v^2 + dragMul*v + drag) * brakesMul
     *
     * with the coefficients below and brakesMul = 5, which is what throttle 0 gives — idle is an
     * airbrake in this flight model, not neutral. Integrating that forward from a speed until it
     * reaches the target speed gives both the distance needed and, read backwards, the speed the
     * aircraft is allowed to be doing at a given distance out. Measured against the table: 2.80 b/t
     * down to 0.50 b/t takes 158 blocks and 124 ticks.
     */

    /** {@code TempMotionVars} drag coefficients, copied from {@code PlaneEntity.TempMotionVars}. */
    private static final double DRAG_QUAD = 0.001;
    private static final double DRAG_MUL = 0.0005;
    private static final double DRAG = 0.001;
    /** {@code brakesMul} at throttle 0 — the whole drag polynomial is multiplied by this. */
    private static final double BRAKES_MULTIPLIER = 5.0;

    /** Speed the table is built down to and up from; covers the whole flyable range. */
    private static final double TABLE_MIN_SPEED = 0.05;
    private static final double TABLE_MAX_SPEED = 3.20;
    private static final double TABLE_STEP = 0.01;
    private static final int TABLE_SIZE = (int) Math.round((TABLE_MAX_SPEED - TABLE_MIN_SPEED) / TABLE_STEP) + 1;

    /**
     * {@code BRAKING_DISTANCE[i]} is the distance travelled while decelerating from
     * {@code TABLE_MIN_SPEED} up to speed {@code i} — i.e. a cumulative curve. The distance between
     * any two speeds is one subtraction, and the inverse ("what speed may I be doing this far out")
     * is one binary search. Built once, so nothing here allocates or iterates per tick.
     */
    private static final double[] BRAKING_DISTANCE = buildBrakingTable();

    private static double[] buildBrakingTable() {
        double[] table = new double[TABLE_SIZE];
        table[0] = 0;
        // Integrate the deceleration between adjacent table speeds. dv is fixed by the table, so the
        // distance for that step is v * dt with dt = dv / decel(v) — no simulation loop needed.
        for (int i = 1; i < TABLE_SIZE; i++) {
            double speed = TABLE_MIN_SPEED + i * TABLE_STEP;
            double decelPerTick = decelerationPerTick(speed);
            double ticks = TABLE_STEP / decelPerTick;
            table[i] = table[i - 1] + speed * ticks;
        }
        return table;
    }

    /** Speed lost in one tick at throttle 0, from the drag polynomial. */
    public static double decelerationPerTick(double speed) {
        return (DRAG_QUAD * speed * speed + DRAG_MUL * speed + DRAG) * BRAKES_MULTIPLIER;
    }

    private static double brakingDistanceFromRest(double speed) {
        double clamped = Mth.clamp(speed, TABLE_MIN_SPEED, TABLE_MAX_SPEED);
        double exact = (clamped - TABLE_MIN_SPEED) / TABLE_STEP;
        int index = (int) exact;
        if (index >= TABLE_SIZE - 1) {
            return BRAKING_DISTANCE[TABLE_SIZE - 1];
        }
        double fraction = exact - index;
        return Mth.lerp(fraction, BRAKING_DISTANCE[index], BRAKING_DISTANCE[index + 1]);
    }

    /**
     * Ground distance needed to decelerate from {@code from} to {@code to} with the throttle closed.
     *
     * @return 0 when the aircraft is already at or below the target speed
     */
    public static double decelerationDistance(double from, double to) {
        if (from <= to) {
            return 0;
        }
        return Math.max(0, brakingDistanceFromRest(from) - brakingDistanceFromRest(to));
    }

    /**
     * The speed to command right now so that closing the throttle arrives at {@code to} exactly
     * {@code distance} blocks from here — the inverse of {@link #decelerationDistance}.
     *
     * <p>Self-correcting in the same way the strike's dive is: an aircraft that is behind the
     * deceleration profile is asked for a lower speed the closer it gets, rather than being cut to
     * the final number in one step. The result is clamped into {@code [to, cruise]}, so it is a
     * no-op for the whole part of the leg that is far enough out.
     */
    public static double speedSchedule(double cruise, double to, double distance) {
        if (distance <= 0) {
            return to;
        }
        double budget = brakingDistanceFromRest(to) + distance;
        // Binary search the cumulative curve for the speed whose braking distance fits the budget.
        int low = 0;
        int high = TABLE_SIZE - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (BRAKING_DISTANCE[mid] <= budget) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        double speed = TABLE_MIN_SPEED + low * TABLE_STEP;
        return Mth.clamp(speed, to, cruise);
    }
}
