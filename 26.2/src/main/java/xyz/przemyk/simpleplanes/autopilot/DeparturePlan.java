package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

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
public record DeparturePlan(RunwayEnd end, double turn, int climbOutObstacles) {

    /** Turn onto course smaller than this is not worth calling a turn. */
    private static final double STRAIGHT_OUT = 20.0;

    /**
     * Chooses the departure end for a flight going to {@code destination}.
     *
     * @param destination the first waypoint, or null when the flight has none — in which case there
     *                    is nothing to be pointed at and the climb-out obstacles decide alone
     */
    public static DeparturePlan decide(Level level, Airfield airfield, @Nullable Vec3 destination,
                                       double rotationMultiplier) {
        RunwayEnd a = airfield.endA();
        RunwayEnd b = airfield.endB();
        DeparturePlan planA = forEnd(level, airfield, a, destination, rotationMultiplier);
        DeparturePlan planB = forEnd(level, airfield, b, destination, rotationMultiplier);
        return cost(planA, destination, rotationMultiplier) <= cost(planB, destination, rotationMultiplier)
            ? planA : planB;
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

    /**
     * One short phrase for {@code /autopilot status} and the tower board, translated for a player
     * and English on the console — the same channel the arrival plan uses, so a flight's plan reads
     * the same way at both ends of it.
     */
    public Component describe() {
        if (climbOutObstacles > 0) {
            return AutopilotText.tr("plan.departure_obstacles", "depart %s, %s in the climb-out",
                end.designator(), climbOutObstacles);
        }
        if (turn < STRAIGHT_OUT) {
            return AutopilotText.tr("plan.departure_straight", "depart %s, straight out",
                end.designator());
        }
        return AutopilotText.tr("plan.departure_turn", "depart %s, %s deg turn to course",
            end.designator(), Math.round(turn));
    }
}
