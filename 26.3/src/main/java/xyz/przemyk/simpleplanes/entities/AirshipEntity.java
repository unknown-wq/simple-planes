package xyz.przemyk.simpleplanes.entities;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import xyz.przemyk.simpleplanes.combat.Ground;
import xyz.przemyk.simpleplanes.misc.MathUtil;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;

/**
 * Seven-seat airship: buoyancy and ballast, a fly-by-wire altitude hold, slow heavy turning.
 * All six flight hooks are replaced, as on the helicopter. Design: design/DESIGN.md section 5.
 *
 * <p>Controls: throttle is engine thrust along the hull; the elevator (W/S, {@code PITCH_UP}) commands a
 * vertical speed of {@link #VS_MAX}, and released the ship holds the altitude where it stopped; the rudder
 * ({@code YAW_RIGHT}) is a ramped yaw rate. Strafe and the space bar are not used.
 *
 * <p>Units are blocks and ticks, mass normalised: every term is an acceleration in b/t^2.
 * {@code xRot} is positive nose up and {@code rotationRoll} positive banked left (PlaneEntity's convention).
 */
public class AirshipEntity extends PlaneEntity {

    // Server state for a client that takes authority when a player boards.
    public static final EntityDataAccessor<Float> TRIM_SYNC = SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> HOLD_Y_SYNC = SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.FLOAT);

    public static final double BALLAST_RANGE = 0.006;
    public static final double RIDER_WEIGHT = 0.0006;
    public static final float PUSH_PER_NOTCH = 0.004f;
    public static final double H_DRAG_QUAD = 0.025;
    public static final double H_DRAG_LIN = 0.004;
    public static final double H_DRAG_CONST = 0.0002;
    public static final double V_DRAG_QUAD = 0.05;
    public static final double V_DRAG_LIN = 0.02;
    public static final double HULL_LIFT = 0.06;
    public static final double YAW_TO_MOTION = 0.03;
    public static final double MAX_SPEED = 1.5;

    /** Elevator authority, b/t. */
    public static final double VS_MAX = 0.12;
    public static final double ALT_GAIN = 0.01;
    public static final double TRIM_KP = 8.0;
    public static final double TRIM_KI = 0.10;
    public static final double TRIM_RATE = 0.02;
    /** Ticks of vertical speed ahead of the ship: the predicted stop point shown while capturing. */
    public static final double CAPTURE_LEAD = 30;
    /** Released elevator: brake to this vertical speed, then hold where the ship stopped. */
    public static final double CAPTURE_STOP_VS = 0.004;
    public static final double PITCH_AT_VS_MAX = 10.0;
    public static final double PITCH_RATE = 0.5;

    public static final float MAX_YAW_RATE = 0.6f;
    public static final float YAW_RAMP = 0.04f;
    /** Cosmetic lean, degrees of roll per deg/t of yaw rate. */
    public static final double ROLL_PER_YAW = -3.0;
    public static final double ROLL_RATE = 0.2;
    public static final double MAX_ROLL = 8.0;
    public static final double GROUND_FRICTION = 0.25;

    /** Envelope clearance: the hull top is 8.5 above the entity. */
    public static final double CLEARANCE = 9.0;
    public static final double PROBE_ALONG = 4.0;
    public static final double PROBE_ACROSS = 2.5;
    /** Terrain look-ahead along the hull: {@code LOOKAHEAD_MIN + LOOKAHEAD_TICKS * vh}, capped. */
    public static final double LOOKAHEAD_MIN = 6.0;
    public static final double LOOKAHEAD_TICKS = 60.0;
    public static final double LOOKAHEAD_MAX = 64.0;
    // One block, so a one-block-thick wall cannot fall between two samples.
    public static final double LOOKAHEAD_STEP = 1.0;
    public static final double GONDOLA_HALF = 1.5;
    /** Ticks the trim needs before a commanded climb is under way; judges whether terrain ahead can be cleared. */
    public static final double CLIMB_DELAY = 30.0;

    private static final String[] CONTROL_KEYS = {"trim", "trim_int", "hold_y", "throttle"};
    private static final float[] SEAT_X = {0, 0.5625f, -0.5625f, 0.5625f, -0.5625f, 0.5625f, -0.5625f};
    private static final float[] SEAT_Z = {1.75f, 0.375f, 0.375f, -1.25f, -1.25f, -2.75f, -2.75f};

    private double trim;
    private double trimInt;
    private double holdY = Double.NaN;
    private boolean capturing;
    private boolean moored;
    private boolean flyByWire = true;
    private boolean clientSeeded;

    private double vsCommand;
    private boolean engineBlocked;
    private int surfaceUnder = Ground.UNKNOWN;
    private int surfaceMax = Ground.UNKNOWN;
    private double floorAhead = Double.NEGATIVE_INFINITY;
    private int lastSyncedTrim = Integer.MIN_VALUE;
    private int lastSyncedHold = Integer.MIN_VALUE;

    public AirshipEntity(EntityType<? extends AirshipEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(TRIM_SYNC, 0.0f);
        builder.define(HOLD_Y_SYNC, Float.NaN);
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        if (level().isClientSide()) {
            if (!isLocalInstanceAuthoritative()) {
                clientSeeded = false;
            } else if (!clientSeeded) {
                clientSeeded = true;
                trim = Mth.clamp(entityData.get(TRIM_SYNC), -1, 1);
                trimInt = trim;
                float h = entityData.get(HOLD_Y_SYNC);
                holdY = Float.isFinite(h) ? h : getY();
                capturing = false;
            }
        }
        if (Double.isNaN(holdY)) {
            holdY = getY();
        }
        moored = false;
        super.tick();
        if (!level().isClientSide()) {
            syncState();
        }
    }

    private void syncState() {
        int t = (int) Math.round(trim * 200);
        if (t != lastSyncedTrim) {
            lastSyncedTrim = t;
            entityData.set(TRIM_SYNC, (float) trim);
        }
        int h = (int) Math.round(holdY * 20);
        if (h != lastSyncedHold) {
            lastSyncedHold = h;
            entityData.set(HOLD_Y_SYNC, (float) holdY);
        }
    }

    /** Sideslip: the horizontal velocity heading follows the hull. No lift here; speed preserved. */
    @Override
    protected Quaternionf tickRotateMotion(TempMotionVars tempMotionVars, Quaternionf q, Vec3 motion) {
        double vh = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        if (vh < 1.0E-4 || getHealth() <= 0) {
            return q;
        }
        double heading = MathUtil.lerpAngle180(YAW_TO_MOTION, MathUtil.getYaw(motion), getYRot());
        double rad = Math.toRadians(heading);
        setDeltaMovement(-Math.sin(rad) * vh, motion.y, Math.cos(rad) * vh);
        return q;
    }

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

        // The wheel is a castor.
        if (onGround() || isOnWater()) {
            Vec3 m = getDeltaMovement();
            double keep = 1.0 - GROUND_FRICTION;
            setDeltaMovement(m.x * keep, m.y, m.z * keep);
        }
        if (onGround()) {
            setXRot(0);
            rotationRoll = 0;
            moored = getThrottle() == 0 && getPitchUp() == 0 && getHealth() > 0;
        }
        return true;
    }

    /** Terrain probe, the fly-by-wire vertical loop and the hull pitch. */
    @Override
    protected void tickPitch(TempMotionVars tempMotionVars) {
        double y = getY();
        double vy = getDeltaMovement().y;
        if (onGround() && vy < 0) {
            vy = 0;
        }
        probeTerrain(y);

        if (getHealth() <= 0) {
            vsCommand = 0;
            trim += Mth.clamp(1 - trim, -TRIM_RATE, TRIM_RATE);
            setXRot((float) approach(getXRot(), 0, PITCH_RATE));
            return;
        }

        if (moored) {
            // Ballast taken on: a nudge cannot float it off.
            trim += Mth.clamp(1 - trim, -TRIM_RATE, TRIM_RATE);
            trimInt = trim;
            holdY = y;
            capturing = false;
            vsCommand = 0;
            setXRot(0);
            return;
        }

        double floor = surfaceMax == Ground.UNKNOWN ? Double.NEGATIVE_INFINITY : surfaceMax + CLEARANCE;
        floor = Math.max(floor, floorAhead);
        // The wheel may come down only where the whole envelope footprint is clear.
        boolean landing = surfaceUnder != Ground.UNKNOWN && surfaceMax == surfaceUnder
            && Ground.isLandable(level(), getX(), getZ());
        double ground = landing ? surfaceUnder : floor;

        byte elevator = getPitchUp();
        if (elevator > 0) {
            vsCommand = VS_MAX;
            holdY = Math.max(y + CAPTURE_LEAD * vy, floor);
            capturing = true;
        } else if (elevator < 0) {
            vsCommand = landing ? -VS_MAX : Mth.clamp(ALT_GAIN * (floor - y), -VS_MAX, VS_MAX);
            holdY = Math.max(y + CAPTURE_LEAD * vy, ground);
            capturing = true;
        } else if (capturing) {
            vsCommand = Math.max(0, Mth.clamp(ALT_GAIN * (floor - y), -VS_MAX, VS_MAX));
            holdY = Math.max(y + CAPTURE_LEAD * vy, floor);
            if (Math.abs(vy) < CAPTURE_STOP_VS || onGround()) {
                capturing = false;
                holdY = Math.max(y, onGround() && landing ? ground : floor);
            }
        } else {
            holdY = Math.max(holdY, onGround() && landing ? ground : floor);
            vsCommand = Mth.clamp(ALT_GAIN * (holdY - y), -VS_MAX, VS_MAX);
        }

        if (flyByWire) {
            double e = vsCommand - vy;
            trimInt = Mth.clamp(trimInt - TRIM_KI * e, -1, 1);
            double want = Mth.clamp(trimInt - TRIM_KP * e, -1, 1);
            trim += Mth.clamp(want - trim, -TRIM_RATE, TRIM_RATE);
        }

        // Level the hull before the wheel touches.
        double agl = surfaceUnder == Ground.UNKNOWN ? Double.MAX_VALUE : y - surfaceUnder;
        double pitchTarget = flyByWire ? PITCH_AT_VS_MAX * vsCommand / VS_MAX : 0;
        if (agl < 1.0 + 20.0 * Math.max(0, -vy)) {
            pitchTarget = 0;
        }
        setXRot((float) approach(getXRot(), pitchTarget, PITCH_RATE));
    }

    /**
     * Five envelope columns (centre, +-4 along the hull, +-2.5 across), then a look-ahead along the hull.
     * Terrain ahead raises the floor early; if it is higher than the ship can climb before arriving, the
     * engines idle until it can.
     */
    private void probeTerrain(double y) {
        Level level = level();
        double yaw = Math.toRadians(getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = Math.cos(yaw), rz = Math.sin(yaw);
        double x = getX(), z = getZ();

        surfaceUnder = Ground.surfaceHeight(level, x, z);
        int max = surfaceUnder;
        max = maxKnown(max, Ground.surfaceHeight(level, x + fx * PROBE_ALONG, z + fz * PROBE_ALONG));
        max = maxKnown(max, Ground.surfaceHeight(level, x - fx * PROBE_ALONG, z - fz * PROBE_ALONG));
        max = maxKnown(max, Ground.surfaceHeight(level, x + rx * PROBE_ACROSS, z + rz * PROBE_ACROSS));
        max = maxKnown(max, Ground.surfaceHeight(level, x - rx * PROBE_ACROSS, z - rz * PROBE_ACROSS));
        surfaceMax = max;

        floorAhead = Double.NEGATIVE_INFINITY;
        engineBlocked = false;
        Vec3 m = getDeltaMovement();
        double vh = Math.sqrt(m.x * m.x + m.z * m.z);
        double reach = Math.min(LOOKAHEAD_MAX, LOOKAHEAD_MIN + LOOKAHEAD_TICKS * vh);
        double closing = Math.max(vh, 0.05);
        for (double d = LOOKAHEAD_STEP; d <= reach; d += LOOKAHEAD_STEP) {
            double px = x + fx * d, pz = z + fz * d;
            for (int side = -1; side <= 1; side++) {
                int s = Ground.surfaceHeight(level, px + rx * GONDOLA_HALF * side, pz + rz * GONDOLA_HALF * side);
                if (s == Ground.UNKNOWN) {
                    continue;
                }
                double need = s + CLEARANCE;
                floorAhead = Math.max(floorAhead, need);
                if (surfaceUnder != Ground.UNKNOWN && s > surfaceUnder) {
                    double arrival = (d - GONDOLA_HALF) / closing;
                    double climbable = 0.8 * VS_MAX * Math.max(0, arrival - CLIMB_DELAY);
                    if (need - y > climbable) {
                        engineBlocked = true;
                    }
                }
            }
        }
    }

    private static int maxKnown(int a, int b) {
        if (a == Ground.UNKNOWN) {
            return b;
        }
        return b == Ground.UNKNOWN ? a : Math.max(a, b);
    }

    /** Rudder: a ramped yaw rate; the hull stays level (no bank correction). */
    @Override
    protected void tickYaw() {
        float target = getHealth() <= 0 ? 0 : MAX_YAW_RATE * Math.signum(getYawRight());
        yawSpeed = Mth.clamp((float) approach(yawSpeed, target, YAW_RAMP), -MAX_YAW_RATE, MAX_YAW_RATE);
        setYRot(getYRot() + yawSpeed);
    }

    /** Order: drag, engine, ballast and buoyancy (gravity included), hull lift, backstop. */
    @Override
    protected void tickMotion(TempMotionVars tempMotionVars) {
        Vec3 m = getDeltaMovement();
        double vx = m.x, vy = m.y, vz = m.z;

        double vh = Math.sqrt(vx * vx + vz * vz);
        double newVh = Math.max(0, vh - (H_DRAG_QUAD * vh * vh + H_DRAG_LIN * vh + H_DRAG_CONST));
        double scale = vh > 1.0E-9 ? newVh / vh : 0;
        vx *= scale;
        vz *= scale;
        vy -= (V_DRAG_QUAD * Math.abs(vy) + V_DRAG_LIN) * vy;

        double pitch = Math.toRadians(getXRot());
        if (isPowered() && !engineBlocked && getHealth() > 0) {
            double push = tempMotionVars.push;
            double yaw = Math.toRadians(getYRot());
            double cp = Math.cos(pitch);
            vx += -Math.sin(yaw) * cp * push;
            vy += Math.sin(pitch) * push;
            vz += Math.cos(yaw) * cp * push;
        }

        // Neutral buoyancy cancels gravity exactly; with no gravity there is neither.
        double weightScale = -tempMotionVars.gravity / 0.03;
        vy += (-BALLAST_RANGE * trim - RIDER_WEIGHT * getPassengers().size()) * weightScale;

        vy += HULL_LIFT * (vx * vx + vz * vz) * pitch;

        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (speed > MAX_SPEED) {
            double k = MAX_SPEED / speed;
            vx *= k;
            vy *= k;
            vz *= k;
        }
        setDeltaMovement(vx, vy, vz);
    }

    /** A cosmetic lean into the turn. */
    @Override
    protected void tickRoll(TempMotionVars tempMotionVars) {
        double target = onGround() || getHealth() <= 0 ? 0 : ROLL_PER_YAW * yawSpeed;
        rotationRoll = (float) Mth.clamp(approach(rotationRoll, target, ROLL_RATE), -MAX_ROLL, MAX_ROLL);
    }

    private static double approach(double current, double target, double rate) {
        return current + Mth.clamp(target - current, -rate, rate);
    }

    // ------------------------------------------------------------------
    // State access (test command, telemetry)
    // ------------------------------------------------------------------

    public double getTrim() {
        return trim;
    }

    public double getTrimIntegral() {
        return trimInt;
    }

    public double getHoldY() {
        return holdY;
    }

    public double getVsCommand() {
        return vsCommand;
    }

    public boolean isCapturing() {
        return capturing;
    }

    public boolean isEngineBlocked() {
        return engineBlocked;
    }

    public boolean isFlyByWire() {
        return flyByWire;
    }

    /** Test aid: sets the ballast and the loop's integrator. */
    public void setTrim(double value) {
        trim = Mth.clamp(value, -1, 1);
        trimInt = trim;
    }

    /** Test aid: centres the elevator and holds {@code y} (the envelope floor still applies). */
    public void setHoldY(double y) {
        setPitchUp((byte) 0);
        capturing = false;
        flyByWire = true;
        holdY = y;
    }

    /** Test aid: with the loop off the ballast stays where it is. */
    public void setFlyByWire(boolean on) {
        flyByWire = on;
    }

    public double getVerticalSpeed() {
        return getDeltaMovement().y;
    }

    public double getHorizontalSpeed() {
        Vec3 m = getDeltaMovement();
        return Math.sqrt(m.x * m.x + m.z * m.z);
    }

    // ------------------------------------------------------------------
    // Persistence: a ship reloaded with its ballast reset would fall or float away.
    // ------------------------------------------------------------------

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putDouble("trim", trim);
        output.putDouble("trim_int", trimInt);
        output.putDouble("hold_y", Double.isNaN(holdY) ? getY() : holdY);
        output.putInt("throttle", getThrottle());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        trim = finiteClamp(input.getDoubleOr("trim", trim), -1, 1, 0);
        trimInt = finiteClamp(input.getDoubleOr("trim_int", trimInt), -1, 1, trim);
        double h = input.getDoubleOr("hold_y", Double.NaN);
        holdY = Double.isFinite(h) ? Mth.clamp(h, level().getMinY() - 64, level().getMaxY() + 512) : Double.NaN;
        capturing = false;
        setThrottle(Mth.clamp(input.getIntOr("throttle", getThrottle()), 0, MAX_THROTTLE));
    }

    private static double finiteClamp(double v, double lo, double hi, double fallback) {
        return Double.isFinite(v) ? Mth.clamp(v, lo, hi) : fallback;
    }

    /** The item form carries no ballast or controls: the world save keeps them, the item does not. */
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
    // Airframe
    // ------------------------------------------------------------------

    @Override
    protected float pushPerNotch() {
        return PUSH_PER_NOTCH;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().size() < SEAT_Z.length;
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction moveFunction) {
        positionRiderGeneric(passenger);
        int index = getPassengers().indexOf(passenger);
        if (index >= 0 && index < SEAT_Z.length) {
            Vector3f pos = transformPos(new Vector3f(SEAT_X[index], 0.1875f, SEAT_Z[index]));
            moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
        }
    }

    @Override
    public float getPassengersRidingOffset() {
        return 0.1875f;
    }

    /** 2.5 * 0.24 = 0.6 deg/t for code using the fixed-wing idiom; tickYaw uses the constants. */
    @Override
    protected float getRotationSpeedMultiplier() {
        return 0.24f;
    }

    @Override
    protected float getGroundPitch() {
        return 0;
    }

    @Override
    protected int getLandingAngle() {
        return 15;
    }

    @Override
    protected boolean acceptsUpgrade(UpgradeType type) {
        return type != SimplePlanesUpgrades.BOOSTER.get()
            && type != SimplePlanesUpgrades.SEATS.get()
            && type != SimplePlanesUpgrades.SHOOTER.get()
            && type != SimplePlanesUpgrades.FLOATY_BEDDING.get();
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        // The envelope is far larger than the hitbox the vanilla rule scales from.
        double d = 17.5 * 64.0 * Entity.getViewScale();
        return distance < d * d;
    }

    @Override
    public double getCameraDistanceMultiplayer() {
        return 2.0;
    }

    @Override
    public int getFuelCost() {
        return 8;
    }

    @Override
    protected Item getItem() {
        return SimplePlanesItems.AIRSHIP_ITEM.get();
    }
}
