package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Surveying a runway and printing what was measured. Lives here rather than on the survey item so
 * the item and {@code /autopilot survey} produce identical output and neither needs a player.
 */
public final class AirfieldReport {

    private AirfieldReport() {}

    /**
     * Surveys the strip between two thresholds, registers it and reports it.
     *
     * <p>Re-surveying a strip <em>replaces</em> the airfield that is already there rather than
     * registering a second one beside it. Marking the same runway twice is the normal way to correct
     * a threshold that was a few blocks out, and it used to leave {@code airfield-1} and
     * {@code airfield-2} sitting on top of each other with no way to tell them apart and — before
     * the browser gained {@code remove} — no way to delete either. The name and any marked parking
     * spots are carried over, because they are the parts a human chose.
     */
    public static Airfield surveyAndRegister(AutopilotOutput output, ServerLevel level, BlockPos first, BlockPos second) {
        AutopilotSavedData data = AutopilotSavedData.get(level);
        Airfield surveyed = Airfield.survey(level, "", first, second);
        Airfield existing = overlapping(data, surveyed);

        Airfield airfield = existing == null
            ? surveyed.withName(uniqueName(data))
            : surveyed.withName(existing.name()).withParkingSpots(existing.parkingSpots());
        data.put(airfield);
        if (existing != null) {
            output.line("Re-surveyed " + airfield.name() + ", replacing the previous measurement.");
        }
        report(output, level, airfield);
        highlight(level, airfield);
        return airfield;
    }

    /**
     * An already-registered airfield describing the same piece of ground as {@code surveyed}, or
     * null. "The same" is both thresholds landing within {@link #RESURVEY_TOLERANCE} of a registered
     * pair, in either order — the runway has two ends and which one is clicked first is arbitrary.
     */
    private static @Nullable Airfield overlapping(AutopilotSavedData data, Airfield surveyed) {
        for (Airfield existing : data.airfieldList()) {
            boolean sameWayRound = near(existing.thresholdA(), surveyed.thresholdA())
                && near(existing.thresholdB(), surveyed.thresholdB());
            boolean reversed = near(existing.thresholdA(), surveyed.thresholdB())
                && near(existing.thresholdB(), surveyed.thresholdA());
            if (sameWayRound || reversed) {
                return existing;
            }
        }
        return null;
    }

    /** How far a re-marked threshold may move and still count as the same runway, in blocks. */
    private static final double RESURVEY_TOLERANCE = 12.0;

    private static boolean near(BlockPos a, BlockPos b) {
        return a.distSqr(b) <= RESURVEY_TOLERANCE * RESURVEY_TOLERANCE;
    }

    public static String uniqueName(AutopilotSavedData data) {
        int index = 1;
        while (data.get("airfield-" + index) != null) {
            index++;
        }
        return "airfield-" + index;
    }

    /** Everything the survey measured, which is the point of the tool. */
    public static void report(AutopilotOutput output, Level level, Airfield airfield) {
        RunwayEnd endA = airfield.endA();
        RunwayEnd endB = airfield.endB();
        // The counts the survey stored, which are the ones bestEnd will use for the rest of this
        // airfield's life. Printing a freshly measured number here would let the report and the
        // decision disagree.
        int obstaclesA = airfield.hasSurveyedApproaches()
            ? airfield.approachObstaclesA() : Airfield.countApproachObstacles(level, endA);
        int obstaclesB = airfield.hasSurveyedApproaches()
            ? airfield.approachObstaclesB() : Airfield.countApproachObstacles(level, endB);
        RunwayEnd best = airfield.bestEnd(level);

        output.success("Airfield " + airfield.name() + " registered (" + airfield.designators() + ")");
        output.line(String.format("  length %.0f, width %d, slope %.1f deg",
            airfield.length(), airfield.width(), airfield.slopeDegrees()));
        output.line(String.format("  threshold %s elevation %.0f, heading %03.0f deg",
            endA.designator(), endA.elevation(), AutopilotMath.compassHeading(endA.landingHeading())));
        output.line(String.format("  threshold %s elevation %.0f, heading %03.0f deg",
            endB.designator(), endB.elevation(), AutopilotMath.compassHeading(endB.landingHeading())));
        output.line(String.format("  surface roughness %.2f blocks (0 is perfectly flat)", airfield.roughness(level)));
        output.line("  approach obstacles: " + endA.designator() + " -> " + obstaclesA
            + ", " + endB.designator() + " -> " + obstaclesB
            + " (of " + (AutopilotConfig.SURVEY_APPROACH_LENGTH / AutopilotConfig.SURVEY_APPROACH_STEP) + " samples)");
        output.line("  preferred landing direction: " + best.designator());
        output.line(airfield.parkingSpots().isEmpty()
            ? "  no marked parking; departures use the apron derived from the survey"
            : "  marked parking spots: " + airfield.parkingSpots().size());
        if (!AirfieldBrowser.isUsable(airfield)) {
            output.warn(String.format("  warning: only %.0f blocks long, and an aircraft needs %.0f"
                    + " to land. Sorties into it will be refused.",
                airfield.length(), AutopilotConfig.MIN_USABLE_RUNWAY_LENGTH));
        }
        if (Math.abs(airfield.slopeDegrees()) > 5) {
            output.warn("  warning: steep slope.");
        }
    }

    /** Marks one parking spot so a player can see where they just put it. */
    public static void highlightParking(ServerLevel level, BlockPos spot) {
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
            spot.getX() + 0.5, spot.getY() + 1.5, spot.getZ() + 0.5, 30, 1.2, 0.6, 1.2, 0.0);
    }

    /** Marks the centreline, both thresholds and every parking spot, in world. */
    public static void highlight(ServerLevel level, Airfield airfield) {
        for (BlockPos spot : airfield.parkingSpots()) {
            highlightParking(level, spot);
        }
        Vec3 a = airfield.pointA();
        Vec3 b = airfield.pointB();
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, a.x, a.y + 1, a.z, 20, 0.4, 1.0, 0.4, 0.0);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, b.x, b.y + 1, b.z, 20, 0.4, 1.0, 0.4, 0.0);
        int steps = (int) Math.min(96, Math.max(1, airfield.length() / 2));
        for (int step = 0; step <= steps; step++) {
            double t = (double) step / steps;
            level.sendParticles(ParticleTypes.END_ROD,
                a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t + 0.5, a.z + (b.z - a.z) * t,
                1, 0.0, 0.0, 0.0, 0.0);
        }
    }
}
