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
import xyz.przemyk.simpleplanes.container.StorageContainer;
import xyz.przemyk.simpleplanes.misc.ChestTypes;
import xyz.przemyk.simpleplanes.network.CyclePlaneInventoryPacket;

/**
 * Chest-upgrade screen.
 *
 * <p>The Iron Chests compat layer is gone, so the only layout left is the one the bundled
 * {@code textures/gui/vanilla_chest.png} is drawn for. That image is a single finished 184x168
 * panel, not a vanilla {@code generic_54.png}-style sheet, so it is blitted whole and the panel
 * size comes from {@link ChestTypes} — the same source the container takes its slot positions from.
 */
@Environment(EnvType.CLIENT)
public class StorageScreen extends AbstractContainerScreen<StorageContainer> {

    private final Identifier texture;
    private final int textureYSize;

    public StorageScreen(StorageContainer screenContainer, Inventory inv, Component titleIn) {
        super(screenContainer, inv, titleIn,
            ChestTypes.getXSize(screenContainer.chestType),
            ChestTypes.getYSize(screenContainer.chestType));
        this.texture = ChestTypes.getGuiTexture(screenContainer.chestType);
        this.textureYSize = ChestTypes.getTextureYSize(screenContainer.chestType);
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
        graphics.blit(RenderPipelines.GUI_TEXTURED, this.texture, x, y, 0.0F, 0.0F, this.imageWidth, this.imageHeight, 256, this.textureYSize);
    }
}
