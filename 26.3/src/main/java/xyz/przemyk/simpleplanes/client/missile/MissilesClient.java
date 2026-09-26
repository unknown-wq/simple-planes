package xyz.przemyk.simpleplanes.client.missile;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.Identifier;
import xyz.przemyk.simpleplanes.client.render.models.LaunchTubeModel;
import xyz.przemyk.simpleplanes.client.render.models.MissileModel;
import xyz.przemyk.simpleplanes.missile.Missiles;

/** Client registration for the missile feature. {@link #init()} is the single call from the client initialiser. */
@Environment(EnvType.CLIENT)
public final class MissilesClient {

    public static final Identifier MISSILE_TEXTURE = Missiles.id("textures/entity/missile.png");
    public static final Identifier TUBE_TEXTURE = Missiles.id("textures/entity/launch_tube.png");

    /** Indexed by tier; index 0 unused. */
    public static final ModelLayerLocation[] MISSILE_LAYERS = new ModelLayerLocation[5];
    public static final ModelLayerLocation[] TUBE_LAYERS = new ModelLayerLocation[5];

    static {
        for (int t = 1; t <= 4; t++) {
            MISSILE_LAYERS[t] = new ModelLayerLocation(Missiles.id("missile"), "t" + t);
            TUBE_LAYERS[t] = new ModelLayerLocation(Missiles.id("launch_tube"), "t" + t);
        }
    }

    private MissilesClient() {}

    public static void init() {
        for (int t = 1; t <= 4; t++) {
            final int tier = t;
            ModelLayerRegistry.registerModelLayer(MISSILE_LAYERS[t], () -> MissileModel.createBodyLayer(tier));
            ModelLayerRegistry.registerModelLayer(TUBE_LAYERS[t], () -> LaunchTubeModel.createBodyLayer(tier));
        }
        EntityRendererRegistry.register(Missiles.MISSILE, MissileRenderer::new);
        BlockEntityRendererRegistry.register(Missiles.LAUNCH_SILO_BE, LaunchSiloRenderer::new);
    }
}
