package xyz.przemyk.simpleplanes.entities;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.joml.Vector3f;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.datapack.PlanePayloadReloadListener;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;

/**
 * One-seat mini helicopter. Stub: no tuning getter is overridden yet, so it flies exactly as
 * {@link HelicopterEntity}. See MINI-HELI-MODEL.md for the seat and the livery.
 */
public class MiniHelicopterEntity extends HelicopterEntity {

    public static final TagKey<Block> MEDICAL_TAG = TagKey.create(Registries.BLOCK,
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "mini_heli_medical"));

    public MiniHelicopterEntity(EntityType<? extends MiniHelicopterEntity> entityType, Level level) {
        super(entityType, level);
    }

    public boolean hasMedicalLivery() {
        return getMaterial().builtInRegistryHolder().is(MEDICAL_TAG);
    }

    // Players only: this also keeps LargeAirframeEntity's livestock pickup out, which mounts via startRiding.
    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty() && passenger instanceof Player;
    }

    @Override
    public boolean tryToAddUpgrade(Player player, ItemStack itemStack) {
        Item item = itemStack.getItem();
        if (SimplePlanesUpgrades.getLargeUpgradeFromItem(item).isPresent()
            || PlanePayloadReloadListener.payloadEntries.containsKey(item)) {
            return false;
        }
        return super.tryToAddUpgrade(player, itemStack);
    }

    // Also closes the wrench screen's route for large upgrades and payloads, which only asks canAddUpgrade.
    @Override
    protected boolean acceptsUpgrade(UpgradeType type) {
        return type != SimplePlanesUpgrades.SEATS.get()
            && type != SimplePlanesUpgrades.SHOOTER.get()
            && type != SimplePlanesUpgrades.FLOATY_BEDDING.get()
            && type != SimplePlanesUpgrades.PAYLOAD.get()
            && !SimplePlanesUpgrades.LARGE_ITEM_UPGRADE_MAP.containsValue(type);
    }

    @Override
    public void positionRider(Entity passenger, MoveFunction moveFunction) {
        positionRiderGeneric(passenger);
        if (getPassengers().indexOf(passenger) == 0) {
            float seatY = getPassengersRidingOffset() + getEntityYOffset(passenger);
            // Rotate about the render pivot (0, 0.375, 0) so the pilot stays put in the cabin.
            Vector3f pos = transformPos(new Vector3f(0, seatY - 0.375f, 0.625f)).add(0, 0.375f, 0);
            moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
        }
    }

    @Override
    public float getPassengersRidingOffset() {
        return 0.4f;
    }

    @Override
    public double getCameraDistanceMultiplayer() {
        return 1.0;
    }

    @Override
    public int getFuelCost() {
        return 4;
    }

    @Override
    protected Item getItem() {
        return SimplePlanesItems.MINI_HELICOPTER_ITEM.get();
    }
}
