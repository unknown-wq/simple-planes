package xyz.przemyk.simpleplanes.container.slots;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import xyz.przemyk.simpleplanes.container.ModifyUpgradesContainer;
import xyz.przemyk.simpleplanes.datapack.PlanePayloadReloadListener;
import xyz.przemyk.simpleplanes.entities.LargeAirframeEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;

public class PlaneUpgradeSlot extends Slot {

    private final ModifyUpgradesContainer gui;

    public PlaneUpgradeSlot(Container container, int index, int xPosition, int yPosition, ModifyUpgradesContainer gui) {
        super(container, index, xPosition, yPosition);
        this.gui = gui;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return SimplePlanesUpgrades.ITEM_UPGRADE_MAP.containsKey(stack.getItem()) ||
                (gui.planeEntity instanceof LargeAirframeEntity &&
                        (SimplePlanesUpgrades.LARGE_ITEM_UPGRADE_MAP.containsKey(stack.getItem()) || PlanePayloadReloadListener.payloadEntries.containsKey(stack.getItem())));
    }
}
