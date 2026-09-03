package xyz.przemyk.simpleplanes.container.slots;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.function.Supplier;

/**
 * NeoForge's {@code SlotItemHandler} is gone — this is a plain vanilla {@link Slot} over a
 * {@link Container}. Burn time is data-driven in 26.2 ({@code Level#fuelValues()}), which a Slot
 * cannot reach on its own, so pass a level supplier when you have one; without it the slot accepts
 * anything and the engine itself rejects non-fuel.
 */
public class FuelSlot extends Slot {

    private final Supplier<Level> levelSupplier;

    public FuelSlot(Container container, int index, int xPosition, int yPosition) {
        this(container, index, xPosition, yPosition, null);
    }

    public FuelSlot(Container container, int index, int xPosition, int yPosition, Supplier<Level> levelSupplier) {
        super(container, index, xPosition, yPosition);
        this.levelSupplier = levelSupplier;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        if (levelSupplier == null) {
            return true;
        }
        Level level = levelSupplier.get();
        return level == null || level.fuelValues().isFuel(stack);
    }
}
