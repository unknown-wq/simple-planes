package xyz.przemyk.simpleplanes.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.client.PlanesModelLayers;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;
import xyz.przemyk.simpleplanes.upgrades.armor.ArmorModel;
import xyz.przemyk.simpleplanes.upgrades.armor.ArmorWindowModel;
import xyz.przemyk.simpleplanes.upgrades.armor.CargoArmorModel;
import xyz.przemyk.simpleplanes.upgrades.armor.HeliArmorModel;
import xyz.przemyk.simpleplanes.upgrades.armor.LargeArmorModel;
import xyz.przemyk.simpleplanes.upgrades.booster.BoosterModel;
import xyz.przemyk.simpleplanes.upgrades.booster.CargoBoosterModel;
import xyz.przemyk.simpleplanes.upgrades.booster.HeliBoosterModel;
import xyz.przemyk.simpleplanes.upgrades.booster.LargeBoosterModel;
import xyz.przemyk.simpleplanes.upgrades.engines.electric.CargoElectricEngineModel;
import xyz.przemyk.simpleplanes.upgrades.engines.electric.ElectricEngineModel;
import xyz.przemyk.simpleplanes.upgrades.engines.electric.HeliElectricEngineModel;
import xyz.przemyk.simpleplanes.upgrades.engines.electric.LargeElectricEngineModel;
import xyz.przemyk.simpleplanes.upgrades.engines.furnace.CargoFurnaceEngineModel;
import xyz.przemyk.simpleplanes.upgrades.engines.furnace.FurnaceEngineModel;
import xyz.przemyk.simpleplanes.upgrades.engines.furnace.HeliFurnaceEngineModel;
import xyz.przemyk.simpleplanes.upgrades.engines.furnace.LargeFurnaceEngineModel;
import xyz.przemyk.simpleplanes.upgrades.engines.liquid.CargoLiquidEngineModel;
import xyz.przemyk.simpleplanes.upgrades.engines.liquid.HeliLiquidEngineModel;
import xyz.przemyk.simpleplanes.upgrades.engines.liquid.LargeLiquidEngineModel;
import xyz.przemyk.simpleplanes.upgrades.engines.liquid.LiquidEngineModel;
import xyz.przemyk.simpleplanes.upgrades.floating.CargoFloatingModel;
import xyz.przemyk.simpleplanes.upgrades.floating.FloatingModel;
import xyz.przemyk.simpleplanes.upgrades.floating.HeliFloatingModel;
import xyz.przemyk.simpleplanes.upgrades.floating.LargeFloatingModel;
import xyz.przemyk.simpleplanes.upgrades.floating.WoodenCargoFloatingModel;
import xyz.przemyk.simpleplanes.upgrades.seats.CargoSeatsModel;
import xyz.przemyk.simpleplanes.upgrades.seats.HeliSeatsModel;
import xyz.przemyk.simpleplanes.upgrades.seats.LargeSeatsModel;
import xyz.przemyk.simpleplanes.upgrades.seats.SeatsModel;
import xyz.przemyk.simpleplanes.upgrades.seats.WoodenCargoSeatsModel;
import xyz.przemyk.simpleplanes.upgrades.seats.WoodenHeliSeatsModel;
import xyz.przemyk.simpleplanes.upgrades.seats.WoodenSeatsModel;
import xyz.przemyk.simpleplanes.upgrades.shooter.HeliShooterModel;
import xyz.przemyk.simpleplanes.upgrades.shooter.LargeShooterModel;
import xyz.przemyk.simpleplanes.upgrades.shooter.ShooterModel;
import xyz.przemyk.simpleplanes.upgrades.solarpanel.CargoSolarPanelModel;
import xyz.przemyk.simpleplanes.upgrades.solarpanel.LargeSolarPanelModel;
import xyz.przemyk.simpleplanes.upgrades.solarpanel.SolarPanelModel;

import java.util.HashMap;
import java.util.Map;

/**
 * Baked upgrade models plus the submit helpers that draw them.
 *
 * <p>In 26.2 an entity renderer may not touch the entity while rendering, so upgrade visuals can no
 * longer live on {@code Upgrade#render(...)}. Instead {@link PlaneRenderState} carries the list of
 * installed {@link UpgradeType}s and this class turns that list into {@code submitModel} calls.
 */
@Environment(EnvType.CLIENT)
public final class UpgradesModels {

    private UpgradesModels() {}

