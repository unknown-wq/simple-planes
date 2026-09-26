package xyz.przemyk.simpleplanes.entities;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;

/** Single-seat jet fighter. Stub: flies with the starter plane's physics; see FIGHTER-MODEL.md for the seat. */
public class FighterEntity extends PlaneEntity {

    public FighterEntity(EntityType<? extends FighterEntity> entityType, Level level) {
        super(entityType, level);
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
        return 1.4f;
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

    @Override
    protected Item getItem() {
        return SimplePlanesItems.FIGHTER_ITEM.get();
    }
}
