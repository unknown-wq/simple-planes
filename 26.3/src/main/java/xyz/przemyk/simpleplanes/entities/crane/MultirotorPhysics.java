package xyz.przemyk.simpleplanes.entities.crane;

/**
 * Rigid multirotor under a thrust vector. Plain Java, no Minecraft imports.
 * Units: blocks, ticks, degrees; mass in drone masses (empty drone = 1).
 * Yaw is Minecraft's yRot; pitch is nose down positive, roll right down positive.
 */
public final class MultirotorPhysics {

    public static final double G = 0.04;
    public static final double T_MAX = 8.0 * G;
    public static final double TILT_MAX = 25.0;
    public static final double TILT_RATE = 4.0;
    public static final double TILT_TAU = 2.0;
    public static final double YAW_RATE_MAX = 6.0;
    public static final double YAW_ACCEL = 1.0;
    public static final double DRAG_H_LIN = 0.02;
    public static final double DRAG_H_QUAD = 0.01;
    public static final double DRAG_V = 0.05;

    public final double[] p = new double[3];
    public final double[] v = new double[3];
    /** Acceleration applied on the last {@link #step}. */
    public final double[] a = new double[3];
    public double yaw;
    public double yawRate;
    public double pitch;
    public double roll;
    public double thrust;
    public double mass = 1.0;
    /** Rated thrust ceiling; {@link #T_MAX} except under a test override. */
    public double tMaxBase = T_MAX;
    /** Thrust ceiling this tick: {@link #tMaxBase} times the ground assist of a low load (set by the owner). */
    public double tMax = T_MAX;
    /** Drag coefficients; the crane's constants unless an airframe sets its own. */
    public double dragHLin = DRAG_H_LIN;
    public double dragHQuad = DRAG_H_QUAD;
    public double dragV = DRAG_V;

    /** Attitude lag toward the commanded tilt, and the rate-limited yaw toward a heading. */
    public void attitude(double pitchCmd, double rollCmd, double yawCmd) {
        pitch += clamp((pitchCmd - pitch) / TILT_TAU, TILT_RATE);
        roll += clamp((rollCmd - roll) / TILT_TAU, TILT_RATE);
        double err = wrapDegrees(yawCmd - yaw);
        double want = Math.signum(err) * Math.min(YAW_RATE_MAX, Math.sqrt(2.0 * YAW_ACCEL * Math.abs(err)));
        yawRate += clamp(want - yawRate, YAW_ACCEL);
        if (Math.abs(err) < 0.05 && Math.abs(yawRate) <= YAW_ACCEL) {
            yawRate = 0;
            yaw = yawCmd;
        } else {
            yaw += yawRate;
        }
        yaw = wrapDegrees(yaw);
    }

    /** Body up axis in world coordinates; the same axis as {@code HelicopterEntity.rotorAxis} with xRot = -pitch, roll = -roll. */
    public double[] up(double[] out) {
        double ps = Math.toRadians(yaw);
        double th = Math.toRadians(pitch);
        double ph = Math.toRadians(roll);
        double sy = Math.sin(ps), cy = Math.cos(ps);
        double st = Math.sin(th), ct = Math.cos(th);
        double sp = Math.sin(ph), cp = Math.cos(ph);
        out[0] = -sp * cy - cp * st * sy;
        out[1] = cp * ct;
        out[2] = -sp * sy + cp * st * cy;
        return out;
    }

    /** Current tilt of the rotor axis from vertical, degrees. */
    public double tilt() {
        return Math.toDegrees(Math.acos(Math.min(1.0, Math.cos(Math.toRadians(pitch)) * Math.cos(Math.toRadians(roll)))));
    }

    /** Drag as an acceleration, written into out. */
    public double[] drag(double[] out) {
        double vh = Math.sqrt(v[0] * v[0] + v[2] * v[2]);
        double k = dragHLin + dragHQuad * vh;
        out[0] = -k * v[0];
        out[1] = -dragV * v[1];
        out[2] = -k * v[2];
        return out;
    }

    private final double[] upScratch = new double[3];
    private final double[] dragScratch = new double[3];

    /** One tick: v += T/m * up + drag + aExtra - G; p += v. aExtra may be null. */
    public void step(double[] aExtra) {
        up(upScratch);
        drag(dragScratch);
        for (int i = 0; i < 3; i++) {
            a[i] = thrust / mass * upScratch[i] + dragScratch[i] + (aExtra == null ? 0 : aExtra[i]);
        }
        a[1] -= G;
        for (int i = 0; i < 3; i++) {
            v[i] += a[i];
            p[i] += v[i];
        }
    }

    static double clamp(double x, double limit) {
        return Math.max(-limit, Math.min(limit, x));
    }

    static double wrapDegrees(double d) {
        d %= 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }
}
