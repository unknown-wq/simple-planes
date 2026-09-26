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
import xyz.przemyk.simpleplanes.client.render.models.MiniHeliGlassModel;
import xyz.przemyk.simpleplanes.client.render.models.MiniHeliMedicalModel;
import xyz.przemyk.simpleplanes.entities.MiniHelicopterEntity;

/** Mini helicopter: standard or medical body by livery, plus the translucent cabin glass. */
@Environment(EnvType.CLIENT)
public class MiniHeliRenderer extends PlaneRenderer<MiniHelicopterEntity> {

    protected final EntityModel<PlaneRenderState> medicalModel;
    protected final MiniHeliGlassModel glassModel;
    private final Matrix4f inverseScratch = new Matrix4f();
    private final Vector3f cameraScratch = new Vector3f();

    public MiniHeliRenderer(EntityRendererProvider.Context context,
                            EntityModel<PlaneRenderState> standardModel,
                            EntityModel<PlaneRenderState> medicalModel,
                            EntityModel<PlaneRenderState> metalModel,
                            EntityModel<PlaneRenderState> rotorModel,
                            MiniHeliGlassModel glassModel,
                            float shadowSize,
                            Identifier metalTexture,
                            Identifier propellerTexture) {
        super(context, standardModel, metalModel, rotorModel, shadowSize, metalTexture, propellerTexture);
        this.medicalModel = medicalModel;
        this.glassModel = glassModel;
    }

    @Override
    public void extractRenderState(MiniHelicopterEntity entity, PlaneRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.medicalLivery = entity.hasMedicalLivery();
    }

    @Override
    protected EntityModel<PlaneRenderState> bodyModel(PlaneRenderState state) {
        return state.medicalLivery ? medicalModel : planeEntityModel;
    }

    @Override
    protected Identifier bodyTexture(PlaneRenderState state) {
        return state.medicalLivery ? MiniHeliMedicalModel.TEXTURE : state.materialTexture;
    }

    @Override
    protected float wobbleScale(PlaneRenderState state) {
        return 0.6F;
    }

    @Override
    protected void submitExtraLayers(PlaneRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        // The pose is camera-relative, so the camera is its origin. Submitting the glass from the point of its
        // box nearest the camera sorts it after the rider (see MiniHeliGlassModel).
        Vector3f camera = inverseScratch.set(poseStack.last().pose()).invert().transformPosition(cameraScratch.zero());
        MiniHeliGlassModel.sortOrigin(camera, state.glassSortOrigin);
        poseStack.pushPose();
        poseStack.translate(state.glassSortOrigin.x, state.glassSortOrigin.y, state.glassSortOrigin.z);
        collector.submitModel(glassModel, state, poseStack, glassModel.renderType(MiniHeliGlassModel.TEXTURE),
                state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        poseStack.popPose();
    }
}
