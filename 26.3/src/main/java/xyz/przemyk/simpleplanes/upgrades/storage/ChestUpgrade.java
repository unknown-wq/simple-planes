package xyz.przemyk.simpleplanes.upgrades.storage;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.container.StorageContainer;
import xyz.przemyk.simpleplanes.misc.ChestTypes;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.LargeUpgrade;

public class ChestUpgrade extends LargeUpgrade {

    private static final StreamCodec<RegistryFriendlyByteBuf, Holder<Item>> ITEM_STREAM_CODEC = ByteBufCodecs.holderRegistry(Registries.ITEM);

    public static final int SIZE = ChestTypes.getSize(ChestTypes.VANILLA_CHEST_NAME);

    /** C4: NeoForge ItemStackHandler -> vanilla SimpleContainer. */
    public final SimpleContainer container = new SimpleContainer(SIZE);
    public Item chestType = Items.CHEST;

    public ChestUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.CHEST.get(), planeEntity);
    }

    @Override
    public void save(ValueOutput output) {
        container.storeAsItemList(output.list("Items", ItemStack.CODEC));
        output.putString("ChestType", BuiltInRegistries.ITEM.getKey(chestType).toString());
    }

    @Override
    public void load(ValueInput input) {
        container.fromItemList(input.listOrEmpty("Items", ItemStack.CODEC));
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(input.getStringOr("ChestType", "minecraft:chest")));
        chestType = item == null ? Items.CHEST : item;
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {
        ITEM_STREAM_CODEC.encode(buffer, Holder.direct(chestType));
    }

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {
        chestType = ITEM_STREAM_CODEC.decode(buffer).value();
    }

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
        return chestType.getDefaultInstance();
    }

    @Override
    public void onApply(ItemStack itemStack) {
        chestType = itemStack.getItem();
    }

    @Override
    public boolean hasStorage() {
        return true;
    }

    @Override
    public void openStorageGui(Player player, int cycleableContainerID) {
        String chestTypeId = BuiltInRegistries.ITEM.getKey(chestType).toString();
        player.openMenu(storageProvider(Component.translatable(SimplePlanesMod.MODID + ":chest"), container, chestTypeId, cycleableContainerID));
    }
}
