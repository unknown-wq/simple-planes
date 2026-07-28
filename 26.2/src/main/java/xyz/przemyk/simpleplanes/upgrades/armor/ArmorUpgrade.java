package xyz.przemyk.simpleplanes.upgrades.armor;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;

public class ArmorUpgrade extends Upgrade {

    private int protectionLevel = 0;

    public ArmorUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.ARMOR.get(), planeEntity);
    }

    public int getProtectionLevel() {
        return protectionLevel;
    }

    @Override
    public void onApply(ItemStack itemStack) {
        planeEntity.level().registryAccess().lookup(Registries.ENCHANTMENT)
            .flatMap(registry -> registry.get(Enchantments.PROTECTION))
            .ifPresent(enchant -> protectionLevel = EnchantmentHelper.getItemEnchantmentLevel(enchant, itemStack));
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {
        buffer.writeByte(protectionLevel);
    }

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {
        protectionLevel = buffer.readByte();
    }

    @Override
    public ItemStack getItemStack() {
        ItemStack itemStack = SimplePlanesItems.ARMOR.get().getDefaultInstance();
        if (protectionLevel > 0) {
            planeEntity.level().registryAccess().lookup(Registries.ENCHANTMENT)
                .flatMap(registry -> registry.get(Enchantments.PROTECTION))
                .ifPresent(enchant -> itemStack.enchant(enchant, protectionLevel));
        }
        return itemStack;
    }

    public float getReducedDamage(float amount) {
        return amount * (1.0f - (0.04f * getArmorValue()));
    }

    public int getArmorValue() {
        return 15 + (protectionLevel * 2);
    }

    @Override
    public void save(ValueOutput output) {
        output.putByte("protection", (byte) protectionLevel);
    }

    @Override
    public void load(ValueInput input) {
        protectionLevel = input.getByteOr("protection", (byte) 0);
    }
}
