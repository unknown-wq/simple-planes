package xyz.przemyk.simpleplanes.entities;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import xyz.przemyk.simpleplanes.misc.MathUtil;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;
import xyz.przemyk.simpleplanes.setup.SimplePlanesConfig;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;

/**
 * The helicopter flight model.
 *
 * <p>Full derivation, the measured numbers and the reasoning behind every constant are in
 * {@code 26.2/HELICOPTER-PHYSICS.md}. This header is the short version and the control contract.
 *
 * <h2>The model in one paragraph</h2>
 * A helicopter has exactly one force generator: a rotor disc rigidly attached to the fuselage,
 * producing a thrust along the airframe's own "up" axis. Everything the aircraft does comes from
 * two numbers — how hard the rotor pushes (<b>collective</b>, the throttle) and which way the
 * airframe is pointing (<b>cyclic</b>, the pitch and roll attitude) — plus a tail rotor that yaws
 * the airframe on the spot (<b>pedals</b>). There is no wing, no lift term, no angle of attack and
 * no stall speed: {@link #tickRotateMotion} does not add lift and {@link #tickMotion} replaces the
 * fixed-wing drag polynomial with an anisotropic one, because a rotor disc is a very different
 * object edge-on and face-on.
 *
 * <h2>Control surface</h2>
 * Everything a controller needs is synchronised entity data, so the server, the flying client and
 * every observer read the same inputs, and a server-side flight director can set them directly.
 *
 * <table border="1">
 *   <caption>Per-tick control inputs</caption>
 *   <tr><th>control</th><th>setter</th><th>range</th><th>meaning</th></tr>
 *   <tr><td>collective trim</td><td>{@link #setThrottle(int)}</td><td>0..5, 0..10 with a booster</td>
 *       <td>rotor thrust = {@value #COLLECTIVE_PER_NOTCH} per notch; {@link #HOVER_THROTTLE} holds altitude</td></tr>
 *   <tr><td>collective boost</td><td>{@link #setCollectiveBoost(boolean)}</td><td>boolean</td>
 *       <td>momentary +{@value #COLLECTIVE_BOOST_NOTCHES} notches; the space bar</td></tr>
 *   <tr><td>longitudinal cyclic</td><td>{@link #setCyclicForward(int)}</td><td>-100 .. +100 percent</td>
 *       <td>+100 tips the disc {@value #MAX_CYCLIC} deg nose down and the aircraft accelerates forward</td></tr>
 *   <tr><td>lateral cyclic</td><td>{@link #setCyclicRight(int)}</td><td>-100 .. +100 percent</td>
 *       <td>+100 banks {@value #MAX_CYCLIC} deg right; below {@link #TURN_COORDINATION_SPEED} that is a
 *           sideways slide, above it a turn</td></tr>
 *   <tr><td>pedals</td><td>{@link #setPedal(int)}</td><td>-1 / 0 / +1</td>
 *       <td>yaw command, up to {@value #MAX_YAW_RATE} deg/tick, independent of airspeed</td></tr>
 * </table>
 *
 * <p>All five are latching: set them when they change, not every tick, exactly like a held key.
 * {@link #setPedal(int)} is an alias for the inherited {@code setYawRight}, and
 * {@link #setCollectiveBoost(boolean)} for {@code setMoveUp}, so the existing packets keep working.
 *
 * <p>The cyclic is <b>proportional</b> and the pedal is <b>a sign</b>, which is not an oversight:
 * the cyclic is a position command (stick position sets disc tilt sets cruise speed) while the
 * pedal is a rate command on an integrator, the same double-integrator shape the fixed-wing rudder
 * has. A controller regulates speed by choosing a cyclic value and holding it, and regulates
 * heading by pulsing the pedal.
 *
 * <p><b>{@code setPitchUp} does nothing on a helicopter.</b> The fixed-wing elevator input is a
 * rate command with the opposite sign convention (+1 = nose <i>up</i>), and reusing it for the
 * cyclic would have made "forward" mean opposite things on the two airframes. It is left untouched
 * and unread; use {@link #setCyclicForward(int)}.
 */
public class HelicopterEntity extends LargeAirframeEntity {

    // ------------------------------------------------------------------
    // Synched control inputs
    // ------------------------------------------------------------------

