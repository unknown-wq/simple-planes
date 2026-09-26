package xyz.przemyk.simpleplanes.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.phys.Vec3;

@Environment(EnvType.CLIENT)
public class QuadcopterRenderState extends PlaneRenderState {

    public boolean carrying;

    public float ropeLength;

    /** Interpolated world position of the hook (the rope's lower end). */
    public Vec3 hookWorld = Vec3.ZERO;
}
