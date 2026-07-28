package xyz.przemyk.simpleplanes.setup;

import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;
import xyz.przemyk.simpleplanes.upgrades.armor.ArmorUpgrade;
import xyz.przemyk.simpleplanes.upgrades.banner.BannerUpgrade;
import xyz.przemyk.simpleplanes.upgrades.booster.BoosterUpgrade;
import xyz.przemyk.simpleplanes.upgrades.engines.electric.ElectricEngineUpgrade;
import xyz.przemyk.simpleplanes.upgrades.engines.furnace.FurnaceEngineUpgrade;
import xyz.przemyk.simpleplanes.upgrades.engines.liquid.LiquidEngineUpgrade;
import xyz.przemyk.simpleplanes.upgrades.floating.FloatingUpgrade;
import xyz.przemyk.simpleplanes.upgrades.folding.FoldingUpgrade;
import xyz.przemyk.simpleplanes.upgrades.heal.HealingUpgrade;
import xyz.przemyk.simpleplanes.upgrades.jukebox.JukeboxUpgrade;
import xyz.przemyk.simpleplanes.upgrades.payload.PayloadUpgrade;
import xyz.przemyk.simpleplanes.upgrades.seats.SeatsUpgrade;
import xyz.przemyk.simpleplanes.upgrades.shooter.ShooterUpgrade;
import xyz.przemyk.simpleplanes.upgrades.solarpanel.SolarPanelUpgrade;
import xyz.przemyk.simpleplanes.upgrades.storage.ChestUpgrade;
import xyz.przemyk.simpleplanes.upgrades.supplycrate.SupplyCrateUpgrade;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public class SimplePlanesUpgrades {

    public static final Map<Item, UpgradeType> ITEM_UPGRADE_MAP = new HashMap<>();
    public static final Map<Item, UpgradeType> LARGE_ITEM_UPGRADE_MAP = new HashMap<>();

    /** Class-load hook — upgrade types are registered eagerly below (contract C1). */
    public static void init() {
        SimplePlanesRegistries.init();
    }

    private static Supplier<UpgradeType> register(String name, UpgradeType upgradeType) {
        UpgradeType value = Registry.register(SimplePlanesRegistries.UPGRADE_TYPE,
            Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, name), upgradeType);
        return () -> value;
    }

    public static void registerUpgradeItem(Item item, UpgradeType upgradeType) {
        ITEM_UPGRADE_MAP.put(item, upgradeType);
    }

    public static void registerLargeUpgradeItem(Item item, UpgradeType upgradeType) {
        LARGE_ITEM_UPGRADE_MAP.put(item, upgradeType);
    }

    public static Optional<UpgradeType> getUpgradeFromItem(Item item) {
        return Optional.ofNullable(ITEM_UPGRADE_MAP.get(item));
    }

    public static Optional<UpgradeType> getLargeUpgradeFromItem(Item item) {
        return Optional.ofNullable(LARGE_ITEM_UPGRADE_MAP.get(item));
    }

    public static final Supplier<UpgradeType> FLOATY_BEDDING = register("floaty_bedding", new UpgradeType(FloatingUpgrade::new));
    public static final Supplier<UpgradeType> BOOSTER = register("booster", new UpgradeType(BoosterUpgrade::new));
    public static final Supplier<UpgradeType> SHOOTER = register("shooter", new UpgradeType(ShooterUpgrade::new));
    public static final Supplier<UpgradeType> HEALING = register("healing", new UpgradeType(HealingUpgrade::new));
    public static final Supplier<UpgradeType> ARMOR = register("armor", new UpgradeType(ArmorUpgrade::new));
    public static final Supplier<UpgradeType> SOLAR_PANEL = register("solar_panel", new UpgradeType(SolarPanelUpgrade::new));
    public static final Supplier<UpgradeType> FOLDING = register("folding", new UpgradeType(FoldingUpgrade::new));
    public static final Supplier<UpgradeType> SEATS = register("seats", new UpgradeType(SeatsUpgrade::new));

    public static final Supplier<UpgradeType> FURNACE_ENGINE = register("furnace_engine", new UpgradeType(FurnaceEngineUpgrade::new, true));
    public static final Supplier<UpgradeType> ELECTRIC_ENGINE = register("electric_engine", new UpgradeType(ElectricEngineUpgrade::new, true));
    public static final Supplier<UpgradeType> LIQUID_ENGINE = register("liquid_engine", new UpgradeType(LiquidEngineUpgrade::new, true));

    public static final Supplier<UpgradeType> BANNER = register("banner", new UpgradeType(BannerUpgrade::new));
    public static final Supplier<UpgradeType> PAYLOAD = register("payload", new UpgradeType(PayloadUpgrade::new));
    public static final Supplier<UpgradeType> CHEST = register("chest", new UpgradeType(ChestUpgrade::new));
    public static final Supplier<UpgradeType> SUPPLY_CRATE = register("supply_crate", new UpgradeType(SupplyCrateUpgrade::new));
    public static final Supplier<UpgradeType> JUKEBOX = register("jukebox", new UpgradeType(JukeboxUpgrade::new));
}