    /** Collective boost — the space bar. Momentary, adds {@link #COLLECTIVE_BOOST_NOTCHES}. */
    public static final EntityDataAccessor<Boolean> MOVE_UP = SynchedEntityData.defineId(HelicopterEntity.class, EntityDataSerializers.BOOLEAN);
    /** Longitudinal cyclic, percent: -100 full back, 0 neutral, +100 full forward. */
    public static final EntityDataAccessor<Byte> CYCLIC_FORWARD = SynchedEntityData.defineId(HelicopterEntity.class, EntityDataSerializers.BYTE);
    /** Lateral cyclic, percent: -100 full left, 0 neutral, +100 full right. */
    public static final EntityDataAccessor<Byte> CYCLIC_RIGHT = SynchedEntityData.defineId(HelicopterEntity.class, EntityDataSerializers.BYTE);

    /** Full cyclic deflection, in the percent units {@link #setCyclicForward(int)} takes. */
    public static final int CYCLIC_FULL = 100;

    // ------------------------------------------------------------------
    // Tuning. Every quantity is per tick; multiply speeds by 20 for blocks/second.
    // The whole derivation, and the measurements that fixed these values, are in
    // 26.2/HELICOPTER-PHYSICS.md. Do not change one without re-reading the ladder tables there:
    // the hover throttle, the climb rates and the top speed are all solved from this set together.
    // ------------------------------------------------------------------

    /** Rotor thrust added per throttle notch, in blocks/tick^2. */
    public static final double COLLECTIVE_PER_NOTCH = 0.010;
    /**
     * The throttle notch at which rotor thrust exactly cancels gravity when level. Solved, not
     * chosen: {@code 3 * 0.010 == 0.030 == -gravity}. Notches 1-2 descend, 4-5 climb.
     */
    public static final int HOVER_THROTTLE = 3;
    /** Notches the collective boost (space) adds while held. */
    public static final int COLLECTIVE_BOOST_NOTCHES = 2;
    /**
     * Climb rate at which axial inflow through the disc has eaten all the rotor's thrust. This is
     * what bounds the climb rate instead of a hard velocity clamp: thrust is scaled by
     * {@code 1 - vy / ROTOR_INFLOW_LIMIT} while going up, so every collective setting has its own
     * equilibrium climb rate and the boosted airframe does not simply accelerate upward for ever.
     */
    public static final double ROTOR_INFLOW_LIMIT = 2.0;

    /** Maximum disc tilt, degrees, in both axes. The whole translational envelope follows from it. */
    public static final double MAX_CYCLIC = 25.0;
    /** How fast the disc tilts toward the commanded attitude, degrees/tick (= 40 deg/s). */
    public static final double MAX_CYCLIC_RATE = 2.0;

    /** Pedal authority, degrees/tick (= 60 deg/s). Does not depend on airspeed — this is a tail rotor. */
    public static final float MAX_YAW_RATE = 3.0f;
    /** Pedal ramp, degrees/tick^2. Same double-integrator shape the fixed-wing rudder has. */
    public static final float YAW_RAMP = 0.5f;

    /**
     * Bank-to-turn gain, degrees/tick of commanded yaw at full bank and at or above
     * {@link #TURN_COORDINATION_SPEED}.
     *
     * <p>Not the realised rate: {@link #applyYaw} runs a small feedback loop against the cyclic's
     * attitude hold whose closed-loop gain was <b>measured at 1.55</b> rather than predicted, so
     * 1.30 here is 2.0 deg/tick of realised heading change... and in practice 0.85 at full bank,
     * because the bank itself is 25 degrees, not 90. See HELICOPTER-PHYSICS.md, "Roll".
     */
    public static final double TURN_FROM_BANK = 2.60;
    /** Horizontal speed at which a bank is a fully coordinated turn rather than a sideways slide. */
    public static final double TURN_COORDINATION_SPEED = 0.80;

    /** Maximum fraction of the heading error the velocity vector is dragged through per tick. */
    public static final double VELOCITY_ALIGN_RATE = 0.10;
    /** Below this horizontal speed the fuselage does not weathervane at all — free hover translation. */
    public static final double ALIGN_MIN_SPEED = 0.30;
    /** At and above this horizontal speed the weathervane works at {@link #VELOCITY_ALIGN_RATE}. */
    public static final double ALIGN_FULL_SPEED = 0.80;

    /** Horizontal drag polynomial: fuselage + disc edge-on. Sets the top speed. */
    public static final double H_DRAG_QUAD = 0.009;
    public static final double H_DRAG_LIN = 0.0025;
    public static final double H_DRAG_CONST = 0.0002;
    /** Vertical drag: the rotor disc face-on, which is enormous. Sets every descent rate. */
    public static final double V_DRAG_QUAD = 0.045;
    public static final double V_DRAG_LIN = 0.050;

