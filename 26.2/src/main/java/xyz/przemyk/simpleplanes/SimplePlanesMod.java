package xyz.przemyk.simpleplanes;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import xyz.przemyk.simpleplanes.autopilot.AutopilotCommand;
import xyz.przemyk.simpleplanes.autopilot.AutopilotComponents;
import xyz.przemyk.simpleplanes.autopilot.AutopilotRegistry;
import xyz.przemyk.simpleplanes.autopilot.TowerWatch;
import xyz.przemyk.simpleplanes.combat.GunshipCommand;
import xyz.przemyk.simpleplanes.combat.GunshipRegistry;
import xyz.przemyk.simpleplanes.misc.CommonEventHandler;
import xyz.przemyk.simpleplanes.network.SimplePlanesNetworking;
import xyz.przemyk.simpleplanes.setup.SimplePlanesBlocks;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;
import xyz.przemyk.simpleplanes.setup.SimplePlanesConfig;
import xyz.przemyk.simpleplanes.setup.SimplePlanesContainers;
import xyz.przemyk.simpleplanes.setup.SimplePlanesDatapack;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesRecipes;
import xyz.przemyk.simpleplanes.setup.SimplePlanesRegistries;
import xyz.przemyk.simpleplanes.setup.SimplePlanesSounds;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;

public class SimplePlanesMod implements ModInitializer {

    public static final String MODID = "simpleplanes";

    @Override
    public void onInitialize() {
        SimplePlanesConfig.init();
        SimplePlanesRegistries.init();
        SimplePlanesEntities.init();
        SimplePlanesBlocks.init();
        SimplePlanesContainers.init();
        SimplePlanesUpgrades.init();
        SimplePlanesSounds.init();
        SimplePlanesComponents.init();
        SimplePlanesItems.init();
        SimplePlanesRecipes.init();
        SimplePlanesDatapack.init();

        SimplePlanesNetworking.register();
        CommonEventHandler.register();

        // autopilot feature: data components for the tools + the /autopilot debug command, plus the
        // registry whose server-tick heartbeat keeps chunks loaded around aircraft in flight.
        AutopilotComponents.init();
        AutopilotRegistry.init();
        TowerWatch.init();
        AutopilotCommand.register();

        // gunship feature: the /gunship command and the server-tick pump that flies armed
        // helicopters. Self-contained in xyz.przemyk.simpleplanes.combat.
        GunshipRegistry.init();
        GunshipCommand.register();

        registerUpgradeItems();
    }

    public static Identifier texture(String filename) {
        return Identifier.fromNamespaceAndPath(MODID, "textures/plane_upgrades/" + filename);
    }

