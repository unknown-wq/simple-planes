package xyz.przemyk.simpleplanes.entities.crane;

/**
 * Rope, winch and pendulum of the crane. Plain Java, no Minecraft imports.
 * One rigid-rope pendulum per horizontal axis; small-angle coupling between the axes is ignored.
 */
public final class SlungLoad {

    public static final double L_MIN = 1.0;
    public static final double L_MAX = 12.0;
    public static final double L_PICK = 4.0;
    public static final double L_CARRY = 3.0;
    public static final double WINCH_RATE = 0.15;
    public static final double WINCH_Y = 0.30;
    public static final double DAMPING = 0.002;
    /** Damping of the empty hook, which has no mass to carry a swing. */
    public static final double HOOK_DAMPING = 0.05;
    public static final double MIN_MASS = 0.05;
    /** Share of the thrust ceiling a steady hover may use; the rest is kept for control. */
    public static final double HOVER_FRACTION = 0.9;
    /** Ground assist: the thrust ceiling grows by up to this share as the load's bottom nears the ground. */
    public static final double ASSIST_MAX = 0.8;
    /** Load-bottom height above ground at which the assist has faded to nothing, blocks. */
    public static final double ASSIST_HEIGHT = 2.0;
    /** Rope angle limit; beyond it a real rope would go slack, which is not modelled. */
    public static final double MAX_ANGLE = Math.toRadians(80);

    /**
     * true: the reaction is the spec's split formula, -(m/M)(G cos + L w^2) sin, which pushes the drone
     * away from the load. false (default): the exact rigid-rope two-body solution, which pulls it toward
     * the load. Sim compares both.
     */
    public static boolean splitModel = false;

    public double length = L_MIN;
    public double targetLength = L_MIN;
    /** Rope angles from the downward vertical, radians: x-y plane and z-y plane. */
    public double thetaX;
    public double thetaZ;
    public double omegaX;
    public double omegaZ;
    /** Load mass, 0 when nothing is on the hook. */
    public double mass;

    public boolean loaded() {
        return mass > 0;
    }

    /** Winches toward {@link #targetLength}; returns true once there. */
    public boolean winch() {
        double t = Math.max(L_MIN, Math.min(L_MAX, targetLength));
        double d = t - length;
        if (Math.abs(d) <= WINCH_RATE) {
            length = t;
            return true;
        }
        length += Math.signum(d) * WINCH_RATE;
        return false;
    }

    /** What the rope does to the drone this tick, as the extra acceleration for {@link MultirotorPhysics#step}. */
    public double[] reaction(MultirotorPhysics drone, double[] out) {
        out[0] = out[1] = out[2] = 0;
        if (!loaded()) {
            return out;
        }
        double[] up = drone.up(new double[3]);
        double[] drag = drone.drag(new double[3]);
        out[0] = axisReaction(drone, up[0], drag[0], thetaX, omegaX);
        out[2] = axisReaction(drone, up[2], drag[2], thetaZ, omegaZ);
        return out;
    }

    private double axisReaction(MultirotorPhysics drone, double up, double drag, double theta, double omega) {
        double mTotal = drone.mass;
        double s = Math.sin(theta);
        double pull = mass * (MultirotorPhysics.G * Math.cos(theta) + length * omega * omega) * s;
        if (splitModel) {
            return -pull / mTotal;
        }
        double own = drone.thrust * up + mTotal * drag;
        double exact = (own + pull) / (mTotal - mass + mass * s * s);
        return exact - own / mTotal;
    }

    /** Integrates the swing with the drone's acceleration of this tick as the pivot acceleration. */
    public void swing(MultirotorPhysics drone) {
        double gl = MultirotorPhysics.G / length;
        double c = loaded() ? DAMPING : HOOK_DAMPING;
        omegaX += -gl * Math.sin(thetaX) - drone.a[0] / length * Math.cos(thetaX) - c * omegaX;
        omegaZ += -gl * Math.sin(thetaZ) - drone.a[2] / length * Math.cos(thetaZ) - c * omegaZ;
        thetaX += omegaX;
        thetaZ += omegaZ;
        if (Math.abs(thetaX) > MAX_ANGLE) {
            thetaX = Math.copySign(MAX_ANGLE, thetaX);
            omegaX = 0;
        }
        if (Math.abs(thetaZ) > MAX_ANGLE) {
            thetaZ = Math.copySign(MAX_ANGLE, thetaZ);
            omegaZ = 0;
        }
    }

    /** Hook (load box top) relative to the drone's origin, written into out. */
    public double[] hookOffset(double[] out) {
        return hookOffset(length, thetaX, thetaZ, out);
    }

    public static double[] hookOffset(double length, double thetaX, double thetaZ, double[] out) {
        out[0] = length * Math.sin(thetaX);
        out[1] = WINCH_Y - length * Math.cos(Math.max(Math.abs(thetaX), Math.abs(thetaZ)));
        out[2] = length * Math.sin(thetaZ);
        return out;
    }

    /** Load velocity relative to the drone, horizontal axis 0 (x) or 2 (z). */
    public double relativeVelocity(int axis) {
        return axis == 0 ? length * omegaX * Math.cos(thetaX) : length * omegaZ * Math.cos(thetaZ);
    }

    /** Rope angle from vertical, degrees. */
    public double swingDegrees() {
        return Math.toDegrees(Math.max(Math.abs(thetaX), Math.abs(thetaZ)));
    }

    public void stopSwing() {
        thetaX = thetaZ = omegaX = omegaZ = 0;
    }

    /** Mass in drone masses of a w x w x h box at the given density (1 = the reference density). */
    public static double massOf(double width, double height, double density) {
        return Math.max(MIN_MASS, width * width * height * density);
    }

    /** Thrust-ceiling multiplier for a load whose bottom is loadAgl blocks above the ground. */
    public static double assist(double loadAgl) {
        return 1.0 + ASSIST_MAX * Math.max(0.0, Math.min(1.0, 1.0 - loadAgl / ASSIST_HEIGHT));
    }

    /** Largest load (drone masses) hovered in free air within {@link #HOVER_FRACTION} of tMax. */
    public static double capacity(double tMax) {
        return HOVER_FRACTION * tMax / MultirotorPhysics.G - 1.0;
    }

    /** Largest load that can leave the ground at all (full assist). */
    public static double liftLimit(double tMax) {
        return HOVER_FRACTION * tMax * (1.0 + ASSIST_MAX) / MultirotorPhysics.G - 1.0;
    }

    /** Highest load-bottom height above ground at which the load still hovers; infinite within capacity, negative over the lift limit. */
    public static double ceiling(double mass, double tMax) {
        double need = (1.0 + mass) * MultirotorPhysics.G / (HOVER_FRACTION * tMax);
        if (need <= 1.0) {
            return Double.POSITIVE_INFINITY;
        }
        return ASSIST_HEIGHT * (1.0 - (need - 1.0) / ASSIST_MAX);
    }
}
