package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Decides whether to climb over the terrain ahead or to go round it, on cost rather than on reflex.
 *
 * <p>{@link TerrainScanner} answers "how high is the ground ahead" and the altitude cascade answers
 * it by climbing. That is the right answer for a hill and the wrong one for a mountain: measured on
 * the reported case — a runway at 69 with a summit at 158 immediately off the north threshold and
 * open water at 61 a short way west — the aircraft spent its arrival climbing 90 blocks to cross
 * ground it could have flown beside, and arrived over the field high and fast exactly where it
 * needed to be low and slow. The old {@code TerrainScanner#avoidanceBias} did have a sidestep, but
 * it only fired when the ridge could not be out-climbed <em>at all</em>, and it chose its side by
 * comparing two fixed &plusmn;35&deg; probes — on the rig that sent the aircraft into the <em>higher</em>
 * flank and down to 15 blocks of ground clearance against its own 22-block minimum.
 *
 * <h2>The search</h2>
 * A handful of candidate headings, scored against each other, and nothing more. For each candidate
 * the ground is sampled along that heading out to a common horizon and the cost is
 *
 * <pre>    cost = extra track flown  +  {@value AutopilotConfig#CLIMB_TRACK_COST} x blocks of climb needed</pre>
 *
 * Flying straight on is one of the candidates, so "over" wins whenever it really is cheaper, which
 * over open or gently rolling ground it always is — the search then returns zero and the aircraft
 * behaves exactly as it did before.
 *
 * <h2>Why a block of climb is worth six blocks of track</h2>
 * A block of height gained at {@link AutopilotConfig#MAX_CLIMB_ANGLE} costs {@code 1/tan(18 deg)} =
 * 3.1 blocks of track, and every block gained to cross a ridge has to be given back on the far
 * side, which costs the same again on the descent limit. Six is that round trip. It is deliberately
 * not larger: the point is to stop the aircraft buying 90 blocks of height to cross a summit that a
 * 60-block sidestep clears, not to make it fly round every hummock.
 *
 * <h2>Unknown ground is never cheap</h2>
 * {@code TerrainScanner.surfaceHeight} returns {@link TerrainScanner#UNKNOWN_HEIGHT} for a column in
 * an unloaded chunk, and this whole codebase has one hard rule about that: unknown must never read
 * as clear. It is the bug that used to make an arriving aircraft choose the runway end it could not
 * see (see {@code Airfield#bestEnd}). So:
 *
 * <ul>
 *   <li>The <b>horizon</b> is how far the ground straight ahead is actually known. Nothing is ever
 *       planned beyond it.</li>
 *   <li>A candidate with a single unknown column inside that horizon is <b>discarded</b>, not
 *       optimistically scored. A deviation is a positive decision to leave the direct track, and it
 *       is only ever made towards ground that has been seen.</li>
 *   <li>Flying straight on needs no evidence, so it is always available. When nothing is known the
 *       planner returns zero and the terrain following behaves as it always has.</li>
 * </ul>
 *
 * <h2>Cost</h2>
 * The search runs only when the terrain ahead would actually force a climb, and then at most every
 * {@value AutopilotConfig#ROUTE_PLAN_INTERVAL} ticks. That is
 * {@code 13 candidates x 8 samples = 104} heightmap lookups per second per aircraft, i.e. about 5 a
 * tick — a quarter of what the always-on {@link TerrainScanner} profile already costs, and a
 * rounding error against the 24-aircraft cap. Over flat ground it costs nothing at all, because it
 * never runs.
 */
public final class RoutePlanner {

    /** Cost sentinel for a candidate whose ground could not all be seen. */
    private static final double UNSEEN = Double.MAX_VALUE;

    /** What the last search concluded, which is the whole of what the readouts report. */
    private enum Verdict { DIRECT, BLIND, OVER, AROUND }

    private double offset;
    private int committedTicks;
    private int nextPlanTick;
    private Verdict verdict = Verdict.DIRECT;
    /** Blocks of climb the decision turns on: what going straight would cost, and what is saved. */
    private double straightClimb;
    private double saved;

    /** Heading offset, in degrees, to add to the commanded heading. Zero means fly the track. */
    public double headingOffset() {
        return offset;
    }

    /**
     * One short phrase for the tower board, translated for a player and English on the console.
     * {@link #decision()} flattens the same component, so the two can never disagree.
     */
    public Component describe() {
        return switch (verdict) {
            case DIRECT -> AutopilotText.tr("plan.direct", "direct");
            case BLIND -> AutopilotText.tr("plan.blind", "direct (terrain not loaded)");
            case OVER -> AutopilotText.tr("plan.over", "over, %s to climb", Math.round(straightClimb));
            case AROUND -> AutopilotText.tr("plan.around", "around %s %s deg, saves %s of climb",
                AutopilotText.tr(offset < 0 ? "plan.left" : "plan.right", offset < 0 ? "left" : "right"),
                Math.round(Math.abs(offset)), Math.round(saved));
        };
    }

    /** {@link #describe()} as plain text, for {@code /autopilot status}. */
    public String decision() {
        return describe().getString();
    }

    public boolean deviating() {
        return offset != 0;
    }

    /** Forgets the current deviation, e.g. when the flight moves on to a different leg. */
    public void reset() {
        offset = 0;
        committedTicks = 0;
        verdict = Verdict.DIRECT;
    }

    /**
     * Re-plans if it is time to, otherwise leaves the standing decision alone.
     *
     * <p>Two separate pieces of hysteresis, for two separate ways of dithering. The side already
     * being flown keeps a {@value AutopilotConfig#ROUTE_PLAN_COMMIT_MARGIN}-block bonus, so a
     * marginal choice cannot alternate between left and right halfway round an obstacle. And for
     * {@value AutopilotConfig#ROUTE_PLAN_COMMIT_TICKS} ticks after a deviation is taken, losing
     * sight of the ground does not cancel it — the aircraft is already committed and the obstacle
     * has not moved.
     *
     * <p>Terrain that is genuinely clear always cancels a deviation immediately, whatever the
     * commitment: that branch is what returns the aircraft to its track once it is past.
     *
     * @param heading  the commanded heading <em>without</em> any deviation, so the search always
     *                 scores the real track as one of its candidates
     * @param altitude the altitude the aircraft would otherwise fly this leg at
     */
    public void update(Level level, Vec3 position, double altitude, double heading, int tick) {
        if (committedTicks > 0) {
            committedTicks--;
        }
        if (tick < nextPlanTick) {
            return;
        }
        nextPlanTick = tick + AutopilotConfig.ROUTE_PLAN_INTERVAL;

        double horizon = knownHorizon(level, position, heading);
        if (horizon < AutopilotConfig.ROUTE_PLAN_MIN_HORIZON) {
            // Not enough loaded ground to plan on. Hold whatever is being flown and let the
            // heightmap terrain following do its job; it copes with unknown by holding altitude.
            if (committedTicks == 0) {
                offset = 0;
                verdict = Verdict.BLIND;
            }
            return;
        }

        straightClimb = climbNeeded(level, position, altitude, heading, horizon);
        if (straightClimb <= 0) {
            offset = 0;
            committedTicks = 0;
            verdict = Verdict.DIRECT;
            return;
        }

        double bestOffset = 0;
        double bestCost = straightClimb * AutopilotConfig.CLIMB_TRACK_COST;
        double bestClimb = straightClimb;
        for (double magnitude = AutopilotConfig.ROUTE_PLAN_DEVIATION_STEP;
             magnitude <= AutopilotConfig.ROUTE_PLAN_MAX_DEVIATION;
             magnitude += AutopilotConfig.ROUTE_PLAN_DEVIATION_STEP) {
            for (double side : new double[] {-1.0, 1.0}) {
                double candidate = magnitude * side;
                double climb = climbNeeded(level, position, altitude, heading + candidate, horizon);
                if (climb == UNSEEN) {
                    continue;
                }
                double cost = deviationCost(candidate, horizon) + climb * AutopilotConfig.CLIMB_TRACK_COST;
                // Keeping the side already being flown when the costs are level stops the aircraft
                // swapping sides halfway round an obstacle, which is the worst of both routes.
                if (candidate * offset > 0) {
                    cost -= AutopilotConfig.ROUTE_PLAN_COMMIT_MARGIN;
                }
                if (cost < bestCost - AutopilotConfig.ROUTE_PLAN_COMMIT_MARGIN) {
                    bestCost = cost;
                    bestOffset = candidate;
                    bestClimb = climb;
                }
            }
        }

        if (bestOffset == 0) {
            offset = 0;
            committedTicks = 0;
            verdict = Verdict.OVER;
            return;
        }
        if (bestOffset != offset) {
            committedTicks = AutopilotConfig.ROUTE_PLAN_COMMIT_TICKS;
        }
        offset = bestOffset;
        saved = straightClimb - bestClimb;
        verdict = Verdict.AROUND;
    }

    /**
     * Extra track flown by leaving the direct line at {@code degrees} for {@code horizon} blocks and
     * rejoining it afterwards — the deviation is flown out and undone, so it is charged twice.
     */
    private static double deviationCost(double degrees, double horizon) {
        return 2.0 * horizon * (1.0 / Math.cos(Math.toRadians(degrees)) - 1.0);
    }

    /**
     * Blocks of climb this heading would force, or {@link #UNSEEN} when any column inside the
     * horizon is in an unloaded chunk.
     */
    private static double climbNeeded(Level level, Vec3 position, double altitude, double heading, double horizon) {
        double step = horizon / AutopilotConfig.ROUTE_PLAN_SAMPLES;
        double highest = Double.NEGATIVE_INFINITY;
        for (int i = 1; i <= AutopilotConfig.ROUTE_PLAN_SAMPLES; i++) {
            Vec3 probe = AutopilotMath.pointAlong(position, heading, step * i);
            int surface = TerrainScanner.surfaceHeight(level, probe.x, probe.z);
            if (surface == TerrainScanner.UNKNOWN_HEIGHT) {
                return UNSEEN;
            }
            highest = Math.max(highest, surface);
        }
        return Math.max(0.0, highest + AutopilotConfig.TERRAIN_CLEARANCE - altitude);
    }

    /** How far the ground along the current track is actually loaded, capped at the scan distance. */
    private static double knownHorizon(Level level, Vec3 position, double heading) {
        double step = (double) AutopilotConfig.SCAN_DISTANCE / AutopilotConfig.ROUTE_PLAN_SAMPLES;
        double reached = 0;
        for (int i = 1; i <= AutopilotConfig.ROUTE_PLAN_SAMPLES; i++) {
            Vec3 probe = AutopilotMath.pointAlong(position, heading, step * i);
            if (TerrainScanner.surfaceHeight(level, probe.x, probe.z) == TerrainScanner.UNKNOWN_HEIGHT) {
                break;
            }
            reached = step * i;
        }
        return reached;
    }
}
