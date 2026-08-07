package xyz.przemyk.simpleplanes.setup;

import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.container.PlaneWorkbenchContainer;
import xyz.przemyk.simpleplanes.items.DescriptionItem;
import xyz.przemyk.simpleplanes.items.ParachuteItem;
import xyz.przemyk.simpleplanes.items.PlaneArmorItem;
import xyz.przemyk.simpleplanes.items.PlaneItem;
import xyz.przemyk.simpleplanes.items.PlaneStrikeToolItem;
import xyz.przemyk.simpleplanes.items.RouteWandItem;
import xyz.przemyk.simpleplanes.items.RunwayToolItem;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

@SuppressWarnings("unused")
public class SimplePlanesItems {

    /** Class-load hook — items and the creative tab are registered eagerly below (contract C1). */
    public static void init() {
    }

    public static ResourceKey<Item> itemKey(String name) {
        return ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, name));
    }

    private static <T extends Item> Supplier<T> register(String name, Function<Item.Properties, T> factory, Item.Properties properties) {
        T value = Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, name),
            factory.apply(properties.setId(itemKey(name))));
        return () -> value;
    }

    private static Supplier<Item> registerSimple(String name) {
        return register(name, Item::new, new Item.Properties());
    }

    public static List<PlaneItem> getPlaneItems() {
        ArrayList<PlaneItem> planeItems = new ArrayList<>(4);
        planeItems.add(PLANE_ITEM.get());
        planeItems.add(LARGE_PLANE_ITEM.get());
        planeItems.add(CARGO_PLANE_ITEM.get());
        planeItems.add(HELICOPTER_ITEM.get());
        return planeItems;
    }

    public static final Supplier<Item> PROPELLER = registerSimple("propeller");

    public static final Supplier<Item> FLOATY_BEDDING = registerSimple("floaty_bedding");
    public static final Supplier<Item> BOOSTER = registerSimple("booster");
    public static final Supplier<Item> HEALING = registerSimple("healing");
    public static final Supplier<Item> ARMOR = register("armor", PlaneArmorItem::new, new Item.Properties().stacksTo(1).enchantable(9));
    public static final Supplier<Item> SOLAR_PANEL = register("solar_panel", Item::new, new Item.Properties().stacksTo(1));
    public static final Supplier<Item> FOLDING = registerSimple("folding");
    public static final Supplier<Item> SUPPLY_CRATE = registerSimple("supply_crate");
    public static final Supplier<Item> SEATS = registerSimple("seats");
    public static final Supplier<Item> SHOOTER = register("shooter",
        properties -> new DescriptionItem(properties, Component.translatable(SimplePlanesMod.MODID + ".shooter_desc",
            Component.keybind("key.plane_inventory_open.desc"), Component.keybind("key.attack"))),
        new Item.Properties());

    public static final Supplier<Item> ELECTRIC_ENGINE = register("electric_engine",
        properties -> new DescriptionItem(properties, Component.translatable(SimplePlanesMod.MODID + ".press_key",
            Component.keybind("key.plane_inventory_open.desc"))), new Item.Properties());
    public static final Supplier<Item> FURNACE_ENGINE = register("furnace_engine",
        properties -> new DescriptionItem(properties, Component.translatable(SimplePlanesMod.MODID + ".press_key",
            Component.keybind("key.plane_inventory_open.desc"))), new Item.Properties());
    public static final Supplier<Item> LIQUID_ENGINE = register("liquid_engine",
        properties -> new DescriptionItem(properties, Component.translatable(SimplePlanesMod.MODID + ".press_key",
            Component.keybind("key.plane_inventory_open.desc"))), new Item.Properties());

    public static final Supplier<Item> WRENCH = registerSimple("wrench");
    public static final Supplier<BlockItem> PLANE_WORKBENCH = register("plane_workbench",
        properties -> new BlockItem(SimplePlanesBlocks.PLANE_WORKBENCH_BLOCK.get(), properties),
        new Item.Properties().useBlockDescriptionPrefix());
    public static final Supplier<BlockItem> CHARGING_STATION = register("charging_station",
        properties -> new BlockItem(SimplePlanesBlocks.CHARGING_STATION_BLOCK.get(), properties),
        new Item.Properties().useBlockDescriptionPrefix());

    public static final Supplier<PlaneItem> PLANE_ITEM = register("plane",
        properties -> new PlaneItem(properties, SimplePlanesEntities.PLANE), new Item.Properties());
    public static final Supplier<PlaneItem> LARGE_PLANE_ITEM = register("large_plane",
        properties -> new PlaneItem(properties, SimplePlanesEntities.LARGE_PLANE), new Item.Properties());
    public static final Supplier<PlaneItem> CARGO_PLANE_ITEM = register("cargo_plane",
        properties -> new PlaneItem(properties, SimplePlanesEntities.CARGO_PLANE), new Item.Properties());
    public static final Supplier<PlaneItem> HELICOPTER_ITEM = register("helicopter",
        properties -> new PlaneItem(properties, SimplePlanesEntities.HELICOPTER), new Item.Properties());

    public static final Supplier<ParachuteItem> PARACHUTE_ITEM = register("parachute", ParachuteItem::new, new Item.Properties());

    // ---- autopilot tools ----
    public static final Supplier<PlaneStrikeToolItem> PLANE_STRIKE_TOOL =
        register("plane_strike_tool", PlaneStrikeToolItem::new, new Item.Properties());
    public static final Supplier<RouteWandItem> ROUTE_WAND =
        register("route_wand", RouteWandItem::new, new Item.Properties());
    public static final Supplier<RunwayToolItem> RUNWAY_TOOL =
        register("runway_tool", RunwayToolItem::new, new Item.Properties());

    public static final Supplier<CreativeModeTab> PLANES_TAB = registerTab("planes_tab", FabricCreativeModeTab.builder()
        .icon(() -> PLANE_ITEM.get().getDefaultInstance())
        .title(Component.translatable(SimplePlanesMod.MODID + ".planes_tab"))
        .displayItems((parameters, output) -> {
            output.accept(PROPELLER.get());
            output.accept(FLOATY_BEDDING.get());
            output.accept(BOOSTER.get());
            output.accept(HEALING.get());
            output.accept(ARMOR.get());
            output.accept(SOLAR_PANEL.get());
            output.accept(FOLDING.get());
            output.accept(SUPPLY_CRATE.get());
            output.accept(SEATS.get());
            output.accept(SHOOTER.get());
            output.accept(ELECTRIC_ENGINE.get());
            output.accept(FURNACE_ENGINE.get());
            output.accept(LIQUID_ENGINE.get());
            output.accept(WRENCH.get());
            output.accept(PLANE_WORKBENCH.get());
            output.accept(CHARGING_STATION.get());
            output.accept(PARACHUTE_ITEM.get());
            output.accept(PLANE_STRIKE_TOOL.get());
            output.accept(ROUTE_WAND.get());
            output.accept(RUNWAY_TOOL.get());

            BuiltInRegistries.BLOCK.get(PlaneWorkbenchContainer.PLANE_MATERIALS_TAG).ifPresent(tag -> tag.forEach(block -> {
                ItemStack planeStack = new ItemStack(PLANE_ITEM.get());
                ItemStack largePlaneStack = new ItemStack(LARGE_PLANE_ITEM.get());
                ItemStack cargoPlaneStack = new ItemStack(CARGO_PLANE_ITEM.get());
                ItemStack heliStack = new ItemStack(HELICOPTER_ITEM.get());

                CompoundTag entityTag = new CompoundTag();
                entityTag.putString("material", BuiltInRegistries.BLOCK.getKey(block.value()).toString());

                planeStack.set(SimplePlanesComponents.ENTITY_TAG, entityTag);
                largePlaneStack.set(SimplePlanesComponents.ENTITY_TAG, entityTag);
                cargoPlaneStack.set(SimplePlanesComponents.ENTITY_TAG, entityTag);
                heliStack.set(SimplePlanesComponents.ENTITY_TAG, entityTag);

                output.accept(planeStack);
                output.accept(largePlaneStack);
                output.accept(cargoPlaneStack);
                output.accept(heliStack);
            }));
        }).build());

    private static Supplier<CreativeModeTab> registerTab(String name, CreativeModeTab tab) {
        CreativeModeTab value = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
            Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, name), tab);
        return () -> value;
    }
}
