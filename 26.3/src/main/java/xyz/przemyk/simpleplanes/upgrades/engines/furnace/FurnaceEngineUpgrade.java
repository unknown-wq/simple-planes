package xyz.przemyk.simpleplanes.upgrades.engines.furnace;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import xyz.przemyk.simpleplanes.container.slots.FuelSlot;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.engines.EngineUpgrade;

import java.util.function.Function;

public class FurnaceEngineUpgrade extends EngineUpgrade {

    /** C4: NeoForge ItemStackHandler -> vanilla SimpleContainer. */
    public final SimpleContainer container = new SimpleContainer(1);
    public int burnTime;
    public int burnTimeTotal;

    public FurnaceEngineUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.FURNACE_ENGINE.get(), planeEntity);
    }

    @Override
    public void tick() {
        if (burnTime > 0) {
            burnTime -= planeEntity.getFuelCost();
            updateClient();
        } else if (planeEntity.getThrottle() > 0) {
            ItemStack itemStack = container.getItem(0);
            int itemBurnTime = xyz.przemyk.simpleplanes.misc.FuelValues.burnDuration(planeEntity.level(), itemStack);
            if (itemBurnTime > 0) {
                burnTimeTotal = itemBurnTime;
                burnTime = itemBurnTime;
                ItemStackTemplate remainder = itemStack.getItem().getCraftingRemainder();
                if (remainder != null) {
                    container.setItem(0, remainder.create());
                } else {
                    itemStack.shrink(1);
                    container.setItem(0, itemStack);
                }
                updateClient();
            }
        }
    }

    @Override
    public boolean isPowered() {
        return burnTime > 0;
    }

    @Override
    public void save(ValueOutput output) {
        container.storeAsItemList(output.list("item", ItemStack.CODEC));
        output.putInt("burnTime", burnTime);
        output.putInt("burnTimeTotal", burnTimeTotal);
    }

    @Override
    public void load(ValueInput input) {
        container.fromItemList(input.listOrEmpty("item", ItemStack.CODEC));
        burnTime = input.getIntOr("burnTime", 0);
        burnTimeTotal = input.getIntOr("burnTimeTotal", 0);
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, container.getItem(0));
        buffer.writeVarInt(burnTime);
        buffer.writeVarInt(burnTimeTotal);
    }

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {
        container.setItem(0, ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
        burnTime = buffer.readVarInt();
        burnTimeTotal = buffer.readVarInt();
    }

    @Override
    public void onRemoved() {
        if (planeEntity.level() instanceof ServerLevel serverLevel) {
            planeEntity.spawnAtLocation(serverLevel, container.getItem(0));
        }
    }

    @Override
    public ItemStack getItemStack() {
        return SimplePlanesItems.FURNACE_ENGINE.get().getDefaultInstance();
    }

    @Override
    public void addContainerData(Function<Slot, Slot> addSlot, Function<DataSlot, DataSlot> addDataSlot) {
        addSlot.apply(new FuelSlot(container, 0, 152, 62, planeEntity::level));
    }
}
