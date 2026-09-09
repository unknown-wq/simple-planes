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
import xyz.przemyk.simpleplanes.upgrades.Upgrade;

import java.util.Collection;
import java.util.List;

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

        for (Upgrade upgrade : upgrades()) {
            upgrade.renderScreenBg(graphics, mouseX, mouseY, a, this);
        }
    }

    /**
     * The upgrades' own overlays in front of the slots, which is where their hover tooltips live.
     *
     * <p>{@code extractTooltip} rather than the background pass: a tooltip is not drawn where it is
     * asked for any more, it is handed to the frame and drawn last, and this is the point in a
     * container screen where the slot tooltips are collected too — so an upgrade gauge and a slot
     * cannot both claim the pointer.
     */
    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        for (Upgrade upgrade : upgrades()) {
            upgrade.renderScreen(graphics, mouseX, mouseY, 0.0F, this);
        }
    }

    /**
     * Widened from protected so the upgrades can ask it. They are in another package and the
     * hover regions belong with the gauge that owns them, not in a table here.
     */
    @Override
    public boolean isHovering(int x, int y, int width, int height, double mouseX, double mouseY) {
        return super.isHovering(x, y, width, height, mouseX, mouseY);
    }

    /** The upgrades installed on the plane this screen is open on, or none if it has gone away. */
    private Collection<Upgrade> upgrades() {
        return menu.planeEntity == null ? List.of() : menu.planeEntity.upgrades.values();
    }
}
