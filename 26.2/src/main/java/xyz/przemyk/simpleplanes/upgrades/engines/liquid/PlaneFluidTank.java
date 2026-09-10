package xyz.przemyk.simpleplanes.upgrades.engines.liquid;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import xyz.przemyk.simpleplanes.datapack.PlaneLiquidFuelReloadListener;

/**
 * The liquid engine's fuel tank, and a {@code Storage<FluidVariant>} that anything else can pour
 * into.
 *
 * <p>It used to be a bare pair of fields with fill/drain helpers, which meant the only thing in the
 * game that could put fuel in it was a player holding a vanilla bucket. Being a real
 * {@link SingleVariantStorage} makes it the currency every other Fabric fluid mod already speaks —
 * a pipe, a tank, a fluid container item — and brings the transaction semantics with it: a
 * transfer that is later aborted rolls the tank back through {@code SnapshotParticipant}, and only
 * a committed one reaches {@link #onFinalCommit}, which is where the change is announced to
 * watching clients.
 *
 * <p><b>Units.</b> The engine, its save format, its packet and its gauge all count millibuckets,
 * 1000 to the bucket; the transfer API counts droplets, {@value FluidConstants#BUCKET} to the
 * bucket. Droplets are what the storage stores, because they are what everyone else's transfer
 * arithmetic is in, and the millibucket view is derived. Insertions and extractions are floored to
 * a whole millibucket ({@value #DROPLETS_PER_MB} droplets), so the tank never holds a fraction the
 * save file and the gauge cannot represent — the same quantisation any millibucket-denominated tank
 * shows a pipe.
 *
 * <p><b>Two ways in, one of them transactional.</b> The engine's own burn and the input slot write
 * the fields directly; a foreign transfer goes through {@link #insert}/{@link #extract}. They never
 * interleave: both run on the server thread, and a transaction is never open across a tick.
 *
 * <p><b>This object is the aircraft's own tank, not the port other mods get.</b> It is deliberately
 * a full read/write storage, because the aircraft itself needs both halves: the input slot fills a
 * bucket from it through {@link #extract}. It must therefore never be handed to foreign code as it
 * is — extraction from a tank in flight is an engine cut-out and a dead pilot. What a neighbouring
 * pipe is offered is the guarded, insert-only view built in {@code LiquidEngineStorage}, which is
 * also where the conditions for touching this tank from outside are written down.
 */
public class PlaneFluidTank extends SingleVariantStorage<FluidVariant> {

    /** {@link FluidConstants#BUCKET} droplets to a bucket, 1000 mB to a bucket. */
    public static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000;

    private final int capacityMb;
    private final Runnable onCommit;

    public PlaneFluidTank(int capacityMb, Runnable onCommit) {
        this.capacityMb = capacityMb;
        this.onCommit = onCommit;
        this.variant = FluidVariant.blank();
    }

    // ------------------------------------------------------------------ Storage

    @Override
    protected FluidVariant getBlankVariant() {
        return FluidVariant.blank();
    }

    @Override
    protected long getCapacity(FluidVariant variant) {
        return capacityMb * DROPLETS_PER_MB;
    }

    /**
     * Only what the engine can burn. Without this a pipe could fill the tank with water and wedge
     * the engine on a fluid it will never get a burn time for, with no way to empty it but a bucket.
     * {@code plane_liquid_fuels} is the same datapack list the bucket path has always consulted.
     */
    @Override
    protected boolean canInsert(FluidVariant variant) {
        return PlaneLiquidFuelReloadListener.fuelMap.containsKey(variant.getFluid());
    }

    @Override
    public long insert(FluidVariant insertedVariant, long maxAmount, TransactionContext transaction) {
        return super.insert(insertedVariant, maxAmount - maxAmount % DROPLETS_PER_MB, transaction);
    }

    @Override
    public long extract(FluidVariant extractedVariant, long maxAmount, TransactionContext transaction) {
        return super.extract(extractedVariant, maxAmount - maxAmount % DROPLETS_PER_MB, transaction);
    }

    /** Only a committed transfer is worth a packet; an aborted one has already been rolled back. */
    @Override
    protected void onFinalCommit() {
        onCommit.run();
    }

    // ------------------------------------------------------------------ the engine's view, in mB

    public boolean isEmpty() {
        return amount <= 0 || variant.isBlank();
    }

    public Fluid getFluid() {
        return variant.isBlank() ? Fluids.EMPTY : variant.getFluid();
    }

    public int getAmountMb() {
        return (int) (amount / DROPLETS_PER_MB);
    }

    public int getCapacityMb() {
        return capacityMb;
    }

    /** Burns fuel. Not a transfer: nothing else may be mid-transaction on this tank. */
    public void drainMb(int mb) {
        amount = Math.max(0, amount - mb * DROPLETS_PER_MB);
        if (amount == 0) {
            variant = FluidVariant.blank();
        }
    }

    /** Restores saved contents. As {@link #drainMb}, this is state assignment, not a transfer. */
    public void setContents(Fluid fluid, int mb) {
        int clamped = Math.max(0, Math.min(mb, capacityMb));
        if (fluid == Fluids.EMPTY || clamped == 0) {
            variant = FluidVariant.blank();
            amount = 0;
        } else {
            variant = FluidVariant.of(fluid);
            amount = clamped * DROPLETS_PER_MB;
        }
    }
}
