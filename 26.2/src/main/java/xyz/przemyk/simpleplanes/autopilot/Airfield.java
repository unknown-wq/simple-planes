package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * A surveyed runway: two thresholds on the centreline plus a measured width. Everything else
 * (heading, length, slope, designators) is derived, so a stored airfield stays consistent even if
 * the constants change.
 *
 * <p>Both thresholds are stored at the surface block the aircraft should touch, so
 * {@code threshold.y} is the runway elevation at that end.
 */
public record Airfield(String name, BlockPos thresholdA, BlockPos thresholdB, int width) {

    public static final Codec<Airfield> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.fieldOf("name").forGetter(Airfield::name),
        BlockPos.CODEC.fieldOf("threshold_a").forGetter(Airfield::thresholdA),
        BlockPos.CODEC.fieldOf("threshold_b").forGetter(Airfield::thresholdB),
        Codec.INT.fieldOf("width").forGetter(Airfield::width)
    ).apply(instance, Airfield::new));

    public Vec3 pointA() {
        return new Vec3(thresholdA.getX() + 0.5, thresholdA.getY() + 1.0, thresholdA.getZ() + 0.5);
    }

    public Vec3 pointB() {
        return new Vec3(thresholdB.getX() + 0.5, thresholdB.getY() + 1.0, thresholdB.getZ() + 0.5);
    }

    /** Landing direction that touches down at threshold A and rolls out towards B. */
    public RunwayEnd endA() {
        return new RunwayEnd(this, pointA(), pointB());
    }

    /** Landing direction that touches down at threshold B and rolls out towards A. */
    public RunwayEnd endB() {
        return new RunwayEnd(this, pointB(), pointA());
    }

    public List<RunwayEnd> ends() {
        List<RunwayEnd> ends = new ArrayList<>(2);
        ends.add(endA());
        ends.add(endB());
        return ends;
    }

    public double length() {
        return AutopilotMath.horizontalDistance(pointA(), pointB());
    }

    /** Runway slope in degrees, positive means uphill from A to B. */
    public double slopeDegrees() {
        double run = length();
        if (run < 1.0E-3) {
            return 0;
        }
        return Math.toDegrees(Math.atan2(pointB().y - pointA().y, run));
    }

    public Vec3 centre() {
        Vec3 a = pointA();
        Vec3 b = pointB();
        return new Vec3((a.x + b.x) * 0.5, Math.max(a.y, b.y), (a.z + b.z) * 0.5);
    }

    /** Designator pair as displayed, e.g. "09/27". */
    public String designators() {
        return endA().designator() + "/" + endB().designator();
    }

    /**
     * Picks the landing direction with the cleaner approach funnel. Ties are broken towards the
     * downhill-to-uphill direction, because landing uphill shortens the roll-out.
     */
    public RunwayEnd bestEnd(Level level) {
        RunwayEnd a = endA();
        RunwayEnd b = endB();
        int obstaclesA = countApproachObstacles(level, a);
        int obstaclesB = countApproachObstacles(level, b);
        if (obstaclesA != obstaclesB) {
            return obstaclesA < obstaclesB ? a : b;
        }
        // Equal obstacles: land towards the higher threshold (uphill).
        return pointB().y >= pointA().y ? a : b;
    }

    /**
     * The runway end a departure should roll <em>from</em>.
     *
     * <p>{@link #bestEnd} answers "which way should an aircraft land", i.e. which threshold to cross
     * on the way in. A departure wants the opposite: it starts at that threshold and rolls away from
     * it, down the same strip, so it climbs out over the cleaner funnel. Returning
     * {@code bestEnd} directly is exactly right — a {@link RunwayEnd} is "threshold plus the far end
     * to run towards", which is the same geometry for a take-off roll as for a roll-out.
     */
    public static RunwayEnd departureEnd(Level level, Airfield airfield) {
        return airfield.bestEnd(level);
    }

    /**
     * Where an aircraft is parked before it taxis: beside the runway, clear of the strip, a little
     * way back from the departure threshold.
     *
     * <p>Falls back to the centreline behind the threshold when the ground alongside is not level
     * with the runway — an apron is only sensible if there is somewhere flat to put it, and the
     * surveyed strip itself is the one piece of ground known to be flat.
     */
    public static Vec3 parkingPosition(Level level, RunwayEnd departure) {
        double heading = departure.landingHeading();
        Vec3 threshold = departure.threshold();
        Vec3 behind = AutopilotMath.pointAlong(threshold, heading + 180.0,
            AutopilotConfig.PARKING_BEHIND_THRESHOLD);

        double sideways = departure.airfield().width() / 2.0 + AutopilotConfig.PARKING_LATERAL_OFFSET;
        for (double side : new double[] {90.0, -90.0}) {
            Vec3 apron = AutopilotMath.pointAlong(behind, heading + side, sideways);
            int surface = TerrainScanner.surfaceHeight(level, apron.x, apron.z);
            if (surface != TerrainScanner.UNKNOWN_HEIGHT && Math.abs(surface - threshold.y) <= 2.0) {
                return new Vec3(apron.x, surface, apron.z);
            }
        }

        int surface = TerrainScanner.surfaceHeight(level, behind.x, behind.z);
        double y = surface == TerrainScanner.UNKNOWN_HEIGHT ? threshold.y : surface;
        return new Vec3(behind.x, y, behind.z);
    }

    /**
     * Counts terrain columns that poke above the glide slope in the approach funnel of one end.
     * Uses the heightmap, so it is O(1) per sample and never forces a chunk load.
     */
    public static int countApproachObstacles(Level level, RunwayEnd end) {
        int violations = 0;
        double heading = end.landingHeading();
        Vec3 threshold = end.threshold();
        for (int distance = AutopilotConfig.SURVEY_APPROACH_STEP;
             distance <= AutopilotConfig.SURVEY_APPROACH_LENGTH;
             distance += AutopilotConfig.SURVEY_APPROACH_STEP) {
            Vec3 probe = AutopilotMath.pointAlong(threshold, heading + 180.0, distance);
            double allowed = end.glideSlopeAltitude(distance) - AutopilotConfig.SURVEY_OBSTACLE_MARGIN;
            int terrain = TerrainScanner.surfaceHeight(level, probe.x, probe.z);
            if (terrain != TerrainScanner.UNKNOWN_HEIGHT && terrain > allowed) {
                violations++;
            }
        }
        return violations;
    }

    /**
     * Standard deviation of the surface height along the centreline — a simple "is this actually
     * flat enough to land on" number. Reported by the survey tool, not used for guidance.
     */
    public double roughness(Level level) {
        int samples = Math.max(2, (int) (length() / 4));
        samples = Math.min(samples, 64);
        double heading = AutopilotMath.headingTo(pointA(), pointB());
        double step = length() / samples;
        List<Integer> heights = new ArrayList<>(samples + 1);
        for (int i = 0; i <= samples; i++) {
            Vec3 probe = AutopilotMath.pointAlong(pointA(), heading, step * i);
            int height = TerrainScanner.surfaceHeight(level, probe.x, probe.z);
            if (height != TerrainScanner.UNKNOWN_HEIGHT) {
                heights.add(height);
            }
        }
        if (heights.size() < 2) {
            return 0;
        }
        double mean = 0;
        for (int height : heights) {
            mean += height;
        }
        mean /= heights.size();
        double variance = 0;
        for (int height : heights) {
            double d = height - mean;
            variance += d * d;
        }
        return Math.sqrt(variance / heights.size());
    }

    /**
     * Surveys a runway from two clicked centreline points. The width is measured outwards from the
     * centreline: the runway is considered to continue sideways for as long as the surface stays
     * within one block of the centreline elevation.
     */
    public static Airfield survey(Level level, String name, BlockPos clickedA, BlockPos clickedB) {
        BlockPos a = snapToSurface(level, clickedA);
        BlockPos b = snapToSurface(level, clickedB);
        int width = measureWidth(level, a, b);
        return new Airfield(name, a, b, width);
    }

    /** Moves a clicked position onto the terrain surface, so a click on a wall still works. */
    private static BlockPos snapToSurface(Level level, BlockPos pos) {
        int surface = TerrainScanner.surfaceHeight(level, pos.getX() + 0.5, pos.getZ() + 0.5);
        if (surface == TerrainScanner.UNKNOWN_HEIGHT) {
            return pos;
        }
        // surfaceHeight is the first free block; the runway surface is the block below it.
        return new BlockPos(pos.getX(), surface - 1, pos.getZ());
    }

    private static int measureWidth(Level level, BlockPos a, BlockPos b) {
        Vec3 centreA = new Vec3(a.getX() + 0.5, a.getY() + 1.0, a.getZ() + 0.5);
        Vec3 centreB = new Vec3(b.getX() + 0.5, b.getY() + 1.0, b.getZ() + 0.5);
        double heading = AutopilotMath.headingTo(centreA, centreB);
        Vec3 middle = new Vec3((centreA.x + centreB.x) * 0.5, (centreA.y + centreB.y) * 0.5, (centreA.z + centreB.z) * 0.5);
        double reference = middle.y;

        int left = 0;
        int right = 0;
        for (int offset = 1; offset <= AutopilotConfig.SURVEY_MAX_WIDTH / 2; offset++) {
            Vec3 probe = AutopilotMath.pointAlong(middle, heading + 90.0, offset);
            if (levelWith(level, probe, reference)) {
                right = offset;
            } else {
                break;
            }
        }
        for (int offset = 1; offset <= AutopilotConfig.SURVEY_MAX_WIDTH / 2; offset++) {
            Vec3 probe = AutopilotMath.pointAlong(middle, heading - 90.0, offset);
            if (levelWith(level, probe, reference)) {
                left = offset;
            } else {
                break;
            }
        }
        return Math.max(3, left + right + 1);
    }

    private static boolean levelWith(Level level, Vec3 probe, double reference) {
        int height = TerrainScanner.surfaceHeight(level, probe.x, probe.z);
        return height != TerrainScanner.UNKNOWN_HEIGHT && Math.abs(height - reference) <= 1.0;
    }

    /**
     * Builds a throwaway landing strip from the terrain at {@code around}, used when a route has no
     * registered airfield to land at. The heading is given, the length is fixed, and the thresholds
     * simply follow the terrain — it is a field landing, not a real runway.
     */
    public static Airfield improvise(Level level, String name, Vec3 around, double heading, int length) {
        Vec3 start = AutopilotMath.pointAlong(around, heading + 180.0, length / 2.0);
        Vec3 end = AutopilotMath.pointAlong(around, heading, length / 2.0);
        BlockPos a = new BlockPos((int) Math.floor(start.x), 0, (int) Math.floor(start.z));
        BlockPos b = new BlockPos((int) Math.floor(end.x), 0, (int) Math.floor(end.z));
        return new Airfield(name, snapToSurface(level, a), snapToSurface(level, b), 8);
    }

    /**
     * Chooses the heading whose terrain is flattest around a point — a cheap "where could I put a
     * strip here" search over 12 candidate directions.
     */
    public static double flattestHeading(Level level, Vec3 around, int length) {
        double bestHeading = 0;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < 12; i++) {
            double heading = i * 30.0;
            double score = 0;
            int samples = 0;
            int previous = TerrainScanner.UNKNOWN_HEIGHT;
            for (int distance = -length / 2; distance <= length / 2; distance += 5) {
                Vec3 probe = AutopilotMath.pointAlong(around, heading, distance);
                int height = TerrainScanner.surfaceHeight(level, probe.x, probe.z);
                if (height == TerrainScanner.UNKNOWN_HEIGHT) {
                    continue;
                }
                if (previous != TerrainScanner.UNKNOWN_HEIGHT) {
                    score += Math.abs(height - previous);
                    samples++;
                }
                previous = height;
            }
            if (samples > 0) {
                score /= samples;
                if (score < bestScore) {
                    bestScore = score;
                    bestHeading = heading;
                }
            }
        }
        return bestHeading;
    }

    /** Heightmap type used for every runway/terrain measurement. */
    public static Heightmap.Types heightmapType() {
        return Heightmap.Types.MOTION_BLOCKING;
    }
}
