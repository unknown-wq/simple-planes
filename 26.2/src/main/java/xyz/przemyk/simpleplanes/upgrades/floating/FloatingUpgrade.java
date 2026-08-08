package xyz.przemyk.simpleplanes.upgrades.floating;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;

public class FloatingUpgrade extends Upgrade {

    public FloatingUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.FLOATY_BEDDING.get(), planeEntity);
    }

    /**
     * Most descent, in blocks/tick, the floats can take out in a single tick.
     *
     * <p>The upgrade used to do {@code y = max(motion.y, 0)}: every tick spent on water, the whole
     * descent was deleted, at any speed. A plane arriving at 3 blocks/tick had its dive erased for
     * free and bobbed on the surface undamaged — which is what "hitting water at huge speed does
     * nothing" was. Floats are buoyancy, not an arrestor cable: they can hold up an aircraft that
     * settles onto the water, and they cannot stop one that arrives at attack speed.
     *
     * <p>0.35 is the same number as {@link xyz.przemyk.simpleplanes.entities.PlaneCollisions#WATER_TOLERANCE_MIN},
     * so everything the upgrade fully arrests is also everything that costs nothing: a gentle water
     * landing is exactly as free as it was, and a dive still goes in.
     */
    public static final double MAX_ARRESTED_DESCENT = 0.35;

    @Override
    public void tick() {
        if (planeEntity.getHealth() > 0 && planeEntity.isOnWater()) {
            Vec3 motion = planeEntity.getDeltaMovement();
            double f = 1;
            // Cancel up to MAX_ARRESTED_DESCENT of sink and let the rest through, rather than
            // cancelling all of it. Climbing motion is left alone.
            double y = motion.y < 0 ? Math.min(motion.y + MAX_ARRESTED_DESCENT, 0) : motion.y;
            planeEntity.setDeltaMovement(motion.x * f, y, motion.z * f);
            // Mth.floor, not (int): a truncating cast rounds towards zero and samples the wrong
            // block at negative coordinates (x = -0.5 would give 0 instead of -1).
            if (planeEntity.level().getBlockState(new BlockPos(Mth.floor(planeEntity.getX()), Mth.floor(planeEntity.getY() + 0.5), Mth.floor(planeEntity.getZ()))).getFluidState().is(FluidTags.WATER)) {
                planeEntity.setDeltaMovement(planeEntity.getDeltaMovement().add(0, 0.04, 0));
            }
        }
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {}

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {}

    @Override
    public ItemStack getItemStack() {
        return SimplePlanesItems.FLOATY_BEDDING.get().getDefaultInstance();
    }
}
