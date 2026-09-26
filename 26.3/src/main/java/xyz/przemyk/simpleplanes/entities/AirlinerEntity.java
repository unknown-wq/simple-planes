package xyz.przemyk.simpleplanes.entities;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.joml.Vector3f;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;

/**
 * Six-seat mini airliner: heavy, slow to turn, long take-off run, nose clamped on the ground below the
 * tail-strike angle. Numbers: design/DESIGN.md section 4; seats, skin and logos: AIRLINER-MODEL.md.
 */
public class AirlinerEntity extends PlaneEntity {

    public static final int LOGO_COUNT = 6;
    public static final EntityDataAccessor<Integer> LOGO = SynchedEntityData.defineId(AirlinerEntity.class, EntityDataSerializers.INT);
    public static final TagKey<Block> METAL_SKIN_TAG = TagKey.create(Registries.BLOCK,
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "airliner_metal_skin"));

    private static final String THROTTLE_KEY = "throttle";
    private static final float[] SEAT_Z = {4.25f, 2.34375f, 1.21875f, 0.09375f, -1.03125f, -2.15625f};

    public static final float MAX_SPEED_DATA = 2.0f;
    /** Keel end touches at 14.5 deg nose-up. */
    public static final float GROUND_PITCH_LIMIT = 12.0f;

    public AirlinerEntity(EntityType<? extends AirlinerEntity> entityType, Level level) {
        super(entityType, level);
        setMaxSpeed(MAX_SPEED_DATA);
        // Rolled before the spawn packet; a saved or item Logo overrides it in readAdditionalSaveData.
        if (!level.isClientSide()) {
            setLogo(level.getRandom().nextInt(LOGO_COUNT));
        }
    }

    @Override
    protected TempMotionVars getMotionVars() {
        TempMotionVars vars = super.getMotionVars();
        vars.maxSpeed = 2.0;
        vars.takeOffSpeed = 0.60;
        vars.stallSpeedFactor = 0.6;
        vars.liftSaturationFactor = 1.25;
        vars.maxLift = 2.0f;
        vars.dragQuad = 0.0006;
        vars.dragMul = 0.0003;
        vars.drag = 0.0005;
        vars.pitchToMotion = 0.16f;
        vars.yawToMotion = 0.06f;
        vars.motionToRotation = 0.05f;
        return vars;
    }

    @Override
    protected float pushPerNotch() {
        return 0.004f;
    }

    @Override
    protected float maxRollRate() {
        return 2.5f;
    }

    @Override
    protected float groundPitchLimit() {
        return GROUND_PITCH_LIMIT;
    }

    @Override
    protected double groundRollingResistance() {
        return 0.007;
    }

    @Override
    protected double groundLinearDragFactor(float friction) {
        return 5.0;
    }

    @Override
    protected int getLandingAngle() {
        return 20;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(LOGO, -1);
    }

    public int getLogo() {
        return entityData.get(LOGO);
    }

    public void setLogo(int logo) {
        entityData.set(LOGO, logo);
    }

    public boolean hasMetalSkin() {
        return getMaterial().builtInRegistryHolder().is(METAL_SKIN_TAG);
    }

    @Override
    public void tick() {
        // -1: saved without a logo.
        if (!level().isClientSide() && getLogo() < 0) {
            setLogo(level().getRandom().nextInt(LOGO_COUNT));
        }
        super.tick();
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        setLogo(input.getIntOr("Logo", getLogo()));
        setThrottle(Math.max(0, input.getIntOr(THROTTLE_KEY, getThrottle())));
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("Logo", getLogo());
        output.putInt(THROTTLE_KEY, getThrottle());
    }

    /** The world save keeps the throttle (an airliner in flight comes back flying); the item does not. */
    @Override
    public ItemStack getItemStack() {
        ItemStack itemStack = super.getItemStack();
        CompoundTag compound = itemStack.get(SimplePlanesComponents.ENTITY_TAG.get());
        if (compound != null && compound.contains(THROTTLE_KEY)) {
            CompoundTag parked = compound.copy();
            parked.remove(THROTTLE_KEY);
            itemStack.set(SimplePlanesComponents.ENTITY_TAG.get(), parked);
        }
        return itemStack;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        if (passenger instanceof PlaneEntity || getPassengers().size() >= SEAT_Z.length) {
            return false;
        }
        // Seat 0 is kept for a player pilot.
        return passenger instanceof Player || nonPlayerPassengers() < SEAT_Z.length - 1;
    }

    private int nonPlayerPassengers() {
        int count = 0;
        for (Entity passenger : getPassengers()) {
            if (!(passenger instanceof Player)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Seat of a passenger, 0 (pilot) to 5, or -1. Vanilla puts a boarding player first in the list; with no
     * player aboard the list starts at seat 1.
     */
    public int seatOf(Entity passenger) {
        int index = getPassengers().indexOf(passenger);
        if (index < 0) {
            return -1;
        }
        int seat = getFirstPassenger() instanceof Player ? index : index + 1;
        return seat < SEAT_Z.length ? seat : -1;
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction moveFunction) {
        positionRiderGeneric(passenger);
        int seat = seatOf(passenger);
        if (seat >= 0) {
            Vector3f pos = new Vector3f(0, 0.375f, SEAT_Z[seat]);
            // Server: Q_Client is stale without a player aboard.
            pos = level().isClientSide() ? transformPos(pos) : transformPosPhysics(pos);
            moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
        }
    }

    @Override
    public float getPassengersRidingOffset() {
        return 0.375f;
    }

    @Override
    protected float getRotationSpeedMultiplier() {
        return 0.35f;
    }

    @Override
    protected float getGroundPitch() {
        return 0;
    }

    @Override
    protected boolean acceptsUpgrade(UpgradeType type) {
        return type != SimplePlanesUpgrades.SEATS.get()
            && type != SimplePlanesUpgrades.SHOOTER.get();
    }

    @Override
    public double getCameraDistanceMultiplayer() {
        return 1.6;
    }

    @Override
    public int getFuelCost() {
        return 10;
    }

    @Override
    protected Item getItem() {
        return SimplePlanesItems.AIRLINER_ITEM.get();
    }
}
