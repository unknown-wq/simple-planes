package xyz.przemyk.simpleplanes.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.container.PlaneInventoryContainer;
import xyz.przemyk.simpleplanes.network.CyclePlaneInventoryPacket;

@Environment(EnvType.CLIENT)
public class PlaneInventoryScreen extends AbstractContainerScreen<PlaneInventoryContainer> {

    public static final Identifier GUI = Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/gui/plane_inventory.png");
    public static final WidgetSprites LEFT_BUTTON_SPRITES = new WidgetSprites(
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "left"),
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "left_highlighted")
    );
    public static final WidgetSprites RIGHT_BUTTON_SPRITES = new WidgetSprites(
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "right"),
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "right_highlighted")
    );

    public PlaneInventoryScreen(PlaneInventoryContainer screenContainer, Inventory inventory, Component title) {
        super(screenContainer, inventory, title);
    }

    /** {@code AbstractContainerScreen#getGuiLeft()} is gone in 26.2; kept for the upgrade screens. */
    public int getGuiLeft() {
        return this.leftPos;
    }

    /** {@code AbstractContainerScreen#getGuiTop()} is gone in 26.2; kept for the upgrade screens. */
    public int getGuiTop() {
        return this.topPos;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new ImageButton(leftPos + 8, topPos + 54, 10, 15, LEFT_BUTTON_SPRITES,
                button -> ClientPlayNetworking.send(new CyclePlaneInventoryPacket(CyclePlaneInventoryPacket.Direction.LEFT))));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        graphics.blit(RenderPipelines.GUI_TEXTURED, GUI, this.leftPos, this.topPos, 0.0F, 0.0F, this.imageWidth, this.imageHeight, 256, 256);

        // TODO(port-26.2): DISABLED — Upgrade#renderScreenBg / #renderScreen overlays (furnace burn
        // bar, energy bar, fluid tank). They took a GuiGraphics, which no longer exists in 26.2, and
        // the fluid one additionally used NeoForge's IClientFluidTypeExtensions.
    }
}
