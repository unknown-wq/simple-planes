package xyz.przemyk.simpleplanes.upgrades.engines.electric;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.client.ModBusClientEventHandler;
import xyz.przemyk.simpleplanes.client.gui.PlaneInventoryScreen;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.misc.EnergyStorageWithSet;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.engines.EngineUpgrade;

public class ElectricEngineUpgrade extends EngineUpgrade {

    public static final int CAPACITY = 1_500_000;

    public final EnergyStorageWithSet energyStorage = new EnergyStorageWithSet(CAPACITY);

    public ElectricEngineUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.ELECTRIC_ENGINE.get(), planeEntity);
        energyStorage.setOnChange(this::updateClient);
    }

    @Override
    public void tick() {
        if (planeEntity.getThrottle() > 0) {
            if (energyStorage.extractEnergy(12 * planeEntity.getFuelCost(), false) > 0) {
                updateClient();
            }
        }
    }

    @Override
    public boolean isPowered() {
        return energyStorage.getEnergyStored() > 12 * planeEntity.getFuelCost();
    }

    @Override
    public void save(ValueOutput output) {
        output.putInt("energy", energyStorage.getEnergyStored());
    }

    @Override
    public void load(ValueInput input) {
        energyStorage.setEnergy(Math.min(input.getIntOr("energy", 0), CAPACITY));
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(energyStorage.getEnergyStored());
    }

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {
        energyStorage.setEnergy(buffer.readVarInt());
    }

    @Override
    public ItemStack getItemStack() {
        return SimplePlanesItems.ELECTRIC_ENGINE.get().getDefaultInstance();
    }

    @Environment(EnvType.CLIENT)
    @Override
    public void renderPowerHUD(GuiGraphicsExtractor graphics, HumanoidArm side,
                               int screenWidth, int screenHeight, float partialTick) {
        int middle = screenWidth / 2;
        int left = side == HumanoidArm.LEFT ? middle - 91 - 29 : middle + 91;
        int top = screenHeight - 22;
        graphics.blit(RenderPipelines.GUI_TEXTURED, ModBusClientEventHandler.HUD_TEXTURE,
            left, top, 38, 44, 22, 21, 256, 256);

        int energy = energyStorage.getEnergyStored();
        if (energy > 0) {
            int filled = energy * 15 / CAPACITY;
            graphics.blit(RenderPipelines.GUI_TEXTURED, ModBusClientEventHandler.HUD_TEXTURE,
                left + 3, top + 16 - filled, 60, 57 - filled, 16, filled + 2, 256, 256);
        }
    }

    @Environment(EnvType.CLIENT)
    @Override
    public void renderScreenBg(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                               float partialTick, PlaneInventoryScreen screen) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, PlaneInventoryScreen.GUI,
            screen.getGuiLeft() + 152, screen.getGuiTop() + 7, 176, 0, 16, 72, 256, 256);

        int energy = energyStorage.getEnergyStored();
        if (energy > 0) {
            int filled = energy * 71 / CAPACITY;
            graphics.blit(RenderPipelines.GUI_TEXTURED, PlaneInventoryScreen.GUI,
                screen.getGuiLeft() + 152, screen.getGuiTop() + 78 - filled,
                192, 71 - filled, 16, filled + 1, 256, 256);
        }
    }

    @Environment(EnvType.CLIENT)
    @Override
    public void renderScreen(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                             float partialTick, PlaneInventoryScreen screen) {
        if (screen.isHovering(152, 7, 16, 72, mouseX, mouseY)) {
            // setTooltipForNextFrame rather than an immediate draw: tooltips are collected and drawn
            // last in 26.2, which is what keeps them on top of the carried item.
            graphics.setTooltipForNextFrame(screen.getFont(),
                Component.translatable(SimplePlanesMod.MODID + ".gui.energy",
                    energyStorage.getEnergyStored()), mouseX, mouseY);
        }
    }
}
