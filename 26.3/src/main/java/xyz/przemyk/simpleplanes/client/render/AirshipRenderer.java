package xyz.przemyk.simpleplanes.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import xyz.przemyk.simpleplanes.entities.AirshipEntity;

/** Airship: the envelope as a fourth layer, and a culling box that covers it. */
@Environment(EnvType.CLIENT)
public class AirshipRenderer extends PlaneRenderer<AirshipEntity> {

    protected final EntityModel<PlaneRenderState> envelopeModel;
    protected final Identifier envelopeTexture;

    public AirshipRenderer(EntityRendererProvider.Context context,
                           EntityModel<PlaneRenderState> gondolaModel,
                           EntityModel<PlaneRenderState> metalModel,
                           EntityModel<PlaneRenderState> propellerModel,
                           EntityModel<PlaneRenderState> envelopeModel,
                           float shadowSize,
                           Identifier metalTexture,
                           Identifier propellerTexture,
                           Identifier envelopeTexture) {
        super(context, gondolaModel, metalModel, propellerModel, shadowSize, metalTexture, propellerTexture);
        this.envelopeModel = envelopeModel;
        this.envelopeTexture = envelopeTexture;
    }

    @Override
    protected void submitExtraLayers(PlaneRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        collector.submitModel(envelopeModel, state, poseStack, envelopeModel.renderType(envelopeTexture),
                state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
    }

    @Override
    protected AABB getBoundingBoxForCulling(AirshipEntity entity, float partialTicks) {
        return entity.getBoundingBox().inflate(9.5, 0, 9.5).expandTowards(0, 9, 0);
    }
}
