package xyz.przemyk.simpleplanes.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import xyz.przemyk.simpleplanes.client.render.models.AirlinerSkinModel;
import xyz.przemyk.simpleplanes.entities.AirlinerEntity;

/** Mini airliner: wooden or metal-skinned body by material, and the airline logo. */
@Environment(EnvType.CLIENT)
public class AirlinerRenderer extends PlaneRenderer<AirlinerEntity> {

    protected final EntityModel<PlaneRenderState> skinModel;

    public AirlinerRenderer(EntityRendererProvider.Context context,
                            EntityModel<PlaneRenderState> woodenModel,
                            EntityModel<PlaneRenderState> skinModel,
                            EntityModel<PlaneRenderState> metalModel,
                            EntityModel<PlaneRenderState> fanModel,
                            float shadowSize,
                            Identifier metalTexture,
                            Identifier propellerTexture) {
        super(context, woodenModel, metalModel, fanModel, shadowSize, metalTexture, propellerTexture);
        this.skinModel = skinModel;
    }

    @Override
    public void extractRenderState(AirlinerEntity entity, PlaneRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.metalSkin = entity.hasMetalSkin();
        state.airlinerLogo = entity.getLogo();
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
        return state.metalSkin ? AirlinerSkinModel.TEXTURE : state.materialTexture;
    }
}
