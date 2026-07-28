package xyz.przemyk.simpleplanes.upgrades.banner;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Banner upgrade rendering.
 *
 * <p>TODO(port-26.2): DISABLED — the banner mounted on the plane's tail is no longer drawn.
 * The 1.21.1 implementation reached into {@code BannerRenderer.flag} / {@code BannerRenderer.renderPatterns}
 * and a shared {@code BannerBlockEntity}; in 26.2 banners render as *block* models (not block-entity
 * models), {@code BannerRenderer} no longer exposes a {@code flag} ModelPart or a static
 * {@code renderPatterns}, and {@code ModelBakery.BANNER_BASE} is gone. It also needed live entity
 * access, which an entity renderer may no longer have.
 */
@Environment(EnvType.CLIENT)
public final class BannerModel {

    private BannerModel() {}

    /*
    private static final BannerBlockEntity BANNER_BLOCK_ENTITY = new BannerBlockEntity(BlockPos.ZERO, Blocks.BLACK_BANNER.defaultBlockState());

    public static void renderBanner(BannerUpgrade bannerUpgrade, float partialTicks, PoseStack poseStack, MultiBufferSource bufferIn, ItemStack banner, int packedLight) {
        PlaneEntity planeEntity = bannerUpgrade.getPlaneEntity();
        if (!banner.isEmpty()) {
            poseStack.pushPose();

            EntityType<?> entityType = planeEntity.getType();
            if (entityType == SimplePlanesEntities.HELICOPTER.get()) {
                poseStack.mulPose(Axis.YP.rotationDegrees(90));
                poseStack.translate(-4, -1.25, 0.025);
            } else {
                poseStack.mulPose(Axis.XP.rotationDegrees(98));
                poseStack.mulPose(Axis.YP.rotationDegrees(90));
                poseStack.translate(1, 3.62, 0.05);
                if (entityType == SimplePlanesEntities.LARGE_PLANE.get()) {
                    poseStack.translate(0.395, 1.92, 0);
                } else if (entityType == SimplePlanesEntities.CARGO_PLANE.get()) {
                    poseStack.translate(-1, 8.0625, 0);
                }
            }

            poseStack.scale(0.6f, -0.6f, -0.6f);
            final BannerItem item = (BannerItem) banner.getItem();
            BANNER_BLOCK_ENTITY.fromItem(banner, item.getColor());
            final float tickCountWithPartial = partialTicks + planeEntity.tickCount;
            float r = (0.05F * Mth.cos(tickCountWithPartial / 5)) * (float) 180;
            r += (float) MathUtil.lerpAngle(partialTicks, MathUtil.wrapSubtractDegrees(bannerUpgrade.rotation, bannerUpgrade.prevRotation), 0);
            BlockEntityRenderer<BannerBlockEntity> renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(BANNER_BLOCK_ENTITY);
            if (renderer instanceof BannerRenderer bannerRenderer) {
                bannerRenderer.flag.xRot = (float) (Math.PI + r / 100.0f);
                BannerRenderer.renderPatterns(poseStack, bufferIn, packedLight, OverlayTexture.NO_OVERLAY, bannerRenderer.flag,
                    ModelBakery.BANNER_BASE, true, BANNER_BLOCK_ENTITY.getBaseColor(), BANNER_BLOCK_ENTITY.getPatterns());
            }
            poseStack.popPose();
        }
    }
    */
}
