package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * How this arrival is going to be flown, decided from the height still to be lost and the distance
 * there is to lose it in — and said out loud, in one short phrase, so the choice can be argued with.
 *
 * <p><b>The pattern is not the default.</b> It used to be: every arrival flew to a fix a fixed
 * {@value AutopilotConfig#FINAL_INTERCEPT_DISTANCE} blocks out at
 * {@value AutopilotConfig#PATTERN_HEIGHT} above the threshold, whatever height it happened to arrive
 * with, and an aircraft that could not get down in that distance simply pressed on, crossed the
 * threshold still airborne and went around. Measured on the rig with an arrival 172 blocks above the
 * runway: it flew the whole 300-block final still 100 blocks high, went around, and took <b>150
 * seconds</b> from top of descent to wheels stopped against 66 seconds for the same arrival flown
 * from a sane height. The orbits a user sees are mostly that loop, not a deliberate hold.
 *
 * <p>So the geometry is chosen instead of assumed, in this order:
 *
 * <ol>
 *   <li><b>Straight in</b> when the height above the standard fix can be lost on the way to it at
 *       {@link AutopilotConfig#MAX_DESCENT_ANGLE}. This is the normal case and it is what the user
 *       asked for: if the runway is free and the slope can be made, just fly the approach.</li>
 *   <li><b>Extended final</b> otherwise — join the centreline further out, on the same glide slope.
 *       Extending helps twice over: there is more track to descend over <em>and</em> the slope is
 *       higher that far out, so there is less to lose. It also makes progress towards the runway,
 *       which an orbit does not, so it is always tried first.</li>
 *   <li><b>Descending orbit</b> only when even the longest final cannot absorb the height. This is
 *       the one case where circling is the right answer, and the phrase says how much height it is
 *       there to lose.</li>
 *   <li><b>Holding</b> when the runway is occupied. Traffic, not geometry.</li>
 * </ol>
 */
public record ArrivalPlan(RunwayEnd end, Entry entry, double interceptDistance, double excess) {

    public enum Entry {
        /** Fly the standard final; nothing needs to be given up first. */
        STRAIGHT_IN,
        /** Join the same glide slope further out, to have room for the height. */
        EXTENDED,
        /** Too high for any final: orbit the fix and give the height up there. */
        ORBIT,
        /** Someone else has the runway. */
        TRAFFIC;

        public boolean circling() {
            return this == ORBIT || this == TRAFFIC;
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
     * Works out the arrival for an aircraft at {@code position}.
     *
     * @param runwayFree whether this aircraft may have the runway; false always produces
     *                   {@link Entry#TRAFFIC}, because no amount of geometry beats another aircraft
     *                   already on the strip
     */
    public static ArrivalPlan decide(RunwayEnd end, Vec3 position, boolean runwayFree,
                                     double rotationSpeedMultiplier) {
        // The shortest final this airframe can fly, not the shortest final there is: a cargo plane
        // turns at a fifth of the starter plane's rate, so the 180-degree join at the fix throws it
        // five times as far off the centreline and it needs the room back. See
        // AutopilotConfig#APPROACH_RADII_NEEDED — for plane and large this is exactly 300 and
        // nothing below changes at all.
        double standard = AutopilotConfig.minimumInterceptDistance(rotationSpeedMultiplier);
        if (!runwayFree) {
            return new ArrivalPlan(end, Entry.TRAFFIC, standard, 0);
        }
        if (reachable(end, position, standard)) {
            return new ArrivalPlan(end, Entry.STRAIGHT_IN, standard, 0);
        }
        // Try progressively longer finals. Stepping rather than solving in closed form because the
        // aircraft's distance to the fix is not a linear function of the fix distance — the fix
        // moves away from an aircraft on one side of the field and towards one on the other.
        for (double distance = standard + AutopilotConfig.INTERCEPT_EXTENSION_STEP;
             distance <= AutopilotConfig.MAX_INTERCEPT_DISTANCE;
             distance += AutopilotConfig.INTERCEPT_EXTENSION_STEP) {
            if (reachable(end, position, distance)) {
                return new ArrivalPlan(end, Entry.EXTENDED, distance, 0);
            }
        }
        return new ArrivalPlan(end, Entry.ORBIT, AutopilotConfig.MAX_INTERCEPT_DISTANCE,
            excessAt(end, position, AutopilotConfig.MAX_INTERCEPT_DISTANCE));
    }

    /** True when the height above the fix at this intercept distance can be lost on the way to it. */
    private static boolean reachable(RunwayEnd end, Vec3 position, double interceptDistance) {
        return excessAt(end, position, interceptDistance) <= 0;
    }

    /**
     * Height the aircraft would still be carrying when it reached the fix, having descended all the
     * way there at the steepest angle the flight director will command. Negative means it arrives
     * low, which the approach copes with by levelling off until the slope catches up.
     */
    private static double excessAt(RunwayEnd end, Vec3 position, double interceptDistance) {
        Vec3 fix = end.approachPoint(interceptDistance, 0);
        double toFix = AutopilotMath.horizontalDistance(position, fix);
        double fixAltitude = end.glideSlopeAltitude(interceptDistance);
        double descentAvailable = toFix * Math.tan(Math.toRadians(AutopilotConfig.MAX_DESCENT_ANGLE));
        return (position.y - fixAltitude) - descentAvailable;
    }
}
