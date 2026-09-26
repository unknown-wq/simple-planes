package xyz.przemyk.simpleplanes.entities;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;

/**
 * 14-seat regional airliner: the mini airliner's narrow size, one seat either side of the aisle. Seats,
 * boarding, hitboxes, skin and logos are {@link AirlinerEntity}'s. It is lighter (collision mass 1.3 against
 * 1.6), and the numbers that depend on mass are scaled by that ratio, m = 0.8125: take-off speed by sqrt(m),
 * the control rates and fuel use by 1/m and m. Numbers: design/DESIGN.md section 4.4.
 */
public class RegionalAirlinerEntity extends AirlinerEntity {

    public RegionalAirlinerEntity(EntityType<? extends RegionalAirlinerEntity> entityType, Level level) {
        super(entityType, level, AirlinerLayout.REGIONAL);
    }

    @Override
    protected TempMotionVars getMotionVars() {
        TempMotionVars vars = super.getMotionVars();
        vars.takeOffSpeed = 0.54;
        return vars;
    }

    @Override
    protected float getRotationSpeedMultiplier() {
        return 0.43f;
    }

    @Override
    protected float maxRollRate() {
        return 3.0f;
    }

    @Override
    protected EntityType<AirlinerPartEntity> partType() {
        return SimplePlanesEntities.REGIONAL_AIRLINER_PART.get();
    }

    @Override
    public double getCameraDistanceMultiplayer() {
        return 1.4;
    }

    @Override
    public int getFuelCost() {
        return 8;
    }

    @Override
    protected Item getItem() {
        return SimplePlanesItems.REGIONAL_AIRLINER_ITEM.get();
    }
}
