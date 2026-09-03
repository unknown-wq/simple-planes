package xyz.przemyk.simpleplanes.misc;

import net.neoforged.neoforge.energy.EnergyStorage;

public class EnergyStorageWithSet extends EnergyStorage {
    private Runnable onChange;

    public EnergyStorageWithSet(int capacity) {
        super(capacity);
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        int received = super.receiveEnergy(maxReceive, simulate);
        if (received > 0 && !simulate && onChange != null) {
            onChange.run();
        }
        return received;
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        int extracted = super.extractEnergy(maxExtract, simulate);
        if (extracted > 0 && !simulate && onChange != null) {
            onChange.run();
        }
        return extracted;
    }

    public void setEnergy(int energy) {
        this.energy = Math.min(energy, capacity);
        if (onChange != null) {
            onChange.run();
        }
    }
}
