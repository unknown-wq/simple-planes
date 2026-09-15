package xyz.przemyk.simpleplanes.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.autopilot.AutopilotConfig;
import xyz.przemyk.simpleplanes.autopilot.RotorcraftConfig;
import xyz.przemyk.simpleplanes.client.AirfieldMarkers;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
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
 * would mark if it were clicked now; see {@link ToolPreview}. That part, and only that part, depends
 * on what the player is holding.
 *
 * <h2>When the registered fields are drawn</h2>
 * Never gated on the survey tool: a runway you cannot see is a runway you cannot taxi onto, line up
 * with or park on, and the client is sent the fields around it once a second whatever is in the
 * player's hand (see {@code AirfieldMarkerSync}). It is gated on <em>distance</em> instead, because
 * the other failure is as bad in the opposite direction — everything within the 512-block send
 * radius, shaded on the ground, permanently, is a mod painting on someone's world.
 *
 * <p>So by default a field is drawn within {@link AutopilotConfig#MARKER_DRAW_RADIUS} of the camera,
 * and that limit is lifted entirely while the player is riding an aircraft, which is exactly when
 * finding a strip from a distance is the point. The <b>Airfield Markers</b> key overrides it in
 * either direction — everything always, or nothing at all. See {@link Mode}.
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

    /**
     * How much of what the client knows about is drawn.
     *
     * <p>Cycled with the <b>Airfield Markers</b> key (Options - Controls - Simple Planes, K by
     * default), which is the only way to change it: a render preference belongs to the person
     * looking at the screen, not to the world, so it is neither a command nor a server setting. It
     * is deliberately not persisted between launches either — there is nowhere to persist a client
     * preference in this mod (see {@code SimplePlanesConfig}), and since the default is the
     * behaviour almost everyone wants, starting from it costs nothing.
     */
    public enum Mode {
        /**
         * Fields within {@link AutopilotConfig#MARKER_DRAW_RADIUS} on foot, and everything the
         * client has been sent while the player is in an aircraft. The default.
         */
        NEARBY("simpleplanes.markers.nearby"),
        /** Everything the client has been sent, on foot as well. */
        ALWAYS("simpleplanes.markers.always"),
        /** Nothing but the tool preview, which the player asked for by holding the tool. */
        OFF("simpleplanes.markers.off");

        private final String messageKey;

        Mode(String messageKey) {
            this.messageKey = messageKey;
        }

        /** Translation key of the line put on the action bar when this mode is selected. */
        public String messageKey() {
            return messageKey;
        }
    }

    private static final Mode[] MODES = Mode.values();

    /**
     * Client thread (the key handler) writes it, render thread reads it, so it is volatile; nothing
     * else about it needs to be atomic, because a person cannot press a key twice in one frame.
     */
    private static volatile Mode mode = Mode.NEARBY;

    public static Mode mode() {
        return mode;
    }

    /** Advances to the next mode and returns it. */
    public static Mode cycleMode() {
        Mode next = MODES[(mode.ordinal() + 1) % MODES.length];
        mode = next;
        return next;
    }

    /**
     * One registered field's geometry together with where it is, so a frame can decide to leave it
     * out without touching its vertices.
     */
    private record Field(double x, double z, List<GroundOverlay.Patch> patches) {}

    /** The registered fields as vertices, and the payload they were built from. */
    private static List<Field> fields = List.of();
    private static int builtGeneration = -1;

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            ToolPreview.Preview preview = ToolPreview.current();
            double radiusSq = drawRadiusSq();
            // Mode OFF does not even rebuild: knownFields() is skipped, the generation counter stays
            // where it is, and the geometry is built on the frame the player turns the overlay back
            // on.
            List<Field> knownFields = radiusSq > 0.0 ? knownFields() : List.of();
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
                for (Field field : knownFields) {
                    if (inRange(field, camera, radiusSq)) {
                        GroundOverlay.fill(p, out, field.patches());
                    }
                }
                GroundOverlay.fill(p, out, previewPatches);
            });
            collector.submitCustomGeometry(pose, RenderTypes.lines(), (p, out) -> {
                for (Field field : knownFields) {
                    if (inRange(field, camera, radiusSq)) {
                        GroundOverlay.lines(p, out, field.patches(), List.of());
                    }
                }
                GroundOverlay.lines(p, out, previewPatches, previewPosts);
            });
        });
    }

    /**
     * How far out fields are drawn this frame, squared: 0 for none at all and
     * {@link Double#POSITIVE_INFINITY} for everything the client has.
     *
     * <p>Riding an aircraft is what lifts the limit, rather than being airborne, because a plane
     * still on its stand is about to need the picture and one that has just landed is taxiing by it.
     * It is the aircraft and not the ground that decides: the whole reason the far limit is worth
     * having is that from the air a runway is a thing you are looking for.
     */
    private static double drawRadiusSq() {
        return switch (mode) {
            case OFF -> 0.0;
            case ALWAYS -> Double.POSITIVE_INFINITY;
            case NEARBY -> {
                LocalPlayer player = Minecraft.getInstance().player;
                yield player != null && player.getVehicle() instanceof PlaneEntity
                    ? Double.POSITIVE_INFINITY
                    : AutopilotConfig.MARKER_DRAW_RADIUS * AutopilotConfig.MARKER_DRAW_RADIUS;
            }
        };
    }

    /**
     * Measured to the camera rather than to the player, which is the same point in first person and
     * the right one in the two cases where it is not: a third-person camera is where the picture is
     * being drawn from, and a spectator has no aircraft to lift the limit for them.
     */
    private static boolean inRange(Field field, Vec3 camera, double radiusSq) {
        if (radiusSq == Double.POSITIVE_INFINITY) {
            return true;
        }
        double dx = field.x() - camera.x;
        double dz = field.z() - camera.z;
        return dx * dx + dz * dz <= radiusSq;
    }

    /**
     * The fields the server has told this client about, built once per payload.
     *
     * <p>Render thread only, which is what makes the plain fields behind it safe: the counter is
     * read from {@code AirfieldMarkers} before the lists, so a payload that lands mid-build is
     * picked up on the next frame rather than half-drawn on this one.
     */
    private static List<Field> knownFields() {
        int generation = AirfieldMarkers.generation();
        if (generation != builtGeneration) {
            fields = buildKnownFields();
            builtGeneration = generation;
        }
        return fields;
    }

    /**
     * One {@link Field} per registered airfield and per registered pad, rather than one flat list of
     * patches, because the draw limit is per field: a runway and its stands are either all drawn or
     * none of them are, and half an airfield is worse than none.
     */
    private static List<Field> buildKnownFields() {
        List<Field> built = new ArrayList<>();
        for (AirfieldMarkersPacket.Runway runway : AirfieldMarkers.runways()) {
            List<GroundOverlay.Patch> patches = new ArrayList<>();
            boolean usable = runway.usable();
            Vec3 a = surface(runway.thresholdA());
            Vec3 b = surface(runway.thresholdB());
            patches.add(new GroundOverlay.Patch(
                GroundOverlay.rectangle(a, b, Math.max(1.0, runway.width() / 2.0)),
                usable ? RUNWAY_FILL : SHORT_RUNWAY_FILL,
                usable ? RUNWAY_LINE : SHORT_RUNWAY_LINE));
            List<BlockPos> stands = runway.stands();
            for (int i = 0; i < stands.size(); i++) {
                Vec3 stand = surface(stands.get(i));
                patches.add(new GroundOverlay.Patch(
                    GroundOverlay.square(stand.x, stand.z, stand.y, STAND_HALF_SIZE),
                    standFill(runway, i), standLine(runway, i)));
            }
            // The centre of the strip, matching what AirfieldMarkerSync measures its own radius to,
            // so "near enough to be sent" and "near enough to be drawn" are distances to the same
            // point and differ only by the number they are compared against.
            built.add(new Field((a.x + b.x) / 2.0, (a.z + b.z) / 2.0, List.copyOf(patches)));
        }
        for (AirfieldMarkersPacket.Pad pad : AirfieldMarkers.pads()) {
            Vec3 centre = surface(pad.centre());
            built.add(new Field(centre.x, centre.z, List.of(
                new GroundOverlay.Patch(
                    GroundOverlay.square(centre.x, centre.z, centre.y, pad.radius() + 0.5),
                    PAD_FILL, PAD_LINE),
                new GroundOverlay.Patch(
                    GroundOverlay.square(centre.x, centre.z, centre.y,
                        pad.radius() + RotorcraftConfig.PAD_CLEARANCE_MARGIN + 0.5),
                    0, PAD_CLEARANCE_LINE))));
        }
        return List.copyOf(built);
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
