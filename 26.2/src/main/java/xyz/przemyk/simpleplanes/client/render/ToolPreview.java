package xyz.przemyk.simpleplanes.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.autopilot.Airfield;
import xyz.przemyk.simpleplanes.autopilot.AutopilotComponents;
import xyz.przemyk.simpleplanes.autopilot.AutopilotConfig;
import xyz.przemyk.simpleplanes.autopilot.RotorcraftConfig;
import xyz.przemyk.simpleplanes.autopilot.TerrainScanner;
import xyz.przemyk.simpleplanes.client.AirfieldMarkers;
import xyz.przemyk.simpleplanes.items.HelipadToolItem;
import xyz.przemyk.simpleplanes.items.RunwayToolItem;
import xyz.przemyk.simpleplanes.network.AirfieldMarkersPacket.Runway;

import java.util.ArrayList;
import java.util.List;

/**
 * What the survey tool in the player's hand would mark if they clicked now, shaded on the ground.
 *
 * <h2>Why the verdict is worked out here rather than left to the click</h2>
 * Every one of these tools already refuses selections, and every refusal used to arrive after the
 * fact: walk to one end of a strip, walk to the other, click, and be told it is nineteen blocks
 * long. The rules are all pure functions of the terrain and the two clicked points, and the terrain
 * is on the client, so the same answer can be had before the click instead of after it. Green means
 * the tool will take it, amber means it will take it and then warn, red means it will refuse.
 *
 * <p><b>Where the preview can and cannot be exact.</b> Parking spots are judged by
 * {@link Airfield#parkingSpotProblem}, which is the very function the click runs, so the shading and
 * the refusal can never disagree. The runway checks are the tool's own two lengths, restated. The
 * pad checks are the survey's cheap half — size, ground, flatness, anything standing over the pad or
 * its clearance ring — but <em>not</em> its approach-sector scan, which reads a hundred and thirty
 * columns square and cannot run twenty times a second. A pad shaded green can therefore still be
 * refused for having no way in; a pad shaded red will always be refused.
 *
 * <p><b>The width of a runway preview is nominal.</b> The survey measures the strip outwards from
 * its own centreline, and there is nothing to measure from until both thresholds exist. The
 * rectangle is drawn {@value #PREVIEW_WIDTH} blocks wide so it reads as a strip rather than a line;
 * the survey will report the real number.
 *
 * <p>Recomputed once per client tick rather than once per frame, and handed to the renderer as an
 * immutable snapshot: it walks the terrain, and doing that sixty or two hundred times a second to
 * redraw something the player cannot move faster than they can walk is waste.
 */
@Environment(EnvType.CLIENT)
public final class ToolPreview {

    private ToolPreview() {}

    /** A selection the tool will accept. */
    private static final int OK_FILL = 0x3340FF80;
    private static final int OK_LINE = 0xE040FF80;
    /** A selection the tool will accept and then complain about. */
    private static final int WARN_FILL = 0x33FFC020;
    private static final int WARN_LINE = 0xE0FFC020;
    /** A selection the tool will refuse. */
    private static final int REFUSED_FILL = 0x33FF4030;
    private static final int REFUSED_LINE = 0xE0FF4030;
    /** The threshold or corner already marked, which is a fact rather than a verdict. */
    private static final int ANCHOR_LINE = 0xE0FFFFFF;
    private static final int ANCHOR_FILL = 0x40FFFFFF;

    /** How tall the post standing on a marked point is, in blocks. */
    private static final double POST_HEIGHT = 4.0;

    /** Nominal width of a runway preview, in blocks. See the class comment. */
    private static final int PREVIEW_WIDTH = 5;

    /** Shortest runway the survey tool accepts, in blocks. Mirrors {@code RunwayToolItem#useOn}. */
    private static final double MIN_MARKED_LENGTH = 20.0;

    /**
     * How far a parking click will look for an airfield, in blocks. Mirrors
     * {@code RunwayToolItem#PARKING_SEARCH_RADIUS}: the preview has to give up looking at exactly
     * the distance the click does, or it shades ground the click will refuse outright.
     */
    private static final double PARKING_SEARCH_RADIUS = 256.0;

    /**
     * Radius of the pad the stand-here gesture marks. Mirrors
     * {@code HelipadToolItem#STAND_HERE_RADIUS}.
     */
    private static final int STAND_HERE_RADIUS = 3;

    /** Half the side of the square drawn on a candidate stand: about the footprint of an aircraft. */
    private static final double STAND_HALF_SIZE = 1.5;

    /** What to draw, as of the last client tick. */
    public record Preview(List<GroundOverlay.Patch> patches, List<GroundOverlay.Post> posts) {

        public boolean isEmpty() {
            return patches.isEmpty() && posts.isEmpty();
        }
    }

    private static final Preview EMPTY = new Preview(List.of(), List.of());

    private static volatile Preview current = EMPTY;

    public static Preview current() {
        return current;
    }

