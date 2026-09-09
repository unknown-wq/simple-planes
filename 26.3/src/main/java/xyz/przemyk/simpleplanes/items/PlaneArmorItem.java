package xyz.przemyk.simpleplanes.items;

import net.minecraft.world.item.Item;

/**
 * TODO(port-26.2): DISABLED — {@code isEnchantable}/{@code getEnchantmentValue}/{@code supportsEnchantment}
 * no longer exist on {@link Item} in 26.2; enchantability is the {@code minecraft:enchantable} data
 * component (see {@code Item.Properties#enchantable(int)} in /opt/mc-src/net/minecraft/world/item/Item.java:433).
 * The enchantment value 9 moved to the registration in SimplePlanesItems, and the
 * "always allow Protection" allowance became data: Protection is gated by the vanilla
 * {@code #minecraft:enchantable/armor} item tag, which
 * {@code data/minecraft/tags/item/enchantable/armor.json} puts this item into. Without that tag the
 * enchantability alone offers nothing, {@code ArmorUpgrade} reads level 0 for every plate, and the
 * enchanted-armour half of the upgrade is unreachable. The tag is coarser than the original
 * allowance — it opens every armour enchantment, not Protection alone — but Protection is the only
 * one {@code ArmorUpgrade} reads.
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
