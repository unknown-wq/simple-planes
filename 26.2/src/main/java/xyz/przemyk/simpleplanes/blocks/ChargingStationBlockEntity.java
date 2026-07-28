package xyz.przemyk.simpleplanes.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.misc.EnergyStorageWithSet;
import xyz.przemyk.simpleplanes.setup.SimplePlanesBlocks;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;
import xyz.przemyk.simpleplanes.upgrades.engines.electric.ElectricEngineUpgrade;

public class ChargingStationBlockEntity extends BlockEntity {

    public final EnergyStorageWithSet energyStorage = new EnergyStorageWithSet(1000);

    public ChargingStationBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(SimplePlanesBlocks.CHARGING_STATION_TILE.get(), blockPos, blockState);
    }

    public static void tick(ChargingStationBlockEntity blockEntity) {
        if (blockEntity.level == null) {
            return;
        }
        // Contract C4: no capability lookup on Fabric. The only energy consumer this ever charged was
        // a plane's electric engine, so we look that up directly.
        for (Entity entity : blockEntity.level.getEntities((Entity) null, new AABB(blockEntity.worldPosition.above()))) {
            if (entity instanceof PlaneEntity planeEntity) {
                for (Upgrade upgrade : planeEntity.upgrades.values()) {
                    if (upgrade instanceof ElectricEngineUpgrade electricEngineUpgrade) {
                        int available = blockEntity.energyStorage.extractEnergy(1000, true);
                        int accepted = electricEngineUpgrade.energyStorage.receiveEnergy(available, false);
                        blockEntity.energyStorage.extractEnergy(accepted, false);
                    }
                }
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("energy", energyStorage.getEnergyStored());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        energyStorage.setEnergy(input.getIntOr("energy", 0));
    }
}
