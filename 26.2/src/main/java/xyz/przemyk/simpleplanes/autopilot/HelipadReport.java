package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.List;

/**
 * Surveying a helipad and printing what was measured. The counterpart of {@link AirfieldReport}, and
 * split out for the same reason: the marking item and {@code /autopilot helipad survey} produce
 * identical output and neither needs a player.
 */
public final class HelipadReport {

    private HelipadReport() {}

    /** How far a re-marked centre may move and still count as the same pad, in blocks. */
    private static final double RESURVEY_TOLERANCE = 8.0;

    /**
     * Surveys the pad between two clicked corners, registers it if it passes, and reports it either
     * way.
     *
     * <p><b>A pad that fails is not registered.</b> The runway survey registers a strip that is too
     * short and marks it {@code TOO SHORT} in the browser, which works because a short runway is
     * still a runway and the refusal happens when a sortie is ordered. A pad that fails here is not
     * a pad at all — it is a hole with water in it, or a courtyard with a roof over it — and
     * registering it would put a name in the list that no flight can ever use. Every refusal says
     * which measurement failed and what would fix it.
     *
     * <p>Re-marking the same ground replaces the pad rather than adding a second one beside it, and
     * carries the name over. Same rule as {@link AirfieldReport#surveyAndRegister} and for the same
     * reason: marking a pad twice is how a player corrects a centre that was a block out.
     */
    public static @Nullable Helipad surveyAndRegister(AutopilotOutput output, ServerLevel level,
                                                      BlockPos cornerA, BlockPos cornerB) {
        AutopilotSavedData data = AutopilotSavedData.get(level);
        Helipad.Survey survey = Helipad.survey(level, "", cornerA, cornerB);
        Helipad existing = survey.pad() == null ? null : overlapping(data, survey.derivedCentre());
        String name = existing == null ? uniqueName(data) : existing.name();

        report(output, survey, name);
        if (!survey.accepted()) {
            return null;
        }
        Helipad pad = survey.pad().withName(name);
        data.put(pad);
        if (existing != null) {
            output.line("Re-surveyed " + name + ", replacing the previous measurement.");
        }
        highlight(level, pad);
        return pad;
    }

    /** Everything the survey measured, whether or not the pad was registered. */
    public static void report(AutopilotOutput output, Helipad.Survey survey, String name) {
        BlockPos centre = survey.derivedCentre();
        int size = 2 * survey.radius() + 1;
        if (survey.accepted()) {
            output.success("Helipad " + name + " registered - " + size + "x" + size
                + " at " + centre.toShortString());
        } else {
            output.warn("Not a usable helipad (" + size + "x" + size + " at "
                + centre.toShortString() + "):");
        }
        // The two coordinates side by side, always, and this is the point of the line. The runway
        // survey used to take the clicked blocks as the thresholds and fly the aircraft to them, so
        // a strip marked on its edge was used on its edge; the fix there and the fix here are the
        // same shape — derive the shape from the ground — and the proof that the derived shape is
        // the one actually used is that this coordinate and the touchdown coordinate in the landing
        // report are the same number.
        output.line(String.format("  marked centre %s -> pad centre %s (moved %.1f blocks);"
                + " touchdown at %.1f, %.1f, %.1f",
            survey.markedCentre().toShortString(), centre.toShortString(), survey.centreMoved(),
            centre.getX() + 0.5, centre.getY() + 1.0, centre.getZ() + 0.5));
        output.line("  surface varies by " + survey.roughness() + " block(s); "
            + (survey.obstacleHeight() <= 0
                ? "nothing standing over the pad"
                : "something stands " + survey.obstacleHeight() + " block(s) above it"));
        output.line("  clear approach bearings: " + describeSectors(survey)
            + " (" + survey.clearSectorCount() + " of " + RotorcraftConfig.APPROACH_SECTORS
            + ", checked to " + RotorcraftConfig.APPROACH_LENGTH + " blocks on a "
            + (int) RotorcraftConfig.APPROACH_SLOPE_DEGREES + " degree path)");
        for (String warning : survey.warnings()) {
            output.warn("  warning: " + warning);
        }
        for (String refusal : survey.refusals()) {
            output.warn("  REFUSED: " + refusal);
        }
    }

    private static String describeSectors(Helipad.Survey survey) {
        boolean[] sectors = survey.sectors();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < sectors.length; i++) {
            if (!sectors[i]) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(String.format("%03d", AutopilotMath.compassDisplay(Helipad.sectorHeading(i))));
        }
        return builder.length() == 0 ? "none" : builder.toString();
    }

    private static @Nullable Helipad overlapping(AutopilotSavedData data, BlockPos centre) {
        for (Helipad pad : data.helipadList()) {
            if (pad.centre().distSqr(centre) <= RESURVEY_TOLERANCE * RESURVEY_TOLERANCE) {
                return pad;
            }
        }
        return null;
    }

    public static String uniqueName(AutopilotSavedData data) {
        int index = 1;
        while (data.helipad("helipad-" + index) != null) {
            index++;
        }
        return "helipad-" + index;
    }

    /**
     * Draws the pad in world: its outline, its centre, and an arrow of particles down each clear
     * approach bearing, so the thing the survey decided is visible without reading the numbers.
     */
    public static void highlight(ServerLevel level, Helipad pad) {
        double y = pad.elevation() + 0.5;
        for (int dx = -pad.radius(); dx <= pad.radius(); dx++) {
            for (int dz = -pad.radius(); dz <= pad.radius(); dz++) {
                if (Math.abs(dx) != pad.radius() && Math.abs(dz) != pad.radius()) {
                    continue;
                }
                level.sendParticles(ParticleTypes.END_ROD,
                    pad.centre().getX() + dx + 0.5, y, pad.centre().getZ() + dz + 0.5,
                    1, 0.0, 0.0, 0.0, 0.0);
            }
        }
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
            pad.centre().getX() + 0.5, y + 1.0, pad.centre().getZ() + 0.5, 30, 0.4, 1.0, 0.4, 0.0);
        for (int sector = 0; sector < RotorcraftConfig.APPROACH_SECTORS; sector++) {
            if ((pad.clearSectors() & (1 << sector)) == 0) {
                continue;
            }
            double heading = Helipad.sectorHeading(sector);
            for (int step = 1; step <= 8; step++) {
                var point = AutopilotMath.pointAlong(pad.touchdown(), heading, step * 2.0);
                level.sendParticles(ParticleTypes.END_ROD, point.x, y, point.z, 1, 0, 0, 0, 0);
            }
        }
    }

    /**
     * Makes a pad and its surroundings resident, so it can be measured, spawned into or asked about.
     *
     * <p>A pad is small enough that one 3x3 block of chunks covers it and its clearance ring, which
     * is the whole difference from {@link AutopilotSpawner#loadAirfield} — a 183-block runway spans
     * a dozen chunks and its stands sit outside them, and both had to be listed separately.
     */
    public static void load(ServerLevel level, Helipad pad) {
        AutopilotSpawner.loadRegion(level, pad.touchdown());
    }

    /** Aircraft standing on this pad right now, for the browser. Empty when its chunks are cold. */
    public static List<? extends PlaneEntity> occupants(ServerLevel level, Helipad pad) {
        return level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(PlaneEntity.class),
            net.minecraft.world.phys.AABB.ofSize(pad.touchdown(),
                (pad.radius() + 1) * 2.0, 8.0, (pad.radius() + 1) * 2.0),
            plane -> true);
    }
}
