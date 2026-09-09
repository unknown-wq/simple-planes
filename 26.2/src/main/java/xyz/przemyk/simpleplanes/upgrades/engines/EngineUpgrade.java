package xyz.przemyk.simpleplanes.upgrades.engines;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.HumanoidArm;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;

public abstract class EngineUpgrade extends Upgrade {

    public EngineUpgrade(UpgradeType type, PlaneEntity planeEntity) {
        super(type, planeEntity);
    }

    @Override
    public void remove() {
        super.remove();
        planeEntity.engineUpgrade = null;
    }

    public abstract boolean isPowered();

    /**
     * Draws this engine's fuel gauge beside the hotbar while the player is flying.
     *
     * <p>Called from the plane HUD, which on 26.2 is a Fabric {@code HudElement} extracting into a
     * {@link GuiGraphicsExtractor} rather than a Forge GUI layer taking a {@code GuiGraphics}. See
     * {@code Upgrade#renderScreenBg} for why naming a client type on a common class is safe here.
     *
     * <p>Not abstract, unlike the version this replaces. An engine with nothing worth showing should
     * be able to say so by leaving it alone, rather than by being forced to write an empty override
     * that the next reader has to check is deliberate.
     *
     * @param side which side of the hotbar to draw on, chosen by the HUD from the player's main hand
     *             and whether the off hand is holding anything
     */
    @Environment(EnvType.CLIENT)
    public void renderPowerHUD(GuiGraphicsExtractor graphics, HumanoidArm side,
                               int screenWidth, int screenHeight, float partialTick) {}
}
