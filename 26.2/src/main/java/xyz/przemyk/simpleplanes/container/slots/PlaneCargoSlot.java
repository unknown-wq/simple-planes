package xyz.przemyk.simpleplanes.container.slots;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import xyz.przemyk.simpleplanes.datapack.PlanePayloadReloadListener;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;

public class PlaneCargoSlot extends Slot {

    public PlaneCargoSlot(Container container, int index, int xPosition, int yPosition) {
        super(container, index, xPosition, yPosition);
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return SimplePlanesUpgrades.LARGE_ITEM_UPGRADE_MAP.containsKey(stack.getItem()) || PlanePayloadReloadListener.payloadEntries.containsKey(stack.getItem());
    }
}
