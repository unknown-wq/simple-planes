package xyz.przemyk.simpleplanes.entities;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;
import xyz.przemyk.simpleplanes.setup.SimplePlanesConfig;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;

/**
 * The two-to-four seat fixed-wing aircraft.
 *
 * <p>The cabin, the large-upgrade bay and the payload rack now live in {@link LargeAirframeEntity},
 * which {@link HelicopterEntity} shares. What is left here is what makes this a <em>plane</em>:
 * a flat resting attitude, half the starter plane's control rates, its seat layout and its item.
 */
public class LargePlaneEntity extends LargeAirframeEntity {

    public LargePlaneEntity(EntityType<? extends LargePlaneEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected float getGroundPitch() {
        return 0;
    }

    @Override
    public int getFuelCost() {
        return SimplePlanesConfig.LARGE_PLANE_FUEL_COST.get();
    }

    @Override
    public void positionRider(Entity passenger, Entity.MoveFunction moveFunction) {
        positionRiderGeneric(passenger);
        int index = getPassengers().indexOf(passenger);

        if (index == 0) {
            Vector3f pos = transformPos(new Vector3f(0, getPassengersRidingOffset() + getEntityYOffset(passenger), 1));
            moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
        } else {
            if (hasLargeUpgrade) {
                index++;
            }
            switch (index) {
                case 1 -> {
                    Vector3f pos = transformPos(new Vector3f(0, getPassengersRidingOffset() + getEntityYOffset(passenger), 0));
                    moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
                }
                case 2 -> {
                    Vector3f pos = transformPos(new Vector3f(0, getPassengersRidingOffset() + getEntityYOffset(passenger), -1));
                    moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
                }
                case 3 -> {
                    Vector3f pos = transformPos(new Vector3f(0, getPassengersRidingOffset() + getEntityYOffset(passenger), -1.8f));
                    moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
                }
            }
        }
    }

    @Override
    public double getCameraDistanceMultiplayer() {
        return SimplePlanesConfig.LARGE_PLANE_CAMERA_DISTANCE_MULTIPLIER.get();
    }

    @Override
    protected Item getItem() {
        return SimplePlanesItems.LARGE_PLANE_ITEM.get();
    }

    @Override
    protected float getRotationSpeedMultiplier() {
        return 0.5f;
    }
}
