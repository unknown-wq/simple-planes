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
     */
    private static @Nullable ParkingSpot markedParkingPosition(Level level, RunwayEnd departure) {
        Airfield airfield = departure.airfield();
        Vec3 threshold = departure.threshold();
        for (BlockPos spot : airfield.parkingSpots()) {
            Vec3 position = usableParkingSpot(level, spot, threshold);
            if (position != null && standFree(level, airfield, position, spot, null)) {
                return new ParkingSpot(position,
                    AutopilotMath.headingTo(position, threshold), false, spot);
            }
        }
        return null;
    }

    /**
     * The marked spot {@code spot} as a usable parking position for a departure from
     * {@code threshold}, or null if the ground there or on the way no longer works.
     */
    private static @Nullable Vec3 usableParkingSpot(Level level, BlockPos spot, Vec3 threshold) {
        Vec3 probe = new Vec3(spot.getX() + 0.5, 0, spot.getZ() + 0.5);
        // The distance is re-checked here as well as at marking time, and against *this* threshold
        // rather than the nearest one. A stand is validated when it is marked against whichever
        // threshold it is closer to; which end a sortie departs from is chosen per flight by
        // bestEnd, so a stand 14 blocks behind one threshold of a 183-block strip is 169 blocks from
        // the other. Seen on the rig once arrivals started filling stands up: with the two nearer
        // ones occupied, a departure took the far one and spent the whole TAXI_TIMEOUT crawling to
        // the threshold, then departed on "could not line up cleanly". Dropping through to the next
        // stand, and finally to the derived apron beside the departure threshold, is a much shorter
        // roll to the same runway.
        if (AutopilotMath.horizontalDistance(probe, threshold) > AutopilotConfig.PARKING_MAX_TAXI_DISTANCE) {
            return null;
        }
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
        AABB box = AABB.ofSize(position, AutopilotConfig.PARKING_SPOT_CLEARANCE * 2,
            6.0, AutopilotConfig.PARKING_SPOT_CLEARANCE * 2);
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
        double heading = end.landingHeading();
        Vec3 threshold = end.threshold();
        for (int distance = AutopilotConfig.SURVEY_APPROACH_STEP;
             distance <= AutopilotConfig.SURVEY_APPROACH_LENGTH;
             distance += AutopilotConfig.SURVEY_APPROACH_STEP) {
            Vec3 probe = AutopilotMath.pointAlong(threshold, heading + 180.0, distance);
            double allowed = Math.max(
                end.glideSlopeAltitude(distance) - AutopilotConfig.SURVEY_OBSTACLE_MARGIN,
                end.elevation());
            int terrain = TerrainScanner.surfaceHeight(level, probe.x, probe.z);
            if (terrain == TerrainScanner.UNKNOWN_HEIGHT || terrain > allowed) {
                score++;
            }
        }
        return score;
    }

    /**
     * Counts terrain columns that poke above the glide slope in the approach funnel of one end.
     * Uses the heightmap, so it is O(1) per sample and never forces a chunk load.
     *
     * <p>Columns in unloaded chunks are not counted, because they were not measured. That makes this
     * an honest report and a dangerous ranking — see {@link #scoreApproach} and {@link #bestEnd}.
     */
    public static int countApproachObstacles(Level level, RunwayEnd end) {
        int violations = 0;
        double heading = end.landingHeading();
        Vec3 threshold = end.threshold();
        for (int distance = AutopilotConfig.SURVEY_APPROACH_STEP;
             distance <= AutopilotConfig.SURVEY_APPROACH_LENGTH;
             distance += AutopilotConfig.SURVEY_APPROACH_STEP) {
            Vec3 probe = AutopilotMath.pointAlong(threshold, heading + 180.0, distance);
            // Never allow less clearance than the runway's own elevation. The margin is subtracted
            // from a slope that starts at the threshold, so within the first couple of samples it
            // asks for headroom *below* the ground the runway is built on: on a perfectly flat
            // superflat test world every airfield reported "approach obstacles 2" at both ends, from
            // the 10- and 20-block samples, with nothing there at all. Ground at runway level is the
            // runway, not an obstacle.
            double allowed = Math.max(
                end.glideSlopeAltitude(distance) - AutopilotConfig.SURVEY_OBSTACLE_MARGIN,
                end.elevation());
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
