package xyz.przemyk.simpleplanes.entities;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;

/**
 * Fixed-wing strike drone (STRIKE-DRONE-MODEL.md): the starter plane's flight model on a light,
 * quicker-handling airframe, so it flies the existing attack run unchanged. At or above the starter
 * plane's rotation multiplier the run needs no push-over lead and no minimum distance.
 */
public class StrikeDroneEntity extends DroneEntity {

    public static final float ROTATION_SPEED_MULTIPLIER = 1.2F;

    public StrikeDroneEntity(EntityType<? extends StrikeDroneEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected float getRotationSpeedMultiplier() {
        return ROTATION_SPEED_MULTIPLIER;
    }

    // No landing gear to rest on.
    @Override
    protected float getGroundPitch() {
        return 0;
    }

    @Override
    protected Item getItem() {
        return SimplePlanesItems.STRIKE_DRONE_ITEM.get();
    }
}
