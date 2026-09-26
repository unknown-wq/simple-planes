package xyz.przemyk.simpleplanes.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.client.render.models.PatrolDroneModel;
import xyz.przemyk.simpleplanes.drone.PatrolDroneEntity;
import xyz.przemyk.simpleplanes.misc.MathUtil;

/** Patrol drone: the airframe lit by the world, and its beacon drawn full-bright on top. */
@Environment(EnvType.CLIENT)
public class PatrolDroneRenderer extends EntityRenderer<PatrolDroneEntity, PatrolDroneRenderState> {

    public static final Identifier TEXTURE = SimplePlanesMod.texture("patrol_drone.png");

    private final PatrolDroneModel body;
    private final PatrolDroneModel beacon;

    public PatrolDroneRenderer(EntityRendererProvider.Context context, PatrolDroneModel body, PatrolDroneModel beacon) {
        super(context);
        this.body = body;
        this.beacon = beacon;
        this.shadowRadius = 0.35F;
    }

    @Override
    public PatrolDroneRenderState createRenderState() {
        return new PatrolDroneRenderState();
    }

    @Override
    public void extractRenderState(PatrolDroneEntity entity, PatrolDroneRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.rotation.set(MathUtil.lerpQ(partialTicks, new Quaternionf(entity.Q_Prev), new Quaternionf(entity.Q_Client)));
        state.propellerRotation = Mth.lerp(partialTicks, entity.propellerRotationOld, entity.propellerRotationNew);
        state.timeSinceHit = entity.getTimeSinceHit() - partialTicks;
        state.tracking = entity.isTracking();
    }

    @Override
    public void submit(PatrolDroneRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();
        // model origin is the airframe centre, 4 px above the skids
        poseStack.translate(0.0F, 0.25F, 0.0F);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.rotate(Axis.YP.rotationDegrees(180.0F));
        poseStack.rotate(new Quaternionf(state.rotation));
        if (state.timeSinceHit > 0.0F) {
            float angle = Mth.clamp(state.timeSinceHit / 10.0F, -30.0F, 30.0F) * 0.4F;
            poseStack.rotate(Axis.ZP.rotationDegrees(Mth.sin(state.ageInTicks) * angle));
        }
        collector.submitModel(body, state, poseStack, body.renderType(TEXTURE),
            state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        collector.submitModel(beacon, state, poseStack, beacon.renderType(TEXTURE),
            LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, state.outlineColor);
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }
}
