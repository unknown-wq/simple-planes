package xyz.przemyk.simpleplanes.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import org.joml.Quaternionf;
import xyz.przemyk.simpleplanes.entities.CargoPlaneEntity;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.misc.MathUtil;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;

import java.util.HashMap;
import java.util.Map;

/**
 * Renderer for every plane / helicopter.
 *
 * <p>Ported to the 1.21.2 render-state API and the 26.2 submit-to-queue world renderer: nothing here
 * may touch the entity, so {@link #extractRenderState} copies the plane's orientation, propeller
 * angle, material texture and upgrade list into {@link PlaneRenderState}.
 */
@Environment(EnvType.CLIENT)
public class PlaneRenderer<T extends PlaneEntity> extends EntityRenderer<T, PlaneRenderState> {

    protected final EntityModel<PlaneRenderState> propellerModel;
    protected final EntityModel<PlaneRenderState> planeEntityModel;
    protected final EntityModel<PlaneRenderState> planeMetalModel;
    protected final Identifier metalTexture;
    protected final Identifier propellerTexture;

    /**
     * Reusable buffers for {@link #extractRenderState}. Renderers are per-entity-type and are only
     * ever used from the render thread, and neither buffer is stored anywhere past the call.
     */
    private final Quaternionf qPrevScratch = new Quaternionf();
    private final Quaternionf qClientScratch = new Quaternionf();

    public PlaneRenderer(EntityRendererProvider.Context context,
                         EntityModel<PlaneRenderState> planeModel,
                         EntityModel<PlaneRenderState> planeMetalModel,
                         EntityModel<PlaneRenderState> propellerModel,
                         float shadowSize,
                         Identifier metalTexture,
                         Identifier propellerTexture) {
        super(context);
        this.propellerModel = propellerModel;
        this.planeEntityModel = planeModel;
        this.planeMetalModel = planeMetalModel;
        this.metalTexture = metalTexture;
        this.propellerTexture = propellerTexture;
        this.shadowRadius = shadowSize;

        // Renderers are rebuilt on every resource reload, which is exactly when the upgrade models
        // have to be re-baked.
        UpgradesModels.bake(context.getModelSet());
    }

    @Override
    public PlaneRenderState createRenderState() {
        return new PlaneRenderState();
    }

    @Override
    public void extractRenderState(T planeEntity, PlaneRenderState state, float partialTicks) {
        super.extractRenderState(planeEntity, state, partialTicks);

        // Scratch buffers: extractRenderState runs once per visible plane per frame on the render
        // thread only, lerpQ does not retain or mutate its arguments, and state.rotation copies.
        state.rotation.set(MathUtil.lerpQ(partialTicks,
                planeEntity.getQ_Prev(qPrevScratch), planeEntity.getQ_Client(qClientScratch)));
        state.propellerRotation = Mth.lerp(partialTicks, planeEntity.propellerRotationOld, planeEntity.propellerRotationNew);
        state.timeSinceHit = planeEntity.getTimeSinceHit() - partialTicks;
        state.materialTexture = getMaterialTexture(planeEntity.getMaterial());
        state.isCargoPlane = planeEntity instanceof CargoPlaneEntity;

        state.upgradeTypes.clear();
        state.hasSeatsUpgrade = false;
        for (Upgrade upgrade : planeEntity.upgrades.values()) {
            state.upgradeTypes.add(upgrade.getType());
            if (upgrade.getType() == SimplePlanesUpgrades.SEATS.get()) {
                state.hasSeatsUpgrade = true;
            }
        }
    }

    @Override
    public void submit(PlaneRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.translate(0.0F, 0.375F, 0.0F);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.mulPose(new Quaternionf(state.rotation));

        EntityType<?> entityType = state.entityType;
        if (entityType == SimplePlanesEntities.PLANE.get()) {
            poseStack.translate(0.0F, -0.5F, -0.5F);
        } else if (entityType == SimplePlanesEntities.LARGE_PLANE.get()) {
            poseStack.translate(0.0F, -0.3F, -1.0F);
        } else if (entityType == SimplePlanesEntities.CARGO_PLANE.get()) {
            poseStack.translate(0.0F, -0.8F, -1.0F);
        } else {
            poseStack.translate(0.0F, 0.0F, 0.9F);
        }

        if (state.timeSinceHit > 0.0F) {
            float angle = Mth.clamp(state.timeSinceHit / 10.0F, -30.0F, 30.0F);
            poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.sin(state.ageInTicks) * angle));
        }

        poseStack.translate(0.0F, -1.1F, 0.0F);

        collector.submitModel(this.planeEntityModel, state, poseStack,
                this.planeEntityModel.renderType(state.materialTexture),
                state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);

        collector.submitModel(this.propellerModel, state, poseStack,
                this.propellerModel.renderType(this.propellerTexture),
                state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);

        collector.submitModel(this.planeMetalModel, state, poseStack,
                this.planeMetalModel.renderType(this.metalTexture),
                state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);

        UpgradesModels.submitUpgrades(state, poseStack, collector, state.lightCoords);

        // TODO(port-26.2): DISABLED — CargoPlaneEntity#largeUpgrades rendering (chests / supply crates
        // drawn as block models inside the cargo bay). The block-model renderer moved behind
        // BlockModelResolver + submitBlockModel and there is no entity-free equivalent of
        // BlockRenderDispatcher#renderSingleBlock(ModelData) any more.
        /*
        if (planeEntity instanceof CargoPlaneEntity cargoPlaneEntity) {
            for (int i = 0; i < cargoPlaneEntity.largeUpgrades.size(); i++) { ... }
        }
        */

        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }

    /**
     * Texture used for the wooden parts of the plane.
     *
     * <p>1.21.1 pulled the first quad's sprite out of the block's baked inventory model. That path is
     * gone in 26.2 (baked models are resolved through {@code BlockModelResolver} and quads are no
     * longer reachable without a level), so this now derives the texture from the block id, which is
     * correct for every vanilla plank/wool style block the mod supports.
     */
    public static Identifier getMaterialTexture(Block block) {
        Identifier cached = cachedTextures.get(block);
        if (cached != null) {
            return cached;
        }

        Identifier key = BuiltInRegistries.BLOCK.getKey(block);
        Identifier texture = key == null
                ? FALLBACK_TEXTURE
                : Identifier.fromNamespaceAndPath(key.getNamespace(), "textures/block/" + key.getPath() + ".png");

        cachedTextures.put(block, texture);
        return texture;
    }

    public static void clearTextureCache() {
        cachedTextures.clear();
    }

    public static final Map<Block, Identifier> cachedTextures = new HashMap<>();
    public static final Identifier FALLBACK_TEXTURE = Identifier.fromNamespaceAndPath("minecraft", "textures/block/oak_planks.png");
}
