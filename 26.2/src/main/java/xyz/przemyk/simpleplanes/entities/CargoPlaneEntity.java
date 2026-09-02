package xyz.przemyk.simpleplanes.entities;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.joml.Vector3f;
import xyz.przemyk.simpleplanes.datapack.PayloadEntry;
import xyz.przemyk.simpleplanes.datapack.PlanePayloadReloadListener;
import xyz.przemyk.simpleplanes.network.*;
import xyz.przemyk.simpleplanes.setup.SimplePlanesConfig;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesRegistries;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.LargeUpgrade;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;
import xyz.przemyk.simpleplanes.upgrades.engines.EngineUpgrade;
import xyz.przemyk.simpleplanes.upgrades.payload.PayloadUpgrade;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class CargoPlaneEntity extends PlaneEntity {

    public List<LargeUpgrade> largeUpgrades = new ArrayList<>(8);

    public CargoPlaneEntity(EntityType<? extends CargoPlaneEntity> entityType, Level level) {
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
    protected boolean tryToAddUpgrade(Player playerEntity, ItemStack itemStack) {
        if (super.tryToAddUpgrade(playerEntity, itemStack)) {
            return true;
        }
        if (largeUpgrades.size() < 8) {
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
    public boolean canAddUpgrade(UpgradeType upgradeType) {
        // Disabling jukebox for now since it sends a packet which is unsupported for multiple upgrades of the same type
        return upgradeType != SimplePlanesUpgrades.JUKEBOX.get() &&
                upgradeType != SimplePlanesUpgrades.SHOOTER.get() && super.canAddUpgrade(upgradeType);
    }

    @Override
    protected void addUpgrade(Player playerEntity, ItemStack itemStack, Upgrade upgrade) {
        if (!level().isClientSide()) {
            upgrade.onApply(itemStack);
            if (!playerEntity.isCreative()) {
                itemStack.shrink(1);
            }
            UpgradeType upgradeType = upgrade.getType();

            if (upgrade instanceof LargeUpgrade largeUpgrade) {
                largeUpgrades.add(largeUpgrade);
                SimplePlanesNetworking.sendToPlayersTrackingEntity(this,
                    NewCargoUpgradePacket.create(SimplePlanesRegistries.UPGRADE_TYPE.getKey(upgradeType), getId(), largeUpgrade));
            } else {
                upgrades.put(SimplePlanesRegistries.UPGRADE_TYPE.getKey(upgradeType), upgrade);
                if (upgradeType.isEngine) {
                    engineUpgrade = (EngineUpgrade) upgrade;
                }
                SimplePlanesNetworking.sendToPlayersTrackingEntity(this,
                    UpdateUpgradePacket.create(true, SimplePlanesRegistries.UPGRADE_TYPE.getKey(upgradeType), this));
            }
        }
    }

    public void addCargoUpgradeIntWorkbench(ItemStack itemStack, LargeUpgrade largeUpgrade) {
        largeUpgrade.onApply(itemStack);
        largeUpgrades.add(largeUpgrade);
        SimplePlanesNetworking.sendToPlayersTrackingEntity(this,
            NewCargoUpgradePacket.create(SimplePlanesRegistries.UPGRADE_TYPE.getKey(largeUpgrade.getType()), getId(), largeUpgrade));
    }

    public void readNewCargoUpgradePacket(Identifier upgradeID, RegistryFriendlyByteBuf packetBuffer) {
        UpgradeType upgradeType = SimplePlanesRegistries.UPGRADE_TYPE.getValue(upgradeID);
        if (upgradeType == null) {
            return;
        }
        Upgrade upgrade = upgradeType.instanceSupplier.apply(this);
        if (upgrade instanceof LargeUpgrade largeUpgrade) {
            largeUpgrades.add(largeUpgrade);
        }
        upgrade.readPacket(packetBuffer);
    }

    /**
     * Server-authoritative, for the reason {@link LargeAirframeEntity#dropPayload()} gives and one
     * more that is specific to this airframe: {@link CargoUpgradeRemovedPacket} addresses a rack by
     * its index in {@link #largeUpgrades}, so a client that had already dropped one of its own
     * would apply every later removal to the wrong rack.
     */
    @Override
    public void dropPayload() {
        if (level().isClientSide()) {
            SimplePlanesClientNetworking.sendDropPayload();
            return;
        }
        for (int index = 0; index < largeUpgrades.size(); index++) {
            LargeUpgrade upgrade = largeUpgrades.get(index);
            if (upgrade.canBeDroppedAsPayload()) {
                upgrade.dropAsPayload();
                if (upgrade.removed) {
                    largeUpgrades.remove(index);
                    SimplePlanesNetworking.sendToPlayersTrackingEntity(this, new CargoUpgradeRemovedPacket((byte) index, getId()));
                }
                break;
            }
        }
    }

    public void removeCargoUpgrade(int index) {
        if (index < 0 || index >= largeUpgrades.size()) {
            return;
        }
        LargeUpgrade upgrade = largeUpgrades.remove(index);
        upgrade.onRemoved();
        upgrade.removed = true;

        if (!level().isClientSide()) {
            SimplePlanesNetworking.sendToPlayersTrackingEntity(this, new CargoUpgradeRemovedPacket((byte) index, getId()));
        }
    }

    @Override
    protected Item getItem() {
        return SimplePlanesItems.CARGO_PLANE_ITEM.get();
    }

    @Override
    protected float getGroundPitch() {
        return 0;
    }

    @Override
    public int getFuelCost() {
        return SimplePlanesConfig.CARGO_PLANE_FUEL_COST.get();
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        List<Entity> passengers = getPassengers();
        if (!upgrades.containsKey(SimplePlanesRegistries.UPGRADE_TYPE.getKey(SimplePlanesUpgrades.SEATS.get()))) {
            return passengers.size() < 2;
        } else {
            return passengers.size() < 6;
        }
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction moveFunction) {
        positionRiderGeneric(passenger);
        int index = getPassengers().indexOf(passenger);

        Vector3f pos = switch (index) {
            case 0 ->
                    transformPos(new Vector3f(0, getPassengersRidingOffset() + 1.0f, 1.5f));
            case 1 ->
                    transformPos(new Vector3f(0, getPassengersRidingOffset() + 1.0f - 0.125f, -12.0f));
            case 2 ->
                    transformPos(new Vector3f(0, getPassengersRidingOffset() + 1.0f - 0.625f, 2.875f));
            case 3 ->
                    transformPos(new Vector3f(0, getPassengersRidingOffset() + 1.0f - 0.625f, 3.75f));
            case 4 ->
                    transformPos(new Vector3f(0.6f, getPassengersRidingOffset() + 1.0f - 0.625f, -6.25f));
            default ->
                    transformPos(new Vector3f(-0.6f, getPassengersRidingOffset() + 1.0f - 0.625f, -6.25f));
        };

        moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
    }

    @Override
    public double getCameraDistanceMultiplayer() {
        return SimplePlanesConfig.CARGO_PLANE_CAMERA_DISTANCE_MULTIPLIER.get();
    }

    @Override
    protected float getRotationSpeedMultiplier() {
        return 0.2f;
    }

    @Override
    public void openContainer(Player player, int containerID) {
        if (containerID == 0) {
            player.openMenu(planeInventoryProvider(this));
        } else {
            int id = 0;
            for (LargeUpgrade upgrade : largeUpgrades) {
                if (upgrade.hasStorage()) {
                    id++;
                    if (containerID == id) {
                        upgrade.openStorageGui(player, id);
                    }
                }
            }
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);

        largeUpgrades.clear();
        for (ValueInput upgradeInput : input.childrenListOrEmpty("large_upgrades")) {
            Identifier identifier = Identifier.tryParse(upgradeInput.getStringOr("id", ""));
            if (identifier == null) {
                continue;
            }
            UpgradeType upgradeType = SimplePlanesRegistries.UPGRADE_TYPE.getValue(identifier);
            if (upgradeType != null) {
                Upgrade upgrade = upgradeType.instanceSupplier.apply(this);
                if (upgrade instanceof LargeUpgrade largeUpgrade) {
                    largeUpgrade.load(upgradeInput.childOrEmpty("nbt"));
                    largeUpgrades.add(largeUpgrade);
                }
            }
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);

        ValueOutput.ValueOutputList list = output.childrenList("large_upgrades");
        for (LargeUpgrade upgrade : largeUpgrades) {
            ValueOutput child = list.addChild();
            child.putString("id", SimplePlanesRegistries.UPGRADE_TYPE.getKey(upgrade.getType()).toString());
            upgrade.save(child.child("nbt"));
        }
    }

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buffer) {
        super.writeSpawnData(buffer);

        buffer.writeVarInt(largeUpgrades.size());
        for (LargeUpgrade upgrade : largeUpgrades) {
            Identifier upgradeID = SimplePlanesRegistries.UPGRADE_TYPE.getKey(upgrade.getType());
            buffer.writeIdentifier(upgradeID);
            upgrade.writePacket(buffer);
        }
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf additionalData) {
        super.readSpawnData(additionalData);
        int largeUpgradesSize = additionalData.readVarInt();
        for (int i = 0; i < largeUpgradesSize; i++) {
            Identifier upgradeID = additionalData.readIdentifier();
            UpgradeType upgradeType = SimplePlanesRegistries.UPGRADE_TYPE.getValue(upgradeID);
            if (upgradeType == null) {
                return;
            }
            Upgrade upgrade = upgradeType.instanceSupplier.apply(this);
            if (upgrade instanceof LargeUpgrade largeUpgrade) {
                largeUpgrades.add(largeUpgrade);
            }
            upgrade.readPacket(additionalData);
        }
    }
}
