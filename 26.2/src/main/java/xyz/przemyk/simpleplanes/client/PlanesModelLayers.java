package xyz.przemyk.simpleplanes.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.Identifier;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.client.render.ParachuteRenderer;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderer;
import xyz.przemyk.simpleplanes.client.render.models.*;
import xyz.przemyk.simpleplanes.entities.CargoPlaneEntity;
import xyz.przemyk.simpleplanes.entities.HelicopterEntity;
import xyz.przemyk.simpleplanes.entities.LargePlaneEntity;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;
import xyz.przemyk.simpleplanes.upgrades.armor.*;
import xyz.przemyk.simpleplanes.upgrades.booster.*;
import xyz.przemyk.simpleplanes.upgrades.engines.electric.*;
import xyz.przemyk.simpleplanes.upgrades.engines.furnace.*;
import xyz.przemyk.simpleplanes.upgrades.engines.liquid.*;
import xyz.przemyk.simpleplanes.upgrades.floating.*;
import xyz.przemyk.simpleplanes.upgrades.seats.*;
import xyz.przemyk.simpleplanes.upgrades.shooter.*;
import xyz.przemyk.simpleplanes.upgrades.solarpanel.*;

/**
 * Model layer ids + the layer/renderer registration that used to live on the NeoForge
 * {@code EntityRenderersEvent} bus. Called from {@link SimplePlanesClient}.
 */
@Environment(EnvType.CLIENT)
public final class PlanesModelLayers {

    private PlanesModelLayers() {}

