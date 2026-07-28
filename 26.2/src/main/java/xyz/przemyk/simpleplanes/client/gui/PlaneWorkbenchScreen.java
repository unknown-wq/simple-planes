package xyz.przemyk.simpleplanes.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.container.PlaneWorkbenchContainer;
import xyz.przemyk.simpleplanes.network.CycleItemsPacket;

@Environment(EnvType.CLIENT)
public class PlaneWorkbenchScreen extends AbstractContainerScreen<PlaneWorkbenchContainer> {

    public static final Identifier GUI = Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/gui/plane_workbench.png");

    public PlaneWorkbenchScreen(PlaneWorkbenchContainer screenContainer, Inventory inv, Component titleIn) {
        super(screenContainer, inv, titleIn);
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new ImageButton(leftPos + 122, topPos + 47, 10, 15, PlaneInventoryScreen.LEFT_BUTTON_SPRITES,
                button -> ClientPlayNetworking.send(new CycleItemsPacket(CycleItemsPacket.Direction.CRAFTING_LEFT))));

        addRenderableWidget(new ImageButton(leftPos + 152, topPos + 47, 10, 15, PlaneInventoryScreen.RIGHT_BUTTON_SPRITES,
                button -> ClientPlayNetworking.send(new CycleItemsPacket(CycleItemsPacket.Direction.CRAFTING_RIGHT))));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int i = this.leftPos;
        int j = (this.height - this.imageHeight) / 2;
        graphics.blit(RenderPipelines.GUI_TEXTURED, GUI, i, j, 0.0F, 0.0F, this.imageWidth, this.imageHeight, 256, 256);
    }
}
