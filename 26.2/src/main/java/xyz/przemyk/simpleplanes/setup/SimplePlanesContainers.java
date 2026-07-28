package xyz.przemyk.simpleplanes.setup;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.container.ModifyUpgradesContainer;
import xyz.przemyk.simpleplanes.container.PlaneInventoryContainer;
import xyz.przemyk.simpleplanes.container.PlaneWorkbenchContainer;
import xyz.przemyk.simpleplanes.container.StorageContainer;

import java.util.function.Supplier;

public class SimplePlanesContainers {

    /** Class-load hook — menu types are registered eagerly below (contract C1). */
    public static void init() {
    }

    private static <T extends AbstractContainerMenu> Supplier<MenuType<T>> register(String name, MenuType<T> menuType) {
        MenuType<T> value = Registry.register(BuiltInRegistries.MENU,
            Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, name), menuType);
        return () -> value;
    }

    public static final Supplier<MenuType<PlaneWorkbenchContainer>> PLANE_WORKBENCH =
        register("plane_workbench", new MenuType<>(PlaneWorkbenchContainer::new, FeatureFlags.VANILLA_SET));

    public static final Supplier<MenuType<ModifyUpgradesContainer>> UPGRADES_REMOVAL =
        register("upgrades_removal", new ExtendedMenuType<ModifyUpgradesContainer, Integer>(
            ModifyUpgradesContainer::new, ByteBufCodecs.VAR_INT));

    public static final Supplier<MenuType<StorageContainer>> STORAGE =
        register("storage", new ExtendedMenuType<StorageContainer, StorageContainer.StorageData>(
            StorageContainer::new, StorageContainer.StorageData.STREAM_CODEC));

    public static final Supplier<MenuType<PlaneInventoryContainer>> PLANE_INVENTORY =
        register("plane_inventory", new ExtendedMenuType<PlaneInventoryContainer, Integer>(
            PlaneInventoryContainer::new, ByteBufCodecs.VAR_INT));

}
