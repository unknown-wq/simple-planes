package xyz.przemyk.simpleplanes.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import xyz.przemyk.simpleplanes.client.render.models.ParachuteModel;
import xyz.przemyk.simpleplanes.entities.ParachuteEntity;

@Environment(EnvType.CLIENT)
public class ParachuteRenderer extends EntityRenderer<ParachuteEntity, ParachuteRenderer.ParachuteRenderState> {

    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("minecraft", "textures/block/white_wool.png");

    private final ParachuteModel parachuteModel;

    public ParachuteRenderer(EntityRendererProvider.Context context, ParachuteModel parachuteModel) {
        super(context);
        this.parachuteModel = parachuteModel;
    }

    @Override
    public ParachuteRenderState createRenderState() {
        return new ParachuteRenderState();
    }

    @Override
    public void extractRenderState(ParachuteEntity entity, ParachuteRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.hasStorageCrate = entity.hasStorageCrate();
    }

    @Override
    public void submit(ParachuteRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();

        // TODO(port-26.2): DISABLED — the barrel block rendered under a supply-crate parachute.
        // BlockRenderDispatcher#renderSingleBlock(BlockState, PoseStack, MultiBufferSource, ...) is
        // gone in 26.2; block models are now submitted through BlockModelResolver/BlockStateModelPart
        // which needs level context the render state does not carry.
        /*
        if (parachuteEntity.hasStorageCrate()) {
            poseStack.pushPose();
            poseStack.translate(-0.5, 0, -0.5);
            BlockState state = Blocks.BARREL.defaultBlockState();
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, poseStack, buffer, packetLight, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
            poseStack.popPose();
        }
        */

        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.translate(0.0F, state.hasStorageCrate ? -2.0F : -3.0F, 0.0F);

        collector.submitModel(this.parachuteModel, state, poseStack,
                this.parachuteModel.renderType(TEXTURE),
                state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);

        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }

    @Environment(EnvType.CLIENT)
    public static class ParachuteRenderState extends EntityRenderState {
        public boolean hasStorageCrate;
    }
}
