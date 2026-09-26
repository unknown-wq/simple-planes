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
 * Mini helicopter, glass layer: the cabin glass and the windscreen as real see-through glass, a fourth layer the
 * renderer draws on top of the usual three. Texture {@link #TEXTURE} (128x32): tinted panes with alpha about 100,
 * a thin opaque frame round each pane and one small highlight. The frame, pillars, roof and body stay opaque in
 * {@link MiniHeliModel} / {@link MiniHeliMedicalModel} and {@link MiniHeliMetalModel}; this layer is the same for
 * both liveries.
 *
 * <p>Rendered with {@link RenderTypes#entityTranslucentCullItemTarget}, the one blended, back-face-culled entity
 * type in 26.2 (vanilla uses it for semi-transparent living entities): blended, so the pilot is visible from
 * outside, and culled, so the pilot, whose eye is inside {@code glass_cabin}, looks out through clear glass. The
 * only pane that faces the eye from inside, the rear of the windscreen, is fully transparent (alpha 0, discarded).
 *
 * <p>Sorting: 26.2 sends a blended model submit to the translucent phase, which draws the submits back to front
 * by the distance of each submit's pose origin, with depth writes on. The rider's player model is also blended,
 * so the glass must be drawn after it or its depth hides the pilot. {@link #sortOrigin} gives the point of the
 * glass's box nearest the camera, which is always nearer than the rider inside it; the renderer translates the
 * pose there before submitting and {@link #applySortOrigin} moves the part back by the same amount, so the glass
 * is drawn in the same place. With Fabulous graphics the type draws into the item-entity target, which is
 * composited by depth, and the order does not matter. See MINI-HELI-MODEL.md.
 */
public class MiniHeliGlassModel extends EntityModel<PlaneRenderState> {

    /** The glass texture. */
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/plane_upgrades/mini_heli_glass.png");

    /** Bounds of the glass in model space (blocks, Y down, root included), used for the sort origin. */
    private static final float MIN_X = -8.0F / 16.0F, MAX_X = 8.0F / 16.0F;
    private static final float MIN_Y = (24.0F - 28.0F) / 16.0F, MAX_Y = (24.0F - 17.0F) / 16.0F;
    private static final float MIN_Z = -18.0F / 16.0F, MAX_Z = 2.0F / 16.0F;

    /** Sort origin used until the render state carries one: the model origin, i.e. no shift. */
    public static final Vector3fc DEFAULT_SORT_ORIGIN = new Vector3f();

    private final ModelPart glass;

    public MiniHeliGlassModel(ModelPart root) {
        super(root, RenderTypes::entityTranslucentCullItemTarget);
        this.glass = root.getChild("Glass");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        // Cabin glass between the tub sill (y = -17), the roof and the door pillars, and the windscreen standing
        // forward onto the nose.
        partdefinition.addOrReplaceChild("Glass", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-8.0F, -28.0F, -16.0F, 16.0F, 11.0F, 18.0F, CubeDeformation.NONE)
                .texOffs(50, 0).addBox(-6.5F, -28.0F, -18.0F, 13.0F, 13.0F, 2.0F, CubeDeformation.NONE),
                PartPose.offset(0.0F, 24.0F, 0.0F));

        return LayerDefinition.create(meshdefinition, 128, 32);
    }

    /**
     * The point of the glass's bounding box nearest to {@code cameraInModel} (the camera position in this model's
     * space, in blocks), written to {@code dest}. When the camera is inside the cabin it is the camera itself.
     */
    public static Vector3f sortOrigin(Vector3fc cameraInModel, Vector3f dest) {
        return dest.set(
                Math.clamp(cameraInModel.x(), MIN_X, MAX_X),
                Math.clamp(cameraInModel.y(), MIN_Y, MAX_Y),
                Math.clamp(cameraInModel.z(), MIN_Z, MAX_Z));
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
        // Once PlaneRenderState carries it: applySortOrigin(state.glassSortOrigin).
        applySortOrigin(DEFAULT_SORT_ORIGIN);
    }
}
