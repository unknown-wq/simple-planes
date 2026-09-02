package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
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

        // Nothing off the strip qualifies. Park on the strip, facing down it.
        Vec3 onRunway = AutopilotMath.pointAlong(threshold, heading, AutopilotConfig.PARKING_ON_RUNWAY_OFFSET);
        return new ParkingSpot(onRunway, heading, true, null);
    }

    /**
     * The stand an arriving aircraft should taxi to, or null when it should stay where it stopped.
     *
     * <p>Deliberately a different question from {@link #parkingPosition}, and not because of the
     * geometry. A departure is asking "where do I start", and the derived apron is a perfectly good
     * answer when nothing is marked; an arrival is asking "is it worth leaving the runway for", and
     * there the derived apron is not an answer at all — it is a guess at a square nobody looked at,
     * reached by a taxi nobody validated, and an aircraft that gets it wrong is stuck off the side
     * of the field instead of merely being in the way on the strip. So only a <em>marked</em> stand
     * will do, and an aircraft that has nowhere marked to go simply stops where it landed, exactly
     * as it always did.
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
        // requiresStands = true: a strip surveyed by this build is not a finished airfield until a
        // stand is marked beside it. Whether that sticks is decided by the caller — re-surveying an
        // airfield that is already registered keeps whatever the registered one had, so correcting a
        // threshold on an old field cannot turn it into one that refuses sorties. See
        // AirfieldReport#surveyAndRegister.
        return new Airfield(name, a, b, width, List.of(),
            countApproachObstacles(level, airfield.endA()),
            countApproachObstacles(level, airfield.endB()), true);
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

    /** Narrowest cross-section that is still worth believing, in blocks. */
    private static final int MIN_MEASURED_WIDTH = 3;

    /**
     * How far the strip reaches to either side of one point, in whole blocks.
     *
     * @param left          blocks of strip found to the left of the probed point
     * @param right         blocks of strip found to its right
     * @param leftBounded   whether the walk to the left stopped at an edge rather than running out of
     *                      probe range. Kept because {@code left == limit} is ambiguous on its own —
     *                      an edge exactly {@code limit} blocks out and ground that carries on past
     *                      the probe produce the same count, and only one of them is a measurement.
     * @param rightBounded  the same, to the right
     */
    private record CrossSection(int left, int right, boolean leftBounded, boolean rightBounded) {
        /** How far the probed point is to the right of the middle of what was found. */
        double offsetFromMiddle() {
            return (right - left) / 2.0;
        }

        int width() {
            return left + right + 1;
        }

        /** True when neither side found an edge, so this says nothing about where the strip ends. */
        boolean unbounded() {
            return !leftBounded && !rightBounded;
        }
    }

    /**
     * How far the strip reaches to either side of {@code point}, by elevation first and by surface
     * material only where elevation found nothing.
     *
     * <h2>Why there are two rules and why this is the order</h2>
     * The elevation walk — the strip continues sideways for as long as the surface stays within a
     * block of {@code reference} — is the whole rule and stays the whole rule wherever it works. It
     * is what a raised strip, an embankment, a plinth or a runway cut into a slope reads as, and it
     * is what every airfield that surveys correctly today is measured by.
     *
     * <p>It reads nothing at all on the case this exists for: a runway <em>painted</em> onto a field,
     * a strip of concrete or gravel or smooth stone laid flush with the ground it sits in. There the
     * probe walks off the runway and out across the field without ever seeing a change, so the survey
     * cannot tell where the strip ends. That was silent and it was wrong in three places at once —
     * the thresholds stayed on whatever corner block was clicked, the width came back as the probe
     * ceiling rather than a measurement, and {@link #centrelineOffset} read zero, so {@code airfields
     * info} did not even say the field needed re-surveying. Measured before this change on a 25-wide
     * smooth-stone strip flush on a stone plateau, both ends clicked on the {@code z=20} edge of a
     * strip running {@code z=20..44}: thresholds stored at {@code z=20}, no correction printed, the
     * whole take-off roll at {@code z=18.7..19.1} — <em>off the strip</em> — and the touchdown at
     * {@code z=21}, one block inside the near edge. Where the surrounding field does happen to have
     * an edge within probe range the answer was worse than useless rather than merely absent: the
     * same strip on a narrower plateau stored {@code z=24}, having centred the runway on the
     * <em>plateau</em>.
     *
     * <p><b>Material is consulted only when elevation is unbounded on both sides</b>, i.e. only when
     * the terrain has said nothing whatsoever about where the strip ends. That ordering is the whole
     * of the safety argument: a genuinely raised strip never reaches the material walk, so no survey
     * that works today can change its answer. The two are never blended and never minimised together
     * — a naturally patchy surface, grass beside dirt beside coarse dirt, would collapse the strip to
     * a block or two if material were allowed to override an edge the terrain really has.
     *
     * <p><b>A material answer that is not credible is thrown away</b> and the elevation answer is kept
     * exactly as it is today. Two ways it can fail: uniform ground — a superflat world, a plateau of
     * one block — walks to the limit on both sides and has found no edges either, and a patch narrower
     * than {@value #MIN_MEASURED_WIDTH} blocks is not a runway. Both give back the unbounded
     * elevation reading, which centres nothing and leaves the clicked line alone. Nothing here invents
     * a centreline out of ground that has none.
     *
     * @param limit how far to probe on each side. {@link #measureWidth} uses half
     *              {@link AutopilotConfig#SURVEY_MAX_WIDTH}, because it probes from the middle;
     *              {@link #centreOnStrip} uses the whole of it, because it probes from wherever the
     *              player clicked and that may be one full width away from the far edge.
     */
    private static CrossSection crossSection(Level level, Vec3 point, double heading,
                                             double reference, int limit) {
        CrossSection byHeight = walkOut(point, heading, limit,
            probe -> levelWith(level, probe, reference));
        if (!byHeight.unbounded()) {
            return byHeight;
        }
        Block surface = surfaceBlock(level, point);
        if (surface == null) {
            return byHeight;
        }
        CrossSection byMaterial = walkOut(point, heading, limit,
            probe -> surfaceBlock(level, probe) == surface);
        if (byMaterial.unbounded() || byMaterial.width() < MIN_MEASURED_WIDTH) {
            return byHeight;
        }
        return byMaterial;
    }

    /** Whether the column at {@code probe} is still the same strip as the point walked out from. */
    @FunctionalInterface
    private interface StripTest {
        boolean sameStrip(Vec3 probe);
    }

    /**
     * Walks out to both sides of {@code point}, perpendicular to {@code heading}, stopping on each
     * side at the first column {@code test} rejects.
     *
     * <p>Probes one column past {@code limit} purely to find out <em>why</em> the walk stopped, and
     * still reports at most {@code limit} blocks either way. Without that extra probe a strip whose
     * edge sits exactly on the limit is indistinguishable from ground that carries on for ever, and
     * telling those two apart is the entire precondition for consulting the surface material.
     */
    private static CrossSection walkOut(Vec3 point, double heading, int limit, StripTest test) {
        int right = 0;
        boolean rightBounded = false;
        for (int offset = 1; offset <= limit + 1; offset++) {
            if (!test.sameStrip(AutopilotMath.pointAlong(point, heading + 90.0, offset))) {
                rightBounded = true;
                break;
            }
            right = Math.min(offset, limit);
        }
        int left = 0;
        boolean leftBounded = false;
        for (int offset = 1; offset <= limit + 1; offset++) {
            if (!test.sameStrip(AutopilotMath.pointAlong(point, heading - 90.0, offset))) {
                leftBounded = true;
                break;
            }
            left = Math.min(offset, limit);
        }
        return new CrossSection(left, right, leftBounded, rightBounded);
    }

    /**
     * The block an aircraft would stand on in this column, or null where there is nothing to read.
     *
     * <p>One block below {@link TerrainScanner#surfaceHeight}, which reports the first <em>free</em>
     * block — the same convention {@link #snapToSurface} uses to turn a click into a threshold, so the
     * material compared here is the material of the surface the runway is made of.
     */
    private static @Nullable Block surfaceBlock(Level level, Vec3 probe) {
        int surface = TerrainScanner.surfaceHeight(level, probe.x, probe.z);
        if (surface == TerrainScanner.UNKNOWN_HEIGHT) {
            return null;
        }
        return level.getBlockState(BlockPos.containing(probe.x, surface - 1, probe.z)).getBlock();
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
     * <p><b>Ground the survey cannot tell from the strip is left alone.</b> The cross-section looks
     * for an edge in elevation and, only where the terrain has none to offer, in the surface material
     * — so a runway painted flush onto a field is centred on its own paint, and ground that is
     * uniform in both, a superflat world or a plateau of one block, produces no edges either way, an
     * offset of zero and the clicked line kept unchanged. See {@link #crossSection}. Nothing here
     * invents a centreline out of ground that has none.
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
     *
     * <p>Deliberately the same {@link #crossSection} the survey itself centres on, so this reports
     * exactly the correction {@code /autopilot airfields resurvey} would apply and never advertises
     * one the survey would then decline to make. That includes the material rule: a field painted
     * flush on a plain used to read 0 here — no edges, nothing to say — while sitting on the corner
     * of its own strip, which is the worst answer available, since it is both wrong and silent.
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
        return Math.max(MIN_MEASURED_WIDTH, crossSection(level, middle, heading, middle.y,
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
