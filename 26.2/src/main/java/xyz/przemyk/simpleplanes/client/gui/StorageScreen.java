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
import xyz.przemyk.simpleplanes.container.StorageContainer;
import xyz.przemyk.simpleplanes.network.CyclePlaneInventoryPacket;

/**
 * Chest-upgrade screen.
 *
 * <p>The Iron Chests compat layer is gone (Agent A deleted {@code compat/**}), so the layout is now
 * always the vanilla chest one, sized from {@link StorageContainer#rowCount}.
 */
@Environment(EnvType.CLIENT)
public class StorageScreen extends AbstractContainerScreen<StorageContainer> {

    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/gui/vanilla_chest.png");

    private final int rowCount;

    public StorageScreen(StorageContainer screenContainer, Inventory inv, Component titleIn) {
        super(screenContainer, inv, titleIn, 176, 114 + screenContainer.rowCount * 18);
        this.rowCount = screenContainer.rowCount;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new ImageButton(leftPos + 3, topPos + 54, 10, 15, PlaneInventoryScreen.LEFT_BUTTON_SPRITES,
                button -> ClientPlayNetworking.send(new CyclePlaneInventoryPacket(CyclePlaneInventoryPacket.Direction.LEFT))));
        addRenderableWidget(new ImageButton(leftPos + imageWidth - 13, topPos + 54, 10, 15, PlaneInventoryScreen.RIGHT_BUTTON_SPRITES,
                button -> ClientPlayNetworking.send(new CyclePlaneInventoryPacket(CyclePlaneInventoryPacket.Direction.RIGHT))));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0.0F, 0.0F, this.imageWidth, this.rowCount * 18 + 17, 256, 256);
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y + this.rowCount * 18 + 17, 0.0F, 126.0F, this.imageWidth, 96, 256, 256);
    }
}
