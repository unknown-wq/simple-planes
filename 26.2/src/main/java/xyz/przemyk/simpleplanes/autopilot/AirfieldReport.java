package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Surveying a runway and printing what was measured. Lives here rather than on the survey item so
 * the item and {@code /autopilot survey} produce identical output and neither needs a player.
 */
public final class AirfieldReport {

    private AirfieldReport() {}

    /** Surveys the strip between two thresholds, registers it and reports it. */
    public static Airfield surveyAndRegister(AutopilotOutput output, ServerLevel level, BlockPos first, BlockPos second) {
        AutopilotSavedData data = AutopilotSavedData.get(level);
        Airfield airfield = Airfield.survey(level, uniqueName(data), first, second);
        data.put(airfield);
        report(output, level, airfield);
        highlight(level, airfield);
        return airfield;
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
        int obstaclesA = Airfield.countApproachObstacles(level, endA);
        int obstaclesB = Airfield.countApproachObstacles(level, endB);
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
        if (airfield.length() < 60) {
            output.warn("  warning: short runway, the roll-out may overrun.");
        }
        if (Math.abs(airfield.slopeDegrees()) > 5) {
            output.warn("  warning: steep slope.");
        }
    }

    /** Marks the centreline and both thresholds so the measurement is visible in world. */
    public static void highlight(ServerLevel level, Airfield airfield) {
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
