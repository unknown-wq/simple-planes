package xyz.przemyk.simpleplanes.upgrades.engines.liquid;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.level.material.MapColor;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.client.ModBusClientEventHandler;
import xyz.przemyk.simpleplanes.client.gui.PlaneInventoryScreen;
import xyz.przemyk.simpleplanes.datapack.PlaneLiquidFuelReloadListener;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesConfig;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.engines.EngineUpgrade;

import java.util.function.Function;

public class LiquidEngineUpgrade extends EngineUpgrade {

    /** C4: NeoForge ItemStackHandler -> vanilla SimpleContainer. Slot 0 = input, slot 1 = output. */
    public final SimpleContainer container = new SimpleContainer(2);

    /**
     * The fuel tank — see {@link PlaneFluidTank}. This is the aircraft's own handle on it, with
     * both halves of the storage: {@link #tickInputSlot} fills a bucket from it as well as into it.
     * Other mods never see this object. A pipe or tank beside the aircraft is given the guarded view
     * in {@link LiquidEngineStorage}, which accepts fuel only while the aircraft is parked and hands
     * none back at all; {@code SimplePlanesMod} is where that lookup is registered.
     */
    public final PlaneFluidTank fluidTank =
        new PlaneFluidTank(SimplePlanesConfig.LIQUID_ENGINE_CAPACITY.get(), this::updateClient);

    public int burnTime;