    public static void tick(Minecraft minecraft) {
        current = compute(minecraft);
    }

    private static Preview compute(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null) {
            return EMPTY;
        }
        for (ItemStack stack : List.of(player.getMainHandItem(), player.getOffhandItem())) {
            if (stack.getItem() instanceof RunwayToolItem) {
                return runwayTool(level, stack, targeted(minecraft));
            }
            if (stack.getItem() instanceof HelipadToolItem) {
                return helipadTool(level, stack, targeted(minecraft), player.blockPosition());
            }
        }
        return EMPTY;
    }

    /** The block the crosshair is on, or null when it is on an entity or on nothing. */
    private static @Nullable BlockPos targeted(Minecraft minecraft) {
        return minecraft.hitResult instanceof BlockHitResult hit
            && hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    // ------------------------------------------------------------------ the runway tool

    private static Preview runwayTool(Level level, ItemStack stack, @Nullable BlockPos aimed) {
        if (Boolean.TRUE.equals(stack.get(AutopilotComponents.PARKING_MODE))) {
            return aimed == null ? EMPTY : parking(level, aimed);
        }
        BlockPos anchor = stack.get(AutopilotComponents.RUNWAY_ANCHOR);
        if (anchor == null) {
            return EMPTY;
        }
        List<GroundOverlay.Patch> patches = new ArrayList<>();
        List<GroundOverlay.Post> posts = new ArrayList<>();
        Vec3 first = onSurface(level, anchor);
        mark(patches, posts, first, 0.5, ANCHOR_FILL, ANCHOR_LINE);
        if (aimed != null) {
            Vec3 second = onSurface(level, aimed);
            int colour = runwayVerdict(anchor, aimed, first, second);
            patches.add(new GroundOverlay.Patch(
                GroundOverlay.rectangle(first, second, PREVIEW_WIDTH / 2.0),
                fillOf(colour), colour));
            posts.add(new GroundOverlay.Post(second, POST_HEIGHT, colour));
        }
        return new Preview(List.copyOf(patches), List.copyOf(posts));
    }

    /**
     * The two length rules, in the order the tool applies them: it refuses a strip under
     * {@value #MIN_MARKED_LENGTH} blocks outright, measured as the tool does between the two clicked
     * blocks; it registers a longer one but warns that sorties into it will be refused unless it
     * clears {@link AutopilotConfig#MIN_USABLE_RUNWAY_LENGTH}, which is measured along the ground
     * because that is what the take-off and landing rolls use.
     */
    private static int runwayVerdict(BlockPos anchor, BlockPos aimed, Vec3 first, Vec3 second) {
        if (Math.sqrt(anchor.distSqr(aimed)) < MIN_MARKED_LENGTH) {
            return REFUSED_LINE;
        }
        double dx = second.x - first.x;
        double dz = second.z - first.z;
        return Math.sqrt(dx * dx + dz * dz) < AutopilotConfig.MIN_USABLE_RUNWAY_LENGTH
            ? WARN_LINE : OK_LINE;
    }

    /**
     * A candidate stand, judged by the same function the click runs.
     *
     * <p>The airfield it is judged against is rebuilt from the marker the server sent, which carries
     * the two thresholds, the width and the stands already marked — everything
     * {@link Airfield#parkingSpotProblem} reads. The rest of a stored airfield is about approaches
     * and about the stand rule, and neither is consulted here.
     */
    private static Preview parking(Level level, BlockPos aimed) {
        Runway nearest = AirfieldMarkers.nearest(aimed.getX() + 0.5, aimed.getZ() + 0.5,
            PARKING_SEARCH_RADIUS);
        int colour = REFUSED_LINE;
        if (nearest != null) {
            Airfield airfield = new Airfield(nearest.name(), nearest.thresholdA(),
                nearest.thresholdB(), nearest.width()).withParkingSpots(nearest.stands());
            colour = Airfield.parkingSpotProblem(level, airfield, aimed) == null ? OK_LINE : REFUSED_LINE;
        }
        List<GroundOverlay.Patch> patches = new ArrayList<>();
        List<GroundOverlay.Post> posts = new ArrayList<>();
        mark(patches, posts, onSurface(level, aimed), STAND_HALF_SIZE, fillOf(colour), colour);
        return new Preview(List.copyOf(patches), List.copyOf(posts));
    }

    // ------------------------------------------------------------------ the helipad marker

    private static Preview helipadTool(Level level, ItemStack stack, @Nullable BlockPos aimed,
                                       BlockPos standingOn) {
        BlockPos anchor = stack.get(AutopilotComponents.HELIPAD_ANCHOR);
        if (anchor == null) {
            // Nothing marked yet, so what the tool would take right now is the sneak + right-click
            // gesture: the default pad centred on the block the player is standing on.
            return pad(level, standingOn, STAND_HERE_RADIUS, List.of(), List.of());
        }
        List<GroundOverlay.Patch> patches = new ArrayList<>();
        List<GroundOverlay.Post> posts = new ArrayList<>();
        mark(patches, posts, onSurface(level, anchor), 0.5, ANCHOR_FILL, ANCHOR_LINE);
        if (aimed == null) {
            return new Preview(List.copyOf(patches), List.copyOf(posts));
        }
        // Exactly how Helipad#survey reads two corners: the larger span decides the radius, and the
        // centre is the middle of the box.
        int radius = Math.max(Math.abs(anchor.getX() - aimed.getX()),
            Math.abs(anchor.getZ() - aimed.getZ())) / 2;
        BlockPos centre = new BlockPos((anchor.getX() + aimed.getX()) / 2, aimed.getY(),
            (anchor.getZ() + aimed.getZ()) / 2);
        return pad(level, centre, radius, patches, posts);
    }

    private static Preview pad(Level level, BlockPos centre, int radius,
                               List<GroundOverlay.Patch> existingPatches,
                               List<GroundOverlay.Post> existingPosts) {
        List<GroundOverlay.Patch> patches = new ArrayList<>(existingPatches);
        List<GroundOverlay.Post> posts = new ArrayList<>(existingPosts);
        Vec3 middle = onSurface(level, centre);
        int colour = padVerdict(level, middle, radius);
        patches.add(new GroundOverlay.Patch(
            GroundOverlay.square(middle.x, middle.z, middle.y, radius + 0.5),
            fillOf(colour), colour));
        patches.add(new GroundOverlay.Patch(
            GroundOverlay.square(middle.x, middle.z, middle.y,
                radius + RotorcraftConfig.PAD_CLEARANCE_MARGIN + 0.5),
            0, colour));
        return new Preview(List.copyOf(patches), List.copyOf(posts));
    }

    /**
     * The survey's cheap refusals: the size bounds, then every column of the pad on solid loaded
     * ground and flat to within {@link RotorcraftConfig#PAD_MAX_ROUGHNESS}, then nothing standing
     * that far above it anywhere in the pad or its clearance ring. The approach-sector scan is not
     * run — see the class comment.
     */
    private static int padVerdict(Level level, Vec3 middle, int radius) {
        if (radius < RotorcraftConfig.MIN_PAD_RADIUS || radius > RotorcraftConfig.MAX_PAD_RADIUS) {
            return REFUSED_LINE;
        }
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        int reach = radius + RotorcraftConfig.PAD_CLEARANCE_MARGIN;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double x = middle.x + dx;
                double z = middle.z + dz;
                int surface = TerrainScanner.surfaceHeight(level, x, z);
                boolean onPad = Math.abs(dx) <= radius && Math.abs(dz) <= radius;
                if (surface == TerrainScanner.UNKNOWN_HEIGHT) {
                    if (onPad) {
                        return REFUSED_LINE;
                    }
                    continue;
                }
                if (surface - middle.y > RotorcraftConfig.PAD_MAX_ROUGHNESS) {
                    return REFUSED_LINE;
                }
                if (onPad) {
                    if (!TerrainScanner.isLandable(level, x, z)) {
                        return REFUSED_LINE;
                    }
                    lowest = Math.min(lowest, surface);
                    highest = Math.max(highest, surface);
                }
            }
        }
        return highest - lowest > RotorcraftConfig.PAD_MAX_ROUGHNESS ? REFUSED_LINE : OK_LINE;
    }

    // ------------------------------------------------------------------ shared

    /** A small square on the ground plus a post standing on it, so it reads from any angle. */
    private static void mark(List<GroundOverlay.Patch> patches, List<GroundOverlay.Post> posts,
                             Vec3 point, double half, int fill, int line) {
        patches.add(new GroundOverlay.Patch(
            GroundOverlay.square(point.x, point.z, point.y, half), fill, line));
        posts.add(new GroundOverlay.Post(point, POST_HEIGHT, line));
    }

    /** The shading that goes with an outline colour: the same hue at the fill alpha. */
    private static int fillOf(int line) {
        if (line == OK_LINE) {
            return OK_FILL;
        }
        if (line == WARN_LINE) {
            return WARN_FILL;
        }
        return line == ANCHOR_LINE ? ANCHOR_FILL : REFUSED_FILL;
    }

    /**
     * The top of the terrain in a column, which is where a threshold or a stand is stored.
     * {@code surfaceHeight} gives the first free block, so its value is the height of the face the
     * aircraft rests on. A column nobody has loaded falls back to the block that was clicked.
     */
    private static Vec3 onSurface(Level level, BlockPos pos) {
        int surface = TerrainScanner.surfaceHeight(level, pos.getX() + 0.5, pos.getZ() + 0.5);
        double y = surface == TerrainScanner.UNKNOWN_HEIGHT ? pos.getY() + 1.0 : surface;
        return new Vec3(pos.getX() + 0.5, y, pos.getZ() + 0.5);
    }
}
