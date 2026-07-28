package xyz.przemyk.simpleplanes.upgrades.heal;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;

public class HealingUpgrade extends Upgrade {

    public HealingUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.HEALING.get(), planeEntity);
    }

    private int cooldown = 10;

    @Override
    public void save(ValueOutput output) {
        output.putInt("cooldown", cooldown);
    }

    @Override
    public void load(ValueInput input) {
        cooldown = input.getIntOr("cooldown", 10);
    }

    @Override
    public void tick() {
        if (cooldown > 0) {
            --cooldown;
        } else {
            remove();
        }
    }

    @Override
    public void onApply(ItemStack itemStack) {
        int health = planeEntity.getHealth();
        int m = planeEntity.getMaxHealth() * 2;
        if (health < m) {
            int heal = planeEntity.getOnGround() ? 2 : 1;
            planeEntity.setHealth(Math.min(health + heal, m));
        }
        planeEntity.goldenHeartsTimeout = 0;
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {}

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {}

    @Override
    public ItemStack getItemStack() {
        return SimplePlanesItems.HEALING.get().getDefaultInstance();
    }
}
