package xyz.przemyk.simpleplanes.misc;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Drop-in replacement for the NeoForge item-handler class of the same name.
 * <p>
 * Fabric has no capability/item-handler system, so this is a plain vanilla {@link Container}
 * that keeps the exact same public API shape the mod used on NeoForge
 * ({@code getSlots}, {@code getStackInSlot}, {@code setStackInSlot}, {@code insertItem},
 * {@code extractItem}, {@code setSize}, {@code serializeNBT}, {@code deserializeNBT}).
 * Because it implements {@link Container}, plain vanilla {@link net.minecraft.world.inventory.Slot}
 * works with it directly — NeoForge's {@code SlotItemHandler} is no longer needed.
 */
public class ItemStackHandler implements Container {

    protected NonNullList<ItemStack> stacks;

    public ItemStackHandler() {
        this(1);
    }

    public ItemStackHandler(int size) {
        stacks = NonNullList.withSize(size, ItemStack.EMPTY);
    }

    public void setSize(int size) {
        stacks = NonNullList.withSize(size, ItemStack.EMPTY);
    }

    public int getSlots() {
        return stacks.size();
    }

    public ItemStack getStackInSlot(int slot) {
        validateSlotIndex(slot);
        return stacks.get(slot);
    }

    public void setStackInSlot(int slot, ItemStack stack) {
        validateSlotIndex(slot);
        stacks.set(slot, stack);
        onContentsChanged(slot);
    }

    public int getSlotLimit(int slot) {
        return 99;
    }

    public boolean isItemValid(int slot, ItemStack stack) {
        return true;
    }

    protected int getStackLimit(int slot, ItemStack stack) {
        return Math.min(getSlotLimit(slot), stack.getMaxStackSize());
    }

    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (!isItemValid(slot, stack)) {
            return stack;
        }
        validateSlotIndex(slot);

        ItemStack existing = stacks.get(slot);

        int limit = getStackLimit(slot, stack);

        if (!existing.isEmpty()) {
            if (!ItemStack.isSameItemSameComponents(stack, existing)) {
                return stack;
            }
            limit -= existing.getCount();
        }

        if (limit <= 0) {
            return stack;
        }

        boolean reachedLimit = stack.getCount() > limit;

        if (!simulate) {
            if (existing.isEmpty()) {
                stacks.set(slot, reachedLimit ? stack.copyWithCount(limit) : stack);
            } else {
                existing.grow(reachedLimit ? limit : stack.getCount());
            }
            onContentsChanged(slot);
        }

        return reachedLimit ? stack.copyWithCount(stack.getCount() - limit) : ItemStack.EMPTY;
    }

    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount == 0) {
            return ItemStack.EMPTY;
        }
        validateSlotIndex(slot);

        ItemStack existing = stacks.get(slot);
        if (existing.isEmpty()) {
            return ItemStack.EMPTY;
        }

        int toExtract = Math.min(amount, existing.getMaxStackSize());

        if (existing.getCount() <= toExtract) {
            if (!simulate) {
                stacks.set(slot, ItemStack.EMPTY);
                onContentsChanged(slot);
                return existing;
            }
            return existing.copy();
        } else {
            if (!simulate) {
                stacks.set(slot, existing.copyWithCount(existing.getCount() - toExtract));
                onContentsChanged(slot);
            }
            return existing.copyWithCount(toExtract);
        }
    }

    protected void onContentsChanged(int slot) {
    }

    protected void validateSlotIndex(int slot) {
        if (slot < 0 || slot >= stacks.size()) {
            throw new IllegalArgumentException("Slot " + slot + " not in valid range - [0," + stacks.size() + ")");
        }
    }

    // ---------------------------------------------------------------- serialization

    public void save(ValueOutput output) {
        output.putInt("Size", stacks.size());
        ContainerHelper.saveAllItems(output, stacks, true);
    }

    public void load(ValueInput input) {
        int size = input.getIntOr("Size", stacks.size());
        if (size != stacks.size()) {
            setSize(size);
        }
        ContainerHelper.loadAllItems(input, stacks);
    }

    public CompoundTag serializeNBT(HolderLookup.Provider registries) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        save(output);
        return output.buildResult();
    }

    public void deserializeNBT(HolderLookup.Provider registries, CompoundTag tag) {
        load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
    }

    // ---------------------------------------------------------------- Container

    @Override
    public int getContainerSize() {
        return stacks.size();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return getStackInSlot(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        return extractItem(slot, count, false);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        validateSlotIndex(slot);
        ItemStack stack = stacks.get(slot);
        stacks.set(slot, ItemStack.EMPTY);
        return stack;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        setStackInSlot(slot, stack);
    }

    @Override
    public int getMaxStackSize() {
        return 99;
    }

    @Override
    public void setChanged() {
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return isItemValid(slot, stack);
    }

    @Override
    public void clearContent() {
        stacks.replaceAll(ignored -> ItemStack.EMPTY);
    }
}