    public static @Nullable EntityModel<PlaneRenderState> SEATS;
    public static @Nullable EntityModel<PlaneRenderState> LARGE_SEATS;
    public static @Nullable EntityModel<PlaneRenderState> CARGO_SEATS;
    public static @Nullable EntityModel<PlaneRenderState> HELI_SEATS;
    public static @Nullable EntityModel<PlaneRenderState> WOODEN_SEATS;
    public static @Nullable EntityModel<PlaneRenderState> WOODEN_HELI_SEATS;
    /** rendered on cargo planes when the seats upgrade is NOT installed */
    public static @Nullable EntityModel<PlaneRenderState> WOODEN_CARGO_SEATS;
    public static @Nullable EntityModel<PlaneRenderState> WOODEN_CARGO_FLOATING;
    public static @Nullable EntityModel<PlaneRenderState> ARMOR_WINDOW;

    public static final Map<UpgradeType, ModelEntry> MODEL_ENTRIES = new HashMap<>();

    public record ModelEntry(@Nullable EntityModel<PlaneRenderState> normal, @Nullable Identifier normalTexture,
                             @Nullable EntityModel<PlaneRenderState> large, @Nullable Identifier largeTexture,
                             @Nullable EntityModel<PlaneRenderState> heli, @Nullable Identifier heliTexture,
                             @Nullable EntityModel<PlaneRenderState> cargo, @Nullable Identifier cargoTexture) {}

    public static final Identifier SEATS_TEXTURE = tex("seats.png");
    public static final Identifier SEATS_LARGE_TEXTURE = tex("seats_large.png");
    public static final Identifier SEATS_CARGO_TEXTURE = tex("cargo_plane_metal.png");
    public static final Identifier SEATS_HELI_TEXTURE = tex("seats_heli.png");

    public static Identifier tex(String filename) {
        return Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/plane_upgrades/" + filename);
    }

