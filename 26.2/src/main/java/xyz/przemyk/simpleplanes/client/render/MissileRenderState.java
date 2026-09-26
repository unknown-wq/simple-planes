package xyz.przemyk.simpleplanes.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/**
 * Render state for a launched missile, read by
 * {@link xyz.przemyk.simpleplanes.client.render.models.MissileModel#setupAnim}.
 *
 * <p>Nothing fills this yet: there is no missile entity or renderer. See MISSILES-MODEL.md for what a renderer's
 * {@code extractRenderState} should copy here and how {@code submit} should orient the model.
 */
@Environment(EnvType.CLIENT)
public class MissileRenderState extends EntityRenderState {

    /** Missile tier, 1 to 4. Selects which baked layer (and texture region) the renderer draws. */
    public int tier = 1;

    /** Fin deployment, 0 = folded against the body (as stowed in the tube) to 1 = fully deployed. */
    public float finsDeployed;

    /** Motor output, 0 = off (no flame) to 1 = full thrust (longest flame). */
    public float thrust;

    /**
     * Interpolated orientation. Identity means the missile stands upright, nose to world +Y; the renderer
     * rotates the model's nose axis onto the flight direction with it.
     */
    public final org.joml.Quaternionf rotation = new org.joml.Quaternionf();
}
