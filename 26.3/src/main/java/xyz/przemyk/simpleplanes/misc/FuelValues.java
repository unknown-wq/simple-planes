package xyz.przemyk.simpleplanes.misc;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;

import java.util.Optional;

/**
 * Burn times for the furnace engine.
 *
 * <p>26.2 answered both questions through {@code Level#fuelValues()} — a server-built
 * {@code FuelValues} table with {@code isFuel(stack)} and {@code burnDuration(stack)}. 26.3 deletes
 * that class and makes fuel a data component instead: an item is fuel iff it carries
 * {@link DataComponents#COOKING_FUEL}, and its burn time is a
 * {@link net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProvider} resolved against a
 * {@link LootContext}. {@code AbstractFurnaceBlockEntity#getBurnDuration} is the vanilla call and
 * this mirrors it.
 *
 * <p>The context vanilla passes is a block-entity one, carrying BLOCK_STATE / BLOCK_ENTITY /
 * CONTAINER / ORIGIN, and the only thing the stock cooking providers read out of it is BLOCK_STATE —
 * {@code minecraft:block/fast_cooking} matches a smoker or a blast furnace and halves the burn time.
 * A plane's furnace engine is an entity and has no block state at all, so the context here is built
 * over {@link LootContextParamSets#EMPTY}: the predicate reads the parameter with
 * {@code getOptionalParameter}, gets null, and evaluates false, which selects the ordinary
 * (non-smoker) burn time. That is the 26.2 number — {@code FuelValues} had no fast variant either.
 */
public final class FuelValues {

    private FuelValues() {
    }

    /** Whether this stack is fuel at all. Mirrors {@code AbstractFurnaceMenu#isFuel}. */
    public static boolean isFuel(ItemStack stack) {
        return stack.has(DataComponents.COOKING_FUEL);
    }

    /**
     * Burn time in ticks, or 0 if the stack is not fuel or the level is not a server level.
     * Resolving needs a {@link ServerLevel} because the number providers are registry entries.
     */
    public static int burnDuration(Level level, ItemStack stack) {
        if (!(level instanceof ServerLevel serverLevel) || !isFuel(stack)) {
            return 0;
        }
        return ResolvableInt.getFromItem(
            stack, DataComponents.COOKING_FUEL, CookingFuel::burnTime, lootContext(serverLevel), 0);
    }

    private static LootContext lootContext(ServerLevel level) {
        return new LootContext.Builder(new LootParams.Builder(level).create(LootContextParamSets.EMPTY))
            .create(Optional.empty());
    }
}
