package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.api.AirspaceGuards;
import xyz.przemyk.simpleplanes.api.Flight;

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
 * <h2>Claimed airspace</h2>
 * Terrain is not the only reason to leave the direct track. A mod that claims land — see
 * {@link AirspaceGuards} — can also say "this pilot should not be over here", and that answer is
 * folded into the same search rather than bolted on beside it, because the two decisions are the
 * same decision: which of these headings is the cheapest way to get where we are going.
 *
 * <p>It is folded in <b>lexicographically, not additively</b>. Each candidate is scored as the pair
 * {@code (avoided samples, track cost)} and compared on the first key before the second. Blending
 * the two into one number was tried on paper and rejected: any weight small enough not to swamp a
 * 90-block climb is small enough that a marginally shorter route wins over an entirely clear one,
 * and any weight large enough to always win is a weight that makes the climb term decorative. The
 * pair says what is actually meant — <em>prefer a route that stays out; among routes that are
 * equally out, fly the cheapest one</em> — and it degenerates to exactly the old comparison when
 * every candidate scores zero avoided samples, which is every candidate on every server that has no
 * guard registered.
 *
 * <p>The first key is read at <b>two resolutions</b>, and {@link #better} is where that happens.
 * Clear against not-clear is the question the feature exists to answer and is never damped. But once
 * both routes are known to cross claimed sky the same number has stopped being a verdict and become
 * a <em>depth</em>, and depth jitters by a sample or two per search purely because the aircraft has
 * moved — so within that band a candidate must beat the side already being flown by more than one
 * sample. Without that, an aircraft crossing a claim too wide to steer round swaps between 60
 * degrees left and 60 degrees right from one second to the next; it still arrives, but it flies like
 * it is drunk, and that was measured rather than imagined.
 *
 * <p>What is asked, and of whom, is the guard's business and not this class's. The search hands over
 * a {@link Flight} — the aircraft, the pilot, whether that pilot is actually <em>aboard</em>, and the
 * two ends of the leg — built once per search and shared by all 105 probes of it, and then scores
 * whatever comes back. A guard that only wants to claim sky against manned flights, or that exempts
 * the claim its own airfield sits in so an aircraft can land there, expresses that by answering
 * {@code false}; nothing in the geometry below knows which rule produced a zero.
 *
 * <h2>Why the pilot is never trapped</h2>
 * There is deliberately no "reject" outcome anywhere in this class. Every search ends in a heading,
 * and the worst it can say is "this one, then". Three cases carry that:
 * <ul>
 *   <li><b>Already inside when the autopilot engages.</b> No candidate is clear, so the first key
 *       cannot pick one on cleanliness; it picks the fewest avoided samples, which is the heading
 *       that leaves soonest. Measured from the middle of a claim, that came out as offset zero —
 *       straight out of the near side — and the aircraft was clear in about six seconds.</li>
 *   <li><b>A claim wider than the horizon.</b> Every candidate scores the same, the first key ties,
 *       and the terrain cost decides, so the aircraft flies its ordinary route out of the far
 *       side.</li>
 *   <li><b>The destination itself inside a claim.</b> Nothing here refuses the leg. The aircraft
 *       stands off while a way round still looks cheaper, and as the waypoint closes, every heading
 *       is equally claimed, the first key ties, and the ordinary arrival logic takes it in. That was
 *       flown: it arrives, having taken a wider path than a permitted pilot would. A guard that is
 *       given the destination — see {@link xyz.przemyk.simpleplanes.api.FlightAwareAirspaceGuard} —
 *       can do better than "eventually" and simply stop claiming the one that contains it. The
 *       fallback here is what happens when it does not, and it is still an arrival.</li>
 * </ul>
 *
 * <h2>Cost</h2>
 * The search runs only when the terrain ahead would actually force a climb, when a deviation is
 * already being flown, or when a guard is registered <em>and</em> the direct track is not already
 * clear of both terrain and claims — and then at most every
 * {@value AutopilotConfig#ROUTE_PLAN_INTERVAL} ticks. That is
 * {@code 13 candidates x 8 samples = 104} heightmap lookups per second per aircraft, i.e. about 5 a
 * tick — a quarter of what the always-on {@link TerrainScanner} profile already costs, and a
 * rounding error against the 24-aircraft cap. Over flat, unclaimed ground it costs one 8-sample
 * probe of the direct track plus one guard call for the aircraft's own position, once a second, and
 * nothing else; with no guard registered it costs nothing at all, because it never runs.
 *
 * <p>The guard calls track the heightmap lookups one for one — the same eight points, asked in the
 * same loop — plus the single "where are we" call. So the worst case a guard can be asked to answer
 * is 105 questions per second per aircraft, and the common case is 9.
 */
public final class RoutePlanner {

    /** Cost sentinel for a candidate whose ground could not all be seen. */
    private static final double UNSEEN = Double.MAX_VALUE;

    /**
     * What the last search concluded, which is the whole of what the readouts report.
     *
     * <p>The three airspace outcomes are kept apart because they are three different things to read
     * off a board and a person acts differently on each: {@code AIRSPACE_AROUND} is the feature
     * working, {@code AIRSPACE_CLIPPED} is it doing the best it can with a claim too wide to get
     * round, and {@code AIRSPACE_LEAVING} is an aircraft that was already inside when the autopilot
     * took it. Collapsing them loses exactly the distinction somebody looking at the board is after.
     */
    private enum Verdict { DIRECT, BLIND, OVER, AROUND, AIRSPACE_AROUND, AIRSPACE_CLIPPED, AIRSPACE_LEAVING }

    private double offset;
    private int committedTicks;
    private int nextPlanTick;
    private Verdict verdict = Verdict.DIRECT;
    /** Blocks of climb the decision turns on: what going straight would cost, and what is saved. */
    private double straightClimb;
    private double saved;
    /** Probes of the direct track that landed in claimed airspace at the last search. */
    private int straightAvoided;

    /**
     * One candidate heading's score: how much of it is inside claimed airspace, and what the terrain
     * costs. Compared on the first field before the second — see the class note on why the two are
     * not blended into one number.
     *
     * @param avoided samples of this candidate that a guard claimed, 0 when nothing is registered.
     * @param climb   blocks of climb this heading would force, or {@link #UNSEEN}.
     */
    private record Probe(int avoided, double climb) {}

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
            case AIRSPACE_AROUND -> AutopilotText.tr("plan.airspace", "around claimed airspace, %s %s deg",
                AutopilotText.tr(offset < 0 ? "plan.left" : "plan.right", offset < 0 ? "left" : "right"),
                Math.round(Math.abs(offset)));
            // Not dressed up as a clean deviation, because it is not one: the claim is wider than
            // anything this search can steer round, and the track still crosses some of it. Saying
            // "around claimed airspace" here would be a readout that promises more than happened.
            case AIRSPACE_CLIPPED -> offset == 0
                ? AutopilotText.tr("plan.airspace_cross", "claimed airspace ahead, no way round it")
                : AutopilotText.tr("plan.airspace_clipped",
                    "claimed airspace ahead, %s %s deg is the clearest",
                    AutopilotText.tr(offset < 0 ? "plan.left" : "plan.right", offset < 0 ? "left" : "right"),
                    Math.round(Math.abs(offset)));
            // Said outright rather than dressed up as a deviation, because the aircraft is not
            // getting out of the way of anything: it is inside, and it is leaving by the shortest
            // way it can see. Anyone reading the board needs to know which of the three is happening.
            // Straight out gets its own phrasing: "leaving right 0 deg" is not a sentence.
            case AIRSPACE_LEAVING -> offset == 0
                ? AutopilotText.tr("plan.airspace_inside_straight",
                    "inside claimed airspace, leaving straight ahead")
                : AutopilotText.tr("plan.airspace_inside",
                    "inside claimed airspace, leaving %s %s deg",
                    AutopilotText.tr(offset < 0 ? "plan.left" : "plan.right", offset < 0 ? "left" : "right"),
                    Math.round(Math.abs(offset)));
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
        straightAvoided = 0;
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
     * @param guarded  what is being flown, by and for whom, and between where and where — passed
     *                 straight through to any {@link AirspaceGuards} and used for nothing else, or
     *                 <b>null when there is no guard to ask</b>. The caller builds one only when
     *                 {@link AirspaceGuards#isActive}, so null here means "nobody is listening", not
     *                 "anonymous flight", and it is the whole of what this feature costs a server
     *                 that has no land-claim mod: every {@link Probe} then carries {@code avoided}
     *                 zero and the search is the terrain-only one it always was.
     */
    public void update(Level level, Vec3 position, double altitude, double heading, int tick,
                       @Nullable Flight guarded) {
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

        // Where the aircraft actually is, asked once, rather than inferred from the samples ahead of
        // it. The nearest sample is a third of the horizon away, so "the track ahead is claimed" and
        // "we are standing in it" are genuinely different facts and the readouts have to tell them
        // apart -- an aircraft 200 blocks short of a border was being reported as inside one. One
        // extra guard call per search buys that, against the 104 the candidate loop can make.
        boolean insideNow = guarded != null
            && AirspaceGuards.isAvoided(guarded, new Vec3(position.x, altitude, position.z));

        Probe straight = probe(level, position, altitude, heading, horizon, guarded);
        straightClimb = straight.climb();
        straightAvoided = straight.avoided();
        if (straightClimb <= 0 && straightAvoided == 0) {
            // Nothing to climb and nobody's sky in the way: this is the whole of the common case,
            // and it is the branch that keeps a guarded server from paying for the candidate loop
            // over ordinary ground.
            //
            // Deliberately not qualified by insideNow. An aircraft standing inside a claim whose
            // whole horizon is clear is already on its way out under its own heading; there is
            // nothing to decide and "direct" is the honest word for it. Sending it round the
            // candidate loop instead would cost 104 probes to arrive at the same zero offset.
            offset = 0;
            committedTicks = 0;
            verdict = Verdict.DIRECT;
            return;
        }

        double bestOffset = 0;
        int bestAvoided = straightAvoided;
        double bestCost = straightClimb * AutopilotConfig.CLIMB_TRACK_COST;
        double bestClimb = straightClimb;
        for (double magnitude = AutopilotConfig.ROUTE_PLAN_DEVIATION_STEP;
             magnitude <= AutopilotConfig.ROUTE_PLAN_MAX_DEVIATION;
             magnitude += AutopilotConfig.ROUTE_PLAN_DEVIATION_STEP) {
            for (double side : new double[] {-1.0, 1.0}) {
                double candidate = magnitude * side;
                Probe scored = probe(level, position, altitude, heading + candidate, horizon, guarded);
                if (scored.climb() == UNSEEN) {
                    continue;
                }
                double cost = deviationCost(candidate, horizon)
                    + scored.climb() * AutopilotConfig.CLIMB_TRACK_COST;
                // Keeping the side already being flown when the costs are level stops the aircraft
                // swapping sides halfway round an obstacle, which is the worst of both routes.
                boolean holdingSide = candidate * offset > 0;
                if (holdingSide) {
                    cost -= AutopilotConfig.ROUTE_PLAN_COMMIT_MARGIN;
                }
                if (better(scored.avoided(), cost, bestAvoided, bestCost, holdingSide)) {
                    bestAvoided = scored.avoided();
                    bestCost = cost;
                    bestOffset = candidate;
                    bestClimb = scored.climb();
                }
            }
        }

        if (bestOffset == 0) {
            offset = 0;
            committedTicks = 0;
            // Straight on won. Saying "over" when the reason is somebody's claim rather than a hill
            // would be a lie about why, so airspace is reported first when it is in play at all.
            verdict = airspaceVerdict(insideNow, bestAvoided, Verdict.OVER);
            return;
        }
        if (bestOffset != offset) {
            committedTicks = AutopilotConfig.ROUTE_PLAN_COMMIT_TICKS;
        }
        offset = bestOffset;
        saved = straightClimb - bestClimb;
        verdict = airspaceVerdict(insideNow, bestAvoided, Verdict.AROUND);
    }

    /**
     * Which of the three airspace outcomes this search is, or the terrain verdict when it is none of
     * them.
     *
     * <p>The order is the order of what a person needs to know first, and each test is a fact rather
     * than an inference:
     * <ol>
     *   <li><b>Inside.</b> Asked of the aircraft's own position, so this is where it <em>is</em>,
     *       not where its samples went. Reported before anything else because it is the case that
     *       looks alarming and is not: the aircraft is leaving, by the best heading it can see.</li>
     *   <li><b>Clipped.</b> Outside, and the chosen route still crosses claimed sky — the claim is
     *       wider than a {@value AutopilotConfig#ROUTE_PLAN_MAX_DEVIATION}-degree deviation can get
     *       round. The route is the clearest available, not a clear one, and the readout says so.</li>
     *   <li><b>Around.</b> Outside, chosen route completely clear, and the search only left the
     *       track because of a claim. This is the feature having worked.</li>
     * </ol>
     *
     * <p>Falls through to the terrain verdict whenever no claim was involved at all, which on a
     * server with no guard registered is every single search.
     */
    private Verdict airspaceVerdict(boolean insideNow, int bestAvoided, Verdict terrain) {
        if (insideNow) {
            return Verdict.AIRSPACE_LEAVING;
        }
        if (bestAvoided > 0) {
            return Verdict.AIRSPACE_CLIPPED;
        }
        // A clear best route only counts as an airspace decision if a claim is what pushed it off
        // the direct track. Without that test every ordinary terrain deviation on a server that
        // happens to have a guard would be captioned as an airspace one.
        return straightAvoided > 0 ? Verdict.AIRSPACE_AROUND : terrain;
    }

    /**
     * Whether a candidate beats the standing best, on the pair described in the class note.
     *
     * <p>With no guard registered every {@code avoided} is 0, the first branch never fires, and this
     * is character for character the comparison this class made before airspace existed. That is not
     * a hope, it is the only path through the method when the counts are equal.
     */
    private static boolean better(int avoided, double cost, int bestAvoided, double bestCost,
                                  boolean holdingSide) {
        boolean clear = avoided == 0;
        boolean bestClear = bestAvoided == 0;
        if (clear != bestClear) {
            // Clear against not clear, and this one is never damped. The margin exists to stop the
            // aircraft dithering between two routes of near-equal length; "one of these is out of
            // somebody's airspace and the other is not" is not a near-equal pair, it is the whole
            // question the feature was added to answer.
            return clear;
        }
        if (avoided != bestAvoided) {
            // Both routes cross claimed sky, so this key has stopped being a verdict and become a
            // depth: eight samples spread over the horizon, of which some number landed inside.
            // Depth moves by a sample or two from one search to the next simply because the aircraft
            // has moved 20 blocks, and comparing it undamped made an aircraft crossing a claim wider
            // than it could steer round swap sides at 60 degrees from one second to the next --
            // measured, not feared. So a candidate that is not the side already being flown has to
            // be better by more than one sample to take the turn. One sample, not a tuned constant:
            // it is the smallest quantity this key can differ by, which makes it the smallest damping
            // that can suppress a one-sample flip, and anything larger would start refusing genuinely
            // better routes.
            return avoided < bestAvoided - (holdingSide ? 0 : 1);
        }
        return cost < bestCost - AutopilotConfig.ROUTE_PLAN_COMMIT_MARGIN;
    }

    /**
     * Extra track flown by leaving the direct line at {@code degrees} for {@code horizon} blocks and
     * rejoining it afterwards — the deviation is flown out and undone, so it is charged twice.
     */
    private static double deviationCost(double degrees, double horizon) {
        return 2.0 * horizon * (1.0 / Math.cos(Math.toRadians(degrees)) - 1.0);
    }

    /**
     * Scores one candidate heading: the blocks of climb it would force, and how many of its samples
     * a guard claimed.
     *
     * <p>One loop for both answers rather than two, because they are asked about exactly the same
     * eight points. That is not a micro-optimisation — running the airspace question as a second
     * pass would double the {@code pointAlong} trigonometry as well as the probes, for a question
     * that is answered on the same column the heightmap was just read from.
     *
     * <p>The climb half short-circuits on an unloaded column exactly as it always did, and the
     * airspace half is then irrelevant: the caller discards an {@link #UNSEEN} candidate outright.
     * Unknown ground never reads as clear, in either sense of clear.
     *
     * @param guarded the flight to ask guards about, or null when nothing is registered or the
     *                switch is off. Null is the whole of the "this feature costs nothing" path: the
     *                probe loop then never touches {@link AirspaceGuards}.
     */
    private static Probe probe(Level level, Vec3 position, double altitude, double heading, double horizon,
                               @Nullable Flight guarded) {
        double step = horizon / AutopilotConfig.ROUTE_PLAN_SAMPLES;
        double highest = Double.NEGATIVE_INFINITY;
        int avoided = 0;
        for (int i = 1; i <= AutopilotConfig.ROUTE_PLAN_SAMPLES; i++) {
            Vec3 point = AutopilotMath.pointAlong(position, heading, step * i);
            int surface = TerrainScanner.surfaceHeight(level, point.x, point.z);
            if (surface == TerrainScanner.UNKNOWN_HEIGHT) {
                return new Probe(0, UNSEEN);
            }
            highest = Math.max(highest, surface);
            if (guarded != null
                && AirspaceGuards.isAvoided(guarded, new Vec3(point.x, altitude, point.z))) {
                avoided++;
            }
        }
        return new Probe(avoided, Math.max(0.0, highest + AutopilotConfig.TERRAIN_CLEARANCE - altitude));
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
