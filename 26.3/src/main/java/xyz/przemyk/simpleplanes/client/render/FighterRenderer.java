package xyz.przemyk.simpleplanes.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import xyz.przemyk.simpleplanes.client.render.models.FighterGlassModel;
import xyz.przemyk.simpleplanes.entities.FighterEntity;

/** Fighter jet: the usual three layers plus the translucent canopy glass. */
@Environment(EnvType.CLIENT)
public class FighterRenderer extends PlaneRenderer<FighterEntity> {

    protected final FighterGlassModel glassModel;
    private final Matrix4f inverseScratch = new Matrix4f();
    private final Vector3f cameraScratch = new Vector3f();

    public FighterRenderer(EntityRendererProvider.Context context,
                           EntityModel<PlaneRenderState> planeModel,
                           EntityModel<PlaneRenderState> metalModel,
                           EntityModel<PlaneRenderState> exhaustModel,
                           FighterGlassModel glassModel,
                           float shadowSize,
                           Identifier metalTexture,
                           Identifier propellerTexture) {
        super(context, planeModel, metalModel, exhaustModel, shadowSize, metalTexture, propellerTexture);
        this.glassModel = glassModel;
    }

    @Override
    protected void submitExtraLayers(PlaneRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        // The pose is camera-relative, so the camera is its origin. The glass is submitted from a point nearer the
        // camera than the rider, so the translucent phase draws it after the pilot (see FighterGlassModel).
        Vector3f camera = inverseScratch.set(poseStack.last().pose()).invert().transformPosition(cameraScratch.zero());
        FighterGlassModel.sortOrigin(camera, state.glassSortOrigin);
        poseStack.pushPose();
        poseStack.translate(state.glassSortOrigin.x, state.glassSortOrigin.y, state.glassSortOrigin.z);
        collector.submitModel(glassModel, state, poseStack, glassModel.renderType(FighterGlassModel.TEXTURE),
                state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        poseStack.popPose();
    }
}
