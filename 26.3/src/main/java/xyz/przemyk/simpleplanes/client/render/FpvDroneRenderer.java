package xyz.przemyk.simpleplanes.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import xyz.przemyk.simpleplanes.entities.FpvDroneEntity;

/**
 * FPV drone: the quadcopter crane's model at {@link #SCALE}, with its charge in the clamp. The pose is
 * {@code QuadcopterRenderer}'s, except that the pivot is lowered with the scale so the legs still
 * stand on the ground.
 */
@Environment(EnvType.CLIENT)
public class FpvDroneRenderer extends PlaneRenderer<FpvDroneEntity> {

    /** Model scale against the crane's; the hitbox in {@code SimplePlanesEntities} follows it. */
    public static final float SCALE = 0.6F;

    public FpvDroneRenderer(EntityRendererProvider.Context context,
                            EntityModel<PlaneRenderState> frameModel,
                            EntityModel<PlaneRenderState> metalModel,
                            EntityModel<PlaneRenderState> rotorModel,
                            float shadowSize,
                            Identifier metalTexture) {
        super(context, frameModel, metalModel, rotorModel, shadowSize, metalTexture, metalTexture);
    }

    @Override
    public PlaneRenderState createRenderState() {
        return new FpvDroneRenderState();
    }

    @Override
    protected void applyModelPose(PlaneRenderState state, PoseStack poseStack) {
        poseStack.translate(0.0F, 0.375F * SCALE, 0.0F);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.rotate(Axis.YP.rotationDegrees(180.0F));
        poseStack.rotate(new Quaternionf(state.rotation));
        poseStack.scale(SCALE, SCALE, SCALE);
        poseStack.translate(0.0F, -0.025F, 0.0F);

        if (state.timeSinceHit > 0.0F) {
            float angle = Mth.clamp(state.timeSinceHit / 10.0F, -30.0F, 30.0F) * 0.4F;
            poseStack.rotate(Axis.ZP.rotationDegrees(Mth.sin(state.ageInTicks) * angle));
        }

        poseStack.translate(0.0F, -1.1F, 0.0F);
    }
}
