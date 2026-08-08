package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

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
public record Airfield(String name, BlockPos thresholdA, BlockPos thresholdB, int width,
                      List<BlockPos> parkingSpots, int approachObstaclesA, int approachObstaclesB) {

    /** Stored obstacle count meaning "never measured" — an airfield from before they were recorded. */
    public static final int OBSTACLES_UNKNOWN = -1;

    public static final Codec<Airfield> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.fieldOf("name").forGetter(Airfield::name),
        BlockPos.CODEC.fieldOf("threshold_a").forGetter(Airfield::thresholdA),
        BlockPos.CODEC.fieldOf("threshold_b").forGetter(Airfield::thresholdB),
        Codec.INT.fieldOf("width").forGetter(Airfield::width),
        // Optional with an empty default, so every airfield surveyed before parking spots existed
        // loads unchanged and simply falls back to the derived apron.
        BlockPos.CODEC.listOf().optionalFieldOf("parking", List.<BlockPos>of())
            .forGetter(Airfield::parkingSpots),
        // Likewise optional: an airfield stored before the counts were recorded loads with
        // OBSTACLES_UNKNOWN and bestEnd falls back to measuring them.
        Codec.INT.optionalFieldOf("obstacles_a", OBSTACLES_UNKNOWN).forGetter(Airfield::approachObstaclesA),
        Codec.INT.optionalFieldOf("obstacles_b", OBSTACLES_UNKNOWN).forGetter(Airfield::approachObstaclesB)
    ).apply(instance, Airfield::new));

    public Airfield {
        parkingSpots = List.copyOf(parkingSpots);
    }

    /** An airfield with no marked parking and no measured approaches. */
    public Airfield(String name, BlockPos thresholdA, BlockPos thresholdB, int width) {
        this(name, thresholdA, thresholdB, width, List.of(), OBSTACLES_UNKNOWN, OBSTACLES_UNKNOWN);
    }

    public Airfield withName(String newName) {
        return new Airfield(newName, thresholdA, thresholdB, width, parkingSpots,
            approachObstaclesA, approachObstaclesB);
    }

    public Airfield withParkingSpots(List<BlockPos> spots) {
        return new Airfield(name, thresholdA, thresholdB, width, spots,
            approachObstaclesA, approachObstaclesB);
    }

    /** True when the survey measured both approach funnels and the numbers can be trusted. */
    public boolean hasSurveyedApproaches() {
        return approachObstaclesA >= 0 && approachObstaclesB >= 0;
    }

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
     *
     * <p><b>The counts come from the survey, not from a fresh measurement.</b> This used to recount
     * both funnels every time, and it chose exactly wrongly. {@link #countApproachObstacles} reads
     * terrain through {@code TerrainScanner.surfaceHeight}, which returns
     * {@link TerrainScanner#UNKNOWN_HEIGHT} for a column in an unloaded chunk, and an unknown column
     * was skipped rather than counted — so an unloaded funnel scored <em>zero obstacles and won</em>.
     * {@code resolveLanding} runs while the aircraft is still hundreds of blocks out, when the far
     * end's approach is exactly the part of the world nobody has loaded, so the aircraft
     * systematically chose the end it could not see. Observed on the rig as arrivals onto end 18
     * against a survey that recorded 36 as preferred; on hilly ground it means choosing the end with
     * the hill in it, which is the reverse of what the function is for.
     *
     * <p>The survey ran with the chunks loaded — {@code /autopilot survey} insists on it — so its
     * numbers are the trustworthy ones, and they are persisted for precisely this. Airfields stored
     * before the counts were recorded fall back to measuring, and that fallback now treats an
     * unknown column as an obstacle rather than as clear sky: "not loaded" must never be the cheapest
     * answer.
     */
    public RunwayEnd bestEnd(Level level) {
        return bestEnd(level, null);
    }

    /**
     * As {@link #bestEnd(Level)}, but for an aircraft that is already somewhere: two ends with
     * equally clean funnels are no longer equal if one of them is behind the aircraft.
     *
     * <p>Obstacles still decide. {@link AutopilotConfig#APPROACH_OBSTACLE_COST} is 400 blocks of
     * track per flagged column, which no plausible overfly can outweigh — landing over a hill to
     * save a detour is exactly the trade this function exists to refuse. What the position does is
     * settle the case the old code settled arbitrarily: with both funnels clean it returned end A
     * regardless of where the aircraft was coming from, so an arrival from the wrong side flew the
     * length of the field, turned round and came back. Measured on the rig, that overfly is 400
     * blocks and about 40 seconds at approach speed.
     *
     * <p>The uphill preference survives as a tie-break rather than as a rule: it is worth
     * {@link AutopilotConfig#UPHILL_END_BONUS} blocks, which decides a level choice and never buys a
     * detour.
     *
     * @param from where the aircraft is now, or null to ask the question without one — which is what
     *             a departure does, since it is standing on the runway either way
     */
    public RunwayEnd bestEnd(Level level, @Nullable Vec3 from) {
        RunwayEnd a = endA();
        RunwayEnd b = endB();
        int obstaclesA;
        int obstaclesB;
        if (hasSurveyedApproaches()) {
            obstaclesA = approachObstaclesA;
            obstaclesB = approachObstaclesB;
        } else {
            obstaclesA = scoreApproach(level, a);
            obstaclesB = scoreApproach(level, b);
        }
        if (from == null) {
            if (obstaclesA != obstaclesB) {
                return obstaclesA < obstaclesB ? a : b;
            }
            // Equal obstacles: land towards the higher threshold (uphill).
            return pointB().y >= pointA().y ? a : b;
        }
        return arrivalCost(a, obstaclesA, from) <= arrivalCost(b, obstaclesB, from) ? a : b;
    }

    /** Track an arrival at {@code from} has to fly to land on this end, plus what its funnel costs. */
    private static double arrivalCost(RunwayEnd end, int obstacles, Vec3 from) {
        Vec3 fix = end.approachPoint(AutopilotConfig.FINAL_INTERCEPT_DISTANCE, 0);
        double track = AutopilotMath.horizontalDistance(from, fix) + AutopilotConfig.FINAL_INTERCEPT_DISTANCE;
        double uphill = end.farEnd().y > end.threshold().y ? AutopilotConfig.UPHILL_END_BONUS : 0;
        return track + obstacles * AutopilotConfig.APPROACH_OBSTACLE_COST - uphill;
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
     * Where an aircraft starts a departure, and which way it is facing there.
     *
     * @param onRunway true when the spot is on the surveyed strip itself rather than off to one side
     */
    public record ParkingSpot(Vec3 position, double heading, boolean onRunway) {}

    /**
     * Where an aircraft is parked before it taxis: beside the runway, clear of the strip, a little
     * way back from the departure threshold — but only if there is somewhere flat to put it.
     *
     * <p><b>Every candidate off the strip must pass the same elevation test.</b> This used to try
     * two aprons with a {@code ±2} block tolerance and then, if neither passed, take the ground
     * straight back from the threshold <em>with no check at all</em>. On a field where the ground
     * falls away off the end of the runway that put the aircraft in a hole: measured in a user's
     * world, a runway at elevation 69 with the ground 11 blocks off the end at 64 parked the
     * aircraft at y=64 and then asked it to taxi 4-5 blocks uphill onto the strip, which the ground
     * handling has no way to do. The unchecked fallback defeated the very check the branch above it
     * exists for.
     *
     * <p>The last resort is now the runway itself. The survey has already established that the
     * strip is flat and its elevation is known exactly, so it is the one placement that cannot be
     * wrong — and a runway departure starts from the threshold anyway. The spot sits inside
     * {@link AutopilotConfig#TAXI_LINEUP_RADIUS} of the threshold, so the taxi phase goes straight
     * to lining up instead of trying to roll backwards to a point behind it.
     */
    public static ParkingSpot parkingPosition(Level level, RunwayEnd departure) {
        double heading = departure.landingHeading();
        Vec3 threshold = departure.threshold();

        // A spot a player marked beats anything derived from probing the ground, because a human
        // looked at it. They are still re-checked here rather than trusted: the terrain may have
        // been dug out since, and which end of the strip is the departure end is decided per flight
        // by Airfield#bestEnd, so a spot that is rollable to one threshold need not be to the other.
        // Anything that fails simply drops through to the next spot and finally to the survey-time
        // heuristic below, so a marked apron can never strand an aircraft that would otherwise fly.
        ParkingSpot marked = markedParkingPosition(level, departure);
        if (marked != null) {
            return marked;
        }

        Vec3 behind = AutopilotMath.pointAlong(threshold, heading + 180.0,
            AutopilotConfig.PARKING_BEHIND_THRESHOLD);

        double sideways = departure.airfield().width() / 2.0 + AutopilotConfig.PARKING_LATERAL_OFFSET;
        for (double side : new double[] {90.0, -90.0}) {
            Vec3 apron = AutopilotMath.pointAlong(behind, heading + side, sideways);
            Vec3 spot = groundedIfLevelWith(level, apron, threshold.y);
            if (spot != null && taxiPathIsRollable(level, spot, threshold)) {
                return new ParkingSpot(spot, AutopilotMath.headingTo(spot, threshold), false);
            }
        }

        // Straight back from the threshold — now held to the same tolerance as the aprons.
        Vec3 straightBack = groundedIfLevelWith(level, behind, threshold.y);
        if (straightBack != null && taxiPathIsRollable(level, straightBack, threshold)) {
            return new ParkingSpot(straightBack, AutopilotMath.headingTo(straightBack, threshold), false);
        }

        // Nothing off the strip qualifies. Park on the strip, facing down it.
        Vec3 onRunway = AutopilotMath.pointAlong(threshold, heading, AutopilotConfig.PARKING_ON_RUNWAY_OFFSET);
        return new ParkingSpot(onRunway, heading, true);
    }

    /**
     * The first marked apron this departure can actually use, or null when the airfield has none
     * marked or none of them qualify right now.
     *
     * <p>"Qualify" is two questions, and they are different. <em>Usable</em> is about the ground —
     * still level with the runway, still rollable to this particular threshold — and a spot that
     * fails it is unusable for everyone. <em>Free</em> is about traffic: an aircraft already sitting
     * there. Spots are tried in the order they were marked, so the first one is the normal
     * departure position and the rest are where a queue forms behind it.
     */
    private static @Nullable ParkingSpot markedParkingPosition(Level level, RunwayEnd departure) {
        Airfield airfield = departure.airfield();
        Vec3 threshold = departure.threshold();
        ParkingSpot occupiedFallback = null;
        for (BlockPos spot : airfield.parkingSpots()) {
            Vec3 position = usableParkingSpot(level, spot, threshold);
            if (position == null) {
                continue;
            }
            ParkingSpot parking = new ParkingSpot(position, AutopilotMath.headingTo(position, threshold), false);
            if (isParkingSpotFree(level, position)) {
                return parking;
            }
            if (occupiedFallback == null) {
                occupiedFallback = parking;
            }
        }
        // Every marked spot is taken. Still better than the derived apron — the ground there is
        // known good, and two aircraft on one square is a problem for whatever clears them onto the
        // runway, not for the survey.
        return occupiedFallback;
    }

    /**
     * The marked spot {@code spot} as a usable parking position for a departure from
     * {@code threshold}, or null if the ground there or on the way no longer works.
     */
    private static @Nullable Vec3 usableParkingSpot(Level level, BlockPos spot, Vec3 threshold) {
        Vec3 probe = new Vec3(spot.getX() + 0.5, 0, spot.getZ() + 0.5);
        Vec3 position = groundedIfLevelWith(level, probe, threshold.y);
        if (position == null || !taxiPathIsRollable(level, position, threshold)) {
            return null;
        }
        return position;
    }

    /** True when no other aircraft is already standing on this apron. */
    public static boolean isParkingSpotFree(Level level, Vec3 position) {
        AABB box = AABB.ofSize(position, AutopilotConfig.PARKING_SPOT_CLEARANCE * 2,
            6.0, AutopilotConfig.PARKING_SPOT_CLEARANCE * 2);
        return level.getEntities(EntityTypeTest.forClass(PlaneEntity.class), box, plane -> true).isEmpty();
    }

    /**
     * Why {@code spot} cannot be a parking apron for {@code airfield}, or null when it can.
     *
     * <p>Marked spots are validated when they are marked rather than when they are used, so the
     * player who put one in the wrong place is told immediately instead of finding out three
     * minutes into a sortie. The four tests are exactly the four ways the ground handling gets
     * stuck: nothing there to stand on, a step up or down onto the strip, a ditch on the way, and a
     * spot so far from the runway that the straight-line taxi is a journey of its own.
     */
    public static @Nullable String parkingSpotProblem(Level level, Airfield airfield, BlockPos spot) {
        Vec3 probe = new Vec3(spot.getX() + 0.5, 0, spot.getZ() + 0.5);
        Vec3 nearest = AutopilotMath.horizontalDistance(probe, airfield.pointA())
            <= AutopilotMath.horizontalDistance(probe, airfield.pointB())
            ? airfield.pointA() : airfield.pointB();

        double distance = AutopilotMath.horizontalDistance(probe, nearest);
        if (distance > AutopilotConfig.PARKING_MAX_TAXI_DISTANCE) {
            return String.format("%.0f blocks from the nearest threshold; the taxi is a straight line,"
                + " so keep it within %.0f", distance, AutopilotConfig.PARKING_MAX_TAXI_DISTANCE);
        }
        int surface = TerrainScanner.surfaceHeight(level, probe.x, probe.z);
        if (surface == TerrainScanner.UNKNOWN_HEIGHT) {
            return "no ground there (the chunk is not loaded, or there is nothing to stand on)";
        }
        if (Math.abs(surface - nearest.y) > AutopilotConfig.PARKING_MAX_ELEVATION_DIFFERENCE) {
            return String.format("%.0f blocks off the runway elevation; an aircraft cannot taxi up or"
                + " down a step", Math.abs(surface - nearest.y));
        }
        Vec3 position = new Vec3(probe.x, surface, probe.z);
        if (!taxiPathIsRollable(level, position, nearest)) {
            return "the ground between it and the threshold is not level all the way";
        }
        for (BlockPos existing : airfield.parkingSpots()) {
            if (existing.distSqr(spot) < AutopilotConfig.PARKING_SPOT_CLEARANCE
                * AutopilotConfig.PARKING_SPOT_CLEARANCE) {
                return "there is already a parking spot at " + existing.toShortString();
            }
        }
        if (airfield.parkingSpots().size() >= AutopilotConfig.MAX_PARKING_SPOTS) {
            return airfield.name() + " already has the maximum of "
                + AutopilotConfig.MAX_PARKING_SPOTS + " parking spots";
        }
        return null;
    }

    /**
     * True when this spot sits on the surveyed strip itself. Not a reason to refuse it — parking on
     * the runway is what the fallback does when nothing beside it is level — but worth saying out
     * loud, because an aircraft waiting there is an aircraft standing on the landing area.
     */
    public boolean isOnStrip(BlockPos spot) {
        Vec3 probe = new Vec3(spot.getX() + 0.5, 0, spot.getZ() + 0.5);
        double heading = AutopilotMath.headingTo(pointA(), pointB());
        double along = AutopilotMath.alongTrack(pointA(), heading, probe);
        return along >= 0 && along <= length()
            && Math.abs(AutopilotMath.lateralOffset(pointA(), heading, probe)) <= width() / 2.0;
    }

    /**
     * The surface at {@code probe} as a parking position, or null when it is unknown or not level
     * with the runway. {@code TerrainScanner.surfaceHeight} reports the first free block, which is
     * the same convention {@link #pointA()} uses for a threshold, so the two are directly comparable.
     */
    private static @Nullable Vec3 groundedIfLevelWith(Level level, Vec3 probe, double runwayElevation) {
        int surface = TerrainScanner.surfaceHeight(level, probe.x, probe.z);
        if (surface == TerrainScanner.UNKNOWN_HEIGHT
            || Math.abs(surface - runwayElevation) > AutopilotConfig.PARKING_MAX_ELEVATION_DIFFERENCE) {
            return null;
        }
        return new Vec3(probe.x, surface, probe.z);
    }

    /**
     * Whether the aircraft can actually roll from a parking spot to the threshold.
     *
     * <p>A level parking spot is not enough on its own: the taxi is a straight line to the lineup
     * point with no obstacle avoidance and no ability to climb, so a spot that is level with the
     * runway but separated from it by a ditch or a step is just as unusable as one in a hole. Every
     * few blocks along that line has to be level with the runway too.
     */
    private static boolean taxiPathIsRollable(Level level, Vec3 from, Vec3 threshold) {
        double distance = AutopilotMath.horizontalDistance(from, threshold);
        int steps = (int) Math.ceil(distance / AutopilotConfig.TAXI_PATH_SAMPLE_STEP);
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            Vec3 probe = new Vec3(from.x + (threshold.x - from.x) * t, 0, from.z + (threshold.z - from.z) * t);
            if (groundedIfLevelWith(level, probe, threshold.y) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Ranking score for one approach funnel when there is no surveyed count to use: obstacles seen,
     * plus every column that could not be seen at all.
     *
     * <p>Separate from {@link #countApproachObstacles} because the two answer different questions.
     * The report answers "what did we find", and saying "20 obstacles" about ground nobody has
     * loaded would be a lie. This answers "which end would I rather commit to", and there the only
     * safe reading of an unknown column is that it might be a hill.
     */
    private static int scoreApproach(Level level, RunwayEnd end) {
        int score = 0;
        for (int distance = AutopilotConfig.SURVEY_APPROACH_STEP;
             distance <= AutopilotConfig.SURVEY_APPROACH_LENGTH;
             distance += AutopilotConfig.SURVEY_APPROACH_STEP) {
            double allowed = Math.max(
                end.glideSlopeAltitude(distance) - AutopilotConfig.SURVEY_OBSTACLE_MARGIN,
                end.elevation());
            FunnelCell cell = funnelCell(level, end, distance);
            if (cell.anyUnknown() || (cell.known() && cell.highest() > allowed)) {
                score++;
            }
        }
        return score;
    }

    /**
     * The terrain found in one 10-block segment of an approach funnel.
     *
     * @param highest    the highest surface of every column that could be read, or
     *                   {@link TerrainScanner#UNKNOWN_HEIGHT} when none of them could
     * @param anyUnknown whether at least one column was in an unloaded chunk. Kept separate from
     *                   {@code highest} because the report and the ranking need opposite answers:
     *                   {@link #countApproachObstacles} must not claim an obstacle it did not see,
     *                   and {@link #scoreApproach} must not treat ground nobody has loaded as clear.
     */
    private record FunnelCell(int highest, boolean anyUnknown) {
        boolean known() {
            return highest != TerrainScanner.UNKNOWN_HEIGHT;
        }
    }

    /**
     * Samples one station of an approach funnel as a patch of ground rather than as a single column.
     *
     * <p>This is the whole of the "bamboo is not treated as an obstacle" fix, and it is not about
     * bamboo. The funnel used to be one heightmap column every {@value AutopilotConfig#SURVEY_APPROACH_STEP}
     * blocks along the extended centreline — 20 points, and nothing else in a corridor 200 blocks
     * long and as wide as the runway. Two things were therefore invisible, and both were measured on
     * the rig with a 20-block-tall obstruction in the funnel of a 160-block field:
     *
     * <ul>
     *   <li><b>Anything narrower than the step.</b> A wall 5 blocks deep sitting between two
     *       stations counted 0. The same wall moved 5 blocks so that a station landed on it counted
     *       1. Bamboo and stone behaved identically, which is the point: this was never a vegetation
     *       bug. Bamboo only made it visible because bamboo grows in narrow clumps.</li>
     *   <li><b>Anything beside the centreline.</b> A clump 4 to 8 blocks to one side of a 25-wide
     *       field's centreline, directly over a station, counted 0 — while the landing gates let the
     *       aircraft be a full runway width off that line.</li>
     * </ul>
     *
     * <p>The cell keeps the reported number on its old scale — still 20 stations, still "n of 20" —
     * so it stays comparable with the counts already persisted on airfields surveyed before this,
     * and it can only ever go up, which is the safe direction. Cost is
     * {@value AutopilotConfig#SURVEY_APPROACH_SUBSTEPS} x {@value AutopilotConfig#SURVEY_APPROACH_LATERAL_SAMPLES}
     * = 25 heightmap lookups per station, 500 per funnel. That is paid at survey time and once per
     * arrival for an airfield old enough to have no stored counts; nothing here runs per tick.
     */
    private static FunnelCell funnelCell(Level level, RunwayEnd end, double distance) {
        double heading = end.landingHeading();
        double halfWidth = Math.max(AutopilotConfig.SURVEY_FUNNEL_MIN_HALF_WIDTH,
            end.airfield().width() / 2.0);
        int highest = TerrainScanner.UNKNOWN_HEIGHT;
        boolean anyUnknown = false;
        for (int step = 0; step < AutopilotConfig.SURVEY_APPROACH_SUBSTEPS; step++) {
            double along = distance - (double) AutopilotConfig.SURVEY_APPROACH_STEP
                * step / AutopilotConfig.SURVEY_APPROACH_SUBSTEPS;
            Vec3 centre = AutopilotMath.pointAlong(end.threshold(), heading + 180.0, along);
            for (int lane = 0; lane < AutopilotConfig.SURVEY_APPROACH_LATERAL_SAMPLES; lane++) {
                double across = halfWidth * (2.0 * lane
                    / (AutopilotConfig.SURVEY_APPROACH_LATERAL_SAMPLES - 1) - 1.0);
                Vec3 probe = AutopilotMath.pointAlong(centre, heading + 90.0, across);
                int terrain = TerrainScanner.surfaceHeight(level, probe.x, probe.z);
                if (terrain == TerrainScanner.UNKNOWN_HEIGHT) {
                    anyUnknown = true;
                } else if (highest == TerrainScanner.UNKNOWN_HEIGHT || terrain > highest) {
                    highest = terrain;
                }
            }
        }
        return new FunnelCell(highest, anyUnknown);
    }

    /**
     * Counts the 10-block segments of one end's approach funnel that have something in them poking
     * above the glide slope. Each segment is sampled as a patch of ground, not as a single column —
     * see {@link #funnelCell}. Uses the heightmap, so it is O(1) per sample and never forces a
     * chunk load.
     *
     * <p>Columns in unloaded chunks are not counted, because they were not measured. That makes this
     * an honest report and a dangerous ranking — see {@link #scoreApproach} and {@link #bestEnd}.
     */
    public static int countApproachObstacles(Level level, RunwayEnd end) {
        int violations = 0;
        for (int distance = AutopilotConfig.SURVEY_APPROACH_STEP;
             distance <= AutopilotConfig.SURVEY_APPROACH_LENGTH;
             distance += AutopilotConfig.SURVEY_APPROACH_STEP) {
            // Never allow less clearance than the runway's own elevation. The margin is subtracted
            // from a slope that starts at the threshold, so within the first couple of samples it
            // asks for headroom *below* the ground the runway is built on: on a perfectly flat
            // superflat test world every airfield reported "approach obstacles 2" at both ends, from
            // the 10- and 20-block samples, with nothing there at all. Ground at runway level is the
            // runway, not an obstacle.
            double allowed = Math.max(
                end.glideSlopeAltitude(distance) - AutopilotConfig.SURVEY_OBSTACLE_MARGIN,
                end.elevation());
            FunnelCell cell = funnelCell(level, end, distance);
            if (cell.known() && cell.highest() > allowed) {
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
     * Surveys a runway from two clicked points on its two ends. The width is measured outwards from
     * the centreline: the runway is considered to continue sideways for as long as the surface stays
     * within one block of the centreline elevation.
     *
     * <p><b>The clicked points are not taken as the centreline.</b> They used to be, and that is the
     * whole of the "the aircraft takes off from the exact block I right-clicked and lands on it"
     * report: a player marking a strip clicks something they can see and stand on, which is an edge
     * or a corner, and every number the arrival is flown to — the lineup, the aim point, the glide
     * slope, the lateral offset, the landing gates — hangs off the threshold. Measured on the rig
     * with both ends clicked on the left edge of a 13-wide strip, the whole take-off roll and the
     * touchdown were at x = -5.5 against a strip running from -6.0 to 7.0: 6 blocks off the middle,
     * with the outboard wing over the drop-off, and the aircraft tracking its centreline perfectly
     * the whole way (lat = -0.2). See {@link #centreOnStrip}.
     */
    public static Airfield survey(Level level, String name, BlockPos clickedA, BlockPos clickedB) {
        BlockPos[] thresholds = centreOnStrip(level,
            snapToSurface(level, clickedA), snapToSurface(level, clickedB));
        BlockPos a = thresholds[0];
        BlockPos b = thresholds[1];
        int width = measureWidth(level, a, b);
        // Both approach funnels are counted here, while the chunks are loaded, and stored. This is
        // the only moment the numbers can be trusted: a survey requires a loaded position, whereas
        // an arriving aircraft asks the question from hundreds of blocks away. See bestEnd.
        Airfield airfield = new Airfield(name, a, b, width);
        return new Airfield(name, a, b, width, List.of(),
            countApproachObstacles(level, airfield.endA()),
            countApproachObstacles(level, airfield.endB()));
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

    /**
     * How far the strip reaches to either side of one point, in whole blocks.
     *
     * @param left  blocks of strip found to the left of the probed point
     * @param right blocks of strip found to its right
     */
    private record CrossSection(int left, int right) {
        /** How far the probed point is to the right of the middle of what was found. */
        double offsetFromMiddle() {
            return (right - left) / 2.0;
        }

        int width() {
            return left + right + 1;
        }
    }

    /**
     * Walks sideways from {@code point}, perpendicular to {@code heading}, stopping at the first
     * column more than a block off {@code reference}.
     *
     * @param limit how far to probe on each side. {@link #measureWidth} uses half
     *              {@link AutopilotConfig#SURVEY_MAX_WIDTH}, because it probes from the middle;
     *              {@link #centreOnStrip} uses the whole of it, because it probes from wherever the
     *              player clicked and that may be one full width away from the far edge.
     */
    private static CrossSection crossSection(Level level, Vec3 point, double heading,
                                             double reference, int limit) {
        int left = 0;
        int right = 0;
        for (int offset = 1; offset <= limit; offset++) {
            if (!levelWith(level, AutopilotMath.pointAlong(point, heading + 90.0, offset), reference)) {
                break;
            }
            right = offset;
        }
        for (int offset = 1; offset <= limit; offset++) {
            if (!levelWith(level, AutopilotMath.pointAlong(point, heading - 90.0, offset), reference)) {
                break;
            }
            left = offset;
        }
        return new CrossSection(left, right);
    }

    /**
     * Moves two clicked end points sideways onto the middle of the strip they are standing on, and
     * returns them as thresholds.
     *
     * <p><b>Each end is centred on its own cross-section, so the clicked heading is a starting guess
     * rather than the answer.</b> The obvious alternative — shift both ends by one common amount, so
     * that the direction the player indicated is preserved exactly — was tried first and is wrong on
     * the case that matters most. Clicking a corner is the normal thing to do, and the two corners
     * that are easiest to reach are usually on opposite sides of the strip; a common shift averages
     * those two offsets to about zero and leaves the centreline running diagonally across the runway,
     * which is exactly the arrival the report complains about. Centring the ends independently turns
     * the same two clicks into the true axis. Measured on a 160x13 strip with the near-left and
     * far-right corners clicked: the clicked heading is 4.3 degrees off the strip, the common shift
     * leaves it there, and independent centring produces 000/180 with both thresholds on the middle.
     *
     * <p>The cost of independence is that the survey may return a slightly different heading from
     * the one clicked, and therefore different designators. That is a correction, not a surprise —
     * the strip's own edges are better evidence of which way it runs than two clicks are.
     *
     * <p><b>Ground the survey cannot tell from the strip is left alone.</b> The cross-section stops
     * at the first column more than a block off the threshold elevation, so on a runway that is
     * flush with the field around it — a mown strip on flat grass, or anything on the superflat test
     * world — both probes run to the limit, the offset comes out zero and the clicked line is kept
     * unchanged. Nothing here invents a centreline out of ground that has no edges.
     */
    private static BlockPos[] centreOnStrip(Level level, BlockPos clickedA, BlockPos clickedB) {
        BlockPos a = clickedA;
        BlockPos b = clickedB;
        for (int pass = 0; pass < AutopilotConfig.SURVEY_CENTRING_PASSES; pass++) {
            double heading = AutopilotMath.headingTo(surfacePoint(a), surfacePoint(b));
            BlockPos movedA = centreEnd(level, a, heading);
            BlockPos movedB = centreEnd(level, b, heading);
            if (movedA.equals(a) && movedB.equals(b)) {
                break;
            }
            a = movedA;
            b = movedB;
        }
        return new BlockPos[] {a, b};
    }

    /** One end moved onto the middle of its own cross-section, re-snapped to the surface there. */
    private static BlockPos centreEnd(Level level, BlockPos end, double heading) {
        Vec3 point = surfacePoint(end);
        CrossSection section = crossSection(level, point, heading, point.y,
            AutopilotConfig.SURVEY_MAX_WIDTH);
        int offset = (int) Math.round(section.offsetFromMiddle());
        if (offset == 0) {
            return end;
        }
        Vec3 moved = AutopilotMath.pointAlong(point, heading + 90.0, offset);
        return snapToSurface(level,
            new BlockPos((int) Math.floor(moved.x), end.getY(), (int) Math.floor(moved.z)));
    }

    /** The point an aircraft touches at a threshold block: the centre of its top face. */
    private static Vec3 surfacePoint(BlockPos threshold) {
        return new Vec3(threshold.getX() + 0.5, threshold.getY() + 1.0, threshold.getZ() + 0.5);
    }

    /**
     * How far the stored centreline of this airfield lies from the middle of the strip underneath
     * it, in blocks — 0 on a runway surveyed since the survey started centring, and up to half the
     * runway width on one surveyed before it. Measures live terrain, so it is only meaningful with
     * the runway's chunks loaded and it is deliberately not stored.
     */
    public double centrelineOffset(Level level) {
        double heading = AutopilotMath.headingTo(pointA(), pointB());
        double offsetA = crossSection(level, pointA(), heading, pointA().y,
            AutopilotConfig.SURVEY_MAX_WIDTH).offsetFromMiddle();
        double offsetB = crossSection(level, pointB(), heading, pointB().y,
            AutopilotConfig.SURVEY_MAX_WIDTH).offsetFromMiddle();
        return Math.max(Math.abs(offsetA), Math.abs(offsetB));
    }

    private static int measureWidth(Level level, BlockPos a, BlockPos b) {
        Vec3 centreA = surfacePoint(a);
        Vec3 centreB = surfacePoint(b);
        double heading = AutopilotMath.headingTo(centreA, centreB);
        Vec3 middle = new Vec3((centreA.x + centreB.x) * 0.5, (centreA.y + centreB.y) * 0.5, (centreA.z + centreB.z) * 0.5);
        // Half the maximum on each side, because this probes from the middle of a centreline that
        // centreOnStrip has already put there. Before that it probed from wherever the player
        // clicked, which is why an edge click on a 25-wide strip used to report a width of 13: one
        // side found nothing and the other hit the limit halfway across.
        return Math.max(3, crossSection(level, middle, heading, middle.y,
            AutopilotConfig.SURVEY_MAX_WIDTH / 2).width());
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
