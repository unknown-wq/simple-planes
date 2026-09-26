package xyz.przemyk.simpleplanes.entities;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;

/** Seven-seat airship. Stub: flies with the starter plane's physics until its own flight model replaces them. */
public class AirshipEntity extends PlaneEntity {

    private static final float[] SEAT_X = {0, 0.5625f, -0.5625f, 0.5625f, -0.5625f, 0.5625f, -0.5625f};
    private static final float[] SEAT_Z = {1.75f, 0.375f, 0.375f, -1.25f, -1.25f, -2.75f, -2.75f};

    public AirshipEntity(EntityType<? extends AirshipEntity> entityType, Level level) {
        super(entityType, level);
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

    @Override
    protected float getRotationSpeedMultiplier() {
        return 0.25f;
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
