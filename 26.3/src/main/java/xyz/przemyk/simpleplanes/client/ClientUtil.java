package xyz.przemyk.simpleplanes.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Small client-side helpers.
 *
 * <p>The tiled fluid-sprite renderer that used to live here is gone — see the TODO below.
 */
@Environment(EnvType.CLIENT)
public final class ClientUtil {

    private ClientUtil() {}

    public static int alpha(int c) {
        return (c >> 24) & 0xFF;
    }

    public static int red(int c) {
        return (c >> 16) & 0xFF;
    }

    public static int green(int c) {
        return (c >> 8) & 0xFF;
    }

    public static int blue(int c) {
        return c & 0xFF;
    }

    // TODO(port-26.2): DISABLED — renderTiledTextureAtlas / renderLiquidEngineFluid.
    // Both relied on APIs that no longer exist in 26.2:
    //  * immediate-mode Tesselator + BufferUploader.drawWithShader and RenderSystem.setShaderTexture
    //    (GUI drawing goes through GuiGraphicsExtractor + RenderPipelines now);
    //  * NeoForge's IClientFluidTypeExtensions / FluidStack for the still-texture lookup, which have
    //    no Fabric counterpart in this port (contract C4 removed the fluid capability entirely).
    /*
    public static void renderTiledTextureAtlas(GuiGraphics guiGraphics, AbstractContainerScreen<?> screen,
                                               TextureAtlasSprite sprite, int x, int y, int width, int height, int depth) { ... }
    public static void renderLiquidEngineFluid(GuiGraphics guiGraphics, PlaneInventoryScreen screen,
                                               FluidStack fluidStack, int height, int width, int fluidHeight) { ... }
    public static void setColorRGBA(int color) { ... }
    */
}
