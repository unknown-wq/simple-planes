package xyz.przemyk.simpleplanes.misc;

/**
 * Self-contained energy buffer. Replaces NeoForge's {@code EnergyStorage}/{@code IEnergyStorage}
 * (contract C4 — capabilities are gone on Fabric, so this is a plain field-holder that keeps the
 * exact method names the mod already used).
 */
public class EnergyStorageWithSet {

    protected int energy;
    protected final int capacity;
    protected final int maxReceive;
    protected final int maxExtract;

    private Runnable onChange;

    public EnergyStorageWithSet(int capacity) {
        this(capacity, capacity, capacity, 0);
    }

    public EnergyStorageWithSet(int capacity, int maxTransfer) {
        this(capacity, maxTransfer, maxTransfer, 0);
    }

    public EnergyStorageWithSet(int capacity, int maxReceive, int maxExtract, int energy) {
        this.capacity = capacity;
        this.maxReceive = maxReceive;
        this.maxExtract = maxExtract;
        this.energy = Math.max(0, Math.min(capacity, energy));
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    public int receiveEnergy(int toReceive, boolean simulate) {
        if (!canReceive() || toReceive <= 0) {
            return 0;
        }

        int received = Math.min(capacity - energy, Math.min(maxReceive, toReceive));
        if (!simulate) {
            energy += received;
            if (received > 0 && onChange != null) {
                onChange.run();
            }
        }
        return received;
    }

    public int extractEnergy(int toExtract, boolean simulate) {
        if (!canExtract() || toExtract <= 0) {
            return 0;
        }

        int extracted = Math.min(energy, Math.min(maxExtract, toExtract));
        if (!simulate) {
            energy -= extracted;
            if (extracted > 0 && onChange != null) {
                onChange.run();
            }
        }
        return extracted;
    }

    public int getEnergyStored() {
        return energy;
    }

    public int getMaxEnergyStored() {
        return capacity;
    }

    public boolean canExtract() {
        return maxExtract > 0;
    }

    public boolean canReceive() {
        return maxReceive > 0;
    }

    public void setEnergy(int energy) {
        this.energy = Math.max(0, Math.min(energy, capacity));
        if (onChange != null) {
            onChange.run();
        }
    }
}