    public LiquidEngineUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.LIQUID_ENGINE.get(), planeEntity);
    }

    @Override
    public void tick() {
        if (!planeEntity.level().isClientSide()) {
            if (burnTime > 0) {
                burnTime -= planeEntity.getFuelCost();
                updateClient();
            } else if (planeEntity.getThrottle() > 0 && !fluidTank.isEmpty()) {
                burnTime = PlaneLiquidFuelReloadListener.fuelMap.getOrDefault(fluidTank.getFluid(), 0);
                if (burnTime > 0) {
                    fluidTank.drainMb(1);
                    updateClient();
                }
            }

            if (container.getItem(1).isEmpty()) {
                tickInputSlot();
            }
        }
    }

    /**
     * Empties whatever fluid container is in the input slot into the tank, or fills it from the
     * tank when there is nothing to take.
     *
     * <p>This used to understand vanilla buckets and nothing else, by name. It now asks
     * {@link FluidStorage#ITEM} what the item in the slot is, which answers for a vanilla bucket
     * (the transfer API registers those itself) and equally for any other mod's tank, cell or
     * canister. The tank refuses fluids the engine cannot burn, so a water bucket is left alone
     * exactly as before.
     *
     * <p>The two slots are kept as they were: the container goes in slot 0, and whatever it turns
     * into — the emptied bucket — is moved to the output slot, so the player takes it from where
     * they always did. A container that stays the same item (a modded tank that is merely now
     * empty) is left in place, and simply does nothing further.
     */
    private void tickInputSlot() {
        ItemStack before = container.getItem(0);
        if (before.isEmpty()) {
            return;
        }

        ContainerItemContext context =
            ContainerItemContext.ofSingleSlot(ContainerStorage.of(container, null).getSlot(0));
        Storage<FluidVariant> itemStorage = context.find(FluidStorage.ITEM);
        if (itemStorage == null) {
            return;
        }

        try (Transaction transaction = Transaction.openOuter()) {
            long moved = StorageUtil.move(itemStorage, fluidTank, variant -> true, Long.MAX_VALUE, transaction);
            if (moved == 0) {
                moved = StorageUtil.move(fluidTank, itemStorage, variant -> true, Long.MAX_VALUE, transaction);
            }
            if (moved == 0) {
                return;
            }
            transaction.commit();
        }

        ItemStack after = container.getItem(0);
        if (!ItemStack.isSameItemSameComponents(before, after)) {
            container.setItem(0, ItemStack.EMPTY);
            container.setItem(1, after);
        }
        updateClient();
    }

    @Override
    public void onRemoved() {
        if (planeEntity.level() instanceof ServerLevel serverLevel) {
            planeEntity.spawnAtLocation(serverLevel, container.getItem(0));
            planeEntity.spawnAtLocation(serverLevel, container.getItem(1));
        }
    }

    @Override
    public ItemStack getItemStack() {
        return SimplePlanesItems.LIQUID_ENGINE.get().getDefaultInstance();
    }

    @Override
    public boolean isPowered() {
        return !fluidTank.isEmpty();
    }

    @Override
    public void save(ValueOutput output) {
        container.storeAsItemList(output.list("items", ItemStack.CODEC));
        output.putString("fluid", BuiltInRegistries.FLUID.getKey(fluidTank.getFluid()).toString());
        output.putInt("fluid_amount", fluidTank.getAmountMb());
        output.putInt("burnTime", burnTime);
    }

    @Override
    public void load(ValueInput input) {
        container.fromItemList(input.listOrEmpty("items", ItemStack.CODEC));
        Fluid fluid = BuiltInRegistries.FLUID.getValue(Identifier.parse(input.getStringOr("fluid", "minecraft:empty")));
        fluidTank.setContents(fluid == null ? Fluids.EMPTY : fluid, input.getIntOr("fluid_amount", 0));
        burnTime = input.getIntOr("burnTime", 0);
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {
        buffer.writeIdentifier(BuiltInRegistries.FLUID.getKey(fluidTank.getFluid()));
        buffer.writeVarInt(fluidTank.getAmountMb());
        buffer.writeVarInt(burnTime);
    }

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {
        Fluid fluid = BuiltInRegistries.FLUID.getValue(buffer.readIdentifier());
        fluidTank.setContents(fluid == null ? Fluids.EMPTY : fluid, buffer.readVarInt());
        burnTime = buffer.readVarInt();
    }

    @Override
    public void addContainerData(Function<Slot, Slot> addSlot, Function<DataSlot, DataSlot> addDataSlot) {
        addSlot.apply(new Slot(container, 0, 152, 8));
        addSlot.apply(new Slot(container, 1, 152, 62));
    }

    // ------------------------------------------------------------------ gauges

    /** Height of the tank window in the inventory screen, in pixels. */
    private static final int TANK_HEIGHT = 36;

    /** Inset of the fluid column inside that window, in pixels on each side. */
    private static final int TANK_INSET = 2;

    /**
     * The colour to draw this tank's contents in.
     *
     * <p>The tiled still-texture this used to draw came from NeoForge's fluid extensions, which have
     * no counterpart here — vanilla has no fluid-to-sprite mapping at all, only the block renderer's
     * private knowledge of water and lava, so any general one would be a guess at a texture path.
     * The fluid's own block is the general answer vanilla does have: every fluid has one, and its map
     * colour is the single colour the game itself picks to stand for it. Water reads blue and lava
     * orange, which is the whole job of a fuel gauge.
     */
    @Environment(EnvType.CLIENT)
    private int fluidColour() {
        MapColor colour = fluidTank.getFluid().defaultFluidState().createLegacyBlock()
            .getMapColor(planeEntity.level(), BlockPos.ZERO);
        return 0xFF000000 | (colour == MapColor.NONE ? 0x4060C0 : colour.col);
    }

    /**
     * The fluid's name, taken from its bucket.
     *
     * <p>A fluid carries no translated name of its own — vanilla never shows one — and its
     * registry id is not a name to put in front of a player. The bucket is the one item that
     * always exists for a fuel this engine can accept, since {@code tickBucket} is how the fuel
     * got in.
     */
    @Environment(EnvType.CLIENT)
    private Component fluidName() {
        return new ItemStack(fluidTank.getFluid().getBucket()).getHoverName();
    }

    @Environment(EnvType.CLIENT)
    @Override
    public void renderPowerHUD(GuiGraphicsExtractor graphics, HumanoidArm side,
                               int screenWidth, int screenHeight, float partialTick) {
        // The frame the furnace engine uses, because the two answer the same question in the same
        // place and a pilot switching airframes should not have to learn a second gauge. This one
        // was never drawn at all before — the engine flew with no fuel indication whatsoever.
        int middle = screenWidth / 2;
        int left = side == HumanoidArm.LEFT ? middle - 91 - 29 : middle + 91;
        int top = screenHeight - 40;
        graphics.blit(RenderPipelines.GUI_TEXTURED, ModBusClientEventHandler.HUD_TEXTURE,
            left, top, 0, 44, 22, 40, 256, 256);

        if (!fluidTank.isEmpty()) {
            int filled = Math.max(1, fluidTank.getAmountMb() * 16 / fluidTank.getCapacityMb());
            graphics.fill(left + 4, top + 16 - filled + 1, left + 18, top + 17, fluidColour());
        }
    }

    @Environment(EnvType.CLIENT)
    @Override
    public void renderScreenBg(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                               float partialTick, PlaneInventoryScreen screen) {
        int left = screen.getGuiLeft();
        int top = screen.getGuiTop();
        graphics.blit(RenderPipelines.GUI_TEXTURED, PlaneInventoryScreen.GUI,
            left + 151, top + 7, 176, 72, 18, 72, 256, 256);

        if (!fluidTank.isEmpty()) {
            int filled = fluidTank.getAmountMb() * (TANK_HEIGHT - 2 * TANK_INSET) / fluidTank.getCapacityMb();
            int bottom = top + 7 + 18 + TANK_HEIGHT - TANK_INSET;
            graphics.fill(left + 151 + TANK_INSET, bottom - filled,
                left + 151 + 18 - TANK_INSET, bottom, fluidColour());
        }
        // The window frame goes on top of the fluid, so the column reads as being inside it.
        graphics.blit(RenderPipelines.GUI_TEXTURED, PlaneInventoryScreen.GUI,
            left + 154, top + 28, 194, 72, 12, 30, 256, 256);
    }

    @Environment(EnvType.CLIENT)
    @Override
    public void renderScreen(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                             float partialTick, PlaneInventoryScreen screen) {
        if (!fluidTank.isEmpty() && screen.isHovering(153, 7 + 18 + 2, 16, 32, mouseX, mouseY)) {
            graphics.setTooltipForNextFrame(screen.getFont(),
                Component.translatable(SimplePlanesMod.MODID + ".gui.fluid", fluidName(),
                    fluidTank.getAmountMb()), mouseX, mouseY);
        }
    }
}
