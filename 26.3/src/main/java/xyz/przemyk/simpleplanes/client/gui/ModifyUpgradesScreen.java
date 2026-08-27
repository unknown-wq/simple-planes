package xyz.przemyk.simpleplanes.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.container.ModifyUpgradesContainer;
import xyz.przemyk.simpleplanes.entities.CargoPlaneEntity;

@Environment(EnvType.CLIENT)
public class ModifyUpgradesScreen extends AbstractContainerScreen<ModifyUpgradesContainer> {

    public static final Identifier GUI = Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/gui/modify_upgrades.png");
    public static final Component UPGRADES_TOOLTIP = Component.translatable(SimplePlanesMod.MODID + ".add_upgrades");
    public static final Component CARGO_TOOLTIP = Component.translatable(SimplePlanesMod.MODID + ".add_cargo");

    public ModifyUpgradesScreen(ModifyUpgradesContainer screenContainer, Inventory inv, Component titleIn) {
        super(screenContainer, inv, titleIn, 176, 184);
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);

        if (this.hoveredSlot != null && this.hoveredSlot.getItem().isEmpty()) {
            if (this.hoveredSlot.index < 6) {
                graphics.setTooltipForNextFrame(this.font, this.font.split(UPGRADES_TOOLTIP, 115), mouseX, mouseY);
            } else if (this.hoveredSlot.index < 14) {
                graphics.setTooltipForNextFrame(this.font, this.font.split(CARGO_TOOLTIP, 115), mouseX, mouseY);
            }
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, -12566464, false);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);

        int i = this.leftPos;
        int j = (this.height - this.imageHeight) / 2;
        graphics.blit(RenderPipelines.GUI_TEXTURED, GUI, i, j, 0.0F, 0.0F, this.imageWidth, this.imageHeight, 256, 256);

        if (this.menu.planeEntity != null) {
            int scale = this.menu.planeEntity instanceof CargoPlaneEntity ? 6 : 11;
            long gameTime = this.minecraft != null && this.minecraft.level != null ? this.minecraft.level.getGameTime() : 0L;
            Quaternionf rotation = new Quaternionf().rotateXYZ(0.3F, (gameTime + a) / 20.0F, (float) Math.PI);
            extractEntityInInventory(graphics, i + 62, j + 8, i + 168, j + 69, scale, rotation, this.menu.planeEntity);

            if (this.menu.planeEntity instanceof CargoPlaneEntity) {
                graphics.blit(RenderPipelines.GUI_TEXTURED, GUI, i + 25, j + 74, 25.0F, 101.0F, 144, 18, 256, 256);
            }
        }

        if (this.menu.errorSlot != -1) {
            Slot slot = this.menu.slots.get(this.menu.errorSlot);
            graphics.blit(RenderPipelines.GUI_TEXTURED, GUI, i + slot.x - 1, j + slot.y - 1, 176.0F, 76.0F, 18, 18, 256, 256);
        }
    }

    /**
     * 26.2 replaced the immediate-mode {@code InventoryScreen.renderEntityInInventory} with a
     * picture-in-picture render state: extract the entity's render state once and hand it to
     * {@link GuiGraphicsExtractor#entity}.
     */
    public static void extractEntityInInventory(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1,
                                                int size, Quaternionf rotation, Entity entity) {
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        EntityRenderer<? super Entity, ?> renderer = dispatcher.getRenderer(entity);
        EntityRenderState renderState = renderer.createRenderState(entity, 1.0F);
        renderState.shadowPieces.clear();
        renderState.outlineColor = 0;

        Vector3f translation = new Vector3f(0.0F, renderState.boundingBoxHeight / 2.0F, 0.0F);
        graphics.entity(renderState, size, translation, rotation, null, x0, y0, x1, y1);
    }
}
