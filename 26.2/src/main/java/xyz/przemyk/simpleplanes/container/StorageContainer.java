package xyz.przemyk.simpleplanes.container;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import xyz.przemyk.simpleplanes.entities.CargoPlaneEntity;
import xyz.przemyk.simpleplanes.entities.LargePlaneEntity;
import xyz.przemyk.simpleplanes.misc.ChestTypes;
import xyz.przemyk.simpleplanes.setup.SimplePlanesContainers;

public class StorageContainer extends AbstractContainerMenu implements CycleableContainer {

    public final int rowCount;
    public final int size;
    public final String chestType;
    public final int cycleableContainerID;

    /** Extra spawn data for the Fabric {@code ExtendedMenuType} (replaces the NeoForge buffer writer). */
    public record StorageData(String chestType, int cycleableContainerID) {
        public static final StreamCodec<RegistryFriendlyByteBuf, StorageData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StorageData::chestType,
            ByteBufCodecs.VAR_INT, StorageData::cycleableContainerID,
            StorageData::new
        );
    }

    /** Client-side constructor — the container contents arrive through the normal slot sync. */
    public StorageContainer(int id, Inventory playerInventory, StorageData data) {
        this(id, playerInventory, new SimpleContainer(ChestTypes.getSize(data.chestType())), data.chestType(), data.cycleableContainerID());
    }

    public StorageContainer(int id, Inventory playerInventory, Container container, String chestType, int cycleableContainerID) {
        super(SimplePlanesContainers.STORAGE.get(), id);
        rowCount = ChestTypes.getRowCount(chestType);
        ChestTypes.addSlots(chestType, container, rowCount, playerInventory, this::addSlot);
        size = container.getContainerSize();
        this.chestType = chestType;
        this.cycleableContainerID = cycleableContainerID;
    }

    @Override
    public boolean stillValid(Player playerIn) {
        Entity entity = playerIn.getVehicle();
        if (entity instanceof LargePlaneEntity largePlaneEntity && entity.isAlive()) {
            return largePlaneEntity.hasStorageUpgrade();
        } else if (entity instanceof CargoPlaneEntity) {
            return entity.isAlive();
        }

        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player playerIn, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack itemstack1 = slot.getItem();
            itemstack = itemstack1.copy();
            if (index < size) {
                if (!this.moveItemStackTo(itemstack1, size, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(itemstack1, 0, size, false)) {
                return ItemStack.EMPTY;
            }

            if (itemstack1.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }

        return itemstack;
    }

    @Override
    public int cycleableContainerID() {
        return cycleableContainerID;
    }
}