    public static final ModelLayerLocation PLANE_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "plane"), "main");
    public static final ModelLayerLocation PLANE_METAL_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "plane"), "metal");
    public static final ModelLayerLocation PROPELLER_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "plane"), "propeller");
    public static final ModelLayerLocation LARGE_PLANE_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "large_plane"), "main");
    public static final ModelLayerLocation LARGE_PLANE_METAL_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "large_plane"), "metal");
    public static final ModelLayerLocation LARGE_PROPELLER_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "large_plane"), "propeller");
    public static final ModelLayerLocation CARGO_PLANE_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "cargo_plane"), "main");
    public static final ModelLayerLocation CARGO_PLANE_METAL_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "cargo_plane"), "metal");
    public static final ModelLayerLocation CARGO_PROPELLER_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "cargo_plane"), "propeller");
    public static final ModelLayerLocation HELICOPTER_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "helicopter"), "main");
    public static final ModelLayerLocation HELICOPTER_METAL_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "helicopter"), "metal");
    public static final ModelLayerLocation HELICOPTER_PROPELLER_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "helicopter"), "propeller");
    public static final ModelLayerLocation PARACHUTE_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "parachute"), "main");
    public static final ModelLayerLocation FURNACE_ENGINE = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "furnace_engine"), "main");
    public static final ModelLayerLocation LARGE_FURNACE_ENGINE = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "furnace_engine"), "large");
    public static final ModelLayerLocation HELI_FURNACE_ENGINE = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "furnace_engine"), "heli");
    public static final ModelLayerLocation CARGO_FURNACE_ENGINE = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "furnace_engine"), "cargo");
    public static final ModelLayerLocation ELECTRIC_ENGINE = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "electric_engine"), "main");
    public static final ModelLayerLocation LARGE_ELECTRIC_ENGINE = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "electric_engine"), "large");
    public static final ModelLayerLocation HELI_ELECTRIC_ENGINE = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "electric_engine"), "heli");
    public static final ModelLayerLocation CARGO_ELECTRIC_ENGINE = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "electric_engine"), "cargo");
    public static final ModelLayerLocation LIQUID_ENGINE = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "liquid_engine"), "main");
    public static final ModelLayerLocation LARGE_LIQUID_ENGINE = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "liquid_engine"), "large");
    public static final ModelLayerLocation HELI_LIQUID_ENGINE = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "liquid_engine"), "heli");
    public static final ModelLayerLocation CARGO_LIQUID_ENGINE = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "liquid_engine"), "cargo");
    public static final ModelLayerLocation BOOSTER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "booster"), "main");
    public static final ModelLayerLocation LARGE_BOOSTER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "booster"), "large");
    public static final ModelLayerLocation HELI_BOOSTER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "booster"), "heli");
    public static final ModelLayerLocation CARGO_BOOSTER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "booster"), "cargo");
    public static final ModelLayerLocation SHOOTER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "shooter"), "main");
    public static final ModelLayerLocation LARGE_SHOOTER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "shooter"), "large");
    public static final ModelLayerLocation HELI_SHOOTER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "shooter"), "heli");
    public static final ModelLayerLocation FLOATING = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "floating"), "main");
    public static final ModelLayerLocation LARGE_FLOATING = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "floating"), "large");
    public static final ModelLayerLocation CARGO_FLOATING = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "floating"), "cargo");
    public static final ModelLayerLocation HELI_FLOATING = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "floating"), "heli");
    public static final ModelLayerLocation ARMOR = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "armor"), "main");
    public static final ModelLayerLocation LARGE_ARMOR = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "armor"), "large");
    public static final ModelLayerLocation HELI_ARMOR = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "armor"), "heli");
    public static final ModelLayerLocation CARGO_ARMOR = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "armor"), "cargo");
    public static final ModelLayerLocation ARMOR_WINDOW = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "armor"), "window");
    public static final ModelLayerLocation SOLAR_PANEL = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "solar_panel"), "main");
    public static final ModelLayerLocation LARGE_SOLAR_PANEL = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "solar_panel"), "large");
    public static final ModelLayerLocation CARGO_SOLAR_PANEL = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "solar_panel"), "cargo");
    public static final ModelLayerLocation SEATS = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "seats"), "main");
    public static final ModelLayerLocation LARGE_SEATS = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "seats"), "large");
    public static final ModelLayerLocation CARGO_SEATS = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "seats"), "cargo");
    public static final ModelLayerLocation HELI_SEATS = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "seats"), "heli");
    public static final ModelLayerLocation WOODEN_SEATS = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "seats"), "wooden");
    public static final ModelLayerLocation WOODEN_HELI_SEATS = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "seats"), "wooden_heli");
    public static final ModelLayerLocation WOODEN_CARGO_SEATS = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "seats"), "wooden_cargo");
    public static final ModelLayerLocation WOODEN_CARGO_FLOATING = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "floating"), "wooden_cargo");

    public static void registerLayers() {
        ModelLayerRegistry.registerModelLayer(PLANE_LAYER, PlaneModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(PLANE_METAL_LAYER, PlaneMetalModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(PROPELLER_LAYER, PropellerModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(LARGE_PLANE_LAYER, LargePlaneModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(LARGE_PLANE_METAL_LAYER, LargePlaneMetalModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(LARGE_PROPELLER_LAYER, LargePropellerModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(CARGO_PLANE_LAYER, CargoPlaneModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(CARGO_PLANE_METAL_LAYER, CargoPlaneMetalModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(CARGO_PROPELLER_LAYER, CargoPropellerModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(HELICOPTER_LAYER, HelicopterModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(HELICOPTER_METAL_LAYER, HelicopterMetalModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(HELICOPTER_PROPELLER_LAYER, HelicopterPropellerModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(PARACHUTE_LAYER, ParachuteModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(FURNACE_ENGINE, FurnaceEngineModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(LARGE_FURNACE_ENGINE, LargeFurnaceEngineModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(HELI_FURNACE_ENGINE, HeliFurnaceEngineModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(CARGO_FURNACE_ENGINE, CargoFurnaceEngineModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(ELECTRIC_ENGINE, ElectricEngineModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(LARGE_ELECTRIC_ENGINE, LargeElectricEngineModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(HELI_ELECTRIC_ENGINE, HeliElectricEngineModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(CARGO_ELECTRIC_ENGINE, CargoElectricEngineModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(LIQUID_ENGINE, LiquidEngineModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(LARGE_LIQUID_ENGINE, LargeLiquidEngineModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(HELI_LIQUID_ENGINE, HeliLiquidEngineModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(CARGO_LIQUID_ENGINE, CargoLiquidEngineModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(BOOSTER, BoosterModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(LARGE_BOOSTER, LargeBoosterModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(HELI_BOOSTER, HeliBoosterModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(CARGO_BOOSTER, CargoBoosterModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(SHOOTER, ShooterModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(LARGE_SHOOTER, LargeShooterModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(HELI_SHOOTER, HeliShooterModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(FLOATING, FloatingModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(LARGE_FLOATING, LargeFloatingModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(CARGO_FLOATING, CargoFloatingModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(HELI_FLOATING, HeliFloatingModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(ARMOR, ArmorModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(LARGE_ARMOR, LargeArmorModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(HELI_ARMOR, HeliArmorModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(CARGO_ARMOR, CargoArmorModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(ARMOR_WINDOW, ArmorWindowModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(SOLAR_PANEL, SolarPanelModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(LARGE_SOLAR_PANEL, LargeSolarPanelModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(CARGO_SOLAR_PANEL, CargoSolarPanelModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(SEATS, SeatsModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(LARGE_SEATS, LargeSeatsModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(CARGO_SEATS, CargoSeatsModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(HELI_SEATS, HeliSeatsModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(WOODEN_SEATS, WoodenSeatsModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(WOODEN_HELI_SEATS, WoodenHeliSeatsModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(WOODEN_CARGO_SEATS, WoodenCargoSeatsModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(WOODEN_CARGO_FLOATING, WoodenCargoFloatingModel::createBodyLayer);
    }

    public static void registerRenderers() {
        EntityRendererRegistry.register(SimplePlanesEntities.PLANE.get(), context -> new PlaneRenderer<PlaneEntity>(context,
                new PlaneModel(context.bakeLayer(PLANE_LAYER)),
                new PlaneMetalModel(context.bakeLayer(PLANE_METAL_LAYER)),
                new PropellerModel(context.bakeLayer(PROPELLER_LAYER)),
                0.6F,
                Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/plane_upgrades/plane_metal.png"),
                Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/plane_upgrades/iron_propeller.png")));

        EntityRendererRegistry.register(SimplePlanesEntities.LARGE_PLANE.get(), context -> new PlaneRenderer<LargePlaneEntity>(context,
                new LargePlaneModel(context.bakeLayer(LARGE_PLANE_LAYER)),
                new LargePlaneMetalModel(context.bakeLayer(LARGE_PLANE_METAL_LAYER)),
                new LargePropellerModel(context.bakeLayer(LARGE_PROPELLER_LAYER)),
                1.0F,
                Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/plane_upgrades/large_plane_metal.png"),
                Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/plane_upgrades/iron_large_propeller.png")));

        EntityRendererRegistry.register(SimplePlanesEntities.CARGO_PLANE.get(), context -> new PlaneRenderer<CargoPlaneEntity>(context,
                new CargoPlaneModel(context.bakeLayer(CARGO_PLANE_LAYER)),
                new CargoPlaneMetalModel(context.bakeLayer(CARGO_PLANE_METAL_LAYER)),
                new CargoPropellerModel(context.bakeLayer(CARGO_PROPELLER_LAYER)),
                1.0F,
                Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/plane_upgrades/cargo_plane_metal.png"),
                Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/plane_upgrades/iron_cargo_propeller.png")));

        EntityRendererRegistry.register(SimplePlanesEntities.HELICOPTER.get(), context -> new PlaneRenderer<HelicopterEntity>(context,
                new HelicopterModel(context.bakeLayer(HELICOPTER_LAYER)),
                new HelicopterMetalModel(context.bakeLayer(HELICOPTER_METAL_LAYER)),
                new HelicopterPropellerModel(context.bakeLayer(HELICOPTER_PROPELLER_LAYER)),
                0.6F,
                Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/plane_upgrades/helicopter_metal.png"),
                Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/plane_upgrades/iron_helicopter_propeller.png")));

        EntityRendererRegistry.register(SimplePlanesEntities.PARACHUTE.get(),
                context -> new ParachuteRenderer(context, new ParachuteModel(context.bakeLayer(PARACHUTE_LAYER))));
    }
}
