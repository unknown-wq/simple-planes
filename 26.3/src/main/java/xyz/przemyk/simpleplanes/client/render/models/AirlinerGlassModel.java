package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;

import java.util.EnumSet;
import java.util.Set;

/**
 * Airliner, glass layer, for either size ({@link AirlinerShape}): one pane in every cabin and cockpit side window and
 * in both windscreen tiers, drawn after the other layers. Texture {@link #TEXTURE} (128x16): light blue-grey,
 * alpha about 90, the top row a little lighter.
 *
 * <p>Same scheme as {@link FighterGlassModel}: {@link RenderTypes#entityTranslucentCull}. Each pane is a box of zero
 * thickness with its two opposite faces, one seen from outside and one from the cabin, so the glass is tinted
 * from both sides and nothing else covers the opening. The side panes stand in the middle of the window band,
 * inside the open windows of the belt plates; the windscreen panes inside the windscreen frames.
 *
 * <p>Sorting: submitted from {@link #sortOrigin}, the point of the glazed box nearest the camera moved
 * {@link #SORT_BIAS} towards it, so a translucent rider (a player) sorts before the glass from outside, and from a
 * seat inside the box the glass sorts last. {@link #applySortOrigin} shifts the part back.
 */
public class AirlinerGlassModel extends EntityModel<PlaneRenderState> {

    /** The glass texture. */
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/plane_upgrades/airliner_glass.png");

    /** How far (blocks) the sort origin is pulled from the glazed box towards the camera. */
    public static final float SORT_BIAS = 0.5F;

    private static final Set<Direction> SIDES = EnumSet.of(Direction.EAST, Direction.WEST);
    private static final Set<Direction> ENDS = EnumSet.of(Direction.NORTH, Direction.SOUTH);

    private final ModelPart glass;
    /** Bounds of the glazed box in model space (blocks, Y down, root included). */
    private final float minX, maxX, minY, maxY, minZ, maxZ;

    public AirlinerGlassModel(ModelPart root, AirlinerShape shape) {
        super(root, RenderTypes::entityTranslucentCull);
        this.glass = root.getChild("Glass");
        this.maxX = shape.halfWidth / 16.0F;
        this.minX = -maxX;
        this.minY = (24.0F + windscreenTop(shape)) / 16.0F;
        this.maxY = (24.0F + AirlinerAirframe.bandBottom(shape)) / 16.0F;
        this.minZ = -87.0F / 16.0F;
        this.maxZ = shape.bodyRear / 16.0F;
    }

    private static int windscreenTop(AirlinerShape shape) {
        return -44 - shape.lift;
    }

    public static LayerDefinition createBodyLayer(AirlinerShape shape) {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();
        CubeListBuilder panes = CubeListBuilder.create();

        // side windows: a pane in the middle of the 2 px band, both sides
        int x = shape.halfWidth - 1;
        int top = AirlinerAirframe.bandTop(shape);
        int h = AirlinerAirframe.bandBottom(shape) - top;
        for (int[] w : shape.windows) {
            panes.texOffs(0, 0).addBox(x, top, w[0], 0, h, w[1] - w[0], SIDES);
            panes.texOffs(0, 0).addBox(-x, top, w[0], 0, h, w[1] - w[0], SIDES);
        }
        // windscreen: inside the 1 px frames of AirlinerMetalModel's ws_upper and ws_lower
        int wsTop = windscreenTop(shape);
        panes.texOffs(0, 0).addBox(-shape.windscreenUpper, wsTop, -82.5F, 2 * shape.windscreenUpper, 4, 0, ENDS);
        panes.texOffs(0, 0).addBox(-shape.windscreenLower, wsTop + 4, -86.5F, 2 * shape.windscreenLower, 5, 0, ENDS);

        partdefinition.addOrReplaceChild("Glass", panes, PartPose.offset(0.0F, 24.0F, 0.0F));
        return LayerDefinition.create(meshdefinition, 128, 16);
    }

    /**
     * The sort origin for a camera at {@code cameraInModel} (model space, blocks), written to {@code dest}: the
     * nearest point of the glazed box, moved up to {@link #SORT_BIAS} towards the camera; inside, the camera itself.
     */
    public Vector3f sortOrigin(Vector3fc cameraInModel, Vector3f dest) {
        dest.set(
                Math.clamp(cameraInModel.x(), minX, maxX),
                Math.clamp(cameraInModel.y(), minY, maxY),
                Math.clamp(cameraInModel.z(), minZ, maxZ));
        float dx = cameraInModel.x() - dest.x, dy = cameraInModel.y() - dest.y, dz = cameraInModel.z() - dest.z;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > 0.0F) {
            float k = Math.min(SORT_BIAS, dist) / dist;
            dest.add(dx * k, dy * k, dz * k);
        }
        return dest;
    }

    /** Shifts the glass by {@code -origin} (blocks, model space); runs after {@code super.setupAnim} resets the pose. */
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
