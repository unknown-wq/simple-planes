package xyz.przemyk.simpleplanes.entities;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.crane.MultirotorPhysics;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;

/**
 * Small FPV quadcopter carrying its charge in the clamp. A separate airframe from the quadcopter
 * crane, which stays peaceful: this one shares only the rigid-multirotor model
 * ({@link MultirotorPhysics}) with its own thrust and drag, and replaces every fixed-wing flight
 * hook with it.
 *
 * <p>It flies its own attack run: {@link #steer} is called by {@code PlaneAutopilot#tickStrike}
 * each tick with the aim point, and the velocity it asks for is turned into a tilt and a thrust
 * here. Cruise at the run-in height towards the target, then, once the target is
 * {@link #DIVE_ANGLE} below, straight at it.
 */
public class FpvDroneEntity extends DroneEntity {

    /** Thrust ceiling: four times its weight. */
    public static final double T_MAX = 4.0 * MultirotorPhysics.G;
    /**
     * Much less drag than the crane, vertical drag above all: the crane's would hold a fall to 0.8
     * blocks/tick and no dive could be faster than that.
     */
    public static final double DRAG_H_LIN = 0.01;
    public static final double DRAG_H_QUAD = 0.012;
    public static final double DRAG_V = 0.012;
    public static final double TILT_MAX = 75.0;
    public static final double CRUISE_SPEED = 2.0;
    public static final double DIVE_SPEED = 2.6;
    /** Elevation of the target below the drone at which it stops cruising and dives. */
    public static final double DIVE_ANGLE = 40.0;
    /** Velocity error to commanded acceleration, per tick, and the ceiling on that acceleration. */
    public static final double K_VEL = 0.25;
    public static final double A_MAX = 0.12;
    /** Altitude hold on the cruise: height error to climb rate, and its ceiling. */
    public static final double K_ALT = 0.05;
    public static final double VZ_MAX = 0.6;
    public static final float ROTATION_SPEED_MULTIPLIER = 2.0F;

    private final MultirotorPhysics phys = new MultirotorPhysics();
    private final double[] scratch = new double[3];
    private boolean physInitialised;
    private boolean diving;
    /** Velocity the last {@link #steer} asked for; null means hold still. */
    private @Nullable Vec3 wanted;

    public FpvDroneEntity(EntityType<? extends FpvDroneEntity> entityType, Level level) {
        super(entityType, level);
        phys.tMax = T_MAX;
        phys.dragHLin = DRAG_H_LIN;
        phys.dragHQuad = DRAG_H_QUAD;
        phys.dragV = DRAG_V;
    }

    /** The attack run's guidance, for this tick: cruise at {@code cruiseAltitude}, then dive onto {@code aim}. */
    public void steer(Vec3 aim, double cruiseAltitude) {
        Vec3 pos = position();
        double dx = aim.x - pos.x;
        double dz = aim.z - pos.z;
        double horizontal = Math.max(Math.hypot(dx, dz), 1.0E-3);
        // Latched: the dive loses the height that made the angle, and must not level off again.
        diving |= Math.toDegrees(Math.atan2(pos.y - aim.y, horizontal)) >= DIVE_ANGLE;
        if (diving) {
            wanted = aim.subtract(pos).normalize().scale(DIVE_SPEED);
        } else {
            double climb = Mth.clamp(K_ALT * (cruiseAltitude - pos.y), -VZ_MAX, VZ_MAX);
            wanted = new Vec3(dx / horizontal * CRUISE_SPEED, climb, dz / horizontal * CRUISE_SPEED);
        }
    }

    public boolean isDiving() {
        return diving;
    }

    @Override
    protected void tickMotion(TempMotionVars tempMotionVars) {
        if (!physInitialised) {
            phys.yaw = getYRot();
            physInitialised = true;
        }
        Vec3 v = getDeltaMovement();
        phys.v[0] = v.x;
        phys.v[1] = v.y;
        phys.v[2] = v.z;

        if (isPowered() && getHealth() > 0) {
            control(wanted == null ? Vec3.ZERO : wanted);
        } else {
            phys.attitude(0, 0, phys.yaw);
            phys.thrust = 0;
        }
        phys.step(null);

        setDeltaMovement(phys.v[0], phys.v[1], phys.v[2]);
        setYRot((float) phys.yaw);
        setXRot((float) phys.pitch);
        // MultirotorPhysics rolls right-down positive; rotationRoll is left-down positive.
        rotationRoll = (float) -phys.roll;
    }

    /**
     * Velocity loop to a thrust vector, then to attitude and thrust, as {@code CraneController} does:
     * the thrust has to supply the commanded acceleration, cancel the drag and hold the weight.
     */
    private void control(Vec3 want) {
        double ax = K_VEL * (want.x - phys.v[0]);
        double ay = K_VEL * (want.y - phys.v[1]);
        double az = K_VEL * (want.z - phys.v[2]);
        double a = Math.sqrt(ax * ax + ay * ay + az * az);
        if (a > A_MAX) {
            ax *= A_MAX / a;
            ay *= A_MAX / a;
            az *= A_MAX / a;
        }
        double[] drag = phys.drag(scratch);
        double tx = ax - drag[0];
        double ty = ay - drag[1] + MultirotorPhysics.G;
        double tz = az - drag[2];

        double th = Math.hypot(tx, tz);
        double tilt = Math.min(Math.atan2(th, Math.max(ty, 0.0)), Math.toRadians(TILT_MAX));
        double ps = Math.toRadians(phys.yaw);
        double dF = 0;
        double dR = 0;
        if (th > 1e-9) {
            dF = (-tx * Math.sin(ps) + tz * Math.cos(ps)) / th;
            dR = (-tx * Math.cos(ps) - tz * Math.sin(ps)) / th;
        }
        double st = Math.sin(tilt);
        double pitchCmd = Math.toDegrees(Math.atan2(st * dF, Math.cos(tilt)));
        double rollCmd = Math.toDegrees(Math.asin(Mth.clamp(st * dR, -1.0, 1.0)));
        // Nose where it is going; a hover keeps its heading.
        double yawCmd = Math.hypot(want.x, want.z) > 0.1
            ? Math.toDegrees(Math.atan2(-want.x, want.z)) : phys.yaw;
        phys.attitude(pitchCmd, rollCmd, yawCmd);

        double[] up = phys.up(scratch);
        phys.thrust = Mth.clamp(tx * up[0] + ty * up[1] + tz * up[2], 0.0, phys.tMax);
    }

    // The fixed-wing hooks do not apply: attitude and motion both come from tickMotion.
    @Override
    protected Quaternionf tickRotateMotion(TempMotionVars tempMotionVars, Quaternionf q, Vec3 motion) {
        return q;
    }

    @Override
    protected boolean tickOnGround(TempMotionVars tempMotionVars) {
        return false;
    }

    @Override
    protected void tickPitch(TempMotionVars tempMotionVars) {}

    @Override
    protected void tickYaw() {}

    @Override
    protected void tickRoll(TempMotionVars tempMotionVars) {}

    @Override
    protected float getRotationSpeedMultiplier() {
        return ROTATION_SPEED_MULTIPLIER;
    }

    @Override
    protected float getGroundPitch() {
        return 0;
    }

    @Override
    protected Item getItem() {
        return SimplePlanesItems.FPV_DRONE_ITEM.get();
    }
}
