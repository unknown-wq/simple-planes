package xyz.przemyk.simpleplanes.upgrades.engines.electric;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.misc.EnergyStorageWithSet;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.engines.EngineUpgrade;

public class ElectricEngineUpgrade extends EngineUpgrade {

    public static final int CAPACITY = 1_500_000;

    public final EnergyStorageWithSet energyStorage = new EnergyStorageWithSet(CAPACITY);

    public ElectricEngineUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.ELECTRIC_ENGINE.get(), planeEntity);
        energyStorage.setOnChange(this::updateClient);
    }

    @Override
    public void tick() {
        if (planeEntity.getThrottle() > 0) {
            if (energyStorage.extractEnergy(12 * planeEntity.getFuelCost(), false) > 0) {
                updateClient();
            }
        }
    }

    @Override
    public boolean isPowered() {
        return energyStorage.getEnergyStored() > 12 * planeEntity.getFuelCost();
    }

    @Override
    public void save(ValueOutput output) {
        output.putInt("energy", energyStorage.getEnergyStored());
    }

    @Override
    public void load(ValueInput input) {
        energyStorage.setEnergy(Math.min(input.getIntOr("energy", 0), CAPACITY));
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(energyStorage.getEnergyStored());
    }

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {
        energyStorage.setEnergy(buffer.readVarInt());
    }

    @Override
    public ItemStack getItemStack() {
        return SimplePlanesItems.ELECTRIC_ENGINE.get().getDefaultInstance();
    }
}
