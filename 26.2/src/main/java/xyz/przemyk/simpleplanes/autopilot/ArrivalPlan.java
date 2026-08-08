package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * How this arrival is going to be flown, decided once at range against what the airframe can
 * actually do — and said out loud, in one short phrase, so the choice can be argued with.
 *
 * <h2>Decided at range, not discovered at the gate</h2>
 * The user's question was why the whole route to a landing cannot be worked out a couple of hundred
 * blocks out and then simply flown. Measured on the rig, the answer was that nothing was worked out
 * at range at all. A sortie's last waypoint is the <em>centre of the destination runway</em>
 * ({@code AutopilotSpawner#launchInbound}), and the cruise ran to that waypoint before anything
 * about the arrival was decided: on a straight-in down the extended centreline the trace shows the
 * aircraft entering {@code DESCENT} 51 blocks <em>past</em> the threshold, over the strip, and then
 * flying a complete circuit — 90 blocks off the centreline at its widest — to get back to a fix 300
 * blocks out on the approach side. 1578 blocks of track for a 780-block flight.
 *
 * <p>So {@link #decisionRange} says how far out the arrival has to be settled, and
 * {@code PlaneAutopilot#tickCruise} hands over there instead of overhead. The range is derived from
 * the aircraft's own turn radius rather than being a constant, because the manoeuvre it has to pay
 * for is the join onto the centreline and that is {@code 2 x turnRadius} wide in the worst case —
 * 119 blocks for the starter airframe at cruise speed and 573 for a cargo plane, whose yaw rate is
 * a fifth. A single number cannot be right for both.
 *
 * <h2>Feasibility, not merely geometry</h2>
 * The four entries below are unchanged. What changed is the test. It used to be one line — can the
 * height above the fix be lost at {@link AutopilotConfig#MAX_DESCENT_ANGLE} — and that is a promise
 * the airframe does not keep:
 *
 * <ul>
 *   <li><b>The descent is sink-rate limited, not angle limited.</b> The altitude cascade clamps the
 *       commanded vertical speed to {@link AutopilotConfig#MAX_SINK_RATE} before it becomes a flight
 *       path angle, so the gradient really available is {@code min(tan(12 deg), 0.30 / v)}. At
 *       cruise speed that is 0.115 against 0.213 — the old figure claimed nearly twice the descent
 *       the aircraft could fly. See {@link AutopilotMath#descentAvailable}.</li>
 *   <li><b>The turn onto final has to fit.</b> Joining the centreline through an angle displaces the
 *       aircraft sideways by up to {@code 2 x turnRadius}, and that displacement has to be washed
 *       out before the landing gates arm at {@link AutopilotConfig#FINAL_HANDOVER_DISTANCE}. It fits
 *       comfortably on the starter airframe and does not fit at all for a heavy one joining fast,
 *       which is exactly the case that used to be found out at the gate: measured on the rig before
 *       {@code speedAtFix} existed, an aircraft that reached the fix at 1.91 blocks/tick swung 87
 *       blocks off the centreline and went around.</li>
 * </ul>
 *
 * <p>Both failures are repaired the same way and in the same order as before — extend the final, and
 * orbit only when no final can absorb it — because a longer final buys track for both the height and
 * the turn.
 *
 * <h2>Committed, and re-checked</h2>
 * The plan is decided once and then held. A plan that cannot be revised is worse than none the
 * moment the world changes, so {@link #stillGood} re-runs the same test against where the aircraft
 * actually is, on {@link AutopilotConfig#ARRIVAL_RECHECK_INTERVAL}, and a replan is a reported
 * event rather than a silent per-tick recomputation. Without the commitment the phrase alone gives
 * the game away: measured on a 172-block-high arrival, the old planner announced
 * {@code extended final 600 -> 450 -> 600 -> straight in} inside one second, because the extension
 * ladder is discrete and an aircraft between two rungs alternates between them.
 */
public record ArrivalPlan(RunwayEnd end, Entry entry, double interceptDistance, double excess) {

    public enum Entry {
        /** Fly the standard final; nothing needs to be given up first. */
        STRAIGHT_IN,
        /** Join the same glide slope further out, to have room for the height or for the turn. */
        EXTENDED,
        /** Too high, or too tight, for any final: orbit the fix and give it up there. */
        ORBIT,
        /** Someone else has the runway. */
        TRAFFIC;

        public boolean circling() {
            return this == ORBIT || this == TRAFFIC;
        }
    }

    /**
     * Everything about the aircraft the arrival has to be checked against. Grouped rather than
     * passed as four loose doubles because every one of them is needed by every check, and because
     * the point of this change is that the plan is a function of the <em>airframe</em> and not only
     * of the geometry.
     *
     * @param position          where the aircraft is now
     * @param speed             its horizontal speed, which is what the descent gradient depends on
     * @param rotationMultiplier the airframe's {@code getRotationSpeedMultiplier}: 1.0 on the
     *                          starter plane, 0.5 on the large one, 0.2 on the cargo plane
     */
    public record Capability(Vec3 position, double speed, double rotationMultiplier) {

        /** Tightest turn this aircraft can fly at the speed it will join the final at. */
        public double joinTurnRadius() {
            return AutopilotMath.turnRadius(AutopilotConfig.APPROACH_SPEED, rotationMultiplier);
        }

        /** Tightest turn it can fly right now, which is what the decision range is sized on. */
        public double currentTurnRadius() {
            return AutopilotMath.turnRadius(speed, rotationMultiplier);
        }
    }

    /**
     * The one-phrase account of this arrival, translated for a player and rendered in English for
     * the console. There is exactly one implementation: {@link #reason()} flattens this, so the
     * board, the status line and the report can never word the same decision differently.
     */
    public Component describe() {
        return switch (entry) {
            case STRAIGHT_IN -> AutopilotText.tr("plan.straight_in", "straight in");
            case EXTENDED -> AutopilotText.tr("plan.extended", "extended final %s",
                Math.round(interceptDistance));
            case ORBIT -> AutopilotText.tr("plan.orbit", "orbit to lose %s", Math.round(excess));
            case TRAFFIC -> AutopilotText.tr("plan.traffic", "holding, runway busy");
        };
    }

    /** {@link #describe()} as plain text, for the status line and the console reports. */
    public String reason() {
        return describe().getString();
    }

    /** The point on the extended centreline this approach joins the glide slope at. */
    public Vec3 interceptFix() {
        return end.approachPoint(interceptDistance, interceptHeight());
    }

    /** Height above the threshold at the intercept, i.e. the glide slope at that distance. */
    public double interceptHeight() {
        return end.glideSlopeAltitude(interceptDistance) - end.elevation();
    }

    /**
     * How far from the threshold this arrival has to be settled, in blocks.
     *
     * <p>The fix distance plus the room the join needs, floored at
     * {@link AutopilotConfig#ARRIVAL_DECISION_FLOOR}. The join term is what makes this a property of
     * the aircraft: {@value AutopilotConfig#ARRIVAL_DECISION_TURN_RADII} radii is the sideways
     * displacement of a course reversal, which is the worst entry there is, and it is 119 blocks for
     * the starter airframe at cruise and 573 for a cargo plane at the same speed.
     */
    public static double decisionRange(Capability aircraft, double interceptDistance) {
        double join = Math.max(AutopilotConfig.ARRIVAL_DECISION_FLOOR,
            AutopilotConfig.ARRIVAL_DECISION_TURN_RADII * aircraft.currentTurnRadius());
        return interceptDistance + join;
    }

    /** The decision range for the standard final, which is what the cruise leg hands over on. */
    public static double decisionRange(Capability aircraft) {
        return decisionRange(aircraft, AutopilotConfig.FINAL_INTERCEPT_DISTANCE);
    }

    /**
     * Works out the arrival for an aircraft with these capabilities.
     *
     * @param runwayFree whether this aircraft may have the runway; false always produces
     *                   {@link Entry#TRAFFIC}, because no amount of geometry beats another aircraft
     *                   already on the strip
     */
    public static ArrivalPlan decide(RunwayEnd end, Capability aircraft, boolean runwayFree) {
        if (!runwayFree) {
            return new ArrivalPlan(end, Entry.TRAFFIC, AutopilotConfig.FINAL_INTERCEPT_DISTANCE, 0);
        }
        double standard = AutopilotConfig.FINAL_INTERCEPT_DISTANCE;
        if (feasible(end, aircraft, standard)) {
            return new ArrivalPlan(end, Entry.STRAIGHT_IN, standard, 0);
        }
        // Try progressively longer finals. Stepping rather than solving in closed form because the
        // aircraft's distance to the fix is not a linear function of the fix distance — the fix
        // moves away from an aircraft on one side of the field and towards one on the other — and
        // because the lateral test is not monotonic in it either.
        for (double distance = standard + AutopilotConfig.INTERCEPT_EXTENSION_STEP;
             distance <= AutopilotConfig.MAX_INTERCEPT_DISTANCE;
             distance += AutopilotConfig.INTERCEPT_EXTENSION_STEP) {
            if (feasible(end, aircraft, distance)) {
                return new ArrivalPlan(end, Entry.EXTENDED, distance, 0);
            }
        }
        return new ArrivalPlan(end, Entry.ORBIT, AutopilotConfig.MAX_INTERCEPT_DISTANCE,
            excessAt(end, aircraft, AutopilotConfig.MAX_INTERCEPT_DISTANCE));
    }

    /**
     * Whether the committed plan still closes from where the aircraft now is — i.e. whether flying
     * it would still get the aircraft onto the slope, on the centreline, at the fix.
     *
     * <p>This is the check the whole feature turns on. A plan that stops closing is a go-around that
     * has not happened yet, and catching it here is the difference between repairing it in the air
     * and discovering it at the gate.
     */
    public boolean closes(Capability aircraft) {
        // An orbit closes for as long as no final can absorb the height, which is what put the
        // aircraft in it. The moment one can, the orbit has served its purpose and is over.
        if (entry == Entry.ORBIT) {
            return !feasible(end, aircraft, AutopilotConfig.MAX_INTERCEPT_DISTANCE);
        }
        // Slack here and nowhere else. {@link #decide} picks a plan that closes exactly, because it
        // is choosing; this asks whether the plan in progress is still worth flying, and the two are
        // not the same question. Without it the test is a hair-trigger as the fix is approached —
        // the distance still to run goes to zero, so any height at all above the slope reads as a
        // failure, and the rig duly recorded a straight-in tearing itself up into an extended final
        // five blocks short of its own fix and then straight back again.
        return excessAt(end, aircraft, interceptDistance) <= AutopilotConfig.ARRIVAL_PROFILE_SLACK
            && joinFits(end, aircraft, interceptDistance);
    }

    /**
     * Whether a materially shorter final would now close, i.e. whether the aircraft is being sent
     * further out than it needs to be.
     *
     * <p>Deliberately a whole {@link AutopilotConfig#ARRIVAL_REPLAN_MARGIN} rung rather than any
     * improvement at all: height comes off faster than the plan assumed on most arrivals, so a plan
     * that gave up its extension the moment it could would step down the ladder one rung a second
     * and be no commitment at all.
     */
    public boolean shorterAvailable(Capability aircraft) {
        if (entry != Entry.EXTENDED) {
            return false;
        }
        double shorter = interceptDistance - AutopilotConfig.ARRIVAL_REPLAN_MARGIN;
        return shorter >= AutopilotConfig.FINAL_INTERCEPT_DISTANCE && feasible(end, aircraft, shorter)
            || shorter < AutopilotConfig.FINAL_INTERCEPT_DISTANCE
                && feasible(end, aircraft, AutopilotConfig.FINAL_INTERCEPT_DISTANCE);
    }

    /**
     * Whether this aircraft can fly a final joined {@code interceptDistance} out: the height has to
     * come off on the way there, and the turn onto the centreline has to fit inside it.
     */
    private static boolean feasible(RunwayEnd end, Capability aircraft, double interceptDistance) {
        return excessAt(end, aircraft, interceptDistance) <= 0
            && joinFits(end, aircraft, interceptDistance);
    }

    /**
     * Height the aircraft would still be carrying when it reached the fix, having descended all the
     * way there as steeply as it is actually able to. Negative means it arrives low, which the
     * approach copes with by levelling off until the slope catches up.
     *
     * <p>The descent is measured with {@link AutopilotMath#descentAvailable} rather than with
     * {@code distance * tan(MAX_DESCENT_ANGLE)}: the cascade clamps the sink rate before the angle,
     * so a fast aircraft cannot fly the angle at all and the geometric figure is close to twice the
     * truth. That single term is the difference between a plan that closes on paper and one that
     * closes in the air.
     */
    private static double excessAt(RunwayEnd end, Capability aircraft, double interceptDistance) {
        Vec3 fix = end.approachPoint(interceptDistance, 0);
        double toFix = AutopilotMath.horizontalDistance(aircraft.position(), fix);
        double fixAltitude = end.glideSlopeAltitude(interceptDistance);
        double descentAvailable = AutopilotMath.descentAvailable(aircraft.speed(),
            AutopilotConfig.APPROACH_SPEED, toFix,
            AutopilotConfig.MAX_DESCENT_ANGLE, AutopilotConfig.MAX_SINK_RATE);
        return (aircraft.position().y - fixAltitude) - descentAvailable;
    }

    /**
     * Whether the turn from the run-in to the runway heading fits in the final being planned.
     *
     * <p>An aircraft joining through {@code theta} is displaced {@code r(1 - cos theta)} to the
     * outside of the turn before it rolls out, up to {@code 2r} for a course reversal. That
     * displacement then has to be washed off against the centreline, and the intercept cut the
     * approach uses is capped at 40 degrees, so it costs {@code offset / tan(40 deg)} of track —
     * and all of it has to be spent before {@link AutopilotConfig#FINAL_HANDOVER_DISTANCE}, where
     * the landing gates arm and start asking for an aircraft inside 10 degrees of the runway
     * heading and 12 degrees of bank.
     *
     * <p>On the starter airframe at {@link AutopilotConfig#APPROACH_SPEED} the radius is 11.5 blocks
     * and the worst case costs 27 of the 150 available, so this never binds and nothing about a
     * normal arrival changes. On a cargo plane the radius is 57 blocks and the same reversal costs
     * 136 — it only just fits, and it does not fit at all from the transit speed. That is the case
     * this test exists for, and the repair is a longer final rather than a go-around at the gate.
     */
    private static boolean joinFits(RunwayEnd end, Capability aircraft, double interceptDistance) {
        Vec3 fix = end.approachPoint(interceptDistance, 0);
        double toFix = AutopilotMath.horizontalDistance(aircraft.position(), fix);
        // Already inside the fix: there is no join left to fly, only the final itself.
        if (toFix < 1.0) {
            return true;
        }
        double runIn = AutopilotMath.headingTo(aircraft.position(), fix);
        double turn = Math.abs(AutopilotMath.angleDelta(runIn, end.landingHeading()));
        double radius = aircraft.joinTurnRadius();
        double displacement = radius * (1.0 - Math.cos(Math.toRadians(turn)));
        double washOut = displacement / Math.tan(Math.toRadians(APPROACH_INTERCEPT_CUT));
        return washOut <= interceptDistance - AutopilotConfig.FINAL_HANDOVER_DISTANCE;
    }

    /**
     * Largest cut {@code PlaneAutopilot#tickApproach} takes at the centreline, in degrees. Kept
     * beside the test that uses it because it is a property of that controller and not a tuning
     * knob: changing it there without changing it here would make the planner promise a capture the
     * approach cannot fly.
     */
    private static final double APPROACH_INTERCEPT_CUT = 40.0;
}
