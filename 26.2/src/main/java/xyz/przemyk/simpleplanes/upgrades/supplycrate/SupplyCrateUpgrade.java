package xyz.przemyk.simpleplanes.upgrades.supplycrate;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.container.StorageContainer;
import xyz.przemyk.simpleplanes.misc.ChestTypes;
import xyz.przemyk.simpleplanes.entities.ParachuteEntity;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.LargeUpgrade;

public class SupplyCrateUpgrade extends LargeUpgrade {

    public static final int SIZE = ChestTypes.getSize(ChestTypes.VANILLA_CHEST_NAME);

    /** C4: NeoForge ItemStackHandler -> vanilla SimpleContainer. */
    public final SimpleContainer container = new SimpleContainer(SIZE);

    public SupplyCrateUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.SUPPLY_CRATE.get(), planeEntity);
    }

    @Override
    public void save(ValueOutput output) {
        container.storeAsItemList(output.list("Items", ItemStack.CODEC));
    }

    @Override
    public void load(ValueInput input) {
        container.fromItemList(input.listOrEmpty("Items", ItemStack.CODEC));
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {}

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {}

    @Override
    public void onRemoved() {
        if (planeEntity.level() instanceof ServerLevel serverLevel) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack itemStack = container.getItem(i);
                if (!itemStack.isEmpty()) {
                    planeEntity.spawnAtLocation(serverLevel, itemStack);
                }
            }
        }
    }

    @Override
    public ItemStack getItemStack() {
        return SimplePlanesItems.SUPPLY_CRATE.get().getDefaultInstance();
    }

    @Override
    public boolean hasStorage() {
        return true;
    }

    @Override
    public void openStorageGui(Player player, int cycleableContainerID) {
        String barrelId = BuiltInRegistries.ITEM.getKey(Items.BARREL).toString();
        player.openMenu(storageProvider(Component.translatable(SimplePlanesMod.MODID + ":supply_crate"), container, barrelId, cycleableContainerID));
    }

    @Override
    public boolean canBeDroppedAsPayload() {
        return true;
    }

    @Override
    public void dropAsPayload() {
        ParachuteEntity parachuteEntity = new ParachuteEntity(planeEntity.level(), container);
        parachuteEntity.setPos(planeEntity.position());
        parachuteEntity.setDeltaMovement(planeEntity.getDeltaMovement());
        planeEntity.level().addFreshEntity(parachuteEntity);
        remove();
    }
}