    /**
     * Hard ceiling on total speed, blocks/tick. A backstop, not a design value: every speed the
     * aircraft actually flies is a thrust/drag equilibrium well under it, and if a measurement ever
     * lands on this number exactly, something else is wrong. The first tuning pass set it to 1.60
     * and the boosted dash sat on it at 1.5999 — which reads as a top speed and is a clamp.
     */
    public static final double MAX_SPEED = 2.00;

    /** Fraction of horizontal speed shed per tick while the skids are on the ground. */
    public static final double GROUND_FRICTION = 0.25;

    /** A dead rotor stops autorotating: the vertical drag it provides collapses to this fraction. */
    public static final double DEAD_DISC_DRAG = 0.35;
    /** Uncontrolled yaw rate of a helicopter that has lost its tail rotor, degrees/tick. */
    public static final float DEAD_SPIN_RATE = 10.0f;
    /** Attitude a dead helicopter falls into, degrees. */
    public static final double DEAD_PITCH = -8.0;
    public static final double DEAD_ROLL = 35.0;

    public HelicopterEntity(EntityType<? extends HelicopterEntity> entityTypeIn, Level worldIn) {
        super(entityTypeIn, worldIn);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder pBuilder) {
        super.defineSynchedData(pBuilder);
        pBuilder.define(MOVE_UP, false);
        pBuilder.define(CYCLIC_FORWARD, (byte) 0);
        pBuilder.define(CYCLIC_RIGHT, (byte) 0);
    }

    // ------------------------------------------------------------------
    // Control surface
    // ------------------------------------------------------------------

    /** @param up true while the collective boost (space) is held. */
    public void setMoveUp(boolean up) {
        entityData.set(MOVE_UP, up);
    }

    /** Alias of {@link #setMoveUp(boolean)} under the name the flight model uses. */
    public void setCollectiveBoost(boolean boost) {
        setMoveUp(boost);
    }

    public boolean getCollectiveBoost() {
        return entityData.get(MOVE_UP);
    }

    /**
     * Longitudinal cyclic. <b>Proportional</b>, unlike the pedal: it is a position command, so the
     * value is the fraction of {@link #MAX_CYCLIC} the disc is tipped to and therefore, through the
     * drag curve, the speed the aircraft settles at. A controller regulating cruise speed sets this
     * and leaves it; it does not need to modulate it.
     *
     * @param forwardPercent -100 full aft (accelerate backwards) .. 0 level .. +100 full forward
     */
    public void setCyclicForward(int forwardPercent) {
        entityData.set(CYCLIC_FORWARD, (byte) Mth.clamp(forwardPercent, -CYCLIC_FULL, CYCLIC_FULL));
    }

    /** @return the longitudinal cyclic, percent of {@link #MAX_CYCLIC}. */
    public int getCyclicForward() {
        return entityData.get(CYCLIC_FORWARD);
    }

    /**
     * Lateral cyclic, proportional in the same units as {@link #setCyclicForward(int)}.
     *
     * @param rightPercent -100 full left bank .. 0 wings level .. +100 full right bank
     */
    public void setCyclicRight(int rightPercent) {
        entityData.set(CYCLIC_RIGHT, (byte) Mth.clamp(rightPercent, -CYCLIC_FULL, CYCLIC_FULL));
    }

    /** @return the lateral cyclic, percent of {@link #MAX_CYCLIC}. */
    public int getCyclicRight() {
        return entityData.get(CYCLIC_RIGHT);
    }

    /**
     * Pedal. <b>A sign, not a proportion</b>, and deliberately so: this is a rate command on an
     * integrator with a {@link #YAW_RAMP} ramp, exactly the shape {@code PlaneEntity#tickYaw} has,
     * so a heading controller written for the fixed-wing rudder — bang-bang with an angular
     * stopping-distance term, {@code AutopilotMath.bangBang} — transfers to this airframe unchanged.
     * Making it proportional would silently break that controller's braking model.
     *
     * <p>Stored in the inherited {@code YAW_RIGHT}, so the existing {@code YawPacket} drives it.
     *
     * @param right -1 yaw left, 0 hold, +1 yaw right
     */
    public void setPedal(int right) {
        setYawRight((byte) Mth.clamp(right, -1, 1));
    }

    /** @return -1, 0 or +1. */
    public byte getPedal() {
        return getYawRight();
    }

    /** Total collective in notches, boost included. Clamped at 0 so a negative throttle cannot invert the rotor. */
    public int getCollectiveNotches() {
        return Math.max(0, getThrottle() + (getCollectiveBoost() ? COLLECTIVE_BOOST_NOTCHES : 0));
    }

    /** Vertical speed, blocks/tick, positive up. The number a climb/descent controller regulates. */
    public double getVerticalSpeed() {
        return getDeltaMovement().y;
    }