    /**
     * (Re-)bakes every upgrade model. Called from each {@link PlaneRenderer} constructor, i.e. once per
     * resource reload, which is exactly when the {@link EntityModelSet} changes.
     */
    public static void bake(EntityModelSet models) {
        MODEL_ENTRIES.clear();

        MODEL_ENTRIES.put(SimplePlanesUpgrades.FURNACE_ENGINE.get(), new ModelEntry(
                new FurnaceEngineModel(models.bakeLayer(PlanesModelLayers.FURNACE_ENGINE)), tex("furnace_engine.png"),
                new LargeFurnaceEngineModel(models.bakeLayer(PlanesModelLayers.LARGE_FURNACE_ENGINE)), tex("furnace_engine_large.png"),
                new HeliFurnaceEngineModel(models.bakeLayer(PlanesModelLayers.HELI_FURNACE_ENGINE)), tex("furnace_engine_heli.png"),
                new CargoFurnaceEngineModel(models.bakeLayer(PlanesModelLayers.CARGO_FURNACE_ENGINE)), tex("furnace_engine_cargo.png")));

        MODEL_ENTRIES.put(SimplePlanesUpgrades.ELECTRIC_ENGINE.get(), new ModelEntry(
                new ElectricEngineModel(models.bakeLayer(PlanesModelLayers.ELECTRIC_ENGINE)), tex("electric_engine.png"),
                new LargeElectricEngineModel(models.bakeLayer(PlanesModelLayers.LARGE_ELECTRIC_ENGINE)), tex("electric_engine_large.png"),
                new HeliElectricEngineModel(models.bakeLayer(PlanesModelLayers.HELI_ELECTRIC_ENGINE)), tex("electric_engine_heli.png"),
                new CargoElectricEngineModel(models.bakeLayer(PlanesModelLayers.CARGO_ELECTRIC_ENGINE)), tex("electric_engine_cargo.png")));

        MODEL_ENTRIES.put(SimplePlanesUpgrades.LIQUID_ENGINE.get(), new ModelEntry(
                new LiquidEngineModel(models.bakeLayer(PlanesModelLayers.LIQUID_ENGINE)), tex("liquid_engine.png"),
                new LargeLiquidEngineModel(models.bakeLayer(PlanesModelLayers.LARGE_LIQUID_ENGINE)), tex("liquid_engine_large.png"),
                new HeliLiquidEngineModel(models.bakeLayer(PlanesModelLayers.HELI_LIQUID_ENGINE)), tex("liquid_engine_heli.png"),
                new CargoLiquidEngineModel(models.bakeLayer(PlanesModelLayers.CARGO_LIQUID_ENGINE)), tex("liquid_engine_cargo.png")));

        MODEL_ENTRIES.put(SimplePlanesUpgrades.BOOSTER.get(), new ModelEntry(
                new BoosterModel(models.bakeLayer(PlanesModelLayers.BOOSTER)), tex("booster.png"),
                new LargeBoosterModel(models.bakeLayer(PlanesModelLayers.LARGE_BOOSTER)), tex("booster_large.png"),
                new HeliBoosterModel(models.bakeLayer(PlanesModelLayers.HELI_BOOSTER)), tex("booster_heli.png"),
                new CargoBoosterModel(models.bakeLayer(PlanesModelLayers.CARGO_BOOSTER)), tex("booster_cargo.png")));

        MODEL_ENTRIES.put(SimplePlanesUpgrades.SHOOTER.get(), new ModelEntry(
                new ShooterModel(models.bakeLayer(PlanesModelLayers.SHOOTER)), tex("shooter.png"),
                new LargeShooterModel(models.bakeLayer(PlanesModelLayers.LARGE_SHOOTER)), tex("shooter_large.png"),
                new HeliShooterModel(models.bakeLayer(PlanesModelLayers.HELI_SHOOTER)), tex("shooter_heli.png"),
                null, null));

        MODEL_ENTRIES.put(SimplePlanesUpgrades.FLOATY_BEDDING.get(), new ModelEntry(
                new FloatingModel(models.bakeLayer(PlanesModelLayers.FLOATING)), tex("floating.png"),
                new LargeFloatingModel(models.bakeLayer(PlanesModelLayers.LARGE_FLOATING)), tex("floating_large.png"),
                new HeliFloatingModel(models.bakeLayer(PlanesModelLayers.HELI_FLOATING)), tex("floating_heli.png"),
                new CargoFloatingModel(models.bakeLayer(PlanesModelLayers.CARGO_FLOATING)), tex("floating_cargo.png")));

        MODEL_ENTRIES.put(SimplePlanesUpgrades.ARMOR.get(), new ModelEntry(
                new ArmorModel(models.bakeLayer(PlanesModelLayers.ARMOR)), tex("armor.png"),
                new LargeArmorModel(models.bakeLayer(PlanesModelLayers.LARGE_ARMOR)), tex("armor_large.png"),
                new HeliArmorModel(models.bakeLayer(PlanesModelLayers.HELI_ARMOR)), tex("armor_heli.png"),
                new CargoArmorModel(models.bakeLayer(PlanesModelLayers.CARGO_ARMOR)), tex("armor_cargo.png")));

        MODEL_ENTRIES.put(SimplePlanesUpgrades.SOLAR_PANEL.get(), new ModelEntry(
                new SolarPanelModel(models.bakeLayer(PlanesModelLayers.SOLAR_PANEL)), tex("solar_panel.png"),
                new LargeSolarPanelModel(models.bakeLayer(PlanesModelLayers.LARGE_SOLAR_PANEL)), tex("solar_panel_large.png"),
                null, null,
                new CargoSolarPanelModel(models.bakeLayer(PlanesModelLayers.CARGO_SOLAR_PANEL)), tex("solar_panel_cargo.png")));

        SEATS = new SeatsModel(models.bakeLayer(PlanesModelLayers.SEATS));
        LARGE_SEATS = new LargeSeatsModel(models.bakeLayer(PlanesModelLayers.LARGE_SEATS));
        CARGO_SEATS = new CargoSeatsModel(models.bakeLayer(PlanesModelLayers.CARGO_SEATS));
        HELI_SEATS = new HeliSeatsModel(models.bakeLayer(PlanesModelLayers.HELI_SEATS));
        WOODEN_SEATS = new WoodenSeatsModel(models.bakeLayer(PlanesModelLayers.WOODEN_SEATS));
        WOODEN_HELI_SEATS = new WoodenHeliSeatsModel(models.bakeLayer(PlanesModelLayers.WOODEN_HELI_SEATS));
        WOODEN_CARGO_SEATS = new WoodenCargoSeatsModel(models.bakeLayer(PlanesModelLayers.WOODEN_CARGO_SEATS));
        WOODEN_CARGO_FLOATING = new WoodenCargoFloatingModel(models.bakeLayer(PlanesModelLayers.WOODEN_CARGO_FLOATING));
        ARMOR_WINDOW = new ArmorWindowModel(models.bakeLayer(PlanesModelLayers.ARMOR_WINDOW));
    }