    /** Used to run in {@code FMLCommonSetupEvent}; on Fabric everything is registered by now. */
    private static void registerUpgradeItems() {
        SimplePlanesUpgrades.registerUpgradeItem(SimplePlanesItems.FLOATY_BEDDING.get(), SimplePlanesUpgrades.FLOATY_BEDDING.get());
        SimplePlanesUpgrades.registerUpgradeItem(SimplePlanesItems.BOOSTER.get(), SimplePlanesUpgrades.BOOSTER.get());
        SimplePlanesUpgrades.registerUpgradeItem(SimplePlanesItems.HEALING.get(), SimplePlanesUpgrades.HEALING.get());
        SimplePlanesUpgrades.registerUpgradeItem(SimplePlanesItems.ARMOR.get(), SimplePlanesUpgrades.ARMOR.get());
        SimplePlanesUpgrades.registerUpgradeItem(SimplePlanesItems.SOLAR_PANEL.get(), SimplePlanesUpgrades.SOLAR_PANEL.get());
        SimplePlanesUpgrades.registerUpgradeItem(SimplePlanesItems.FOLDING.get(), SimplePlanesUpgrades.FOLDING.get());
        SimplePlanesUpgrades.registerUpgradeItem(SimplePlanesItems.SEATS.get(), SimplePlanesUpgrades.SEATS.get());
        SimplePlanesUpgrades.registerUpgradeItem(SimplePlanesItems.SHOOTER.get(), SimplePlanesUpgrades.SHOOTER.get());
        SimplePlanesUpgrades.registerUpgradeItem(SimplePlanesItems.FURNACE_ENGINE.get(), SimplePlanesUpgrades.FURNACE_ENGINE.get());
        SimplePlanesUpgrades.registerUpgradeItem(SimplePlanesItems.ELECTRIC_ENGINE.get(), SimplePlanesUpgrades.ELECTRIC_ENGINE.get());
        SimplePlanesUpgrades.registerUpgradeItem(SimplePlanesItems.LIQUID_ENGINE.get(), SimplePlanesUpgrades.LIQUID_ENGINE.get());

        SimplePlanesUpgrades.registerUpgradeItem(Items.BANNER.pick(DyeColor.WHITE), SimplePlanesUpgrades.BANNER.get());
        SimplePlanesUpgrades.registerUpgradeItem(Items.BANNER.pick(DyeColor.ORANGE), SimplePlanesUpgrades.BANNER.get());
        SimplePlanesUpgrades.registerUpgradeItem(Items.BANNER.pick(DyeColor.MAGENTA), SimplePlanesUpgrades.BANNER.get());
        SimplePlanesUpgrades.registerUpgradeItem(Items.BANNER.pick(DyeColor.LIGHT_BLUE), SimplePlanesUpgrades.BANNER.get());
        SimplePlanesUpgrades.registerUpgradeItem(Items.BANNER.pick(DyeColor.YELLOW), SimplePlanesUpgrades.BANNER.get());
        SimplePlanesUpgrades.registerUpgradeItem(Items.BANNER.pick(DyeColor.LIME), SimplePlanesUpgrades.BANNER.get());
        SimplePlanesUpgrades.registerUpgradeItem(Items.BANNER.pick(DyeColor.PINK), SimplePlanesUpgrades.BANNER.get());
        SimplePlanesUpgrades.registerUpgradeItem(Items.BANNER.pick(DyeColor.GRAY), SimplePlanesUpgrades.BANNER.get());
        SimplePlanesUpgrades.registerUpgradeItem(Items.BANNER.pick(DyeColor.LIGHT_GRAY), SimplePlanesUpgrades.BANNER.get());
        SimplePlanesUpgrades.registerUpgradeItem(Items.BANNER.pick(DyeColor.CYAN), SimplePlanesUpgrades.BANNER.get());
        SimplePlanesUpgrades.registerUpgradeItem(Items.BANNER.pick(DyeColor.PURPLE), SimplePlanesUpgrades.BANNER.get());
        SimplePlanesUpgrades.registerUpgradeItem(Items.BANNER.pick(DyeColor.BLUE), SimplePlanesUpgrades.BANNER.get());
        SimplePlanesUpgrades.registerUpgradeItem(Items.BANNER.pick(DyeColor.BROWN), SimplePlanesUpgrades.BANNER.get());
        SimplePlanesUpgrades.registerUpgradeItem(Items.BANNER.pick(DyeColor.GREEN), SimplePlanesUpgrades.BANNER.get());
        SimplePlanesUpgrades.registerUpgradeItem(Items.BANNER.pick(DyeColor.RED), SimplePlanesUpgrades.BANNER.get());
        SimplePlanesUpgrades.registerUpgradeItem(Items.BANNER.pick(DyeColor.BLACK), SimplePlanesUpgrades.BANNER.get());

        SimplePlanesUpgrades.registerLargeUpgradeItem(Items.CHEST, SimplePlanesUpgrades.CHEST.get());
        SimplePlanesUpgrades.registerLargeUpgradeItem(SimplePlanesItems.SUPPLY_CRATE.get(), SimplePlanesUpgrades.SUPPLY_CRATE.get());
        SimplePlanesUpgrades.registerLargeUpgradeItem(Items.JUKEBOX, SimplePlanesUpgrades.JUKEBOX.get());

        // TODO(port-26.2): DISABLED — IronChestsCompat / QuarkCompat registrations removed with compat/**.
    }
}
