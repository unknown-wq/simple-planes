package xyz.przemyk.simpleplanes.entities;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import xyz.przemyk.simpleplanes.autopilot.Blast;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;

/**
 * A one-way attack drone, launched by the strike tool and never recovered: nobody rides it, nothing
 * drops when it goes, and it carries the small fixed charge of {@link Blast#forDrone()} whatever the
 * tool is set to. {@code PlaneAutopilot#tickStrike} fuzes it at the closest point of approach.
 */
public abstract class DroneEntity extends PlaneEntity {

    protected DroneEntity(EntityType<? extends DroneEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    public Blast warhead(Blast asked) {
        return asked.forDrone();
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return false;
    }

    // The strike launcher fits a booster; nothing else has anywhere to go.
    @Override
    protected boolean acceptsUpgrade(UpgradeType type) {
        return type == SimplePlanesUpgrades.BOOSTER.get();
    }

    // Single use: the airframe is destroyed with its warhead.
    @Override
    protected void dropItem(ServerLevel serverLevel) {}
}
