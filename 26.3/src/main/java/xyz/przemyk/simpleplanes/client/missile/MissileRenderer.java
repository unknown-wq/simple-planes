package xyz.przemyk.simpleplanes.client.missile;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.client.render.MissileRenderState;
import xyz.przemyk.simpleplanes.client.render.models.MissileModel;
import xyz.przemyk.simpleplanes.missile.MissileEntity;

/**
 * Draws a missile with the tier's {@link MissileModel}, oriented along its flight direction, plus a flame-only
 * copy of the model in {@code RenderTypes.eyes} so the flame glows. The hooks are driven through the render
 * state: in 26.3 {@code submitModel} is deferred and calls {@code setupAnim(state)} itself at draw time.
 */
@Environment(EnvType.CLIENT)
public class MissileRenderer extends EntityRenderer<MissileEntity, MissileRenderState> {

    private final MissileModel[] models = new MissileModel[5];
    private final MissileModel[] glow = new MissileModel[5];

    public MissileRenderer(EntityRendererProvider.Context context) {
        super(context);
        for (int t = 1; t <= 4; t++) {
            models[t] = new MissileModel(context.bakeLayer(MissilesClient.MISSILE_LAYERS[t]));
            glow[t] = new MissileModel(context.bakeLayer(MissilesClient.MISSILE_LAYERS[t])).flameOnly();
        }
        this.shadowRadius = 0.3F;
    }

    @Override
    public MissileRenderState createRenderState() {
        return new MissileRenderState();
    }

    @Override
    public void extractRenderState(MissileEntity entity, MissileRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.tier = entity.tier().tier;
        state.finsDeployed = entity.finsDeployed(partialTicks);
        state.thrust = entity.thrust();
        state.boosterAttached = entity.boosterAttached();
        Vec3 d = Vec3.directionFromRotation(entity.getXRot(partialTicks), entity.getYRot(partialTicks));
        state.rotation.rotationTo(0.0F, 1.0F, 0.0F, (float) d.x, (float) d.y, (float) d.z);
        state.shadowRadius = new float[]{0, 0.2F, 0.3F, 0.4F, 0.6F}[state.tier];
    }

    @Override
    public void submit(MissileRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        MissileModel model = models[state.tier];
        poseStack.pushPose();
        poseStack.translate(0.0F, state.boundingBoxHeight / 2.0F, 0.0F);
        poseStack.rotate(state.rotation);
        poseStack.translate(0.0F, -MissileModel.lengthBlocks(state.tier) / 2.0F, 0.0F);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.translate(0.0F, -1.5F, 0.0F);
        collector.submitModel(model, state, poseStack, model.renderType(MissilesClient.MISSILE_TEXTURE),
            state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        if (state.thrust > 0.05F) {
            collector.submitModel(glow[state.tier], state, poseStack, RenderTypes.eyes(MissilesClient.MISSILE_TEXTURE),
                state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        }
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }

    @Override
    protected AABB getBoundingBoxForCulling(MissileEntity entity, float partialTicks) {
        return super.getBoundingBoxForCulling(entity, partialTicks).inflate(entity.tier().length);
    }
}
