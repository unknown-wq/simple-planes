package xyz.przemyk.simpleplanes.entities;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import xyz.przemyk.simpleplanes.datapack.PayloadEntry;
import xyz.przemyk.simpleplanes.datapack.PlanePayloadReloadListener;
import xyz.przemyk.simpleplanes.network.SUpgradeRemovedPacket;
import xyz.przemyk.simpleplanes.network.SimplePlanesClientNetworking;
import xyz.przemyk.simpleplanes.network.SimplePlanesNetworking;
import xyz.przemyk.simpleplanes.setup.SimplePlanesRegistries;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.LargeUpgrade;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;
import xyz.przemyk.simpleplanes.upgrades.payload.PayloadUpgrade;

import java.util.List;
import java.util.Optional;

/**
 * Everything a large, multi-seat airframe has that is <em>not</em> a flight model: the cabin, the
 * single large-upgrade bay, the payload rack, and the habit of collecting livestock.
 *
 * <h2>Why this class exists</h2>
 * {@link HelicopterEntity} used to extend {@link LargePlaneEntity} directly. It never wanted the
 * wing — it wanted the cabin. The result was a class that inherited a fixed-wing ground pitch, a
 * fixed-wing rotation-rate multiplier, a fixed-wing drag polynomial and a fixed-wing lift term, and
 * then stubbed out roughly half of them one override at a time; the ones it forgot were the ones
 * that made the old helicopter fly like a plane with the rudder unbolted. See
 * {@code HELICOPTER-PHYSICS.md}.
 *
 * <p>Splitting the cabin out of the wing means a helicopter can be a peer of a large plane rather
 * than a special case of one, and neither has to know anything about the other's aerodynamics.
 * Nothing about {@link LargePlaneEntity}'s behaviour changes: every member here was moved out of it
 * verbatim.
 *
 * <p><b>Type checks.</b> Code that asks "does this aircraft have a large-upgrade bay" — the upgrade
 * slot, the storage container, the modify-upgrades screen, {@link LargeUpgrade} itself — must test
 * for this class and not for {@link LargePlaneEntity}, or the helicopter silently loses its bay.
 */
public class LargeAirframeEntity extends PlaneEntity {

    public boolean hasLargeUpgrade = false;

    public LargeAirframeEntity(EntityType<? extends LargeAirframeEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    public void tick() {
        super.tick();

        List<Entity> list = level().getEntities(this, getBoundingBox().inflate(0.2F, -0.01F, 0.2F), EntitySelector.pushableBy(this));
        for (Entity entity : list) {
            if (!level().isClientSide() && !(getControllingPassenger() instanceof Player) &&
                !entity.hasPassenger(this) &&
                !entity.isPassenger() && entity instanceof LivingEntity && !(entity instanceof Player)) {
                entity.startRiding(this);
            }
        }
    }

    @Override
    public boolean tryToAddUpgrade(Player playerEntity, ItemStack itemStack) {
        if (super.tryToAddUpgrade(playerEntity, itemStack)) {
            return true;
        }
        if (!hasLargeUpgrade && getPassengers().size() < 2) {
            Optional<UpgradeType> upgradeTypeOptional = SimplePlanesUpgrades.getLargeUpgradeFromItem(itemStack.getItem());
            if (upgradeTypeOptional.map(upgradeType -> {
                if (canAddUpgrade(upgradeType)) {
                    Upgrade upgrade = upgradeType.instanceSupplier.apply(this);
                    addUpgrade(playerEntity, itemStack, upgrade);
                    return true;
                }
                return false;
            }).orElse(false)) {
                return true;
            }
            PayloadEntry payloadEntry = PlanePayloadReloadListener.payloadEntries.get(itemStack.getItem());
            if (payloadEntry != null) {
                addUpgrade(playerEntity, itemStack, new PayloadUpgrade(this, payloadEntry));
                return true;
            }
        }

        return false;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        List<Entity> passengers = getPassengers();
        if (passenger.getVehicle() == this || passenger instanceof PlaneEntity) {
            return false;
        }
        if (!upgrades.containsKey(SimplePlanesRegistries.UPGRADE_TYPE.getKey(SimplePlanesUpgrades.SEATS.get()))) {
            return passengers.size() <= 1 && (passengers.isEmpty() || !hasLargeUpgrade);
        } else {
            return hasLargeUpgrade ? passengers.size() < 3 : passengers.size() < 4;
        }
    }

    public float getEntityYOffset(Entity passenger) {
        if (passenger instanceof Villager) {
            return ((Villager) passenger).isBaby() ? -0.1f : -0.3f;
        }
        return -0.4f;
    }

    public boolean hasStorageUpgrade() {
        if (hasLargeUpgrade) {
            for (Upgrade upgrade : upgrades.values()) {
                if (upgrade instanceof LargeUpgrade largeUpgrade) {
                    return largeUpgrade.hasStorage();
                }
            }
        }

        return false;
    }

    /**
     * Server-authoritative, because a payload drop has to reach every client that tracks the
     * aircraft and not only the one that pressed the key. Dropping the rack locally and telling
     * nobody left the payload hanging under the plane on every other client until the entity was
     * re-tracked, and left their upgrade map disagreeing with the server's.
     */
    @Override
    public void dropPayload() {
        if (level().isClientSide()) {
            SimplePlanesClientNetworking.sendDropPayload();
            return;
        }
        for (Upgrade upgrade : upgrades.values()) {
            if (upgrade.canBeDroppedAsPayload()) {
                upgrade.dropAsPayload();
                if (upgrade.removed) {
                    Identifier upgradeID = SimplePlanesRegistries.UPGRADE_TYPE.getKey(upgrade.getType());
                    upgrades.remove(upgradeID);
                    SimplePlanesNetworking.sendToPlayersTrackingEntity(this, new SUpgradeRemovedPacket(upgradeID, getId()));
                }
                break;
            }
        }
    }
}
