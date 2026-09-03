package xyz.przemyk.simpleplanes.upgrades.folding;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;

public class FoldingUpgrade extends Upgrade {

    public FoldingUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.FOLDING.get(), planeEntity);
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {}

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {}

    @Override
    public ItemStack getItemStack() {
        return SimplePlanesItems.FOLDING.get().getDefaultInstance();
    }
}
