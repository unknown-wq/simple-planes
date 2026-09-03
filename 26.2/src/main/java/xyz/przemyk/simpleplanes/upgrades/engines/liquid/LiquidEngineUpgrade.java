package xyz.przemyk.simpleplanes.upgrades.engines.liquid;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import xyz.przemyk.simpleplanes.datapack.PlaneLiquidFuelReloadListener;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesConfig;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.engines.EngineUpgrade;

import java.util.function.Function;

public class LiquidEngineUpgrade extends EngineUpgrade {

    public static final int BUCKET_AMOUNT = 1000;

    /** C4: NeoForge ItemStackHandler -> vanilla SimpleContainer. Slot 0 = input, slot 1 = output. */
    public final SimpleContainer container = new SimpleContainer(2);

    /** C4: NeoForge FluidTank/FluidStack -> plain local state owned by this upgrade. */
    public final PlaneFluidTank fluidTank = new PlaneFluidTank(SimplePlanesConfig.LIQUID_ENGINE_CAPACITY.get());

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
                burnTime = PlaneLiquidFuelReloadListener.fuelMap.getOrDefault(fluidTank.fluid, 0);
                if (burnTime > 0) {
                    fluidTank.drain(1);
                    updateClient();
                }
            }

            if (container.getItem(1).isEmpty()) {
                tickBucket();
            }
        }
    }

    /**
     * Vanilla-bucket-only replacement for the NeoForge fluid-handler capability transfer
     * (see "Disabled content" in PORT-STATUS.md).
     */
    private void tickBucket() {
        ItemStack itemStack = container.getItem(0);
        if (itemStack.isEmpty() || !(itemStack.getItem() instanceof BucketItem)) {
            return;
        }

        if (itemStack.is(Items.BUCKET)) {
            // draining the tank into an empty bucket
            if (!fluidTank.isEmpty() && fluidTank.amount >= BUCKET_AMOUNT) {
                ItemStack filled = fluidTank.fluid.getBucket().getDefaultInstance();
                fluidTank.drain(BUCKET_AMOUNT);
                container.setItem(0, ItemStack.EMPTY);
                container.setItem(1, filled);
                updateClient();
            }
            return;
        }

        Fluid bucketFluid = findBucketFluid(itemStack);
        if (bucketFluid == null || bucketFluid == Fluids.EMPTY) {
            return;
        }
        if ((fluidTank.isEmpty() || fluidTank.fluid == bucketFluid) && fluidTank.getSpace() >= BUCKET_AMOUNT) {
            fluidTank.fill(bucketFluid, BUCKET_AMOUNT);
            container.setItem(0, ItemStack.EMPTY);
            container.setItem(1, Items.BUCKET.getDefaultInstance());
            updateClient();
        }
    }

    private static Fluid findBucketFluid(ItemStack itemStack) {
        for (Fluid fluid : PlaneLiquidFuelReloadListener.fuelMap.keySet()) {
            if (fluid != Fluids.EMPTY && itemStack.is(fluid.getBucket())) {
                return fluid;
            }
        }
        return null;
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
        output.putString("fluid", BuiltInRegistries.FLUID.getKey(fluidTank.fluid).toString());
        output.putInt("fluid_amount", fluidTank.amount);
        output.putInt("burnTime", burnTime);
    }

    @Override
    public void load(ValueInput input) {
        container.fromItemList(input.listOrEmpty("items", ItemStack.CODEC));
        Fluid fluid = BuiltInRegistries.FLUID.getValue(Identifier.parse(input.getStringOr("fluid", "minecraft:empty")));
        fluidTank.fluid = fluid == null ? Fluids.EMPTY : fluid;
        fluidTank.amount = Math.min(input.getIntOr("fluid_amount", 0), fluidTank.capacity);
        burnTime = input.getIntOr("burnTime", 0);
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {
        buffer.writeIdentifier(BuiltInRegistries.FLUID.getKey(fluidTank.fluid));
        buffer.writeVarInt(fluidTank.amount);
        buffer.writeVarInt(burnTime);
    }

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {
        Fluid fluid = BuiltInRegistries.FLUID.getValue(buffer.readIdentifier());
        fluidTank.fluid = fluid == null ? Fluids.EMPTY : fluid;
        fluidTank.amount = buffer.readVarInt();
        burnTime = buffer.readVarInt();
    }

    @Override
    public void addContainerData(Function<Slot, Slot> addSlot, Function<DataSlot, DataSlot> addDataSlot) {
        addSlot.apply(new Slot(container, 0, 152, 8));
        addSlot.apply(new Slot(container, 1, 152, 62));
    }

    /** Minimal stand-in for NeoForge's {@code FluidTank} (C4 — no Transfer API). */
    public static class PlaneFluidTank {
        public final int capacity;
        public Fluid fluid = Fluids.EMPTY;
        public int amount = 0;

        public PlaneFluidTank(int capacity) {
            this.capacity = capacity;
        }

        public boolean isEmpty() {
            return amount <= 0 || fluid == Fluids.EMPTY;
        }

        public int getSpace() {
            return capacity - amount;
        }

        public int fill(Fluid fluid, int toFill) {
            if (isEmpty()) {
                this.fluid = fluid;
                this.amount = 0;
            } else if (this.fluid != fluid) {
                return 0;
            }
            int filled = Math.min(toFill, getSpace());
            amount += filled;
            return filled;
        }

        public int drain(int toDrain) {
            int drained = Math.min(toDrain, amount);
            amount -= drained;
            if (amount <= 0) {
                amount = 0;
                fluid = Fluids.EMPTY;
            }
            return drained;
        }
    }
}
