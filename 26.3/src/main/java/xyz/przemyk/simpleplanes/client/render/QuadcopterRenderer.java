package xyz.przemyk.simpleplanes.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import xyz.przemyk.simpleplanes.entities.QuadcopterEntity;
import xyz.przemyk.simpleplanes.misc.MathUtil;

import java.util.List;

/** Quadcopter crane: the drone model and the rope, which vanilla draws from the render state's leash. */
@Environment(EnvType.CLIENT)
public class QuadcopterRenderer extends EntityRenderer<QuadcopterEntity, QuadcopterRenderState> {

    /** Model scale; 1.0 is the contract's size. */
    public static final float SCALE = 1.0F;

    protected final EntityModel<PlaneRenderState> frameModel;
    protected final EntityModel<PlaneRenderState> metalModel;
    protected final EntityModel<PlaneRenderState> rotorModel;
    protected final Identifier metalTexture;

    public QuadcopterRenderer(EntityRendererProvider.Context context,
                              EntityModel<PlaneRenderState> frameModel,
                              EntityModel<PlaneRenderState> metalModel,
                              EntityModel<PlaneRenderState> rotorModel,
                              float shadowSize,
                              Identifier metalTexture) {
        super(context);
        this.frameModel = frameModel;
        this.metalModel = metalModel;
        this.rotorModel = rotorModel;
        this.metalTexture = metalTexture;
        this.shadowRadius = shadowSize;
    }

    @Override
    public QuadcopterRenderState createRenderState() {
        return new QuadcopterRenderState();
    }

    @Override
    public void extractRenderState(QuadcopterEntity entity, QuadcopterRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.rotation.set(MathUtil.lerpQ(partialTicks, new Quaternionf(entity.Q_Prev), new Quaternionf(entity.Q_Client)));
        state.propellerRotation = Mth.lerp(partialTicks, entity.propellerRotationOld, entity.propellerRotationNew);
        state.timeSinceHit = entity.getTimeSinceHit() - partialTicks;
        state.materialTexture = PlaneRenderer.getMaterialTexture(entity.getMaterial());
        state.carrying = entity.isCarrying();
        state.ropeLength = entity.getRopeLength();
        state.hookWorld = entity.hookWorld(partialTicks);

        if (state.ropeLength > 1.05F || state.carrying) {
            EntityRenderState.LeashState rope = new EntityRenderState.LeashState();
            rope.offset = new Vec3(0, QuadcopterEntity.WINCH_HEIGHT, 0);
            rope.start = entity.getPosition(partialTicks).add(rope.offset);
            rope.end = state.hookWorld;
            BlockPos startPos = BlockPos.containing(rope.start);
            BlockPos endPos = BlockPos.containing(rope.end);
            rope.startBlockLight = getBlockLightLevel(entity, startPos);
            rope.endBlockLight = getBlockLightLevel(entity, endPos);
            rope.startSkyLight = entity.level().getBrightness(LightLayer.SKY, startPos);
            rope.endSkyLight = entity.level().getBrightness(LightLayer.SKY, endPos);
            rope.slack = false;
            state.leashStates = List.of(rope);
        } else {
            state.leashStates = null;
        }
    }

    @Override
    protected AABB getBoundingBoxForCulling(QuadcopterEntity entity, float partialTicks) {
        return entity.getBoundingBox().expandTowards(0, -entity.getRopeLength() - 0.5, 0);
    }

    @Override
    public void submit(QuadcopterRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.translate(0.0F, 0.375F, 0.0F);
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

        collector.submitModel(frameModel, state, poseStack, frameModel.renderType(state.materialTexture),
                state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        collector.submitModel(rotorModel, state, poseStack, rotorModel.renderType(metalTexture),
                state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        collector.submitModel(metalModel, state, poseStack, metalModel.renderType(metalTexture),
                state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);

        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }
}
