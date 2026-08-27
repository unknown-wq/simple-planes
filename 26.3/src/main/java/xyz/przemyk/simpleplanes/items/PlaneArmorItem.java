package xyz.przemyk.simpleplanes.items;

import net.minecraft.world.item.Item;

/**
 * TODO(port-26.2): DISABLED — {@code isEnchantable}/{@code getEnchantmentValue}/{@code supportsEnchantment}
 * no longer exist on {@link Item} in 26.2; enchantability is the {@code minecraft:enchantable} data
 * component (see {@code Item.Properties#enchantable(int)} in /opt/mc-src/net/minecraft/world/item/Item.java:433).
 * The enchantment value 9 moved to the registration in SimplePlanesItems. The extra
 * "always allow Protection" allowance is dropped — Protection is gated by the vanilla
 * {@code #minecraft:enchantable/armor} item tag now, which this item is not in.
 * <p>
 * Original body:
 * <pre>
 * &#64;Override public boolean isEnchantable(ItemStack stack) { return true; }
 * &#64;Override public int getEnchantmentValue() { return 9; }
 * &#64;Override public boolean supportsEnchantment(ItemStack stack, Holder&lt;Enchantment&gt; enchantment) {
 *     if (enchantment == Enchantments.PROTECTION) return true;
 *     return super.supportsEnchantment(stack, enchantment);
 * }
 * </pre>
 */
public class PlaneArmorItem extends Item {

    public PlaneArmorItem(Properties properties) {
        super(properties);
    }
}
