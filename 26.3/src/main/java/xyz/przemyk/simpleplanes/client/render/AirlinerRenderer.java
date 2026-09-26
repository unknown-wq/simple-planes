package xyz.przemyk.simpleplanes.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import xyz.przemyk.simpleplanes.client.render.models.AirlinerGlassModel;
import xyz.przemyk.simpleplanes.client.render.models.AirlinerShape;
import xyz.przemyk.simpleplanes.entities.AirlinerEntity;

/** Airliner of either size: wooden or metal-skinned body by material, the airline logo, and the window glass. */
@Environment(EnvType.CLIENT)
public class AirlinerRenderer extends PlaneRenderer<AirlinerEntity> {

    protected final EntityModel<PlaneRenderState> skinModel;
    protected final AirlinerGlassModel glassModel;
    private final AirlinerShape shape;
    private final Matrix4f inverseScratch = new Matrix4f();
    private final Vector3f cameraScratch = new Vector3f();

    public AirlinerRenderer(EntityRendererProvider.Context context,
                            AirlinerShape shape,
                            EntityModel<PlaneRenderState> woodenModel,
                            EntityModel<PlaneRenderState> skinModel,
                            EntityModel<PlaneRenderState> metalModel,
                            EntityModel<PlaneRenderState> fanModel,
                            AirlinerGlassModel glassModel,
                            float shadowSize) {
        super(context, woodenModel, metalModel, fanModel, shadowSize, shape.metalTexture(), shape.metalTexture());
        this.skinModel = skinModel;
        this.glassModel = glassModel;
        this.shape = shape;
    }

    @Override
    public void extractRenderState(AirlinerEntity entity, PlaneRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.metalSkin = entity.hasMetalSkin();
        state.airlinerLogo = entity.getLogo();
        state.airlinerGear = entity.gearDown(partialTicks);
        // A rider does not see the back of his own seat in first person, only the cushion.
        Minecraft minecraft = Minecraft.getInstance();
        Entity camera = minecraft.getCameraEntity();
        state.airlinerHiddenSeat = camera != null && camera.getVehicle() == entity && minecraft.options.getCameraType().isFirstPerson()
            ? entity.seatOf(camera) : -1;
    }

    @Override
    protected EntityModel<PlaneRenderState> bodyModel(PlaneRenderState state) {
        return state.metalSkin ? skinModel : planeEntityModel;
    }

    @Override
    protected Identifier bodyTexture(PlaneRenderState state) {
        return state.metalSkin ? shape.skinTexture() : state.materialTexture;
    }

    @Override
    protected void submitExtraLayers(PlaneRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        // Camera in model space from the camera-relative pose; the glass is submitted from a point nearer the camera
        // than the riders (see AirlinerGlassModel).
        Vector3f camera = inverseScratch.set(poseStack.last().pose()).invert().transformPosition(cameraScratch.zero());
        glassModel.sortOrigin(camera, state.glassSortOrigin);
        poseStack.pushPose();
        poseStack.translate(state.glassSortOrigin.x, state.glassSortOrigin.y, state.glassSortOrigin.z);
        collector.submitModel(glassModel, state, poseStack, glassModel.renderType(AirlinerGlassModel.TEXTURE),
                state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        poseStack.popPose();
    }
}
