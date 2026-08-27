package xyz.przemyk.simpleplanes.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.resources.Identifier;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;

import java.util.ArrayList;
import java.util.List;

/**
 * Render state for every plane / helicopter renderer.
 *
 * <p>Since 1.21.2 an {@link net.minecraft.client.renderer.entity.EntityRenderer} may not touch the
 * entity while rendering; everything the renderer (and every model's {@code setupAnim}) needs is
 * copied here in {@code extractRenderState}.
 */
@Environment(EnvType.CLIENT)
public class PlaneRenderState extends EntityRenderState {

    /** Interpolated plane orientation (q_prev -> q_client). */
    public final org.joml.Quaternionf rotation = new org.joml.Quaternionf();

    /** Interpolated propeller angle, in radians. */
    public float propellerRotation;

    /** {@code getTimeSinceHit() - partialTicks}; > 0 means the damage wobble is playing. */
    public float timeSinceHit;

    /** Texture of the block the plane is built from. */
    public Identifier materialTexture = PlaneRenderer.FALLBACK_TEXTURE;

    /** True when the entity is a {@code CargoPlaneEntity}. */
    public boolean isCargoPlane;

    /** True when a seats upgrade is installed (cargo planes render default wooden seats otherwise). */
    public boolean hasSeatsUpgrade;

    /** Upgrade types installed on the plane, in iteration order. */
    public final List<UpgradeType> upgradeTypes = new ArrayList<>();
}
