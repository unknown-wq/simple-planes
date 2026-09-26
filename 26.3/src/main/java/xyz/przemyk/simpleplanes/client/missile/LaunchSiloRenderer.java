package xyz.przemyk.simpleplanes.client.missile;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.client.render.MissileRenderState;
import xyz.przemyk.simpleplanes.client.render.models.LaunchTubeModel;
import xyz.przemyk.simpleplanes.client.render.models.MissileModel;
import xyz.przemyk.simpleplanes.missile.LaunchSiloBlockEntity;

/**
 * Draws the whole silo from its master block: the tube of the silo's tier with its hatch, and the stowed missile
 * until the missile entity takes over. Every silo block has an invisible render shape, so this is all there is.
 */
@Environment(EnvType.CLIENT)
public class LaunchSiloRenderer implements BlockEntityRenderer<LaunchSiloBlockEntity, LaunchSiloRenderer.State> {

    private static final MissileRenderState[] STOWED = new MissileRenderState[5];

    static {
        for (int t = 1; t <= 4; t++) {
            STOWED[t] = new MissileRenderState();
            STOWED[t].tier = t;
        }
    }

    private final LaunchTubeModel[] tubes = new LaunchTubeModel[5];
    private final MissileModel[] missiles = new MissileModel[5];

    public LaunchSiloRenderer(BlockEntityRendererProvider.Context context) {
        for (int t = 1; t <= 4; t++) {
            tubes[t] = new LaunchTubeModel(context.bakeLayer(MissilesClient.TUBE_LAYERS[t]));
            missiles[t] = new MissileModel(context.bakeLayer(MissilesClient.MISSILE_LAYERS[t]));
        }
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(LaunchSiloBlockEntity be, State state, float partialTicks, Vec3 cameraPosition,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, state, partialTicks, cameraPosition, breakProgress);
        state.tier = be.tier().tier;
        state.hatchOpen = be.hatch(partialTicks);
        state.missileLoaded = be.showsStowedMissile();
        if (be.getLevel() != null) state.lightCoords = LightCoordsUtil.getLightCoords(be.getLevel(), be.getBlockPos().above());
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        LaunchTubeModel tube = tubes[state.tier];
        float o = LaunchTubeModel.footprintBlocks(state.tier) / 2.0F;
        Float hatch = state.hatchOpen;
        poseStack.pushPose();
        poseStack.translate(o, 1.0F, o);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        collector.submitModel(tube, hatch, poseStack, tube.renderType(MissilesClient.TUBE_TEXTURE),
            state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        if (state.breakProgress != null) {
            collector.order(1).submitCrumblingOverlay(tube, hatch, poseStack, tube.renderType(MissilesClient.TUBE_TEXTURE),
                state.lightCoords, OverlayTexture.NO_OVERLAY, -1, state.breakProgress);
        }
        if (state.missileLoaded) {
            MissileModel missile = missiles[state.tier];
            poseStack.translate(0.0F, (LaunchTubeModel.SEAT_PX[state.tier] - 24) / 16.0F, 0.0F);
            collector.submitModel(missile, STOWED[state.tier], poseStack, missile.renderType(MissilesClient.MISSILE_TEXTURE),
                state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        }
        poseStack.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Environment(EnvType.CLIENT)
    public static class State extends BlockEntityRenderState {
        public int tier = 1;
        public float hatchOpen;
        public boolean missileLoaded;
    }
}
