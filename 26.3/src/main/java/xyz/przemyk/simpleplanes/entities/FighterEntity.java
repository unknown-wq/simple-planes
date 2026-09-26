package xyz.przemyk.simpleplanes.entities;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.joml.Vector3f;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;
import xyz.przemyk.simpleplanes.upgrades.booster.BoosterUpgrade;

/**
 * Single-seat jet fighter: the fixed-wing model with more thrust that fades later, more drag, faster
 * control ramps, level tricycle gear and a tail-strike clamp (DESIGN.md §3). Seat: FIGHTER-MODEL.md.
 *
 * <table>
 * <caption>Flight parameters (starter plane in brackets)</caption>
 * <tr><td>pushPerNotch</td><td>0.012 (0.00625)</td></tr>
 * <tr><td>MAX_SPEED data, thrust fade</td><td>2.5 (1.0)</td></tr>
 * <tr><td>maxSpeed clamp</td><td>3.0 (3.0)</td></tr>
 * <tr><td>takeOffSpeed / stallSpeedFactor / liftSaturationFactor / maxLift</td><td>0.45 / 0.6 / 1.3 / 2.0 (0.30 / 0.55 / 1.3 / 2.0)</td></tr>
 * <tr><td>dragQuad / dragMul / drag</td><td>0.00145 / 0.0005 / 0.001 (0.001 / 0.0005 / 0.001)</td></tr>
 * <tr><td>pitchToMotion / yawToMotion / motionToRotation</td><td>0.3 / 0.2 / 0.05 (0.2 / 0.1 / 0.05)</td></tr>
 * <tr><td>rotation multiplier</td><td>1.4: pitch 7.0, yaw 3.5 deg/t (1.0)</td></tr>
 * <tr><td>maxRollRate</td><td>8.0 deg/t (5.0)</td></tr>
 * <tr><td>ground pitch / ground pitch limit</td><td>0 / 10 deg (5 / none)</td></tr>
 * <tr><td>ground rolling resistance / linear drag factor</td><td>0.030 / 24 (0 / 20*(3-friction))</td></tr>
 * </table>
 */
public class FighterEntity extends PlaneEntity {

    public static final float MAX_SPEED_DATA = 2.5f;
    public static final float PUSH_PER_NOTCH = 0.012f;
    public static final double TAKE_OFF_SPEED = 0.45;
    public static final double STALL_SPEED_FACTOR = 0.6;
    public static final double LIFT_SATURATION_FACTOR = 1.3;
    public static final float MAX_LIFT = 2.0f;
    public static final double MAX_SPEED_CLAMP = 3.0;
    public static final double DRAG_QUAD = 0.00145;
    public static final double DRAG_MUL = 0.0005;
    public static final double DRAG = 0.001;
    public static final float PITCH_TO_MOTION = 0.3f;
    public static final float YAW_TO_MOTION = 0.2f;
    public static final float MOTION_TO_ROTATION = 0.05f;
    public static final float ROTATION_SPEED_MULTIPLIER = 1.4f;
    public static final float MAX_ROLL_RATE = 8.0f;
    /** Tail strike at asin(0.75 / 3.5) = 12.4 deg; 2 deg margin. */
    public static final float GROUND_PITCH_LIMIT = 10.0f;
    public static final double GROUND_ROLLING_RESISTANCE = 0.030;
    public static final double GROUND_LINEAR_DRAG_FACTOR = 24.0;
    private static final String THROTTLE_KEY = "throttle";

    public FighterEntity(EntityType<? extends FighterEntity> entityType, Level level) {
        super(entityType, level);
        setMaxSpeed(MAX_SPEED_DATA);
    }

    @Override
    protected TempMotionVars getMotionVars() {
        TempMotionVars vars = super.getMotionVars();
        vars.maxSpeed = MAX_SPEED_CLAMP;
        vars.takeOffSpeed = TAKE_OFF_SPEED;
        vars.stallSpeedFactor = STALL_SPEED_FACTOR;
        vars.liftSaturationFactor = LIFT_SATURATION_FACTOR;
        vars.maxLift = MAX_LIFT;
        vars.dragQuad = DRAG_QUAD;
        vars.dragMul = DRAG_MUL;
        vars.drag = DRAG;
        vars.pitchToMotion = PITCH_TO_MOTION;
        vars.yawToMotion = YAW_TO_MOTION;
        vars.motionToRotation = MOTION_TO_ROTATION;
        return vars;
    }

    @Override
    protected float pushPerNotch() {
        return PUSH_PER_NOTCH;
    }

    @Override
    protected float maxRollRate() {
        return MAX_ROLL_RATE;
    }

    @Override
    protected float groundPitchLimit() {
        return GROUND_PITCH_LIMIT;
    }

    // tickPitch, which applies groundPitchLimit, is skipped on the ground below take-off speed.
    @Override
    protected boolean tickOnGround(TempMotionVars tempMotionVars) {
        boolean speedingUp = super.tickOnGround(tempMotionVars);
        if (getOnGround()) {
            setXRot(Math.min(getXRot(), groundPitchLimit()));
        }
        return speedingUp;
    }

    @Override
    protected double groundRollingResistance() {
        return GROUND_ROLLING_RESISTANCE;
    }

    @Override
    protected double groundLinearDragFactor(float friction) {
        return GROUND_LINEAR_DRAG_FACTOR;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty();
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction moveFunction) {
        positionRiderGeneric(passenger);
        if (getPassengers().indexOf(passenger) == 0) {
            Vector3f pos = transformPos(new Vector3f(0, 0.0625f, 0.125f));
            moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
        }
    }

    @Override
    public float getPassengersRidingOffset() {
        return 0.0625f;
    }

    @Override
    protected float getRotationSpeedMultiplier() {
        return ROTATION_SPEED_MULTIPLIER;
    }

    @Override
    protected float getGroundPitch() {
        return 0;
    }

    @Override
    protected boolean acceptsUpgrade(UpgradeType type) {
        return type != SimplePlanesUpgrades.SEATS.get()
            && type != SimplePlanesUpgrades.SHOOTER.get()
            && type != SimplePlanesUpgrades.FLOATY_BEDDING.get();
    }

    @Override
    public double getCameraDistanceMultiplayer() {
        return 1.2;
    }

    @Override
    public int getFuelCost() {
        return 6;
    }

    // The world save keeps the throttle, as the helicopter's does; the item form does not.
    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt(THROTTLE_KEY, getThrottle());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        setThrottle(Mth.clamp(input.getIntOr(THROTTLE_KEY, getThrottle()), 0, BoosterUpgrade.MAX_THROTTLE));
    }

    @Override
    public ItemStack getItemStack() {
        ItemStack stack = super.getItemStack();
        CompoundTag tag = stack.get(SimplePlanesComponents.ENTITY_TAG.get());
        if (tag != null && tag.contains(THROTTLE_KEY)) {
            CompoundTag parked = tag.copy();
            parked.remove(THROTTLE_KEY);
            stack.set(SimplePlanesComponents.ENTITY_TAG.get(), parked);
        }
        return stack;
    }

    @Override
    protected Item getItem() {
        return SimplePlanesItems.FIGHTER_ITEM.get();
    }
}