    /** Draws every upgrade model that belongs on the plane described by {@code state}. */
    public static void submitUpgrades(PlaneRenderState state, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        UpgradeType seatsType = SimplePlanesUpgrades.SEATS.get();
        UpgradeType armorType = SimplePlanesUpgrades.ARMOR.get();

        for (UpgradeType type : state.upgradeTypes) {
            if (type == seatsType) {
                submitSeats(state, poseStack, collector, light);
                continue;
            }

            submitUpgrade(type, state, poseStack, collector, light);

            if (type == armorType && ARMOR_WINDOW != null) {
                ModelEntry armor = MODEL_ENTRIES.get(armorType);
                Identifier windowTexture = armor == null ? null : textureFor(armor, state.entityType);
                if (windowTexture != null) {
                    collector.submitModel(ARMOR_WINDOW, state, poseStack,
                            RenderTypes.entityTranslucentCull(windowTexture),
                            light, OverlayTexture.NO_OVERLAY, state.outlineColor);
                }
            }
        }

        if (state.isCargoPlane && !state.hasSeatsUpgrade && WOODEN_CARGO_SEATS != null) {
            collector.submitModel(WOODEN_CARGO_SEATS, state, poseStack,
                    WOODEN_CARGO_SEATS.renderType(state.materialTexture),
                    light, OverlayTexture.NO_OVERLAY, state.outlineColor);
        }
    }

    private static @Nullable Identifier textureFor(ModelEntry entry, EntityType<?> entityType) {
        if (entityType == SimplePlanesEntities.PLANE.get()) {
            return entry.normalTexture();
        }
        if (entityType == SimplePlanesEntities.LARGE_PLANE.get()) {
            return entry.largeTexture();
        }
        if (entityType == SimplePlanesEntities.CARGO_PLANE.get()) {
            return entry.cargoTexture();
        }
        return entry.heliTexture();
    }

    private static @Nullable EntityModel<PlaneRenderState> modelFor(ModelEntry entry, EntityType<?> entityType) {
        if (entityType == SimplePlanesEntities.PLANE.get()) {
            return entry.normal();
        }
        if (entityType == SimplePlanesEntities.LARGE_PLANE.get()) {
            return entry.large();
        }
        if (entityType == SimplePlanesEntities.CARGO_PLANE.get()) {
            return entry.cargo();
        }
        return entry.heli();
    }

    private static void submitUpgrade(UpgradeType type, PlaneRenderState state, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        ModelEntry entry = MODEL_ENTRIES.get(type);
        if (entry == null) {
            return;
        }

        EntityModel<PlaneRenderState> model = modelFor(entry, state.entityType);
        Identifier texture = textureFor(entry, state.entityType);
        if (model == null || texture == null) {
            return;
        }

        collector.submitModel(model, state, poseStack, RenderTypes.armorCutoutNoCull(texture),
                light, OverlayTexture.NO_OVERLAY, state.outlineColor);
    }

    private static void submitSeats(PlaneRenderState state, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        EntityType<?> entityType = state.entityType;
        if (entityType == SimplePlanesEntities.PLANE.get()) {
            submitPair(SEATS, WOODEN_SEATS, SEATS_TEXTURE, state, poseStack, collector, light);
        } else if (entityType == SimplePlanesEntities.LARGE_PLANE.get()) {
            submitPair(LARGE_SEATS, null, SEATS_LARGE_TEXTURE, state, poseStack, collector, light);
        } else if (entityType == SimplePlanesEntities.CARGO_PLANE.get()) {
            submitPair(CARGO_SEATS, null, SEATS_CARGO_TEXTURE, state, poseStack, collector, light);
        } else {
            submitPair(HELI_SEATS, WOODEN_HELI_SEATS, SEATS_HELI_TEXTURE, state, poseStack, collector, light);
        }
    }

    private static void submitPair(@Nullable EntityModel<PlaneRenderState> metalModel,
                                   @Nullable EntityModel<PlaneRenderState> woodenModel,
                                   Identifier metalTexture,
                                   PlaneRenderState state, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        if (metalModel != null) {
            collector.submitModel(metalModel, state, poseStack, RenderTypes.armorCutoutNoCull(metalTexture),
                    light, OverlayTexture.NO_OVERLAY, state.outlineColor);
        }
        if (woodenModel != null) {
            collector.submitModel(woodenModel, state, poseStack, woodenModel.renderType(state.materialTexture),
                    light, OverlayTexture.NO_OVERLAY, state.outlineColor);
        }
    }
}