    /** Horizontal speed, blocks/tick. The number a cruise controller regulates — never the total. */
    public double getHorizontalSpeed() {
        Vec3 m = getDeltaMovement();
        return Math.sqrt(m.x * m.x + m.z * m.z);
    }

    /**
     * Component of the horizontal velocity along the nose, blocks/tick. Negative flying backwards.
     * Not the same as {@link #getHorizontalSpeed()} for this airframe, which can fly sideways.
     */
    public double forwardSpeed() {
        Vec3 m = getDeltaMovement();
        double yaw = Math.toRadians(getYRot());
        return m.z * Math.cos(yaw) - m.x * Math.sin(yaw);
    }

    /**
     * Only reachable if a controller left a stale input on a helicopter it stopped flying, and the
     * one thing that would keep it flying away by itself. Called on dismount by the base class.
     */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity livingEntity) {
        Vec3 result = super.getDismountLocationForPassenger(livingEntity);
        if (getPassengers().isEmpty()) {
            setCyclicForward(0);
            setCyclicRight(0);
            setMoveUp(false);
        }
        return result;
    }

    /**
     * The control state is saved and restored, which {@code PlaneEntity} deliberately does not do.
     *
     * <p>For a plane the throttle is thrust and a plane reloaded at idle glides down and lands. For
     * a helicopter the throttle <em>is</em> the collective, so an airframe that came back from a
     * save at notch 0 would drop out of the sky at {@code 8.6} blocks/s through no fault of whoever
     * parked it. Persisting the collective is the only answer that keeps a hovering helicopter
     * hovering across a chunk unload.
     *
     * <p>It also makes the airframe drivable from the console — {@code /summon} with a {@code
     * throttle} tag, {@code /data merge entity} to move a control — which is what every measurement
     * in {@code HELICOPTER-PHYSICS.md} was taken with, since there is no fixed-wing autopilot that
     * understands this aircraft.
     */
    @Override
    protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("throttle", getThrottle());
        output.putBoolean("collective_boost", getCollectiveBoost());
        output.putInt("cyclic_forward", getCyclicForward());
        output.putInt("cyclic_right", getCyclicRight());
        output.putByte("pedal", getPedal());
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input) {
        super.readAdditionalSaveData(input);
        setThrottle(input.getIntOr("throttle", getThrottle()));
        setMoveUp(input.getBooleanOr("collective_boost", getCollectiveBoost()));
        setCyclicForward(input.getIntOr("cyclic_forward", getCyclicForward()));
        setCyclicRight(input.getIntOr("cyclic_right", getCyclicRight()));
        setPedal(input.getByteOr("pedal", getPedal()));
    }

    /** The five control keys {@link #addAdditionalSaveData} writes. */
    private static final String[] CONTROL_KEYS =
        {"throttle", "collective_boost", "cyclic_forward", "cyclic_right", "pedal"};

    /**
     * The item form of a helicopter is a machine on a shelf, and a machine on a shelf has its
     * controls centred.
     *
     * <p>{@link PlaneEntity#getItemStack()} builds the item's payload by calling
     * {@link #addAdditionalSaveData}, which is also what persists the collective across a save so a
     * hovering helicopter comes back hovering. Those two want opposite things from the same data:
     * folding a machine down with the collective at 5 and setting it back up handed the owner an
     * airframe that climbed away by itself the moment it had power. The world save keeps the
     * controls; the item does not.
     *
     * <p>This also settles the ordering in
     * {@link PlaneEntity#getDismountLocationForPassenger}, which takes the item on the Folding
     * upgrade's path before it centres the controls.
     */
    @Override
    public ItemStack getItemStack() {
        ItemStack itemStack = super.getItemStack();
        CompoundTag compound = itemStack.get(SimplePlanesComponents.ENTITY_TAG.get());
        if (compound != null) {
            CompoundTag parked = compound.copy();
            for (String key : CONTROL_KEYS) {
                parked.remove(key);
            }
            itemStack.set(SimplePlanesComponents.ENTITY_TAG.get(), parked);
        }
        return itemStack;
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    /**
     * The airframe's own "up" axis, in world coordinates, as a unit vector. This is the direction
     * the rotor pushes and it is the whole flight model: collective sets its length, cyclic sets
     * its direction.
     *
     * <p>Built from {@code getXRot()} / {@code rotationRoll} / {@code getYRot()} rather than from
     * {@code transformPos}, for two reasons. The first is the one {@link PlaneCollisions#upY} and
     * {@code PlaneEntity#transformPosPhysics} already document: {@code transformPos} rotates by
     * {@code Q_Client}, which on a server with nobody aboard is never written and is stale for the
     * entity's whole life. The second is timing — the quaternion is only rebuilt from the euler
     * angles at the <em>end</em> of {@code tick()}, so any body-frame vector taken from it during
     * the tick lags the attitude the cyclic just commanded by one tick. Reading the angles directly
     * removes that lag, which matters here in a way it does not for a plane: for a plane the thrust
     * is a small correction to a wing that is doing the work, and for a helicopter it is everything.
     *
     * <p>Derived by pushing {@code (0, 1, 0)} through the same
     * {@code Ry(-yaw) * Rx(-pitch) * Rz(-roll)} composition {@code transformPos} uses, so it agrees
     * with the renderer and with the seat positions exactly. Its {@code y} component is
     * {@code cos(pitch) * cos(roll)}, i.e. {@link PlaneCollisions#upY}.
     */
    public Vector3f rotorAxis(Vector3f dest) {
        double yaw = Math.toRadians(getYRot());
        double pitch = Math.toRadians(getXRot());
        double roll = Math.toRadians(rotationRoll);
        double sy = Math.sin(yaw), cy = Math.cos(yaw);
        double sp = Math.sin(pitch), cp = Math.cos(pitch);
        double sr = Math.sin(roll), cr = Math.cos(roll);
        return dest.set(
            (float) (sr * cy + cr * sp * sy),
            (float) (cr * cp),
            (float) (sr * sy - cr * sp * cy));
    }

    // ------------------------------------------------------------------
    // The tick. PlaneEntity.tick() calls, in this order:
    //   tickRotateMotion -> tickOnGround -> tickPitch -> tickYaw -> tickMotion -> tickRoll
    // The helicopter overrides all six, so not one line of the fixed-wing flight model runs.
    // tickPitch carries the whole attitude update (pitch and roll together) because it is the last
    // hook before tickMotion, and the thrust must be built from the attitude commanded this tick.
    // tickRoll is consequently empty.
    // ------------------------------------------------------------------

    /**
     * Turn coordination, and the only thing this hook does — there is no wing to add lift with, so
     * the quaternion comes back untouched.
     *
     * <p>A helicopter with the disc banked over accelerates sideways; that is correct in a hover
     * and useless in cruise, where a bank is supposed to produce a <em>turn</em>. The difference
     * between the two is the fuselage and the tail fin, which weathervane in proportion to airspeed.
     * So the horizontal velocity vector is dragged toward the nose at a rate that is zero below
     * {@link #ALIGN_MIN_SPEED} and {@link #VELOCITY_ALIGN_RATE} above {@link #ALIGN_FULL_SPEED}:
     * below 6 blocks/s the helicopter translates freely in any direction, above 16 it flies where
     * it points. The rotation preserves the speed exactly; nothing here adds or removes energy.
     *
     * <p>{@code lerpAngle180} rather than {@code lerpAngle}, so deliberate rearwards flight is a
     * stable equilibrium instead of flipping through 180 degrees.
     */
    @Override
    protected Quaternionf tickRotateMotion(TempMotionVars tempMotionVars, Quaternionf q, Vec3 motion) {
        double vh = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        if (vh < 1.0E-4 || getHealth() <= 0) {
            return q;
        }
        double rate = VELOCITY_ALIGN_RATE * Mth.clamp(
            (vh - ALIGN_MIN_SPEED) / (ALIGN_FULL_SPEED - ALIGN_MIN_SPEED), 0.0, 1.0);
        if (rate <= 0) {
            return q;
        }
        double newYaw = MathUtil.lerpAngle180(rate, MathUtil.getYaw(motion), getYRot());
        double rad = Math.toRadians(newYaw);
        setDeltaMovement(-Math.sin(rad) * vh, motion.y, Math.cos(rad) * vh);
        return q;
    }

    /**
     * Ground handling: skid friction and the repair timer. Returns {@code true} so that the
     * attitude update in {@link #tickPitch} still runs — it is what levels the disc while the
     * aircraft is sitting on its skids.
     */
    @Override
    protected boolean tickOnGround(TempMotionVars tempMotionVars) {
        if (getDeltaMovement().lengthSqr() < 0.01 && getOnGround()) {
            notMovingTime += 1;
        } else {
            notMovingTime = 0;
        }
        if (notMovingTime > 200 && getHealth() < getMaxHealth() && getPlayer() != null) {
            setHealth(getHealth() + 1);
            notMovingTime = 100;
        }

        refreshGroundContact();

        // Skids, not wheels: a helicopter on the ground does not roll anywhere. Gated on real
        // contact rather than on getOnGround(), whose coyote timer stays true for four ticks after
        // lift-off — see PHYSICS-AUDIT.md B6 for the same trap on the fixed-wing side.
        if (onGround() || isOnWater()) {
            Vec3 m = getDeltaMovement();
            double keep = 1.0 - GROUND_FRICTION;
            setDeltaMovement(m.x * keep, m.y, m.z * keep);
        }
        return true;
    }

    /**
     * The attitude update: both cyclic axes, rate-limited to {@link #MAX_CYCLIC_RATE}.
     *
     * <p>Cyclic is a <em>position</em> command, not a rate command. Holding the stick forward buys a
     * fixed disc tilt and therefore a fixed acceleration, and releasing it returns the disc to level
     * — which is what makes the aircraft settle at a speed instead of accelerating for ever, and
     * what makes "how fast am I going" a function of where the stick is rather than of how long it
     * has been there. The fixed-wing elevator is the opposite (a rate command on an integrator) and
     * that is the single biggest handling difference between the two airframes.
     *
     * <p>Sign conventions, which are not obvious and are worth stating once: this model uses
     * {@code PlaneEntity}'s internal convention where <b>positive {@code xRot} is nose up</b> (the
     * inverse of vanilla's) and <b>positive {@code rotationRoll} is banked left</b>. So a forward
     * cyclic command becomes a negative pitch target and a right cyclic command a negative roll
     * target.
     */
    @Override
    protected void tickPitch(TempMotionVars tempMotionVars) {
        if (getHealth() <= 0) {
            setXRot((float) approach(getXRot(), DEAD_PITCH, MAX_CYCLIC_RATE));
            rotationRoll = (float) approach(rotationRoll, getId() % 2 == 0 ? DEAD_ROLL : -DEAD_ROLL, MAX_CYCLIC_RATE);
            return;
        }

        boolean grounded = onGround();
        double targetPitch = grounded ? 0 : -MAX_CYCLIC * getCyclicForward() / CYCLIC_FULL;
        double targetRoll = grounded ? 0 : -MAX_CYCLIC * getCyclicRight() / CYCLIC_FULL;
        float newPitch = (float) approach(getXRot(), targetPitch, MAX_CYCLIC_RATE);
        pitchDelta = newPitch - getXRot();
        setXRot(newPitch);
        rotationRoll = (float) approach(rotationRoll, targetRoll, MAX_CYCLIC_RATE);
    }

    /** This tick's elevator-axis change, needed by {@link #tickYaw()} — see {@link #applyYaw}. */
    private float pitchDelta;

    /** Move {@code current} toward {@code target} by at most {@code rate}, on the shortest arc. */
    private static double approach(double current, double target, double rate) {
        return current + Mth.clamp(Mth.wrapDegrees(target - current), -rate, rate);
    }

    /**
     * Pedals, plus the bank-to-turn coupling.
     *
     * <p>The pedal is a tail rotor: it works at a standstill and it works at cruise, at the same
     * {@link #MAX_YAW_RATE}, which is the single most visible way a helicopter is not a plane. The
     * ramp keeps the same double-integrator shape as {@code PlaneEntity#tickYaw} so a controller
     * written against the fixed-wing rudder (a bang-bang with an angular-stopping-distance term)
     * transfers unchanged; only the numbers differ.
     *
     * <p>On top of that, a bank turns the aircraft, scaled by how much airspeed there is to turn
     * with. Both effects add, so a coordinated turn is stick and pedal exactly as it is in a real
     * helicopter, and the aircraft still pirouettes on the spot with the stick centred.
     */
    @Override
    protected void tickYaw() {
        if (getHealth() <= 0) {
            float spin = getId() % 2 == 0 ? DEAD_SPIN_RATE : -DEAD_SPIN_RATE;
            yawSpeed += (spin - yawSpeed) * 0.1f;
            setYRot(getYRot() + yawSpeed);
            return;
        }
        applyYaw(commandedYawRate());
    }

    /** The yaw rate the controls are asking for this tick, degrees/tick in the world frame. */
    private float commandedYawRate() {

        byte pedal = getPedal();
        if (pedal > 0) {
            yawSpeed += YAW_RAMP;
        } else if (pedal < 0) {
            yawSpeed -= YAW_RAMP;
        } else if (yawSpeed > 0) {
            yawSpeed = Math.max(0, yawSpeed - YAW_RAMP);
        } else if (yawSpeed < 0) {
            yawSpeed = Math.min(0, yawSpeed + YAW_RAMP);
        }
        yawSpeed = Mth.clamp(yawSpeed, -MAX_YAW_RATE, MAX_YAW_RATE);

        float yaw = yawSpeed;
        if (!onGround()) {
            // Gated on the FORWARD component of the velocity, not on the horizontal speed. A
            // coordinated turn is a thing you do in forward flight; a helicopter banked over in a
            // hover slides sideways and keeps pointing where it was pointing. With the gate on total
            // horizontal speed a hover sidestep reached 0.5 b/t and then flew a circle - measured,
            // 147 degrees of unasked-for heading change in 400 ticks.
            double f = Mth.clamp(forwardSpeed() / TURN_COORDINATION_SPEED, 0.0, 1.0);
            // rotationRoll > 0 is banked left, and a left bank must turn left, i.e. decrease yaw.
            yaw -= (float) (TURN_FROM_BANK * Math.sin(Math.toRadians(rotationRoll)) * f);
        }
        return yaw;
    }

    /**
     * Turn the nose by {@code wanted} degrees <em>of world heading</em>, compensating for the
     * attitude the aircraft is in.
     *
     * <h2>Why this is not just {@code setYRot(getYRot() + wanted)}</h2>
     * {@code PlaneEntity#tick} folds the euler deltas back into the attitude quaternion as three
     * <em>body-frame</em> rotations — {@code q.rotateZ(droll)}, {@code q.rotateX(dpitch)},
     * {@code q.rotateY(dyaw)} — and then decomposes the result again. So the numbers written into
     * {@code yRot}/{@code xRot}/{@code rotationRoll} are consumed as body rates, not as euler
     * angles, and the euler-rate kinematics of the Y-X-Z sequence the mod uses are
     *
     * <pre>
     *   wx = ydot*cos(p)*sin(r) + pdot*cos(r)
     *   wy = ydot*cos(p)*cos(r) - pdot*sin(r)
     *   wz = -ydot*sin(p)       + rdot
     * </pre>
     *
     * Inverting those for a pure body-Y input gives {@code ydot = dyaw * cos(r) / cos(p)}, and for a
     * pure body-X input {@code ydot = dpitch * sin(r) / cos(p)}. Both are real and both were
     * measured, not assumed:
     *
     * <ul>
     *   <li>A pedal input of 3.0 deg/tick produced <b>3.309</b> deg/tick of heading while the disc
     *       was tipped 25 degrees nose down, and exactly 3.000 while level. {@code 3.0/cos(25) =
     *       3.309}.</li>
     *   <li>The bank-to-turn term, symmetric by construction, produced <b>+0.469</b> deg/tick to the
     *       right and <b>-1.030</b> to the left at the same speed and bank angle — a 2.2x asymmetry
     *       and a constant offset of -0.25 deg/tick. That is the second term: the cyclic is holding
     *       the pitch against the disturbance the yaw itself creates, and each tick of that
     *       correction leaks {@code dpitch * sin(r) / cos(p)} back into the heading, with a sign that
     *       does not flip when the bank does.</li>
     * </ul>
     *
     * <p>So the heading command is corrected for the leak and then scaled into body-Y units. The
     * effect is that {@link #MAX_YAW_RATE} means what it says at any attitude, and a left turn and a
     * right turn are mirror images. {@code cos} is floored at 0.2 so a helicopter that has been
     * flipped onto its back by an explosion cannot divide by zero.
     */
    private void applyYaw(float wanted) {
        double roll = Math.toRadians(rotationRoll);
        double cp = Math.max(0.2, Math.cos(Math.toRadians(getXRot())));
        double cr = Math.cos(roll);
        if (Math.abs(cr) < 0.2) {
            cr = Math.copySign(0.2, cr);
        }
        double leak = pitchDelta * Math.sin(roll) / cp;
        setYRot(getYRot() + (float) ((wanted - leak) * cp / cr));
    }

    /**
     * The motion integration, replacing {@code PlaneEntity#tickMotion} entirely.
     *
     * <p>Order per tick: drag, then rotor thrust, then gravity, then the speed backstop. Drag is
     * <b>anisotropic</b>, which is the one thing about this that is not a plane with different
     * numbers. A rotor disc seen edge-on is a slim thing and seen face-on it is the largest surface
     * on the aircraft, so the vertical drag coefficients are an order of magnitude above the
     * horizontal ones. That single asymmetry is what makes a helicopter behave like one: it gives a
     * bounded, collective-selectable rate of descent, it makes an engine failure a fast glide rather
     * than a fall, and it keeps the vertical axis from being a function of the top speed.
     *
     * <p>Gravity is read from {@code tempMotionVars.gravity} rather than hard-coded, so that
     * {@code isNoGravity()} keeps working the way {@code PlaneEntity#tick} intends.
     */
    @Override
    protected void tickMotion(TempMotionVars tempMotionVars) {
        Vec3 m = getDeltaMovement();
        double vy = m.y;
        double vh = Math.sqrt(m.x * m.x + m.z * m.z);

        // --- drag ---
        double discDrag = getHealth() <= 0 ? DEAD_DISC_DRAG : 1.0;
        vy -= (V_DRAG_QUAD * Math.abs(vy) + V_DRAG_LIN) * vy * discDrag;

        double newVh = Math.max(0, vh - (H_DRAG_QUAD * vh * vh + H_DRAG_LIN * vh + H_DRAG_CONST));
        double scale = vh > 1.0E-9 ? newVh / vh : 0;
        double vx = m.x * scale;
        double vz = m.z * scale;

        // --- rotor thrust, along the airframe's own up axis ---
        double thrust = rotorThrust(vy);
        if (thrust > 0) {
            Vector3f axis = rotorAxis(pushScratch);
            vx += axis.x() * thrust;
            vy += axis.y() * thrust;
            vz += axis.z() * thrust;
        }

        // --- gravity ---
        vy += tempMotionVars.gravity;

        // --- backstop ---
        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (speed > MAX_SPEED) {
            double k = MAX_SPEED / speed;
            vx *= k;
            vy *= k;
            vz *= k;
        }

        setDeltaMovement(vx, vy, vz);
    }

    /**
     * Rotor thrust magnitude for this tick, blocks/tick^2.
     *
     * @param vy the vertical speed <em>after</em> drag, blocks/tick, positive up
     */
    private double rotorThrust(double vy) {
        if (!isPowered() || getHealth() <= 0) {
            return 0;
        }
        double thrust = COLLECTIVE_PER_NOTCH * getCollectiveNotches();
        if (vy > 0) {
            // Axial inflow: climbing takes the rotor's air away from it. This is what makes each
            // collective setting have an equilibrium climb rate instead of a constant acceleration.
            thrust *= Mth.clamp(1.0 - vy / ROTOR_INFLOW_LIMIT, 0.0, 1.0);
        }
        return thrust;
    }

    /** Roll is part of the attitude update in {@link #tickPitch}, one hook earlier. */
    @Override
    protected void tickRoll(TempMotionVars tempMotionVars) {}

    /**
     * The fixed-wing "mass proxy" scales the rudder as {@code 2.5f * multiplier}, and 1.2 is chosen
     * so that idiom reproduces this airframe's real {@link #MAX_YAW_RATE} of 3.0 deg/tick. The
     * matching pitch idiom, {@code 5.0f * multiplier}, does <b>not</b> describe a helicopter — the
     * cyclic is a position command limited by {@link #MAX_CYCLIC_RATE}. Use the constants.
     */
    @Override
    protected float getRotationSpeedMultiplier() {
        return 1.2f;
    }

    @Override
    public int getFuelCost() {
        return SimplePlanesConfig.HELICOPTER_FUEL_COST.get();
    }

    @Override
    public void positionRider(Entity passenger, Entity.MoveFunction moveFunction) {
        positionRiderGeneric(passenger);
        int index = getPassengers().indexOf(passenger);

        if (index == 0) {
            Vector3f pos = transformPos(new Vector3f(0, getPassengersRidingOffset() + getEntityYOffset(passenger), 0.5f));
            moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
        } else {
            if (hasLargeUpgrade) {
                index++;
            }
            switch (index) {
                case 1 -> {
                    Vector3f pos = transformPos(new Vector3f(0, getPassengersRidingOffset() + getEntityYOffset(passenger), -0.5f));
                    moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
                }
                case 2 -> {
                    Vector3f pos = transformPos(new Vector3f(-1, getPassengersRidingOffset() + getEntityYOffset(passenger), 0));
                    moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
                }
                case 3 -> {
                    Vector3f pos = transformPos(new Vector3f(1, getPassengersRidingOffset() + getEntityYOffset(passenger), 0));
                    moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
                }
            }
        }
    }

    @Override
    protected Item getItem() {
        return SimplePlanesItems.HELICOPTER_ITEM.get();
    }

    @Override
    public boolean canAddUpgrade(UpgradeType upgradeType) {
        if (upgradeType == SimplePlanesUpgrades.SOLAR_PANEL.get()) {
            return false;
        }
        return super.canAddUpgrade(upgradeType);
    }

    @Override
    public double getCameraDistanceMultiplayer() {
        return SimplePlanesConfig.HELI_CAMERA_DISTANCE_MULTIPLIER.get();
    }
}
