package xyz.przemyk.simpleplanes.upgrades.solarpanel;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import xyz.przemyk.simpleplanes.entities.LargePlaneEntity;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;
import xyz.przemyk.simpleplanes.upgrades.engines.electric.ElectricEngineUpgrade;

import org.jspecify.annotations.Nullable;

public class SolarPanelUpgrade extends Upgrade {

    private final short MAX_PER_TICK;

    public SolarPanelUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.SOLAR_PANEL.get(), planeEntity);
        if (planeEntity instanceof LargePlaneEntity) {
            MAX_PER_TICK = 10;
        } else {
            MAX_PER_TICK = 5;
        }
    }

    @Override
    public void tick() {
        PlaneEntity entity = getPlaneEntity();
        Level world = entity.level();
        if (canSeeSun(world, entity.getOnPos().above())) {
            float brightness = MAX_PER_TICK * getSunBrightness(entity.level(), 1.0F);
            if (entity.engineUpgrade instanceof ElectricEngineUpgrade engine) {
                engine.energyStorage.receiveEnergy((int) brightness, false);
            }
        }
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {}

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {}

    @Override
    public ItemStack getItemStack() {
        return SimplePlanesItems.SOLAR_PANEL.get().getDefaultInstance();
    }

    private static boolean canSeeSun(@Nullable Level level, BlockPos pos) {
        return level != null && level.dimensionType().hasSkyLight() && level.getSkyDarken() < 4 && level.canSeeSky(pos);
    }

    public static float getSunBrightness(Level world, float partialTicks) {
        // 26.2: Level#getTimeOfDay is gone (day time became the world-clock system).
        // EnvironmentAttributes.SUN_ANGLE is in degrees and equals the old getTimeOfDay() * 360.
        float sunAngle = world.environmentAttributes().getDimensionValue(EnvironmentAttributes.SUN_ANGLE) * (float) (Math.PI / 180.0);
        float f1 = 1.0F - (Mth.cos(sunAngle) * 2.0F + 0.2F);
        f1 = Mth.clamp(f1, 0.0F, 1.0F);
        f1 = 1.0F - f1;
        f1 = (float) (f1 * (1.0D - world.getRainLevel(partialTicks) * 5.0F / 16.0D));
        f1 = (float) (f1 * (1.0D - world.getThunderLevel(partialTicks) * 5.0F / 16.0D));
        return f1 * 0.8F;
    }
}
