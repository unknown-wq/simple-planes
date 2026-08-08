package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Cheap, predictable terrain awareness for one aircraft.
 *
 * <p>The forward profile is built from {@link Level#getHeight(Heightmap.Types, int, int)}, which is
 * an O(1) lookup into the chunk's heightmap — no block iteration and, importantly, no chunk
 * loading: {@code Level#getHeight} checks {@code hasChunk} first and reports {@code getMinY()} for
 * chunks that are not resident. Those samples are reported as {@link #UNKNOWN_HEIGHT} and ignored,
 * so an aircraft flying into ungenerated terrain simply keeps its current altitude rather than
 * diving at a phantom valley.
 *
 * <p>Cost per tick is {@value AutopilotConfig#SCAN_SAMPLES} forward samples plus two side probes,
 * i.e. a bounded handful of heightmap lookups — small enough to run for every autopilot aircraft
 * every tick without caching.
 */
public class TerrainScanner {

    /** Returned when the column is not loaded, or lies outside the buildable world. */
    public static final int UNKNOWN_HEIGHT = Integer.MIN_VALUE;

    private double highestAhead = UNKNOWN_HEIGHT;
    private double highestLeft = UNKNOWN_HEIGHT;
    private double highestRight = UNKNOWN_HEIGHT;
    private double distanceToHighest = Double.MAX_VALUE;
    private boolean valid;

    /**
     * Surface height of a column: the Y of the first free block above the terrain, or
     * {@link #UNKNOWN_HEIGHT} if the chunk is not loaded.
     *
     * <p>This is the <em>clearance</em> surface — the top of whatever the aircraft would touch if it
     * flew down this column. {@link Heightmap.Types#MOTION_BLOCKING} tests
     * {@code blocksMotion() || !getFluidState().isEmpty()}, so a sea reports its own waterline and a
     * forest its canopy. That is the right answer for "how low may I fly here", which is what terrain
     * following and the approach obstacle counts ask, and the wrong answer for "may I put the wheels
     * down here" — see {@link #landableSurfaceHeight}.
     */
    public static int surfaceHeight(Level level, double x, double z) {
        return heightAt(level, Airfield.heightmapType(), x, z);
    }

    /**
     * Surface an aircraft could actually come to rest on: the top of the highest block that blocks
     * motion, with any fluid standing on it ignored. Over an ocean this is the sea floor rather than
     * the waterline, and over a lava lake the rock underneath it.
     *
     * <p>{@link Heightmap.Types#OCEAN_FLOOR} carries {@code Usage.LIVE_WORLD}, so unlike the
     * worldgen-only heightmaps it is maintained on a running server and costs the same single O(1)
     * lookup as {@link #surfaceHeight}.
     */
    public static int landableSurfaceHeight(Level level, double x, double z) {
        return heightAt(level, Heightmap.Types.OCEAN_FLOOR, x, z);
    }

    /**
     * Whether the surface directly under a column is ground an aircraft can touch down on, as
     * opposed to the top of a body of water or lava.
     *
     * <p>The two heightmaps differ by exactly the fluid standing on top of the terrain, so comparing
     * them answers the question without a single block lookup. An unloaded column answers
     * <em>false</em> on purpose: everything that consults this is deciding whether to commit to a
     * touchdown, and "the server has not loaded that ground" must never be the answer that lets the
     * commitment be made.
     */
    public static boolean isLandable(Level level, double x, double z) {
        int clearance = surfaceHeight(level, x, z);
        return clearance != UNKNOWN_HEIGHT && clearance == landableSurfaceHeight(level, x, z);
    }

    private static int heightAt(Level level, Heightmap.Types type, double x, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        if (!level.hasChunkAt(blockX, blockZ)) {
            return UNKNOWN_HEIGHT;
        }
        int height = level.getHeight(type, blockX, blockZ);
        if (height <= level.getMinY()) {
            return UNKNOWN_HEIGHT;
        }
        return height;
    }

    /** Rebuilds the forward terrain profile along the current ground track. */
    public void scan(Level level, Vec3 position, double heading) {
        highestAhead = UNKNOWN_HEIGHT;
        distanceToHighest = Double.MAX_VALUE;
        valid = false;

        double step = (double) AutopilotConfig.SCAN_DISTANCE / AutopilotConfig.SCAN_SAMPLES;
        for (int i = 1; i <= AutopilotConfig.SCAN_SAMPLES; i++) {
            double distance = step * i;
            Vec3 probe = AutopilotMath.pointAlong(position, heading, distance);
            int height = surfaceHeight(level, probe.x, probe.z);
            if (height == UNKNOWN_HEIGHT) {
                continue;
            }
            valid = true;
            if (height > highestAhead) {
                highestAhead = height;
                distanceToHighest = distance;
            }
        }

        highestLeft = probeSector(level, position, heading - AutopilotConfig.SCAN_SIDE_ANGLE);
        highestRight = probeSector(level, position, heading + AutopilotConfig.SCAN_SIDE_ANGLE);
    }

    private static double probeSector(Level level, Vec3 position, double heading) {
        double highest = UNKNOWN_HEIGHT;
        for (int i = 1; i <= 4; i++) {
            double distance = AutopilotConfig.SCAN_DISTANCE * 0.25 * i;
            Vec3 probe = AutopilotMath.pointAlong(position, heading, distance);
            int height = surfaceHeight(level, probe.x, probe.z);
            if (height != UNKNOWN_HEIGHT && height > highest) {
                highest = height;
            }
        }
        return highest;
    }

    /** True when at least one forward sample hit loaded terrain. */
    public boolean hasData() {
        return valid;
    }

    public double highestAhead() {
        return highestAhead;
    }

    public double distanceToHighest() {
        return distanceToHighest;
    }

    /** Lowest altitude that still clears everything on the scanned track. */
    public double safeAltitude() {
        if (!valid) {
            return UNKNOWN_HEIGHT;
        }
        return highestAhead + AutopilotConfig.TERRAIN_CLEARANCE;
    }

    /**
     * Which way to sidestep a ridge: -1 to turn left, +1 to turn right, 0 to hold heading.
     * Only advises a turn when one side is meaningfully lower than the track ahead, so the aircraft
     * does not weave over flat ground.
     */
    public int avoidanceBias(double currentAltitude) {
        if (!valid || highestAhead == UNKNOWN_HEIGHT) {
            return 0;
        }
        // Only dodge if we genuinely cannot out-climb the ridge in the distance available.
        double deficit = highestAhead + AutopilotConfig.TERRAIN_CLEARANCE - currentAltitude;
        if (deficit <= 0) {
            return 0;
        }
        double climbAvailable = Math.tan(Math.toRadians(AutopilotConfig.MAX_CLIMB_ANGLE)) * distanceToHighest;
        if (climbAvailable > deficit * 1.5) {
            return 0;
        }
        boolean leftKnown = highestLeft != UNKNOWN_HEIGHT;
        boolean rightKnown = highestRight != UNKNOWN_HEIGHT;
        if (!leftKnown && !rightKnown) {
            return 0;
        }
        double left = leftKnown ? highestLeft : Double.MAX_VALUE;
        double right = rightKnown ? highestRight : Double.MAX_VALUE;
        // Require a real advantage before committing to a dodge.
        if (left < highestAhead - 4 && left <= right) {
            return -1;
        }
        if (right < highestAhead - 4) {
            return 1;
        }
        return 0;
    }

    /**
     * Precise line-of-sight check along the approach path, used to confirm the final approach
     * corridor really is clear before committing to a landing. This is a genuine voxel raycast
     * ({@link Level#clip(ClipContext)}), so unlike the heightmap it also catches overhangs.
     *
     * <p>Fluids count. The corridor used to be traced with {@link ClipContext.Fluid#NONE}, which is
     * the setting for "what would I bump into" — and water is the one thing on an approach that an
     * aircraft passes straight through and does not come out of. A sea standing above the glide
     * slope is as much of a reason to go around as a hillside is, and it is invisible to the
     * heightmap check as well, because the heightmap reports the waterline as though it were ground.
     */
    public static boolean pathClear(Level level, Entity entity, Vec3 from, Vec3 to) {
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, entity));
        return hit.getType() == HitResult.Type.MISS;
    }
}
