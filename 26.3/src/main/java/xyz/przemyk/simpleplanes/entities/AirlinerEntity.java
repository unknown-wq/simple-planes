package xyz.przemyk.simpleplanes.entities;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.joml.Vector3f;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;

/**
 * Six-seat mini airliner. Stub: flies with the starter plane's physics; see AIRLINER-MODEL.md for seats,
 * skin and logos.
 */
public class AirlinerEntity extends PlaneEntity {

    public static final int LOGO_COUNT = 6;
    public static final EntityDataAccessor<Integer> LOGO = SynchedEntityData.defineId(AirlinerEntity.class, EntityDataSerializers.INT);
    public static final TagKey<Block> METAL_SKIN_TAG = TagKey.create(Registries.BLOCK,
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "airliner_metal_skin"));

    private static final float[] SEAT_Z = {4.25f, 2.34375f, 1.21875f, 0.09375f, -1.03125f, -2.15625f};

    public AirlinerEntity(EntityType<? extends AirlinerEntity> entityType, Level level) {
        super(entityType, level);
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
        // -1: never rolled (placed from an item without a Logo tag).
        if (!level().isClientSide() && getLogo() < 0) {
            setLogo(level().getRandom().nextInt(LOGO_COUNT));
        }
        super.tick();
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        setLogo(input.getIntOr("Logo", -1));
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("Logo", getLogo());
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().size() < SEAT_Z.length && !(passenger instanceof PlaneEntity);
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction moveFunction) {
        positionRiderGeneric(passenger);
        int index = getPassengers().indexOf(passenger);
        if (index >= 0 && index < SEAT_Z.length) {
            Vector3f pos = transformPos(new Vector3f(0, 0.375f, SEAT_Z[index]));
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
