package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

/**
 * Fighter jet, glass layer: the four canopy tiers as see-through glass, a fourth layer drawn on top of the
 * material, metal and exhaust layers. Texture {@link #TEXTURE} (128x64): light blue-grey panes, alpha 100 to 112,
 * with slightly denser edges and one highlight streak per long pane. The canopy frame (sill rails and windscreen
 * bow) and the seat stay opaque in {@link FighterMetalModel}.
 *
 * <p>Same scheme as {@link MiniHeliGlassModel}: {@link RenderTypes#entityTranslucentCull} (blended, back faces
 * culled). The pilot's eye is inside {@code canopy_main} or {@code canopy_mid}, so every face of that tier is a
 * back face from the seat; the faces of the other tiers that point at the eye (the bottoms, the rear of
 * {@code canopy_front}, the side ledges of {@code canopy_main}'s top) are alpha 0. Only the top of
 * {@code canopy_front} and the front and rear ledges of {@code canopy_main}'s top, plain faint panes, are seen
 * from the seat. Faces that lie on another tier, on the fuselage or on the dorsal spine are alpha 0 too, so from
 * outside no two tinted panes are coplanar.
 *
 * <p>Sorting: submitted from {@link #sortOrigin}, the point of the canopy's box nearest the camera moved
 * {@link #SORT_BIAS} further towards it, so it sorts after the rider (whose model is also translucent) even when a
 * steep bank tips the rider's submit origin a few pixels out of the box. {@link #applySortOrigin} shifts the part
 * back. See FIGHTER-MODEL.md.
 */
public class FighterGlassModel extends EntityModel<PlaneRenderState> {

    /** The glass texture. */
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/plane_upgrades/fighter_glass.png");

    /** Bounds of the canopy in model space (blocks, Y down, root included), used for the sort origin. */
    private static final float MIN_X = -7.0F / 16.0F, MAX_X = 7.0F / 16.0F;
    private static final float MIN_Y = (24.0F - 33.0F) / 16.0F, MAX_Y = (24.0F - 22.0F) / 16.0F;
    private static final float MIN_Z = -33.0F / 16.0F, MAX_Z = 0.0F;

    /** How far (blocks) the sort origin is pulled from the canopy towards the camera. */
    public static final float SORT_BIAS = 0.5F;

    private final ModelPart glass;

    public FighterGlassModel(ModelPart root) {
        super(root, RenderTypes::entityTranslucentCull);
        this.glass = root.getChild("Glass");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        // canopy_front (windscreen foot), canopy_main, canopy_mid, canopy_top; same boxes as the old opaque canopy.
        partdefinition.addOrReplaceChild("Glass", CubeListBuilder.create()
                .texOffs(84, 0).addBox(-5.0F, -25.0F, -33.0F, 10.0F, 3.0F, 5.0F, CubeDeformation.NONE)
                .texOffs(0, 0).addBox(-7.0F, -27.0F, -28.0F, 14.0F, 5.0F, 28.0F, CubeDeformation.NONE)
                .texOffs(0, 33).addBox(-6.0F, -30.0F, -26.0F, 12.0F, 3.0F, 25.0F, CubeDeformation.NONE)
                .texOffs(74, 33).addBox(-4.0F, -33.0F, -21.0F, 8.0F, 3.0F, 18.0F, CubeDeformation.NONE),
                PartPose.offset(0.0F, 24.0F, 0.0F));

        return LayerDefinition.create(meshdefinition, 128, 64);
    }

    /**
     * The sort origin for a camera at {@code cameraInModel} (model space, blocks), written to {@code dest}: the
     * nearest point of the canopy's box, moved up to {@link #SORT_BIAS} towards the camera. Inside the box it is the
     * camera itself.
     */
    public static Vector3f sortOrigin(Vector3fc cameraInModel, Vector3f dest) {
        dest.set(
                Math.clamp(cameraInModel.x(), MIN_X, MAX_X),
                Math.clamp(cameraInModel.y(), MIN_Y, MAX_Y),
                Math.clamp(cameraInModel.z(), MIN_Z, MAX_Z));
        float dx = cameraInModel.x() - dest.x, dy = cameraInModel.y() - dest.y, dz = cameraInModel.z() - dest.z;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > 0.0F) {
            float k = Math.min(SORT_BIAS, dist) / dist;
            dest.add(dx * k, dy * k, dz * k);
        }
        return dest;
    }

    /**
     * Shifts the glass by {@code -origin} (blocks, model space) so that a pose translated by {@code +origin} draws it
     * in the usual place. Must run in {@code setupAnim}, after {@code super.setupAnim} resets the pose.
     */
    public void applySortOrigin(Vector3fc origin) {
        this.glass.x = -origin.x() * 16.0F;
        this.glass.y = 24.0F - origin.y() * 16.0F;
        this.glass.z = -origin.z() * 16.0F;
    }

    @Override
    public void setupAnim(PlaneRenderState state) {
        super.setupAnim(state);
        applySortOrigin(state.glassSortOrigin);
    }
}
