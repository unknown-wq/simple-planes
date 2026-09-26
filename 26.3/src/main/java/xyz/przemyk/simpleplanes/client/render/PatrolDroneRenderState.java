package xyz.przemyk.simpleplanes.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.joml.Quaternionf;

/** What {@link PatrolDroneRenderer} and {@code PatrolDroneModel} need from a patrol drone. */
@Environment(EnvType.CLIENT)
public class PatrolDroneRenderState extends EntityRenderState {

    public final Quaternionf rotation = new Quaternionf();

    /** Interpolated rotor angle, radians. */
    public float propellerRotation;

    /** {@code getTimeSinceHit() - partialTicks}; > 0 while the hit wobble plays. */
    public float timeSinceHit;

    /** Following a target: the beacon shows red instead of green. */
    public boolean tracking;
}
