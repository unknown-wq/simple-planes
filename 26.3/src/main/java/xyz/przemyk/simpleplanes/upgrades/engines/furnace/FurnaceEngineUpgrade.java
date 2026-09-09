package xyz.przemyk.simpleplanes.upgrades.engines.furnace;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.entity.HumanoidArm;
import xyz.przemyk.simpleplanes.client.ModBusClientEventHandler;
import xyz.przemyk.simpleplanes.client.gui.PlaneInventoryScreen;
import xyz.przemyk.simpleplanes.container.slots.FuelSlot;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.engines.EngineUpgrade;

import java.util.function.Function;

public class FurnaceEngineUpgrade extends EngineUpgrade {

    /** C4: NeoForge ItemStackHandler -> vanilla SimpleContainer. */
    public final SimpleContainer container = new SimpleContainer(1);
    public int burnTime;
    public int burnTimeTotal;

    public FurnaceEngineUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.FURNACE_ENGINE.get(), planeEntity);
    }

    @Override
    public void tick() {
        if (burnTime > 0) {
            burnTime -= planeEntity.getFuelCost();
            updateClient();
        } else if (planeEntity.getThrottle() > 0) {
            ItemStack itemStack = container.getItem(0);
            int itemBurnTime = xyz.przemyk.simpleplanes.misc.FuelValues.burnDuration(planeEntity.level(), itemStack);
            if (itemBurnTime > 0) {
                burnTimeTotal = itemBurnTime;
                burnTime = itemBurnTime;
                // Same order as a vanilla furnace: burn one, and hand back the crafting remainder
                // only once the stack has run out. Substituting it straight away replaced the whole
                // slot with a single item and threw away the rest of a stacked fuel.
                Item fuelItem = itemStack.getItem();
                itemStack.shrink(1);
                if (itemStack.isEmpty()) {
                    ItemStackTemplate remainder = fuelItem.getCraftingRemainder();
                    container.setItem(0, remainder != null ? remainder.create() : ItemStack.EMPTY);
                } else {
                    container.setItem(0, itemStack);
                }
                updateClient();
            }
        }
    }

    @Override
    public boolean isPowered() {
        return burnTime > 0;
    }

    @Override
    public void save(ValueOutput output) {
        container.storeAsItemList(output.list("item", ItemStack.CODEC));
        output.putInt("burnTime", burnTime);
        output.putInt("burnTimeTotal", burnTimeTotal);
    }

    @Override
    public void load(ValueInput input) {
        container.fromItemList(input.listOrEmpty("item", ItemStack.CODEC));
        burnTime = input.getIntOr("burnTime", 0);
        burnTimeTotal = input.getIntOr("burnTimeTotal", 0);
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, container.getItem(0));
        buffer.writeVarInt(burnTime);
        buffer.writeVarInt(burnTimeTotal);
    }

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {
        container.setItem(0, ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
        burnTime = buffer.readVarInt();
        burnTimeTotal = buffer.readVarInt();
    }

    @Override
    public void onRemoved() {
        if (planeEntity.level() instanceof ServerLevel serverLevel) {
            planeEntity.spawnAtLocation(serverLevel, container.getItem(0));
        }
    }

    @Override
    public ItemStack getItemStack() {
        return SimplePlanesItems.FURNACE_ENGINE.get().getDefaultInstance();
    }

    @Override
    public void addContainerData(Function<Slot, Slot> addSlot, Function<DataSlot, DataSlot> addDataSlot) {
        addSlot.apply(new FuelSlot(container, 0, 152, 62, planeEntity::level));
    }

    /** How many pixels of the burn arrow one full charge of fuel is worth. */
    private static final int BURN_BAR_PIXELS = 13;

    /** The burn duration assumed for a stack loaded before its total was recorded. */
    private static final int UNKNOWN_BURN_TOTAL = 200;

    private int burnPixels() {
        return burnTime * BURN_BAR_PIXELS / (burnTimeTotal == 0 ? UNKNOWN_BURN_TOTAL : burnTimeTotal);
    }

    @Environment(EnvType.CLIENT)
    @Override
    public void renderPowerHUD(GuiGraphicsExtractor graphics, HumanoidArm side,
                               int screenWidth, int screenHeight, float partialTick) {
        int middle = screenWidth / 2;
        int left = side == HumanoidArm.LEFT ? middle - 91 - 29 : middle + 91;
        int top = screenHeight - 40;
        graphics.blit(RenderPipelines.GUI_TEXTURED, ModBusClientEventHandler.HUD_TEXTURE,
            left, top, 0, 44, 22, 40, 256, 256);

        if (burnTime > 0) {
            int burn = burnPixels();
            graphics.blit(RenderPipelines.GUI_TEXTURED, ModBusClientEventHandler.HUD_TEXTURE,
                left + 4, top + 16 - burn, 22, 56 - burn, 14, burn + 1, 256, 256);
        }

        // What is in the fuel slot, drawn where the gauge points at it: knowing the fire is nearly
        // out is only half of what a pilot needs, the other half being whether there is anything
        // left to put on it.
        ItemStack fuel = container.getItem(0);
        if (!fuel.isEmpty()) {
            int slotX = side == HumanoidArm.LEFT ? middle - 91 - 26 : middle + 91 + 3;
            int slotY = screenHeight - 19;
            graphics.item(fuel, slotX, slotY);
            graphics.itemDecorations(Minecraft.getInstance().font, fuel, slotX, slotY);
        }
    }

    @Environment(EnvType.CLIENT)
    @Override
    public void renderScreenBg(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                               float partialTick, PlaneInventoryScreen screen) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, PlaneInventoryScreen.GUI,
            screen.getGuiLeft() + 151, screen.getGuiTop() + 44, 208, 0, 18, 35, 256, 256);

        if (burnTime > 0) {
            int burn = burnPixels();
            graphics.blit(RenderPipelines.GUI_TEXTURED, PlaneInventoryScreen.GUI,
                screen.getGuiLeft() + 152, screen.getGuiTop() + 57 - burn,
                208, 47 - burn, 14, burn + 1, 256, 256);
        }
    }
}
