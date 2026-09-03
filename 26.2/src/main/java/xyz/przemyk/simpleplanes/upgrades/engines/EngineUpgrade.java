package xyz.przemyk.simpleplanes.upgrades.engines;

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

    // TODO(port-26.2): DISABLED — renderPowerHUD(GuiGraphics, ...). GuiGraphics does not exist in
    // 26.2 (HUD rendering moved to net.minecraft.client.gui.Hud + render pipelines). Agent C owns
    // the replacement HUD element.
}
