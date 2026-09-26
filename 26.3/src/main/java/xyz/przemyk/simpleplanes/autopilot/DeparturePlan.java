package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Which way this aircraft is going to leave the field, decided before it releases the brakes.
 *
 * <h2>The end was being chosen backwards</h2>
 * {@code Airfield#departureEnd} called {@code bestEnd(level)} with no position and no destination,
 * and {@code bestEnd} answers a different question: <em>which threshold would you rather cross on
 * the way in</em>. Its score is the obstacle count of each end's <b>approach</b> funnel, which is
 * the ground <em>before</em> that threshold. A departure that rolls from that threshold runs the
 * other way down the strip and climbs out past the <em>far</em> one — over the opposite end's
 * funnel, which is the one {@code bestEnd} had just rejected. So on a field with a hill off one
 * end, the aircraft landed away from the hill and took off straight at it.
 *
 * <p>The fix is not to invert the call, because a departure has a second input the old code had
 * none of: <b>where it is going</b>. A sortie that turns 180 degrees off the runway spends the first
 * part of its climb flying away from its destination — measured on the rig as the whole length of
 * the strip plus the turn — and there is no reason to do that when the other end points the right
 * way and is just as clean.
 *
 * <h2>The score</h2>
 * <pre>    cost(end) = track from the far threshold to the first waypoint
 *              + turnRadius x the turn onto course, in radians
 *              + {@value AutopilotConfig#DEPARTURE_OBSTACLE_COST} x columns in the climb-out</pre>
 *
 * <p>The first term is what a wrong-way departure really costs — a runway length of flying in the
 * wrong direction — and the second is the turn itself; together they come to about 210 blocks on a
 * 160-block strip at climb speed. The obstacle term is the same 400 blocks a column costs an
 * arrival, so <b>one blocked column outweighs any wrong-way departure</b>, which is the ordering
 * this has to have: turning the aircraft round is cheap and climbing out at a hillside is not.
 *
 * <p>The obstacle count comes from the <em>survey</em>, not from a fresh measurement, for the same
 * reason {@code bestEnd} takes it from there: the survey ran with the ground loaded and a departure
 * is decided while most of the climb-out is not. An airfield stored before the counts were recorded
 * falls back to measuring, and that fallback counts an unknown column as an obstacle.
 */
public record DeparturePlan(RunwayEnd end, double turn, int climbOutObstacles,
                            TaxiPlanner.@Nullable Route taxi, double entryAlong, @Nullable Component note) {

    /** Turn onto course smaller than this is not worth calling a turn. */
    private static final double STRAIGHT_OUT = 20.0;

    /**
     * Rotation multiplier the destination score is computed with, whoever asks for it.
     *
     * <p>{@code AutopilotSpawner#launchSortie} scores the ends through {@code Airfield#departureEnd}
     * before there is an aircraft, to choose which threshold to put a new airframe beside, so the
     * score cannot depend on the airframe. The aircraft itself then chooses by taxi length (see
     * {@link #decideForTaxi}) and uses this score only to break a tie.
     */
    public static final double SCORING_ROTATION_MULTIPLIER = 1.0;

    public DeparturePlan(RunwayEnd end, double turn, int climbOutObstacles) {
        this(end, turn, climbOutObstacles, null, 0.0, null);
    }

    /**
     * Chooses the departure end for a flight going to {@code destination} without looking at the
     * ground between the aircraft and the runway: destination and climb-out only. Used before an
     * aircraft exists, to decide where to park a new one.
     *
     * @param destination the first waypoint, or null when the flight has none — in which case there
     *                    is nothing to be pointed at and the climb-out obstacles decide alone
     */
    public static DeparturePlan decide(Level level, Airfield airfield, @Nullable Vec3 destination,
                                       double rotationMultiplier) {
        RunwayEnd only = airfield.oneWayEnd();
        if (only != null) {
            return forEnd(level, airfield, only, destination, rotationMultiplier);
        }
        RunwayEnd a = airfield.endA();
        RunwayEnd b = airfield.endB();
        DeparturePlan planA = forEnd(level, airfield, a, destination, rotationMultiplier);
        DeparturePlan planB = forEnd(level, airfield, b, destination, rotationMultiplier);
        return cost(planA, destination, rotationMultiplier) <= cost(planB, destination, rotationMultiplier)
            ? planA : planB;
    }

    /** One end considered for a departure by an aircraft that is already on the ground. */
    private record Option(DeparturePlan plan, TaxiPlanner.Plan route, @Nullable Component closed,
                          boolean oneWayClosed) {

        double length() {
            return route.route() == null ? Double.MAX_VALUE : route.route().length();
        }
    }

    /**
     * Chooses the departure end for an aircraft standing at {@code plane}'s position: the end it can
     * reach by the shortest ground route, unless that end is closed by a one-way rule, faces
     * arrivals landing the other way, has obstacles in its climb-out that the other end does not,
     * or has no route at all. Runway length is enforced by the route itself: it may only join the
     * centreline where at least the airframe's required run is left ahead
     * ({@link TaxiPlanner#entryGoal}), so an intersection entry that is too short is never offered.
     *
     * <p>Lengths within {@link AutopilotConfig#TAXI_TIE_TOLERANCE} are a tie and the destination
     * score of {@link #decide} breaks it.
     */
    public static DeparturePlan decideForTaxi(Level level, Airfield airfield, @Nullable Vec3 destination,
                                              PlaneEntity plane) {
        TaxiPlanner.Dims dims = TaxiPlanner.dims(plane);
        Vec3 from = plane.position();
        List<String> arrivals = arrivalsLanding(level, airfield, plane);
        List<Option> options = new ArrayList<>(2);
        for (RunwayEnd end : airfield.ends()) {
            DeparturePlan base = forEnd(level, airfield, end, destination, SCORING_ROTATION_MULTIPLIER);
            Component closed = null;
            boolean oneWay = !airfield.allows(end);
            if (oneWay) {
                closed = AutopilotText.tr("plan.reason_one_way", "one-way %s", airfield.oneWay());
            } else if (arrivals.contains(end.opposite().designator())) {
                closed = AutopilotText.tr("plan.reason_arrivals", "arrivals landing %s",
                    end.opposite().designator());
            }
            TaxiPlanner.Plan route = oneWay ? TaxiPlanner.Plan.none("one-way")
                : TaxiPlanner.plan(level, airfield, plane, dims, from, List.of(TaxiPlanner.entryGoal(end, dims)), false);
            options.add(new Option(base, route, closed, oneWay));
        }

        // Usable: a route and nothing against it. Failing that, accept a conflict with arrivals (the
        // runway reservation still keeps them apart), never a one-way rule.
        List<Option> usable = new ArrayList<>();
        for (Option option : options) {
            if (option.route.route() != null && option.closed == null) {
                usable.add(option);
            }
        }
        if (usable.isEmpty()) {
            for (Option option : options) {
                if (option.route.route() != null && !option.oneWayClosed) {
                    usable.add(option);
                }
            }
        }
        if (usable.isEmpty()) {
            // No route to either end: keep the destination choice (restricted to an open end) and say why.
            DeparturePlan fallback = decide(level, airfield, destination, SCORING_ROTATION_MULTIPLIER);
            String problem = null;
            for (Option option : options) {
                if (option.plan.end.designator().equals(fallback.end.designator())) {
                    problem = option.route.problem();
                }
            }
            return new DeparturePlan(fallback.end, fallback.turn, fallback.climbOutObstacles, null, 0.0,
                AutopilotText.tr("plan.reason_no_route", "no taxi route: %s", problem == null ? "?" : problem));
        }

        Option chosen = usable.get(0);
        for (Option option : usable.subList(1, usable.size())) {
            chosen = better(chosen, option, destination);
        }

        // Why not the end with the shorter taxi, when that is not the one chosen.
        Option shortest = null;
        for (Option option : options) {
            if (option.route.route() != null && (shortest == null || option.length() < shortest.length())) {
                shortest = option;
            }
        }
        Option other = null;
        for (Option option : options) {
            if (option != chosen) {
                other = option;
            }
        }
        Component note = null;
        if (other != null) {
            String otherName = other.plan.end.designator();
            if (shortest == other && other.length() + AutopilotConfig.TAXI_TIE_TOLERANCE < chosen.length()) {
                Component why = other.closed != null ? other.closed
                    : other.plan.climbOutObstacles > chosen.plan.climbOutObstacles
                        ? AutopilotText.tr("plan.reason_climb_out", "%s in its climb-out", other.plan.climbOutObstacles)
                        : null;
                if (why != null) {
                    note = AutopilotText.tr("plan.not_end", "not %s: %s", otherName, why);
                }
            } else if (other.route.route() == null && !other.oneWayClosed
                && nearerThreshold(from, other.plan.end, chosen.plan.end)) {
                note = AutopilotText.tr("plan.not_end", "not %s: %s", otherName,
                    AutopilotText.tr("plan.reason_route", "%s", other.route.problem() == null ? "no route" : other.route.problem()));
            } else if (other.oneWayClosed && nearerThreshold(from, other.plan.end, chosen.plan.end)) {
                note = AutopilotText.tr("plan.not_end", "not %s: %s", otherName, other.closed);
            }
        }
        TaxiPlanner.Route route = chosen.route.route();
        // The nearest point of the runway may be too far down it for this airframe to depart from:
        // then the route runs on to where enough runway is left, and that is worth saying.
        if (route != null) {
            TaxiPlanner.Plan nearest = TaxiPlanner.plan(level, airfield, plane, dims, from,
                List.of(TaxiPlanner.centrelineGoal(airfield)), false);
            if (nearest.route() != null
                && route.length() > nearest.route().length() + AutopilotConfig.TAXI_TIE_TOLERANCE) {
                RunwayEnd end = chosen.plan.end;
                double run = end.length()
                    - AutopilotMath.alongTrack(end.threshold(), end.landingHeading(), nearest.route().end());
                double needed = dims.requiredRun() + AutopilotConfig.TAXI_LINEUP_ALLOWANCE;
                if (run < needed) {
                    AircraftType type = AircraftType.of(plane);
                    Component shortNote = AutopilotText.tr("plan.reason_short",
                        "nearest entry leaves %s blocks of run, %s needs %s",
                        Math.round(Math.max(0.0, run)), type == null ? "this aircraft" : type.getSerializedName(),
                        Math.round(needed));
                    note = note == null ? shortNote : Component.empty().append(note).append("; ").append(shortNote);
                }
            }
        }
        double along = route == null ? 0.0
            : AutopilotMath.alongTrack(chosen.plan.end.threshold(), chosen.plan.end.landingHeading(), route.end());
        DeparturePlan plan = chosen.plan;
        return new DeparturePlan(plan.end, plan.turn, plan.climbOutObstacles, route, Math.max(0.0, along), note);
    }

    private static boolean nearerThreshold(Vec3 from, RunwayEnd candidate, RunwayEnd than) {
        return AutopilotMath.horizontalDistance(from, candidate.threshold())
            < AutopilotMath.horizontalDistance(from, than.threshold());
    }

    /** Fewer climb-out obstacles first, then the shorter taxi, then the destination score. */
    private static Option better(Option a, Option b, @Nullable Vec3 destination) {
        if (a.plan.climbOutObstacles != b.plan.climbOutObstacles) {
            return a.plan.climbOutObstacles < b.plan.climbOutObstacles ? a : b;
        }
        if (Math.abs(a.length() - b.length()) > AutopilotConfig.TAXI_TIE_TOLERANCE) {
            return a.length() < b.length() ? a : b;
        }
        return cost(a.plan, destination, SCORING_ROTATION_MULTIPLIER)
            <= cost(b.plan, destination, SCORING_ROTATION_MULTIPLIER) ? a : b;
    }

    /** Designators of the ends arrivals to this field are committed to, other than {@code self}. */
    private static List<String> arrivalsLanding(Level level, Airfield airfield, PlaneEntity self) {
        List<String> ends = new ArrayList<>();
        for (PlaneEntity plane : AutopilotRegistry.active()) {
            if (plane == self || plane.level() != level) {
                continue;
            }
            PlaneAutopilot autopilot = plane.getAutopilot();
            if (autopilot == null) {
                continue;
            }
            String designator = autopilot.arrivalDesignator(airfield.name());
            if (designator != null && !ends.contains(designator)) {
                ends.add(designator);
            }
        }
        return ends;
    }

    private static DeparturePlan forEnd(Level level, Airfield airfield, RunwayEnd end,
                                        @Nullable Vec3 destination, double rotationMultiplier) {
        // Rolling from this threshold means climbing out past the far one, so the funnel that
        // matters is the opposite end's — the ground beyond where the wheels leave the strip.
        int obstacles = airfield.approachObstacles(level, end.opposite());
        double turn = destination == null ? 0
            : Math.abs(AutopilotMath.angleDelta(end.landingHeading(),
                AutopilotMath.headingTo(end.farEnd(), destination)));
        return new DeparturePlan(end, turn, obstacles);
    }

    private static double cost(DeparturePlan plan, @Nullable Vec3 destination, double rotationMultiplier) {
        double track = destination == null ? 0
            : AutopilotMath.horizontalDistance(plan.end.farEnd(), destination);
        double radius = AutopilotMath.turnRadius(AutopilotConfig.CLIMB_SPEED, rotationMultiplier);
        return track + radius * Math.toRadians(plan.turn)
            + plan.climbOutObstacles * AutopilotConfig.DEPARTURE_OBSTACLE_COST;
    }

    /** Taxi route length, or -1 when there is none. */
    public double taxiLength() {
        return taxi == null ? -1 : taxi.length();
    }

    /**
     * One short phrase for {@code /autopilot status} and the tower board, translated for a player
     * and English on the console — the same channel the arrival plan uses, so a flight's plan reads
     * the same way at both ends of it.
     */
    public Component describe() {
        MutableComponent text;
        if (climbOutObstacles > 0) {
            text = AutopilotText.tr("plan.departure_obstacles", "depart %s, %s in the climb-out",
                end.designator(), climbOutObstacles);
        } else if (turn < STRAIGHT_OUT) {
            text = AutopilotText.tr("plan.departure_straight", "depart %s, straight out",
                end.designator());
        } else {
            text = AutopilotText.tr("plan.departure_turn", "depart %s, %s deg turn to course",
                end.designator(), Math.round(turn));
        }
        if (taxi != null) {
            text.append(", ").append(AutopilotText.tr("plan.taxi_length", "taxi %s blocks",
                Math.round(taxi.length())));
            if (entryAlong > 2.0) {
                text.append(", ").append(AutopilotText.tr("plan.intersection", "entering %s blocks in, %s to run",
                    Math.round(entryAlong), Math.round(end.length() - entryAlong)));
            }
        }
        if (note != null) {
            text.append(", ").append(note);
        }
        return text;
    }
}
