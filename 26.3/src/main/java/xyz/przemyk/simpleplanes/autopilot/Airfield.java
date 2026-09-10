package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * A surveyed runway: two thresholds on the centreline plus the strip's width. Everything else
 * (heading, length, slope, designators) is derived, so a stored airfield stays consistent even if
 * the constants change.
 *
 * <p>A survey run by this build takes all of that from the box the player marked out — see
 * {@link #footprint} — so its thresholds lie on a world axis. An airfield saved by an earlier build
 * may be diagonal; it loads and flies exactly as saved. See {@link #isAxisAligned()}.
 *
 * <p>Both thresholds are stored at the surface block the aircraft should touch, so
 * {@code threshold.y} is the runway elevation at that end.
 */
public record Airfield(String name, BlockPos thresholdA, BlockPos thresholdB, int width,
                      List<BlockPos> parkingSpots, int approachObstaclesA, int approachObstaclesB,
                      boolean requiresStands) {

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
        Codec.INT.optionalFieldOf("obstacles_b", OBSTACLES_UNKNOWN).forGetter(Airfield::approachObstaclesB),
        // Whether this airfield is held to the rule that a runway is not finished until a stand is
        // marked beside it. Optional and false by default, and that default is the entire reason
        // this is a stored flag rather than "parkingSpots.isEmpty()": a field surveyed before the
        // rule existed also has no marked stand, and it has to go on working exactly as it did.
        // Nothing already on disk is reinterpreted — an absent key means "grandfathered", which is
        // what every saved airfield is. Only a survey run by this build writes true.
        Codec.BOOL.optionalFieldOf("requires_stands", false).forGetter(Airfield::requiresStands)
    ).apply(instance, Airfield::new));

    public Airfield {
        parkingSpots = List.copyOf(parkingSpots);
    }

    /** An airfield with no marked parking, no measured approaches and no stand requirement. */
    public Airfield(String name, BlockPos thresholdA, BlockPos thresholdB, int width) {
        this(name, thresholdA, thresholdB, width, List.of(), OBSTACLES_UNKNOWN, OBSTACLES_UNKNOWN, false);
    }

    public Airfield withName(String newName) {
        return new Airfield(newName, thresholdA, thresholdB, width, parkingSpots,
            approachObstaclesA, approachObstaclesB, requiresStands);
    }

    public Airfield withParkingSpots(List<BlockPos> spots) {
        return new Airfield(name, thresholdA, thresholdB, width, spots,
            approachObstaclesA, approachObstaclesB, requiresStands);
    }

    public Airfield withRequiredStands(boolean required) {
        return new Airfield(name, thresholdA, thresholdB, width, parkingSpots,
            approachObstaclesA, approachObstaclesB, required);
    }

    /**
     * True when this airfield is registered but its apron has not been marked yet — the state the
     * survey now calls unfinished. Grandfathered airfields are never in it, whatever their parking
     * list looks like.
     */
    public boolean standsMissing() {
        return requiresStands && parkingSpots.isEmpty();
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
        int obstaclesA = approachObstacles(level, a);
        int obstaclesB = approachObstacles(level, b);
        if (from == null) {
            if (obstaclesA != obstaclesB) {
                return obstaclesA < obstaclesB ? a : b;
            }
            // Equal obstacles: land towards the higher threshold (uphill).
            return pointB().y >= pointA().y ? a : b;
        }
        return arrivalCost(a, obstaclesA, from) <= arrivalCost(b, obstaclesB, from) ? a : b;
    }

    /**
     * Columns poking through the approach funnel of one end: the surveyed count where there is one,
     * and a live measurement otherwise.
     *
     * <p>Split out of {@link #bestEnd} because a departure needs the same number about the
     * <em>opposite</em> end — the funnel it climbs out over — and there was no way to ask for it.
     * See {@link DeparturePlan}.
     */
    public int approachObstacles(Level level, RunwayEnd end) {
        if (!hasSurveyedApproaches()) {
            return scoreApproach(level, end);
        }
        // Which of the two stored counts this is, by which of the two stored thresholds the end
        // crosses. Nearest rather than equal: a RunwayEnd is a value, callers build their own with
        // opposite(), and a count silently attributed to the wrong end of the strip is the exact
        // failure this whole area has already had once.
        int surveyed = end.threshold().distanceToSqr(pointA()) <= end.threshold().distanceToSqr(pointB())
            ? approachObstaclesA : approachObstaclesB;
        // ...and whatever has appeared since. A survey is a photograph: it is trustworthy about the
        // moment it was taken and says nothing about a hill that was built, or a chunk that was
        // generated, afterwards. Taking the larger of the two keeps the survey as the floor — which
        // is what stops an unloaded funnel scoring zero and winning — while letting an obstacle the
        // aircraft can now actually see be counted. Unknown columns are skipped in the live count
        // for exactly that reason: the surveyed number already speaks for them.
        return Math.max(surveyed, countApproachObstacles(level, end));
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
     * <p>This used to be {@code airfield.bestEnd(level)}, and that was the wrong question asked of
     * the wrong data. {@link #bestEnd} scores each end by its own <em>approach</em> funnel — the
     * ground before its threshold — because that is what an arrival flies through. A departure that
     * rolls from that threshold runs the other way down the strip and climbs out past the far one,
     * over the opposite end's funnel: the one {@code bestEnd} had just rejected. On a field with a
     * hill off one end, the aircraft landed away from the hill and departed straight at it.
     *
     * <p>{@link DeparturePlan} asks it properly, and with the one input the old call did not have —
     * where the flight is going. See that class for the score.
     */
    public static RunwayEnd departureEnd(Level level, Airfield airfield) {
        return departureEnd(level, airfield, null);
    }

    /**
     * As {@link #departureEnd(Level, Airfield)}, for a flight that knows where it is going.
     *
     * <p>Called from two places that must not disagree — the spawner, which puts the aircraft on a
     * parking spot beside one threshold, and the flight director, which then taxis to it. Both terms
     * of {@link DeparturePlan}'s score favour the same end (the one nearer the destination is also
     * the one with the smaller turn onto course), so the airframe's turn rate can change the turn
     * the plan <em>reports</em> but not the end it picks.
     */
    public static RunwayEnd departureEnd(Level level, Airfield airfield, @Nullable Vec3 destination) {
        return DeparturePlan.decide(level, airfield, destination, 1.0).end();
    }

    /**
     * Where an aircraft stands: the position, which way it faces there, and the marked block it came
     * from if a human put it there.
     *
     * @param onRunway true when the spot is on the surveyed strip itself rather than off to one side
     * @param marked   the stored spot this came from, or null for an apron derived from the survey.
     *                 It is the identity a taxiing aircraft claims, so that a second one on its way
     *                 in picks a different square instead of driving into it — see
     *                 {@link #standFree}. A derived apron has no identity to claim, which is one more
     *                 reason it is a fallback and not the design.
     */
    public record ParkingSpot(Vec3 position, double heading, boolean onRunway, @Nullable BlockPos marked) {}

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

        // Each derived candidate is checked for an aircraft standing on it as well as for level
        // ground. It never used to be, because nothing was ever left standing anywhere: a derived
        // apron was only ever reached when no stand was marked, and the aircraft using it taxied away
        // within seconds. Arrivals now park and stay, including on the square this heuristic picks —
        // it is a fixed offset from the threshold, so every departure from that end picks the same
        // one.
        double sideways = departure.airfield().width() / 2.0 + AutopilotConfig.PARKING_LATERAL_OFFSET;
        for (double side : new double[] {90.0, -90.0}) {
            Vec3 apron = AutopilotMath.pointAlong(behind, heading + side, sideways);
            Vec3 spot = groundedIfLevelWith(level, apron, threshold.y);
            if (spot != null && taxiPathIsRollable(level, spot, threshold)
                && standFree(level, spot, null, null)) {
                return new ParkingSpot(spot, AutopilotMath.headingTo(spot, threshold), false, null);
            }
        }

        // Straight back from the threshold — now held to the same tolerance as the aprons.
        Vec3 straightBack = groundedIfLevelWith(level, behind, threshold.y);
        if (straightBack != null && taxiPathIsRollable(level, straightBack, threshold)
            && standFree(level, straightBack, null, null)) {
            return new ParkingSpot(straightBack, AutopilotMath.headingTo(straightBack, threshold), false, null);
        }

        // Nothing off the strip qualifies. Park on the strip, facing down it — stepping further
        // along it for each square that is taken, which is the check every candidate above it
        // already makes and the last resort was the one place that did not. The offset is fixed, so
        // without the walk every departure from this end is placed on the same block and the second
        // aircraft is spawned inside the first: the failure the derived aprons were given their own
        // occupancy test for, on the one candidate that cannot fall through to anything else.
        // Bounded by the take-off run that has to be left behind the new position, so the aircraft
        // is never moved so far down the strip that it cannot get off it; past that the threshold
        // square is returned, which is what this always returned.
        double along = AutopilotConfig.PARKING_ON_RUNWAY_OFFSET;
        Vec3 onRunway = AutopilotMath.pointAlong(threshold, heading, along);
        while (!standFree(level, onRunway, null, null)
            && along + AutopilotConfig.PARKING_SPOT_CLEARANCE
                + AutopilotConfig.TAKEOFF_LENGTH_NEEDED <= departure.length()) {
            along += AutopilotConfig.PARKING_SPOT_CLEARANCE;
            onRunway = AutopilotMath.pointAlong(threshold, heading, along);
        }
        return new ParkingSpot(onRunway, heading, true, null);
    }

    /**
     * The stand an arriving aircraft should taxi to, or null when it should stay where it stopped.
     *
     * <p>Deliberately a different question from {@link #parkingPosition}, and not because of the
     * geometry. A departure is asking "where do I start", and an apron worked out on the spot is a
     * perfectly good answer when nothing is marked; an arrival is asking "is it worth leaving the
     * runway for", and there an apron worked out on the spot is not an answer at all — it is a guess
     * at a square nobody looked at, reached by a taxi nobody validated, and an aircraft that gets it
     * wrong is stuck off the side of the field instead of merely being in the way on the strip. So
     * only a <em>marked</em> stand will do, and an aircraft that has nowhere marked to go simply
     * stops where it landed, exactly as it always did.
     *
     * <p><b>"Marked" is a test, not a provenance, and one of those stands may have come from the
     * survey rather than from a click.</b> The objection above is to a square produced at the moment
     * it is used and acted on unexamined — it is not an objection to the geometry, which is the same
     * geometry {@code AirfieldReport} now runs once at survey time and then puts through
     * {@link #parkingSpotProblem} before storing anything. That is the whole of the judgement
     * {@code park} holds a player's own click to, and refuses it for failing: ground to stand on, no
     * step up or down onto the strip, a line to the threshold that is rollable the whole way, and
     * clearance from the other stands. A square that passes them has been looked at in the only way
     * this code has ever looked at one, and the taxi has been validated by the same walk that
     * validates a player's. A stored stand also gains the thing a square derived on the spot can
     * never have — an identity a taxiing aircraft can claim, so a second arrival picks another
     * square instead of driving into the first; see {@link ParkingSpot#marked()}.
     *
     * <p>What the survey cannot supply is the part of a click that is not a measurement: where the
     * player would <em>like</em> their aircraft to sit. So a derived stand is reported with its
     * coordinate rather than left to be discovered, and it is removed with the same one gesture that
     * removes any other stand.
     *
     * <p>Nearest first, measured from where the aircraft actually came to rest rather than from a
     * threshold: on a 183-block strip the two ends are 183 blocks apart and the aircraft is
     * somewhere in between, so "nearest to the threshold" would routinely send it the long way.
     *
     * <p>The distance cap is its own constant and is much larger than the one a marked spot is
     * validated against. {@link AutopilotConfig#PARKING_MAX_TAXI_DISTANCE} bounds a stand's distance
     * from the <em>nearest threshold</em>; an arrival stops part way down the strip, so the honest
     * bound on the same geometry is the runway length plus that — see
     * {@link AutopilotConfig#TAXI_IN_MAX_DISTANCE}.
     *
     * @param from  where the aircraft came to rest
     * @param asker the aircraft asking, excluded from the "already taken" tests
     */
    public static @Nullable TaxiIn arrivalStand(Level level, Airfield airfield, Vec3 from,
                                                @Nullable PlaneEntity asker) {
        ParkingSpot best = null;
        List<Vec3> bestRoute = List.of();
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos spot : airfield.parkingSpots()) {
            double distance = AutopilotMath.horizontalDistance(from,
                new Vec3(spot.getX() + 0.5, from.y, spot.getZ() + 0.5));
            if (distance > AutopilotConfig.TAXI_IN_MAX_DISTANCE || distance >= bestDistance) {
                continue;
            }
            // Level ground on the square and level ground every couple of blocks along the line the
            // aircraft is going to drive down — the same two tests a departure's spot passes, asked
            // about the legs that are actually going to be driven rather than about the threshold.
            Vec3 probe = new Vec3(spot.getX() + 0.5, 0, spot.getZ() + 0.5);
            Vec3 position = groundedIfLevelWith(level, probe, from.y);
            if (position == null || !standFree(level, airfield, position, spot, asker)) {
                continue;
            }
            List<Vec3> route = taxiInRoute(level, airfield, from, position);
            if (route == null) {
                continue;
            }
            best = new ParkingSpot(position, AutopilotMath.headingTo(from, position),
                airfield.isOnStrip(spot), spot);
            bestRoute = route;
            bestDistance = distance;
        }
        return best == null ? null : new TaxiIn(best, bestRoute);
    }

    /** A chosen stand and the legs to drive to it, in order, ending on the stand itself. */
    public record TaxiIn(ParkingSpot stand, List<Vec3> route) {}

    /**
     * The first marked apron this departure can actually use, or null when the airfield has none
     * marked or none of them qualify right now.
     *
     * <p>"Qualify" is two questions, and they are different. <em>Usable</em> is about the ground —
     * still level with the runway, still rollable to this particular threshold — and a spot that
     * fails it is unusable for everyone. <em>Free</em> is about traffic: an aircraft already sitting
     * there or on its way. Spots are tried in the order they were marked, so the first one is the
     * normal departure position and the rest are where a queue forms behind it.
     *
     * <p><b>A stand that is taken is skipped outright, and there is no "least bad" stand.</b> This
     * used to remember the first occupied spot and return it when nothing was free, on the reasoning
     * that known-good ground beats a derived apron and that two aircraft on one square is a problem
     * for whatever clears them onto the runway. That reasoning held only while every aircraft that
     * ever stood on a stand was a departure that was about to leave it. Arrivals now taxi in and stay
     * there, so the aircraft being stacked on may be parked for good — and the new one is spawned
     * <em>inside</em> it. Measured: with two arrivals parked and the third stand out of taxi range for
     * that threshold, a sortie was placed on top of a parked aircraft. Falling through to the derived
     * apron is what the fallback is for.
     *
     * <p><b>Being a long roll from this particular threshold ranks a stand last; it does not
     * disqualify it.</b> {@link AutopilotConfig#PARKING_MAX_TAXI_DISTANCE} used to be applied here as
     * a veto, measured against the departure threshold — and that quietly threw the whole apron away
     * on every field longer than 64 blocks whose stands are grouped at one end, which is every field
     * a human builds. Which end a sortie departs from is chosen per flight from where it is going, so
     * the same airfield lost its stands on roughly half its departures and kept them on the other
     * half: from the ground it looks like the command ignoring the parking spots at random.
     * Reproduced on the rig on a 210-block strip with one stand 51 blocks behind threshold 09 — the
     * sortie out of 09 spawned on the stand, the sortie out of 27 spawned on the runway itself, 2
     * blocks from the far threshold, because the derived apron off that end had no level ground
     * either. Unsurveyed ground is exactly what marking a stand is supposed to stop an aircraft
     * being put on, so a marked stand that is level, rollable and free is now always preferred to it;
     * the distance only decides which marked stand wins.
     */
    private static @Nullable ParkingSpot markedParkingPosition(Level level, RunwayEnd departure) {
        Airfield airfield = departure.airfield();
        Vec3 threshold = departure.threshold();
        ParkingSpot distant = null;
        double distantRoll = Double.MAX_VALUE;
        for (BlockPos spot : airfield.parkingSpots()) {
            Vec3 position = usableParkingSpot(level, spot, threshold);
            if (position == null || !standFree(level, airfield, position, spot, null)) {
                continue;
            }
            ParkingSpot parking = new ParkingSpot(position,
                AutopilotMath.headingTo(position, threshold), false, spot);
            double roll = AutopilotMath.horizontalDistance(position, threshold);
            if (roll <= AutopilotConfig.PARKING_MAX_TAXI_DISTANCE) {
                return parking;
            }
            // Nearest of the far ones rather than the first of them: the marked order is the queue
            // order for the stands beside this threshold, and it says nothing useful about which of
            // the stands at the other end of the strip is the shorter roll.
            if (roll < distantRoll) {
                distant = parking;
                distantRoll = roll;
            }
        }
        return distant;
    }

    /**
     * The marked spot {@code spot} as a usable parking position for a departure from
     * {@code threshold}, or null if the ground there or on the way no longer works.
     *
     * <p>Ground only. How far the stand is from this threshold is a ranking question and is answered
     * by the caller — see {@link #markedParkingPosition}. Every block of the roll is still checked
     * here, however long it is, so a stand separated from the departure threshold by a ditch is
     * rejected exactly as it always was.
     */
    private static @Nullable Vec3 usableParkingSpot(Level level, BlockPos spot, Vec3 threshold) {
        Vec3 probe = new Vec3(spot.getX() + 0.5, 0, spot.getZ() + 0.5);
        Vec3 position = groundedIfLevelWith(level, probe, threshold.y);
        if (position == null || !taxiPathIsRollable(level, position, threshold)) {
            return null;
        }
        return position;
    }

    /**
     * The route an arrival drives from where it stopped to a stand: turn off the runway, run down
     * the apron, turn in. Null when none of the ground it would cross is usable.
     *
     * <p>This is the only routing in the whole feature, and it is three straight legs rather than a
     * path search. Two measurements on the rig made each of them necessary.
     *
     * <p><b>Turning off first, rather than heading straight for the stand.</b> A stand beside the far
     * threshold of a 183-block runway is 150 blocks from where an arrival stops, and the straight
     * line to it runs down the strip for most of that — the aircraft would still be holding the
     * runway 545 ticks after touchdown, against 794 ticks for the entire arrival it is meant to
     * improve on. Turning off sideways costs about 16 blocks of extra track, 80 ticks at
     * {@link AutopilotConfig#TAXI_SPEED}, and clears the landing surface in that time instead.
     *
     * <p><b>Running down the apron rather than cutting across it.</b> Stands are usually marked in a
     * row, and a straight line from the runway to the far one goes through the near one — where an
     * aircraft is very likely to be standing, since that is what stands are for. Measured: two
     * arrivals a few seconds apart, the second correctly picked the further stand because the nearer
     * was claimed, drove at it in a straight line and came to rest against the first aircraft 18
     * blocks short. So the middle leg is flown one {@link AutopilotConfig#PARKING_SPOT_CLEARANCE}
     * outboard of the outermost stand on that side, which is a taxiway lane in everything but name,
     * and the aircraft turns in only when it is abeam its own stand.
     *
     * <p>A stand that is not off to one side at all — marked off the end of the runway, or on the
     * strip itself — gets neither leg: there is no side to turn off towards, and the natural exit is
     * along the strip. Whatever route is produced, every leg is checked for level ground before the
     * aircraft is committed to it, and a lane that fails falls back to the direct line rather than
     * costing the aircraft its stand.
     */
    public static @Nullable List<Vec3> taxiInRoute(Level level, Airfield airfield, Vec3 from, Vec3 stand) {
        double heading = AutopilotMath.headingTo(airfield.pointA(), airfield.pointB());
        double standLateral = AutopilotMath.lateralOffset(airfield.pointA(), heading, stand);
        double halfWidth = airfield.width() / 2.0;
        if (Math.abs(standLateral) > halfWidth) {
            double side = Math.signum(standLateral);
            // Outboard of every stand on this side, and never inside the rectangle the runway
            // release is tested against — a lane on the boundary would leave the release depending
            // on which side of a rounding the nosewheel happened to sit.
            double lane = halfWidth + AutopilotConfig.RUNWAY_CLEAR_MARGIN + 1.0;
            for (BlockPos other : airfield.parkingSpots()) {
                double lateral = AutopilotMath.lateralOffset(airfield.pointA(), heading,
                    new Vec3(other.getX() + 0.5, 0, other.getZ() + 0.5));
                if (Math.signum(lateral) == side) {
                    lane = Math.max(lane, Math.abs(lateral) + AutopilotConfig.PARKING_SPOT_CLEARANCE);
                }
            }
            List<Vec3> route = new ArrayList<>(3);
            double fromAlong = AutopilotMath.alongTrack(airfield.pointA(), heading, from);
            double standAlong = AutopilotMath.alongTrack(airfield.pointA(), heading, stand);
            if (AutopilotMath.lateralOffset(airfield.pointA(), heading, from) * side < lane - 1.0) {
                route.add(airfield.stripPoint(fromAlong, lane * side, stand.y));
            }
            if (Math.abs(standAlong - fromAlong) > AutopilotConfig.TAXI_IN_ARRIVED_RADIUS) {
                route.add(airfield.stripPoint(standAlong, lane * side, stand.y));
            }
            route.add(stand);
            if (routeIsRollable(level, from, route)) {
                return route;
            }
        }
        List<Vec3> direct = List.of(stand);
        return routeIsRollable(level, from, direct) ? direct : null;
    }

    /** A point in runway coordinates: {@code along} blocks from threshold A, {@code lateral} across. */
    private Vec3 stripPoint(double along, double lateral, double elevation) {
        double heading = AutopilotMath.headingTo(pointA(), pointB());
        Vec3 point = AutopilotMath.pointAlong(
            AutopilotMath.pointAlong(pointA(), heading, along), heading + 90.0, lateral);
        return new Vec3(point.x, elevation, point.z);
    }

    private static boolean routeIsRollable(Level level, Vec3 from, List<Vec3> route) {
        Vec3 previous = from;
        for (Vec3 leg : route) {
            if (!taxiPathIsRollable(level, previous, leg)) {
                return false;
            }
            previous = leg;
        }
        return true;
    }

    /**
     * True when this stand is neither occupied, nor spoken for, nor remembered as occupied.
     *
     * <p>Three questions, and only the first one existed for as long as nothing taxied in.
     *
     * <ol>
     *   <li><b>Standing on it</b> — an entity search, which is the whole answer as long as every
     *       aircraft that ever uses a stand is already on it at spawn time.</li>
     *   <li><b>On its way to it</b> — a taxi takes hundreds of ticks, and for all of them the
     *       aircraft is somewhere between the runway and a square it fully intends to occupy.
     *       Without this two arrivals a few seconds apart both pick the nearest free square and drive
     *       at it, and {@code PlaneEntity#canBeCollidedWith} is unconditionally true. Derived from
     *       the live autopilots rather than stored, for the reason {@link RunwayOccupancy#activeCount}
     *       is derived: a reservation with its own lifetime leaks one for every aircraft that goes
     *       away without running its release path, which is what happens on every crash.</li>
     *   <li><b>Left standing on it, in a chunk nobody has loaded</b> — see {@link StandOccupancy}.
     *       A parked aircraft renews no chunk ticket, so the entity search above goes empty 40 ticks
     *       after it arrives and every later arrival taxis on top of it.</li>
     * </ol>
     *
     * @param asker excluded from all three, so an aircraft can ask about the stand it already holds
     */
    public static boolean standFree(Level level, Vec3 position, @Nullable BlockPos marked,
                                    @Nullable PlaneEntity asker) {
        // One clearance across, not two. AABB#ofSize takes the full extent, so the box used to reach
        // a whole PARKING_SPOT_CLEARANCE to either side of the square — and that is exactly the
        // smallest gap parkingSpotProblem lets a player leave between two stands. An aircraft is up
        // to 3 blocks wide, so a machine standing on the next stand at the minimum legal separation
        // had its hull inside this box and both squares read as occupied: the pair of stands could
        // never be used at once, an arrival skipped the free one and stopped on the runway, and a
        // departure fell through to the derived apron. Half the extent keeps the test on the square
        // itself, still catches anything actually parked on it, and leaves a block of daylight
        // against an aircraft on the neighbouring stand.
        AABB box = AABB.ofSize(position, AutopilotConfig.PARKING_SPOT_CLEARANCE,
            6.0, AutopilotConfig.PARKING_SPOT_CLEARANCE);
        if (!level.getEntities(EntityTypeTest.forClass(PlaneEntity.class), box,
            plane -> plane != asker).isEmpty()) {
            return false;
        }
        return marked == null || standFree(level, marked, asker);
    }

    /** The two tests from {@link #standFree} that are about a <em>marked</em> stand specifically. */
    private static boolean standFree(Level level, BlockPos marked, @Nullable PlaneEntity asker) {
        for (PlaneEntity plane : AutopilotRegistry.active()) {
            if (plane == asker || plane.level() != level) {
                continue;
            }
            PlaneAutopilot autopilot = plane.getAutopilot();
            if (autopilot != null && autopilot.claimsStand(marked)) {
                return false;
            }
        }
        return true;
    }

    /** As {@link #standFree}, for a stand of a named airfield, so the memory can be consulted too. */
    public static boolean standFree(Level level, Airfield airfield, Vec3 position, BlockPos marked,
                                    @Nullable PlaneEntity asker) {
        return standFree(level, position, marked, asker)
            && !StandOccupancy.isTaken(level, airfield.name(), marked, asker);
    }

    /**
     * Why {@code spot} cannot be a parking apron for {@code airfield}, or null when it can.
     *
     * <p>Marked spots are validated when they are marked rather than when they are used, so the
     * player who put one in the wrong place is told immediately instead of finding out three
     * minutes into a sortie. Four of the tests are the four ways the ground handling gets stuck:
     * nothing there to stand on, a step up or down onto the strip, a ditch on the way, and a spot so
     * far from the runway that the straight-line taxi is a journey of its own.
     *
     * <p>The other two are the ones the strip's own surface rule makes and this list used to be
     * exempt from — standing fluid over the square, and something in the air above it. See
     * {@link #standColumnProblem}.
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
        String column = standColumnProblem(level, probe, surface);
        if (column != null) {
            return column;
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
            // Horizontally, and about the column rather than the block that was named. A stand is
            // stored on the surface of its column, so naming a block a few above an existing stand
            // is the same square — and BlockPos#distSqr is three-dimensional, so the height
            // difference alone carried it past this clearance and marked a second stand on the exact
            // coordinates of the first.
            if (AutopilotMath.horizontalDistance(probe,
                new Vec3(existing.getX() + 0.5, 0, existing.getZ() + 0.5))
                < AutopilotConfig.PARKING_SPOT_CLEARANCE) {
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
     * Why an aircraft cannot stand on this column, or null when it can. The two questions
     * {@link #surfaceProblem(Level, Vec3, Vec3, int)} asks about every column of the strip, asked
     * about one square of apron: is the block it would rest on fluid, and is there anything over it.
     *
     * <p><b>Fluid.</b> {@link TerrainScanner#surfaceHeight} is the MOTION_BLOCKING heightmap, which
     * counts a fluid as ground and so reports a waterline as though it were a surface — and every
     * other test on a stand then agrees that it is one. A flooded ditch beside the runway is level
     * with the strip because the water is, and the taxi to it is rollable because a pond is flat, so
     * the stand was stored, a departure was spawned on the surface of the water and an arrival taxied
     * into it. The strip itself has refused fluid ever since the surface rule arrived
     * ({@code permittedCover}); the apron beside it is now held to the same rule.
     *
     * <p><b>Headroom.</b> {@link AutopilotConfig#RUNWAY_CLEAR_HEIGHT} blocks of the same permitted
     * cover the strip demands, so a square under a low roof or inside a shed is refused instead of
     * being marked and then spawned into. This matters more than it did: the survey now derives a
     * stand unprompted on every fresh field, where before a human had to pick one and could see what
     * was over it. A hangar tall enough to hold an aircraft passes, exactly as a runway with a canopy
     * well above it does.
     *
     * <p><b>Four block reads, and no second heightmap.</b> The obvious way to ask about the fluid is
     * {@link TerrainScanner#isLandable}, which compares MOTION_BLOCKING with OCEAN_FLOOR and costs no
     * block lookup at all. It cannot be used here: OCEAN_FLOOR carries {@code Usage.LIVE_WORLD} and is
     * never sent to a client, and a client chunk holds it unprimed, so on the logical client it
     * answers "unknown" for every column in the world. {@link #parkingSpotProblem} is exactly the
     * function the survey tool's preview runs on the client, every tick, to decide whether the square
     * under the player's crosshair is shaded green — and answering it off that heightmap would shade
     * every square in the world red. The block under the surface, and the blocks above it, read the
     * same on both sides.
     *
     * @param surface the column's surface as {@link TerrainScanner#surfaceHeight} reports it: the
     *                first free block, so the block that would be stood on is one below
     */
    private static @Nullable String standColumnProblem(Level level, Vec3 probe, int surface) {
        int x = (int) Math.floor(probe.x);
        int z = (int) Math.floor(probe.z);
        BlockPos ground = new BlockPos(x, surface - 1, z);
        BlockState under = level.getBlockState(ground);
        if (!under.getFluidState().isEmpty()) {
            return "it stands on " + blockName(under) + " at " + ground.toShortString()
                + "; an aircraft parks on ground, not on a waterline";
        }
        for (int y = surface; y < surface + AutopilotConfig.RUNWAY_CLEAR_HEIGHT; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (!permittedCover(state)) {
                return blockName(state) + " is over it at " + pos.toShortString()
                    + "; a stand needs " + AutopilotConfig.RUNWAY_CLEAR_HEIGHT
                    + " blocks of clear air above it, as the runway does";
            }
        }
        return null;
    }

    /**
     * The block a stand on this column is stored as: the surface block itself, exactly as a
     * threshold is stored.
     *
     * <p>One place, because more than one thing produces a stand now. A click on the side of a
     * block, a click on its top and a square the survey worked out for itself all have to come out
     * as the same stored position, or the same square would read as two different stands.
     *
     * <p>Deliberately still the clearance surface, which is the convention a threshold is stored in
     * and the one every elevation on this airfield is compared against. On a column with water over
     * it that block is the water itself — which is why a column with water over it is no longer a
     * stand at all: {@link #standColumnProblem} refuses it before anything is stored. Reading a
     * second heightmap here instead would fix the water case by storing a stand under the water and
     * leave stands and thresholds measured off different surfaces everywhere else.
     *
     * <p>Only meaningful on a column whose chunk is loaded; ask
     * {@link #parkingSpotProblem} first, which refuses an unknown column before anything else.
     */
    public static BlockPos standBlock(Level level, int x, int z) {
        return new BlockPos(x, TerrainScanner.surfaceHeight(level, x + 0.5, z + 0.5) - 1, z);
    }

    /**
     * True when this spot sits on the surveyed strip itself. Not a reason to refuse it — parking on
     * the runway is what the fallback does when nothing beside it is level — but worth saying out
     * loud, because an aircraft waiting there is an aircraft standing on the landing area.
     */
    public boolean isOnStrip(BlockPos spot) {
        return isOnStrip(new Vec3(spot.getX() + 0.5, 0, spot.getZ() + 0.5), 0.0);
    }

    /**
     * Whether a point is inside the surveyed rectangle, grown by {@code margin} on every side.
     *
     * <p>This is the real test behind "the aircraft is clear of the runway", and it has to be a
     * rectangle rather than a distance from anything. A landing rolls out somewhere down the middle
     * of the strip and then turns off to one side: measured from the threshold it is <em>further
     * away</em> the whole time it is still on the runway, and measured from the centre it can be
     * closer to it after turning off than it was on the centreline. Only the two coordinates the
     * survey actually measured — how far along and how far across — answer the question, and this
     * is the pair of numbers the landing report is already written in.
     */
    public boolean isOnStrip(Vec3 point, double margin) {
        double heading = AutopilotMath.headingTo(pointA(), pointB());
        double along = AutopilotMath.alongTrack(pointA(), heading, point);
        return along >= -margin && along <= length() + margin
            && Math.abs(AutopilotMath.lateralOffset(pointA(), heading, point)) <= width() / 2.0 + margin;
    }

    /**
     * The surface at {@code probe} as a parking position, or null when it is unknown, under fluid,
     * or not level with the runway. {@code TerrainScanner.surfaceHeight} reports the first free
     * block, which is the same convention {@link #pointA()} uses for a threshold, so the two are
     * directly comparable.
     */
    private static @Nullable Vec3 groundedIfLevelWith(Level level, Vec3 probe, double runwayElevation) {
        int surface = TerrainScanner.surfaceHeight(level, probe.x, probe.z);
        if (surface == TerrainScanner.UNKNOWN_HEIGHT
            || Math.abs(surface - runwayElevation) > AutopilotConfig.PARKING_MAX_ELEVATION_DIFFERENCE) {
            return null;
        }
        // Ground, not a waterline. This is every parking position the code ever produces — the
        // derived aprons, the square an arrival taxis to, and each sample of the taxi path itself —
        // and the heightmap answers "level with the runway" with a yes for the surface of a pond,
        // which is how an aircraft came to be spawned on water beside a lake and taxied through a
        // flooded ditch on the way to the threshold. One block read, for the reason
        // standColumnProblem gives for reading blocks rather than consulting OCEAN_FLOOR: this runs
        // on the client too, and that heightmap is not there.
        if (!level.getBlockState(BlockPos.containing(probe.x, surface - 1, probe.z))
            .getFluidState().isEmpty()) {
            return null;
        }
        return new Vec3(probe.x, surface, probe.z);
    }

    /**
     * Whether the aircraft can actually roll from a parking spot to the threshold.
     *
     * <p>A level parking spot is not enough on its own: the taxi is a straight line with no obstacle
     * avoidance and no ability to climb, so a spot that is level with the runway but separated from
     * it by a ditch or a step is just as unusable as one in a hole. Every few blocks along that line
     * has to be level with the runway too.
     *
     * <p>Used in both directions. A departure asks it about the line from its stand to the threshold;
     * an arrival asks it about the line from where it stopped to the stand it is thinking of taxiing
     * to. Same ground, same tolerance, and the elevation reference is {@code to.y} either way.
     */
    private static boolean taxiPathIsRollable(Level level, Vec3 from, Vec3 to) {
        double distance = AutopilotMath.horizontalDistance(from, to);
        int steps = (int) Math.ceil(distance / AutopilotConfig.TAXI_PATH_SAMPLE_STEP);
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            Vec3 probe = new Vec3(from.x + (to.x - from.x) * t, 0, from.z + (to.z - from.z) * t);
            if (groundedIfLevelWith(level, probe, to.y) == null) {
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
     * flat enough to land on" number.
     *
     * <p>Reported by the survey tool and, since the survey started refusing strips it cannot land
     * on, also enforced: see {@link AutopilotConfig#RUNWAY_MAX_ROUGHNESS} and
     * {@link #surfaceProblem(Level, Vec3, Vec3, int)}. It is the whole-strip half of that judgement
     * and the weaker half — it measures the centreline only, and about the mean rather than about
     * the runway's own line, so a smooth ramp scores as rough. What catches a lump, and catches one
     * out at the edge of the strip where this never looks, is the per-column rule beside it.
     */
    public double roughness(Level level) {
        return roughness(level, pointA(), pointB());
    }

    /**
     * As {@link #roughness(Level)}, for a strip that has not been registered — or surveyed — yet.
     * The ends are in {@link #pointA()}'s convention.
     */
    public static double roughness(Level level, Vec3 endA, Vec3 endB) {
        double length = AutopilotMath.horizontalDistance(endA, endB);
        int samples = Math.max(2, (int) (length / 4));
        samples = Math.min(samples, 64);
        double heading = AutopilotMath.headingTo(endA, endB);
        double step = length / samples;
        List<Integer> heights = new ArrayList<>(samples + 1);
        for (int i = 0; i <= samples; i++) {
            Vec3 probe = AutopilotMath.pointAlong(endA, heading, step * i);
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

    // ------------------------------------------------------------------ the surface of the strip

    /**
     * Why this runway's surface will not do, or null when it will. See
     * {@link #surfaceProblem(Level, Vec3, Vec3, int)}, which this is the registered-airfield form of.
     */
    public @Nullable String surfaceProblem(Level level) {
        return surfaceProblem(level, pointA(), pointB(), width());
    }

    /**
     * Why the strip between two ends will not do as a runway surface, or null when nothing is wrong
     * with it. The same shape as {@link #parkingSpotProblem}: one human-readable sentence naming a
     * block coordinate the player can walk to, or null.
     *
     * <p><b>Deliberately not a method on a registered airfield.</b> It takes the two ends and a
     * width, so a selection that has not been surveyed — let alone registered — can be judged before
     * the player commits to it, and so the survey, the browser and an in-world preview all apply one
     * rule rather than three that drift apart.
     *
     * <h2>The rule</h2>
     * Every probed column of the strip must have its surface within
     * {@link AutopilotConfig#RUNWAY_MAX_SURFACE_STEP} of the straight line between the two ends, and
     * carry nothing above that surface, for {@link AutopilotConfig#RUNWAY_CLEAR_HEIGHT} blocks, but
     * <em>air, snow or grass</em>.
     *
     * <h2>What "air, snow or grass" admits, exactly</h2>
     * The test is {@link #permittedCover} and it is written in properties and tags rather than as a
     * list of blocks, so a modded plant that declares itself a plant is treated as one. In order:
     * <ul>
     *   <li><b>Air</b> — {@code isAir()}, so cave air and void air count too.</li>
     *   <li><b>No fluid</b> — anything with a fluid state, including a waterlogged block, is refused
     *       before any other test. Water and lava standing on a strip are the one kind of "cover" an
     *       aircraft does not roll through, and the heightmaps cannot see the difference (see
     *       {@link TerrainScanner#isLandable}).</li>
     *   <li><b>Snow</b> — a snow <em>layer</em> of fewer than {@code SnowLayerBlock.HEIGHT_IMPASSABLE}
     *       layers, which is vanilla's own line between snow a walking mob paths straight through and
     *       a drift it has to climb. So a dusting up to four layers deep is runway; five or more is a
     *       drift and is refused, and so is a full {@code snow_block} standing on the strip. A runway
     *       <em>built</em> of snow blocks is fine — those are the surface, not cover. Powder snow is
     *       refused wherever it appears: it is a hole with a lid.</li>
     *   <li><b>Grass</b> — {@link net.minecraft.tags.BlockTags#REPLACEABLE_BY_TREES} minus
     *       {@link net.minecraft.tags.BlockTags#LEAVES}. That tag is vanilla's own answer to "what is
     *       a plant a growing tree pushes through", which is the same question as "what will an
     *       undercarriage mow down": short and tall grass, ferns and large ferns, dry grass, bushes,
     *       dead bushes, leaf litter, vines, glow lichen, and every flower — the small ones through
     *       {@code #small_flowers} and the two-block sunflower, lilac, rose bush, peony and pitcher
     *       plant by name. Leaves are cut back out of it because a canopy hanging over a strip is a
     *       tree, and a tree is the thing this rule exists to catch.</li>
     * </ul>
     *
     * <p>So, and these are the cases worth knowing before you meet them: <b>tall grass and flowers
     * pass. Crops, saplings, wool carpets, cobwebs, torches, signs and rails do not</b> — they are
     * not in that tag, and a sapling in particular is a tree that has not happened yet. <b>Slabs,
     * stairs, fences, fence gates, walls and chests do not</b>, by a second test: a column whose
     * surface sits above the runway line is only accepted when the block it sits on is a full block
     * of collision, i.e. genuinely a piece of ground one block higher rather than a thing standing on
     * the ground. That second test is what separates a fence post from a bump, which no measurement
     * of height alone can do.
     *
     * <h2>What it lets through, knowingly</h2>
     * A single <em>full</em> block — a stone block, a chiselled block, a piece of wool — placed flush
     * on an otherwise level strip reads as a one-block rise in the ground, because that is physically
     * what it is, and {@link AutopilotConfig#RUNWAY_MAX_SURFACE_STEP} tolerates one block so that the
     * width measurement and this rule agree about which columns are the runway. Two of them stacked,
     * or anything that is not a full cube, is refused.
     *
     * <h2>Unloaded ground is an obstacle</h2>
     * A column whose chunk is not resident is refused by name, never skipped. The whole file is
     * emphatic about this — see {@link TerrainScanner#UNKNOWN_HEIGHT} and
     * {@link TerrainScanner#isLandable} — and it matters more here than anywhere: "the server has not
     * looked at that ground" must never come out as "that ground is clear".
     *
     * <h2>Cost</h2>
     * {@code length / 4} along-track stations capped at 64 — the sampling {@link #roughness} already
     * uses — plus one, times {@link AutopilotConfig#RUNWAY_SURFACE_LATERAL_SAMPLES} columns across
     * the width: at most 325 columns. Each column costs one heightmap lookup, which is O(1) and does
     * not load a chunk, plus block reads within a band only
     * {@code RUNWAY_CLEAR_HEIGHT + RUNWAY_MAX_SURFACE_STEP + 1} = 5 blocks tall, no block of which is
     * read twice — six per column is the ceiling and four is the worst any column shape actually
     * reaches. So a worst-case survey is 325 heightmap lookups and under 2000 block reads, whatever
     * the runway is made of and however tall the trees on it are: the walk is bounded by the band,
     * not by the terrain. Bounded on purpose — this runs on the server thread, and a 160-block field
     * must not turn into tens of thousands of lookups.
     *
     * @param endA  one end of the strip, as {@link #pointA()} gives it — the centre of the top face
     *              of the threshold block, so {@code y} is the first free block above the surface
     * @param endB  the other end, in the same convention
     * @param width the strip's measured width in blocks; values below 1 are treated as 1
     */
    public static @Nullable String surfaceProblem(Level level, Vec3 endA, Vec3 endB, int width) {
        double length = AutopilotMath.horizontalDistance(endA, endB);
        if (length < 1.0) {
            return "both ends are on the same block, so there is no strip to check";
        }
        double heading = AutopilotMath.headingTo(endA, endB);
        int stations = Math.min(64, Math.max(2, (int) (length / 4)));
        double step = length / stations;
        double halfWidth = Math.max(1, width) / 2.0;
        for (int station = 0; station <= stations; station++) {
            double along = step * station;
            Vec3 centre = AutopilotMath.pointAlong(endA, heading, along);
            double elevation = endA.y + (endB.y - endA.y) * (along / length);
            for (int sample = 0; sample < AutopilotConfig.RUNWAY_SURFACE_LATERAL_SAMPLES; sample++) {
                Vec3 probe = AutopilotMath.pointAlong(centre, heading + 90.0,
                    lateralOffset(sample, halfWidth));
                String problem = columnProblem(level, probe, elevation);
                if (problem != null) {
                    return problem;
                }
            }
        }
        // Last, and only because it is the one complaint with no coordinate attached to it. Anything
        // local enough to point at has already been pointed at above.
        double roughness = roughness(level, endA, endB);
        if (roughness > AutopilotConfig.RUNWAY_MAX_ROUGHNESS) {
            return String.format("the surface varies by %.2f blocks along the centreline, and %.2f is"
                + " as uneven as a runway may be; level it, or mark a strip that does not climb so"
                + " far end to end", roughness, AutopilotConfig.RUNWAY_MAX_ROUGHNESS);
        }
        return null;
    }

    /**
     * Where the {@code index}th lateral probe of a station goes, in blocks right of the centreline.
     *
     * <p>Centre first and then alternating outwards, so that when several things are wrong the one
     * that is reported is the one nearest the middle of the runway — which is the one an aircraft
     * meets first and the one a player looking for it will find soonest.
     */
    private static double lateralOffset(int index, double halfWidth) {
        if (index == 0) {
            return 0;
        }
        int rings = Math.max(1, AutopilotConfig.RUNWAY_SURFACE_LATERAL_SAMPLES / 2);
        int ring = (index + 1) / 2;
        double magnitude = halfWidth * ring / rings;
        return index % 2 == 1 ? -magnitude : magnitude;
    }

    /**
     * What is wrong with one column of the strip, or null. {@code elevation} is the runway's own
     * surface at this point along the centreline, in {@link #pointA()}'s convention: the first free
     * block above the ground, so the runway surface block itself is one below it.
     */
    private static @Nullable String columnProblem(Level level, Vec3 probe, double elevation) {
        int x = (int) Math.floor(probe.x);
        int z = (int) Math.floor(probe.z);
        int runway = (int) Math.round(elevation);
        int ground = TerrainScanner.landableSurfaceHeight(level, probe.x, probe.z);
        if (ground == TerrainScanner.UNKNOWN_HEIGHT) {
            return "the ground at " + x + ", " + z + " is not loaded, so nothing there has been"
                + " looked at; stand on the runway, or force-load it, and survey again";
        }
        int ceiling = runway + AutopilotConfig.RUNWAY_CLEAR_HEIGHT;
        int lowest = runway - AutopilotConfig.RUNWAY_MAX_SURFACE_STEP;

        // The heightmap's idea of the ground is the top of the highest block that blocks motion,
        // which is one block too high wherever something the aircraft would drive straight through
        // is sitting on the runway: a four-layer snow drift blocks motion, tall grass does not, and
        // both are permitted cover. So walk down through whatever is permitted to find the surface
        // the wheels would actually run on. Bounded by the band itself, so this is at most
        // RUNWAY_CLEAR_HEIGHT + RUNWAY_MAX_SURFACE_STEP + 1 block reads however tall the column is.
        int surface = ground;
        if (ground <= ceiling) {
            while (surface > lowest
                && permittedCover(level.getBlockState(new BlockPos(x, surface - 1, z)))) {
                surface--;
            }
        }

        if (surface > runway + AutopilotConfig.RUNWAY_MAX_SURFACE_STEP) {
            BlockPos top = new BlockPos(x, surface - 1, z);
            return blockName(level.getBlockState(top)) + " stands " + (surface - runway)
                + (surface - runway == 1 ? " block" : " blocks") + " above the runway at "
                + top.toShortString();
        }
        if (surface < lowest) {
            return "the ground drops " + (runway - surface)
                + " blocks below the runway at " + x + " " + surface + " " + z
                + "; the strip has a hole in it";
        }
        // A column inside the step tolerance but topped by something that is not a full block of
        // collision is a thing standing on the runway, not a runway one block higher. This is the
        // only test that tells a fence post from a bump, and it is why fences, walls, gates, slabs,
        // stairs and chests are refused while a raised patch of ground is not.
        if (surface > runway) {
            BlockPos top = new BlockPos(x, surface - 1, z);
            BlockState state = level.getBlockState(top);
            if (!state.isCollisionShapeFullBlock(level, top)) {
                return blockName(state) + " stands on the runway at " + top.toShortString()
                    + "; a runway carries nothing but air, snow or grass";
            }
        }
        for (int y = Math.max(ground, surface); y < ceiling; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (!permittedCover(state)) {
                return blockName(state) + " is on the runway at " + pos.toShortString()
                    + "; a runway carries nothing but air, snow or grass, for "
                    + AutopilotConfig.RUNWAY_CLEAR_HEIGHT + " blocks above its surface";
            }
        }
        return null;
    }

    /**
     * Whether a block standing over the strip is cover an aircraft rolls through rather than an
     * obstacle it hits. The exact admissions and refusals are listed on
     * {@link #surfaceProblem(Level, Vec3, Vec3, int)}, which is where a player-facing rule belongs.
     */
    private static boolean permittedCover(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        // Before anything else, and including waterlogged blocks: a flooded runway is not a runway,
        // and it is invisible to every heightmap this file otherwise reads.
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.is(Blocks.SNOW)) {
            // Vanilla's own threshold, from SnowLayerBlock#isPathfindable: below it a walking mob
            // paths straight through the snow, at or above it the snow is something to be climbed.
            // Borrowing the number rather than inventing one means a drift that stops a cow also
            // stops an aeroplane, which is the answer a player will already expect.
            return state.getValue(SnowLayerBlock.LAYERS) < SnowLayerBlock.HEIGHT_IMPASSABLE;
        }
        return state.is(BlockTags.REPLACEABLE_BY_TREES) && !state.is(BlockTags.LEAVES);
    }

    /** A block's name as a player sees it in their inventory, for a message they have to act on. */
    private static String blockName(BlockState state) {
        return state.getBlock().getName().getString();
    }

    /**
     * The runway a pair of clicked corners marks out.
     *
     * @param thresholdA the end of the centreline nearest the first click
     * @param thresholdB the end of the centreline nearest the second click
     * @param width      the strip's width in whole blocks, taken from the selection
     */
    public record Footprint(BlockPos thresholdA, BlockPos thresholdB, int width) {}

    /**
     * Narrowest strip the survey will register, in blocks. A selection this thin cannot be a runway
     * footprint anybody meant — it is two clicks down one line, which is how the tool used to be
     * driven — so it is widened rather than refused, and this is the only number in the geometry that
     * does not come from the selection.
     */
    private static final int MIN_MEASURED_WIDTH = 3;

    /**
     * Turns two clicked corners into a runway: the axis-aligned box they span, laid out along
     * whichever of its two sides is longer.
     *
     * <p><b>The selection is the runway.</b> Origin, length, width and orientation all come from the
     * box and from nothing else: the thresholds are the middle blocks of its two short edges, the
     * width is its short span, and the heading is therefore always a multiple of 90 degrees. What the
     * player marks out is what they get, and the client preview can draw it exactly because this is a
     * pure function of the two positions with no terrain in it.
     *
     * <p><b>What this replaces.</b> The survey used to take the two clicks as the two ends of the
     * centreline and then measure the width outwards from it against the terrain. Clicking two
     * opposite corners — the natural gesture, and the one {@code HelipadToolItem} has always used —
     * therefore made the corner-to-corner diagonal the runway axis: a 19x27 selection registered as a
     * strip 32 long on a heading of 139/319 degrees, laid diagonally across the ground that was
     * marked and running off it at both ends. The width came from the terrain walk, which on flat or
     * uniform ground simply ran out at its probe ceiling and reported
     * {@code SURVEY_MAX_WIDTH / 2 * 2 + 1} = 25 whatever was selected. Neither number was the
     * player's.
     *
     * <p><b>Spans are inclusive block counts</b> — a click on x=3016 and one on x=3034 select the 19
     * blocks 3016..3034 — but the thresholds sit at the centres of the first and last of those
     * blocks, so {@link #length()}, which is the threshold-to-threshold roll an aircraft actually
     * has, is one block less than the footprint. An even width puts the stored centreline half a
     * block towards the lower edge, because a threshold is a block and not a line.
     *
     * <p>A square selection is laid out along X, arbitrarily but predictably.
     */
    public static Footprint footprint(BlockPos cornerA, BlockPos cornerB) {
        int minX = Math.min(cornerA.getX(), cornerB.getX());
        int maxX = Math.max(cornerA.getX(), cornerB.getX());
        int minZ = Math.min(cornerA.getZ(), cornerB.getZ());
        int maxZ = Math.max(cornerA.getZ(), cornerB.getZ());
        int spanX = maxX - minX + 1;
        int spanZ = maxZ - minZ + 1;
        boolean alongX = spanX >= spanZ;
        int width = Math.max(MIN_MEASURED_WIDTH, alongX ? spanZ : spanX);
        BlockPos low;
        BlockPos high;
        if (alongX) {
            int centreZ = minZ + (spanZ - 1) / 2;
            low = new BlockPos(minX, cornerA.getY(), centreZ);
            high = new BlockPos(maxX, cornerB.getY(), centreZ);
        } else {
            int centreX = minX + (spanX - 1) / 2;
            low = new BlockPos(centreX, cornerA.getY(), minZ);
            high = new BlockPos(centreX, cornerB.getY(), maxZ);
        }
        // The click order is kept, so "threshold 1" is the end the player marked first and the
        // designators do not swap between one survey of a strip and the next.
        boolean firstIsLow = alongX
            ? cornerA.getX() <= cornerB.getX()
            : cornerA.getZ() <= cornerB.getZ();
        return firstIsLow
            ? new Footprint(low.atY(cornerA.getY()), high.atY(cornerB.getY()), width)
            : new Footprint(high.atY(cornerA.getY()), low.atY(cornerB.getY()), width);
    }

    /**
     * Surveys a runway from two clicked corners of the strip.
     *
     * <p>The geometry is {@link #footprint}'s and comes from the selection alone; the terrain is read
     * only to put each threshold on the surface of its own column and to count the two approach
     * funnels. Those counts are taken here, while the chunks are loaded, and stored: this is the only
     * moment they can be trusted, because an arriving aircraft asks the question from hundreds of
     * blocks away. See {@link #bestEnd}.
     */
    public static Airfield survey(Level level, String name, BlockPos cornerA, BlockPos cornerB) {
        Footprint footprint = footprint(cornerA, cornerB);
        BlockPos a = snapToSurface(level, footprint.thresholdA());
        BlockPos b = snapToSurface(level, footprint.thresholdB());
        Airfield airfield = new Airfield(name, a, b, footprint.width());
        // requiresStands = true: a strip surveyed by this build is not a finished airfield until a
        // stand is marked beside it. Whether that sticks is decided by the caller — re-surveying an
        // airfield that is already registered keeps whatever the registered one had, so correcting a
        // threshold on an old field cannot turn it into one that refuses sorties. See
        // AirfieldReport#surveyAndRegister.
        return new Airfield(name, a, b, footprint.width(), List.of(),
            countApproachObstacles(level, airfield.endA()),
            countApproachObstacles(level, airfield.endB()), true);
    }

    /**
     * Re-reads the terrain under an airfield whose geometry is already settled: the thresholds are
     * re-snapped to the surface of their own columns and both approach funnels are recounted.
     *
     * <p>Nothing about the footprint moves. Since the survey takes its geometry from the selection
     * rather than from the ground, there is no measurement left for a re-survey to correct — what it
     * is for now is a strip whose surroundings have changed, so that {@code bestEnd} stops preferring
     * an end that has since had a hill built off it. To change the shape of a runway, mark it again.
     */
    public static Airfield remeasure(Level level, Airfield airfield) {
        BlockPos a = snapToSurface(level, airfield.thresholdA());
        BlockPos b = snapToSurface(level, airfield.thresholdB());
        Airfield moved = new Airfield(airfield.name(), a, b, airfield.width());
        return new Airfield(airfield.name(), a, b, airfield.width(), airfield.parkingSpots(),
            countApproachObstacles(level, moved.endA()),
            countApproachObstacles(level, moved.endB()), airfield.requiresStands());
    }

    /**
     * True when this airfield runs along a world axis, which is every airfield surveyed by this build.
     *
     * <p>An airfield saved by an earlier one can be diagonal — the survey took the two clicks as the
     * two ends of the centreline, so two corner clicks stored the diagonal — and it is loaded and
     * flown exactly as saved. This is how {@code airfields info} spots one and says so; nothing
     * reinterprets a stored threshold on its own.
     */
    public boolean isAxisAligned() {
        return thresholdA.getX() == thresholdB.getX() || thresholdA.getZ() == thresholdB.getZ();
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
