package xyz.przemyk.simpleplanes.upgrades;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import xyz.przemyk.simpleplanes.container.StorageContainer;
import xyz.przemyk.simpleplanes.entities.LargePlaneEntity;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

public abstract class LargeUpgrade extends Upgrade {

    public LargeUpgrade(UpgradeType type, PlaneEntity planeEntity) {
        super(type, planeEntity);
        if (planeEntity instanceof LargePlaneEntity largePlaneEntity) {
            largePlaneEntity.hasLargeUpgrade = true;
        }
    }

    @Override
    public void remove() {
        if (planeEntity instanceof LargePlaneEntity largePlaneEntity) {
            largePlaneEntity.hasLargeUpgrade = false;
        }
        super.remove();
    }

    public boolean hasStorage() {
        return false;
    }

    public void openStorageGui(Player player, int cycleableContainerID) {}

    /**
     * NeoForge's {@code openMenu(provider, buf -> { buf.writeUtf(type); buf.writeByte(id); })} is
     * gone; on Fabric the extra screen-opening data comes from an {@link ExtendedMenuProvider}
     * whose codec is declared on the {@code ExtendedMenuType} in {@code SimplePlanesContainers}.
     */
    protected static ExtendedMenuProvider<StorageContainer.StorageData> storageProvider(
        Component title, Container container, String chestType, int cycleableContainerID) {
        StorageContainer.StorageData data = new StorageContainer.StorageData(chestType, cycleableContainerID);
        return new ExtendedMenuProvider<>() {
            @Override
            public StorageContainer.StorageData getScreenOpeningData(ServerPlayer serverPlayer) {
                return data;
            }

            @Override
            public Component getDisplayName() {
                return title;
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
                return new StorageContainer(containerId, inventory, container, chestType, cycleableContainerID);
            }
        };
    }
}
