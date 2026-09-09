package xyz.przemyk.simpleplanes.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.autopilot.RotorcraftConfig;
import xyz.przemyk.simpleplanes.client.AirfieldMarkers;
import xyz.przemyk.simpleplanes.network.AirfieldMarkersPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the airfields and helipads on the ground.
 *
 * <p>Hooked to {@code LevelRenderEvents.COLLECT_SUBMITS}, which is the point in the frame where a
 * mod hands geometry to the level renderer. Nothing is drawn immediately: two batches are submitted,
 * the shading and then the outlines, and the renderer runs them in its own phases. Both render types
 * blend, so both land after the opaque terrain and the shading is genuinely translucent over it.
 *
 * <p>The pose handed to the submission is our own, not the one the level renderer is using: a
 * submission copies the pose the moment it is made, so a local {@link PoseStack} translated by minus
 * the camera position is all that is needed to work in world coordinates, and it cannot leave the
 * shared stack in a state the rest of the frame did not expect.
 *
 * <h2>Reading it</h2>
 * A <b>long pale rectangle</b> is a surveyed runway; it turns amber when the strip is too short for
 * the autopilot to use, which is the one thing about a registered runway a player cannot see by
 * looking at it. A <b>small cyan square</b> beside it is a marked stand, and an <b>orange</b> one is
 * a stand with an aircraft on it or an aircraft on its way to it. A <b>violet square</b> is a
 * helicopter pad, drawn inside the wider violet outline of the clearance the survey required around
 * it.
 *
 * <p>A stand the server could not see into is drawn <b>grey</b>, and that is a third answer rather
 * than a hedge: fields are sent much further than entities are loaded, so on a distant field nobody
 * knows what is parked. Grey says so. See {@code AirfieldMarkersPacket.Runway#unknownStands}.
 *
 * <p>The <b>green, amber and red</b> shapes on top of all that are the selection the tool in hand
 * would mark if it were clicked now; see {@link ToolPreview}.
 *
 * <h2>What is built when</h2>
 * The registered fields change at most once a second and only when something about them actually
 * changed, so their geometry is built when the payload changes and kept — not rebuilt per frame,
 * which is how it was and which spent two lists, a {@code Vec3[4]} per runway, per stand and two per
 * pad on every frame to arrive at the same vertices as the frame before. The tool preview is cached
 * on the same principle a tick at a time; see {@link ToolPreview}. What remains per frame is the
 * camera-relative pose and the two submissions, which have to be.
 */
@Environment(EnvType.CLIENT)
public final class AirfieldOverlayRenderer {

    private AirfieldOverlayRenderer() {}

    private static final int RUNWAY_FILL = 0x22F0F0F0;
    private static final int RUNWAY_LINE = 0xB0F0F0F0;
    /** A registered strip too short for a sortie to be accepted into it. */
    private static final int SHORT_RUNWAY_FILL = 0x26FFB020;
    private static final int SHORT_RUNWAY_LINE = 0xC0FFB020;
    private static final int FREE_STAND_FILL = 0x3320D0FF;
    private static final int FREE_STAND_LINE = 0xCC20D0FF;
    private static final int TAKEN_STAND_FILL = 0x33FF6A20;
    private static final int TAKEN_STAND_LINE = 0xCCFF6A20;
    /** A stand out where the server cannot see what is standing on it: neither free nor taken. */
    private static final int UNKNOWN_STAND_FILL = 0x2C98A0A8;
    private static final int UNKNOWN_STAND_LINE = 0xA098A0A8;
    private static final int PAD_FILL = 0x33C080FF;
    private static final int PAD_LINE = 0xCCC080FF;
    private static final int PAD_CLEARANCE_LINE = 0x70C080FF;

    /** Half the side of the square drawn on a marked stand: about the footprint of an aircraft. */
    private static final double STAND_HALF_SIZE = 1.5;

    /** The registered fields as vertices, and the payload they were built from. */
    private static List<GroundOverlay.Patch> fields = List.of();
    private static int builtGeneration = -1;

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            ToolPreview.Preview preview = ToolPreview.current();
            List<GroundOverlay.Patch> knownFields = knownFields();
            if (knownFields.isEmpty() && preview.isEmpty()) {
                return;
            }
            List<GroundOverlay.Patch> previewPatches = preview.patches();
            List<GroundOverlay.Post> previewPosts = preview.posts();
            Vec3 camera = context.levelState().cameraRenderState.pos;
            PoseStack pose = new PoseStack();
            pose.translate(-camera.x, -camera.y, -camera.z);
            SubmitNodeCollector collector = context.submitNodeCollector();
            // Two lists handed to each batch rather than one concatenated list, because
            // concatenating them is a copy of everything on screen, per frame, to save a loop.
            collector.submitCustomGeometry(pose, RenderTypes.debugQuads(), (p, out) -> {
                GroundOverlay.fill(p, out, knownFields);
                GroundOverlay.fill(p, out, previewPatches);
            });
            collector.submitCustomGeometry(pose, RenderTypes.lines(), (p, out) -> {
                GroundOverlay.lines(p, out, knownFields, List.of());
                GroundOverlay.lines(p, out, previewPatches, previewPosts);
            });
        });
    }

    /**
     * The fields the server has told this client about, built once per payload.
     *
     * <p>Render thread only, which is what makes the plain fields behind it safe: the counter is
     * read from {@code AirfieldMarkers} before the lists, so a payload that lands mid-build is
     * picked up on the next frame rather than half-drawn on this one.
     */
    private static List<GroundOverlay.Patch> knownFields() {
        int generation = AirfieldMarkers.generation();
        if (generation != builtGeneration) {
            fields = buildKnownFields();
            builtGeneration = generation;
        }
        return fields;
    }

    private static List<GroundOverlay.Patch> buildKnownFields() {
        List<GroundOverlay.Patch> patches = new ArrayList<>();
        for (AirfieldMarkersPacket.Runway runway : AirfieldMarkers.runways()) {
            boolean usable = runway.usable();
            patches.add(new GroundOverlay.Patch(
                GroundOverlay.rectangle(surface(runway.thresholdA()), surface(runway.thresholdB()),
                    Math.max(1.0, runway.width() / 2.0)),
                usable ? RUNWAY_FILL : SHORT_RUNWAY_FILL,
                usable ? RUNWAY_LINE : SHORT_RUNWAY_LINE));
            List<BlockPos> stands = runway.stands();
            for (int i = 0; i < stands.size(); i++) {
                Vec3 stand = surface(stands.get(i));
                patches.add(new GroundOverlay.Patch(
                    GroundOverlay.square(stand.x, stand.z, stand.y, STAND_HALF_SIZE),
                    standFill(runway, i), standLine(runway, i)));
            }
        }
        for (AirfieldMarkersPacket.Pad pad : AirfieldMarkers.pads()) {
            Vec3 centre = surface(pad.centre());
            patches.add(new GroundOverlay.Patch(
                GroundOverlay.square(centre.x, centre.z, centre.y, pad.radius() + 0.5),
                PAD_FILL, PAD_LINE));
            patches.add(new GroundOverlay.Patch(
                GroundOverlay.square(centre.x, centre.z, centre.y,
                    pad.radius() + RotorcraftConfig.PAD_CLEARANCE_MARGIN + 0.5),
                0, PAD_CLEARANCE_LINE));
        }
        return List.copyOf(patches);
    }

    /**
     * Unknown before taken: the two are never both set, and if a later sender ever let them be, the
     * honest answer is the one to show.
     */
    private static int standFill(AirfieldMarkersPacket.Runway runway, int index) {
        if (runway.standUnknown(index)) {
            return UNKNOWN_STAND_FILL;
        }
        return runway.standOccupied(index) ? TAKEN_STAND_FILL : FREE_STAND_FILL;
    }

    private static int standLine(AirfieldMarkersPacket.Runway runway, int index) {
        if (runway.standUnknown(index)) {
            return UNKNOWN_STAND_LINE;
        }
        return runway.standOccupied(index) ? TAKEN_STAND_LINE : FREE_STAND_LINE;
    }

    /**
     * The point on top of a stored block. Thresholds, stands and pad centres are all stored as the
     * surface block the aircraft touches — the same convention {@code Airfield#pointA} uses — so the
     * overlay never has to probe the terrain for the height of anything already registered.
     */
    private static Vec3 surface(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
    }
}
