package xyz.przemyk.simpleplanes.entities.crane;

/**
 * Cascaded position/velocity controller of the crane, run every tick before the physics.
 * Plain Java, no Minecraft imports. Outputs attitude and thrust commands in the drone's frame.
 */
public final class CraneController {

    public static final double K_POS = 0.05;
    public static final double V_MAX = 0.8;
    public static final double K_VEL = 0.15;
    public static final double A_MAX = 0.018;
    public static final double A_MAX_LOADED = 0.010;
    public static final double K_ALT = 0.05;
    public static final double VZ_MAX = 0.30;
    public static final double K_VZ = 0.2;
    public static final double SWING_GAIN = -0.4;
    public static final double SWING_FULL_MASS = 0.65;
    /** Floor of the handling factor (share of climb rate and acceleration left at no thrust margin). */
    public static final double HANDLING_MIN = 0.15;
    public static final double YAW_SPEED = 0.15;

    /** Swing-damping gain at full coupling; negative accelerates against the load's relative velocity. */
    public double swingGain = SWING_GAIN;
    /** Horizontal speed limit; lowered by the state machine for fine positioning. */
    public double vMax = V_MAX;
    /** Vertical speed limit. */
    public double vzMax = VZ_MAX;

    public double pitchCmd;
    public double rollCmd;
    public double yawCmd = Double.NaN;
    public double thrustCmd;
    public double tiltCmd;
    /** 1 empty; falls with the thrust margin a load leaves (see {@link #handling}). */
    public double handling = 1.0;
    public boolean saturated;
    public final double[] accelCmd = new double[3];

    /** Computes the commands for this tick and applies them: attitude through the lag, thrust directly. */
    public void control(MultirotorPhysics drone, SlungLoad rope, double tx, double ty, double tz) {
        double G = MultirotorPhysics.G;
        boolean carrying = rope.loaded();
        handling = carrying ? handling(drone) : 1.0;
        double aMax = carrying ? A_MAX_LOADED * Math.min(1.0, rope.length / 4.0) * handling : A_MAX;
        double ks = carrying ? swingGain * Math.min(1.0, rope.mass / SWING_FULL_MASS) : 0.0;
        double vH = vMax * (0.5 + 0.5 * handling);

        double vxCmd = MultirotorPhysics.clamp(K_POS * (tx - drone.p[0]), vH);
        double vzCmdH = MultirotorPhysics.clamp(K_POS * (tz - drone.p[2]), vH);
        double hs = Math.hypot(vxCmd, vzCmdH);
        if (hs > vH) {
            vxCmd *= vH / hs;
            vzCmdH *= vH / hs;
        }
        vxCmd += ks * rope.relativeVelocity(0);
        vzCmdH += ks * rope.relativeVelocity(2);
        double ax = MultirotorPhysics.clamp(K_VEL * (vxCmd - drone.v[0]), aMax);
        double az = MultirotorPhysics.clamp(K_VEL * (vzCmdH - drone.v[2]), aMax);
        double ah = Math.hypot(ax, az);
        if (ah > aMax) {
            ax *= aMax / ah;
            az *= aMax / ah;
            ah = aMax;
        }

        double vyCmd = Math.max(-vzMax, Math.min(vzMax * handling, K_ALT * (ty - drone.p[1])));
        double ay = K_VZ * (vyCmd - drone.v[1]);
        accelCmd[0] = ax;
        accelCmd[1] = ay;
        accelCmd[2] = az;

        double tilt = Math.min(Math.atan2(ah, G + ay), Math.toRadians(MultirotorPhysics.TILT_MAX));
        tiltCmd = Math.toDegrees(tilt);
        double ps = Math.toRadians(drone.yaw);
        double dF = 0, dR = 0;
        if (ah > 1e-9) {
            dF = (-ax * Math.sin(ps) + az * Math.cos(ps)) / ah;
            dR = (-ax * Math.cos(ps) - az * Math.sin(ps)) / ah;
        }
        double st = Math.sin(tilt);
        pitchCmd = Math.toDegrees(Math.atan2(st * dF, Math.cos(tilt)));
        rollCmd = Math.toDegrees(Math.asin(MultirotorPhysics.clamp(st * dR, 1.0)));

        double vh = Math.hypot(drone.v[0], drone.v[2]);
        if (vh > YAW_SPEED) {
            yawCmd = Math.toDegrees(Math.atan2(-drone.v[0], drone.v[2]));
        } else if (Double.isNaN(yawCmd)) {
            yawCmd = drone.yaw;
        }

        drone.attitude(pitchCmd, rollCmd, yawCmd);
        double cosTilt = Math.cos(Math.toRadians(drone.tilt()));
        double t = drone.mass * (G + ay) / cosTilt;
        saturated = t >= drone.tMax;
        thrustCmd = Math.max(0.0, Math.min(drone.tMax, t));
        drone.thrust = thrustCmd;
    }

    /**
     * Thrust margin left by the load at this tick's ceiling, relative to the empty drone's: 1 for the empty
     * drone, falling linearly to {@link #HANDLING_MIN} as the hover thrust approaches the ceiling. Scales the
     * climb rate, the horizontal acceleration and (by half) the horizontal speed.
     */
    public static double handling(MultirotorPhysics drone) {
        double margin = 1.0 - drone.mass * MultirotorPhysics.G / drone.tMax;
        double empty = 1.0 - MultirotorPhysics.G / MultirotorPhysics.T_MAX;
        return Math.max(HANDLING_MIN, Math.min(1.0, margin / empty));
    }

    /** Hold the current heading until the drone moves fast enough to face its travel. */
    public void resetYaw(MultirotorPhysics drone) {
        yawCmd = drone.yaw;
    }
}
