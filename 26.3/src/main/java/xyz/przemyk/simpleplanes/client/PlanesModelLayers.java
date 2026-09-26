package xyz.przemyk.simpleplanes.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.Identifier;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.client.render.AirlinerRenderer;
import xyz.przemyk.simpleplanes.client.render.AirshipRenderer;
import xyz.przemyk.simpleplanes.client.render.FpvDroneRenderer;
import xyz.przemyk.simpleplanes.client.render.MiniHeliRenderer;
import xyz.przemyk.simpleplanes.client.render.ParachuteRenderer;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderer;
import xyz.przemyk.simpleplanes.client.render.QuadcopterRenderer;
import xyz.przemyk.simpleplanes.client.render.models.*;
import xyz.przemyk.simpleplanes.entities.CargoPlaneEntity;
import xyz.przemyk.simpleplanes.entities.FighterEntity;
import xyz.przemyk.simpleplanes.entities.HelicopterEntity;
import xyz.przemyk.simpleplanes.entities.LargePlaneEntity;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.entities.StrikeDroneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;
import xyz.przemyk.simpleplanes.upgrades.armor.*;
import xyz.przemyk.simpleplanes.upgrades.booster.*;
import xyz.przemyk.simpleplanes.upgrades.engines.furnace.*;
import xyz.przemyk.simpleplanes.upgrades.engines.liquid.*;
import xyz.przemyk.simpleplanes.upgrades.floating.*;
import xyz.przemyk.simpleplanes.upgrades.seats.*;
import xyz.przemyk.simpleplanes.upgrades.shooter.*;

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
    public static final ModelLayerLocation FIGHTER_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "fighter"), "main");
    public static final ModelLayerLocation FIGHTER_METAL_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "fighter"), "metal");
    public static final ModelLayerLocation FIGHTER_PROPELLER_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "fighter"), "propeller");
    public static final ModelLayerLocation AIRLINER_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "airliner"), "main");
    public static final ModelLayerLocation AIRLINER_SKIN_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "airliner"), "skin");
    public static final ModelLayerLocation AIRLINER_METAL_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "airliner"), "metal");
    public static final ModelLayerLocation AIRLINER_PROPELLER_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "airliner"), "propeller");
    public static final ModelLayerLocation AIRSHIP_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "airship"), "main");
    public static final ModelLayerLocation AIRSHIP_METAL_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "airship"), "metal");
    public static final ModelLayerLocation AIRSHIP_PROPELLER_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "airship"), "propeller");
    public static final ModelLayerLocation AIRSHIP_ENVELOPE_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "airship"), "envelope");
    public static final ModelLayerLocation MINI_HELI_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "mini_helicopter"), "main");
    public static final ModelLayerLocation MINI_HELI_MEDICAL_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "mini_helicopter"), "medical");
    public static final ModelLayerLocation MINI_HELI_METAL_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "mini_helicopter"), "metal");
    public static final ModelLayerLocation MINI_HELI_PROPELLER_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "mini_helicopter"), "propeller");
    public static final ModelLayerLocation MINI_HELI_GLASS_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "mini_helicopter"), "glass");
    public static final ModelLayerLocation QUADCOPTER_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "quadcopter"), "main");
    public static final ModelLayerLocation QUADCOPTER_METAL_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "quadcopter"), "metal");
    public static final ModelLayerLocation QUADCOPTER_PROPELLER_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "quadcopter"), "propeller");
    public static final ModelLayerLocation STRIKE_DRONE_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "strike_drone"), "main");
    public static final ModelLayerLocation STRIKE_DRONE_METAL_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "strike_drone"), "metal");
    public static final ModelLayerLocation STRIKE_DRONE_PROPELLER_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "strike_drone"), "propeller");
    public static final ModelLayerLocation PARACHUTE_LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "parachute"), "main");
    public static final ModelLayerLocation FURNACE_ENGINE = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "furnace_engine"), "main");
    public static final ModelLayerLocation LARGE_FURNACE_ENGINE = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "furnace_engine"), "large");
    public static final ModelLayerLocation HELI_FURNACE_ENGINE = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "furnace_engine"), "heli");
    public static final ModelLayerLocation CARGO_FURNACE_ENGINE = new ModelLayerLocation(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "furnace_engine"), "cargo");
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
        ModelLayerRegistry.registerModelLayer(FIGHTER_LAYER, FighterModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(FIGHTER_METAL_LAYER, FighterMetalModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(FIGHTER_PROPELLER_LAYER, FighterExhaustModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(AIRLINER_LAYER, AirlinerModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(AIRLINER_SKIN_LAYER, AirlinerSkinModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(AIRLINER_METAL_LAYER, AirlinerMetalModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(AIRLINER_PROPELLER_LAYER, AirlinerFanModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(AIRSHIP_LAYER, AirshipModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(AIRSHIP_METAL_LAYER, AirshipMetalModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(AIRSHIP_PROPELLER_LAYER, AirshipPropellerModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(AIRSHIP_ENVELOPE_LAYER, AirshipEnvelopeModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(MINI_HELI_LAYER, MiniHeliModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(MINI_HELI_MEDICAL_LAYER, MiniHeliMedicalModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(MINI_HELI_METAL_LAYER, MiniHeliMetalModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(MINI_HELI_PROPELLER_LAYER, MiniHeliRotorModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(MINI_HELI_GLASS_LAYER, MiniHeliGlassModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(QUADCOPTER_LAYER, DroneModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(QUADCOPTER_METAL_LAYER, DroneMetalModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(QUADCOPTER_PROPELLER_LAYER, DroneRotorModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(STRIKE_DRONE_LAYER, StrikeDroneModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(STRIKE_DRONE_METAL_LAYER, StrikeDroneMetalModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(STRIKE_DRONE_PROPELLER_LAYER, StrikeDronePropellerModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(PARACHUTE_LAYER, ParachuteModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(FURNACE_ENGINE, FurnaceEngineModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(LARGE_FURNACE_ENGINE, LargeFurnaceEngineModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(HELI_FURNACE_ENGINE, HeliFurnaceEngineModel::createBodyLayer);
        ModelLayerRegistry.registerModelLayer(CARGO_FURNACE_ENGINE, CargoFurnaceEngineModel::createBodyLayer);
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

        EntityRendererRegistry.register(SimplePlanesEntities.FIGHTER.get(), context -> new PlaneRenderer<FighterEntity>(context,
                new FighterModel(context.bakeLayer(FIGHTER_LAYER)),
                new FighterMetalModel(context.bakeLayer(FIGHTER_METAL_LAYER)),
                new FighterExhaustModel(context.bakeLayer(FIGHTER_PROPELLER_LAYER)),
                1.0F,
                SimplePlanesMod.texture("fighter_metal.png"),
                SimplePlanesMod.texture("fighter_metal.png")));

        EntityRendererRegistry.register(SimplePlanesEntities.AIRLINER.get(), context -> new AirlinerRenderer(context,
                new AirlinerModel(context.bakeLayer(AIRLINER_LAYER)),
                new AirlinerSkinModel(context.bakeLayer(AIRLINER_SKIN_LAYER)),
                new AirlinerMetalModel(context.bakeLayer(AIRLINER_METAL_LAYER)),
                new AirlinerFanModel(context.bakeLayer(AIRLINER_PROPELLER_LAYER)),
                1.0F,
                SimplePlanesMod.texture("airliner_metal.png"),
                SimplePlanesMod.texture("airliner_metal.png")));

        EntityRendererRegistry.register(SimplePlanesEntities.AIRSHIP.get(), context -> new AirshipRenderer(context,
                new AirshipModel(context.bakeLayer(AIRSHIP_LAYER)),
                new AirshipMetalModel(context.bakeLayer(AIRSHIP_METAL_LAYER)),
                new AirshipPropellerModel(context.bakeLayer(AIRSHIP_PROPELLER_LAYER)),
                new AirshipEnvelopeModel(context.bakeLayer(AIRSHIP_ENVELOPE_LAYER)),
                2.5F,
                SimplePlanesMod.texture("airship_metal.png"),
                SimplePlanesMod.texture("airship_metal.png"),
                SimplePlanesMod.texture("airship_envelope.png")));

        EntityRendererRegistry.register(SimplePlanesEntities.MINI_HELICOPTER.get(), context -> new MiniHeliRenderer(context,
                new MiniHeliModel(context.bakeLayer(MINI_HELI_LAYER)),
                new MiniHeliMedicalModel(context.bakeLayer(MINI_HELI_MEDICAL_LAYER)),
                new MiniHeliMetalModel(context.bakeLayer(MINI_HELI_METAL_LAYER)),
                new MiniHeliRotorModel(context.bakeLayer(MINI_HELI_PROPELLER_LAYER)),
                new MiniHeliGlassModel(context.bakeLayer(MINI_HELI_GLASS_LAYER)),
                0.5F,
                SimplePlanesMod.texture("mini_heli_metal.png"),
                SimplePlanesMod.texture("mini_heli_metal.png")));

        EntityRendererRegistry.register(SimplePlanesEntities.QUADCOPTER.get(), context -> new QuadcopterRenderer(context,
                new DroneModel(context.bakeLayer(QUADCOPTER_LAYER)),
                new DroneMetalModel(context.bakeLayer(QUADCOPTER_METAL_LAYER)),
                new DroneRotorModel(context.bakeLayer(QUADCOPTER_PROPELLER_LAYER)),
                0.5F,
                SimplePlanesMod.texture("drone_metal.png")));

        EntityRendererRegistry.register(SimplePlanesEntities.STRIKE_DRONE.get(), context -> new PlaneRenderer<StrikeDroneEntity>(context,
                new StrikeDroneModel(context.bakeLayer(STRIKE_DRONE_LAYER)),
                new StrikeDroneMetalModel(context.bakeLayer(STRIKE_DRONE_METAL_LAYER)),
                new StrikeDronePropellerModel(context.bakeLayer(STRIKE_DRONE_PROPELLER_LAYER)),
                0.75F,
                SimplePlanesMod.texture("strike_drone_metal.png"),
                SimplePlanesMod.texture("strike_drone_metal.png")));

        // The crane's model, scaled down; its own layers, baked a second time.
        EntityRendererRegistry.register(SimplePlanesEntities.FPV_DRONE.get(), context -> new FpvDroneRenderer(context,
                new DroneModel(context.bakeLayer(QUADCOPTER_LAYER)),
                new DroneMetalModel(context.bakeLayer(QUADCOPTER_METAL_LAYER)),
                new DroneRotorModel(context.bakeLayer(QUADCOPTER_PROPELLER_LAYER)),
                0.3F,
                SimplePlanesMod.texture("drone_metal.png")));

        EntityRendererRegistry.register(SimplePlanesEntities.PARACHUTE.get(),
                context -> new ParachuteRenderer(context, new ParachuteModel(context.bakeLayer(PARACHUTE_LAYER))));
    }
}
