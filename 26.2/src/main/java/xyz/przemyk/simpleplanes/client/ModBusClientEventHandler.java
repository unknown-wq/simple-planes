package xyz.przemyk.simpleplanes.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.upgrades.booster.BoosterUpgrade;

/**
 * The plane HUD (health hearts + throttle gauge).
 *
 * <p>Was a NeoForge {@code RegisterGuiLayersEvent} layer; on 26.2 it is a Fabric
 * {@link HudElement}, which draws during GUI render-state extraction via
 * {@link GuiGraphicsExtractor}. Registered from {@link SimplePlanesClient}.
 *
 * <p>The class name is kept so the engine upgrades can keep referencing {@link #HUD_TEXTURE}.
 */
@Environment(EnvType.CLIENT)
public final class ModBusClientEventHandler implements HudElement {

    public static final Identifier HUD_TEXTURE = Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/gui/plane_hud.png");
    public static final Identifier HUD_ELEMENT_ID = Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "plane_hud");

    public static final ModBusClientEventHandler INSTANCE = new ModBusClientEventHandler();

    private ModBusClientEventHandler() {}

    private static final int FULL = 0;
    private static final int EMPTY = 16;
    private static final int GOLD = 32;
    private static final int V_OFFSET = 35;
    private static final int MAX_ROW_SIZE = 5;

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !(player.getVehicle() instanceof PlaneEntity planeEntity)) {
            return;
        }

        int scaledWidth = graphics.guiWidth();
        int scaledHeight = graphics.guiHeight();
        int leftAlign = scaledWidth / 2 + 91;

        int health = planeEntity.getHealth();
        int hearts = Math.min((int) planeEntity.getMaxHealth(), 10);

        // TODO(port-26.2): the vanilla Gui.rightHeight cursor is gone in 26.2, so the rows are placed
        // at a fixed offset above the hotbar instead of stacking on top of the vanilla mount bar.
        int rowIndex = 0;
        for (int heart = 0; hearts > 0; heart += MAX_ROW_SIZE) {
            int top = scaledHeight - 49 - rowIndex * 10;
            int rowCount = Math.min(hearts, MAX_ROW_SIZE);
            hearts -= rowCount;

            for (int i = 0; i < rowCount; ++i) {
                int x = leftAlign - i * 16 - 16;
                int u;
                if (i + heart + 10 < health) {
                    u = GOLD;
                } else if (i + heart < health) {
                    u = FULL;
                } else {
                    u = EMPTY;
                }
                graphics.blit(RenderPipelines.GUI_TEXTURED, HUD_TEXTURE, x, top, u, V_OFFSET, 16, 9, 256, 256);
            }
            rowIndex++;
        }

        graphics.blit(RenderPipelines.GUI_TEXTURED, HUD_TEXTURE, scaledWidth - 24, scaledHeight - 42, 0, 84, 22, 40, 256, 256);
        int throttle = planeEntity.getThrottle();
        if (throttle > 0) {
            int throttleScaled = throttle * 28 / BoosterUpgrade.MAX_THROTTLE;
            graphics.blit(RenderPipelines.GUI_TEXTURED, HUD_TEXTURE,
                    scaledWidth - 24 + 10, scaledHeight - 42 + 6 + 28 - throttleScaled,
                    22, 90 + 28 - throttleScaled, 2, throttleScaled, 256, 256);
        }

        // TODO(port-26.2): DISABLED — EngineUpgrade#renderPowerHUD (fuel / energy gauge next to the
        // hotbar). It took a GuiGraphics, which no longer exists; restoring it needs the upgrade
        // classes (Agent B) to move to GuiGraphicsExtractor.
    }
}
