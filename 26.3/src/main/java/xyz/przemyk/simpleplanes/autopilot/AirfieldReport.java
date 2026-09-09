package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;

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
     *
     * <p><b>Re-surveying also carries over whether the field is held to the stand rule.</b> A fresh
     * survey sets it; a re-survey keeps whatever the registered airfield had. Otherwise correcting a
     * threshold that was a few blocks out on a world that predates the rule would silently convert a
     * working field into one whose sorties are refused — a re-survey is how a player fixes a runway,
     * not how they opt into a new requirement.
     *
     * <p><b>A strip whose surface will not do is refused and nothing is registered</b>, and this is
     * the one thing here that is not like the length check beside it. {@code TOO SHORT} registers,
     * because a short runway is still a runway — the strip is real, the refusal happens when a sortie
     * is ordered, and lengthening it is a re-survey away. A strip with a fence line down it, a tree
     * on it or a foot of water over it is not a landing surface at all, and registering one would put
     * a name in the browser that no flight can ever use. {@code HelipadReport#surveyAndRegister}
     * already draws exactly this line and gives exactly this reason.
     *
     * <p>The other half of the argument is that a survival player cannot undo it. The whole
     * {@code /autopilot} tree requires {@code LEVEL_GAMEMASTERS}, so {@code airfields remove} is not
     * available to the person holding the survey tool: a bad airfield they registered is one they are
     * stuck with. Refusing costs them one more right-click; registering costs them a permanent entry
     * they cannot delete.
     *
     * <p>Refusing never removes anything either. Re-marking a registered field that has since had a
     * wall built across it leaves the registered field exactly as it was — the survey simply declines
     * to replace it, and says so.
     *
     * @return the airfield that was registered, or null when the strip was refused
     */
    public static @Nullable Airfield surveyAndRegister(AutopilotOutput output, ServerLevel level, BlockPos first, BlockPos second) {
        AutopilotSavedData data = AutopilotSavedData.get(level);
        Airfield surveyed = Airfield.survey(level, "", first, second);
        Airfield existing = overlapping(data, surveyed);

        Airfield airfield = existing == null
            ? surveyed.withName(uniqueName(data))
            : surveyed.withName(existing.name()).withParkingSpots(existing.parkingSpots())
                .withRequiredStands(existing.requiresStands());

        String surface = airfield.surfaceProblem(level);
        if (surface != null) {
            reportCentring(output, first, second, airfield);
            report(output, level, airfield, null, surface, false);
            output.warn(existing == null
                ? "  Nothing was registered. Clear the strip and mark both ends again."
                : "  Nothing was changed; " + existing.name() + " is still registered exactly as it"
                    + " was. Clear the strip and mark both ends again.");
            return null;
        }

        BlockPos derived = deriveStand(level, airfield, existing == null);
        if (derived != null) {
            airfield = airfield.withParkingSpots(List.of(derived));
        }
        data.put(airfield);
        if (existing != null) {
            output.line("Re-surveyed " + airfield.name() + ", replacing the previous measurement.");
        }
        reportCentring(output, first, second, airfield);
        report(output, level, airfield, derived, null, true);
        highlight(level, airfield);
        return airfield;
    }

    /**
     * A stand for a runway that has just been surveyed and has none, or null when the ground beside
     * neither threshold will do.
     *
     * <p>The candidate squares are the ones {@link Airfield#parkingPosition} already works out for a
     * departure — beside the threshold, clear of the strip — and each is then put through
     * {@link Airfield#parkingSpotProblem}, which is the whole of the judgement
     * {@code /autopilot airfields park} and the survey tool apply to a square a player picks. Nothing
     * is stored that a player could not have marked at that spot themselves, and nothing is accepted
     * on weaker evidence than theirs: the four tests are the four ways the ground handling gets
     * stuck, and passing them is what "marked" has always meant in this code. What a human click
     * carries that this does not is a <em>preference</em> — the hangar they want the aircraft next
     * to — which is why the report says the stand was derived and where, and why removing it is one
     * gesture.
     *
     * <p>Three cases are deliberately left alone.
     * <ul>
     *   <li><b>A re-survey.</b> {@code surveyAndRegister} already carries a registered field's
     *       stands and its grandfathering across, and a field from before the stand rule is entitled
     *       to have no stand. Deriving one there would put a stand on a working world's runway that
     *       nobody asked for.</li>
     *   <li><b>A field that is not held to the stand rule, or already has a stand.</b> Both are
     *       {@link Airfield#standsMissing()}; there is nothing missing to supply.</li>
     *   <li><b>A strip too short to land on.</b> It is refused for its length whatever is marked
     *       beside it, so clearing its "not finished" line would only make the report agree with
     *       itself less.</li>
     * </ul>
     *
     * <p>The on-strip fallback is skipped as well. It is the last resort for a departure, which has
     * to start somewhere and is about to roll away; stored as a stand it would send every arrival
     * onto the landing area and leave it there.
     */
    private static @Nullable BlockPos deriveStand(ServerLevel level, Airfield airfield, boolean fresh) {
        if (!fresh || !airfield.standsMissing() || !AirfieldBrowser.isUsable(airfield)) {
            return null;
        }
        for (RunwayEnd end : airfield.ends()) {
            Airfield.ParkingSpot candidate = Airfield.parkingPosition(level, end);
            if (candidate.onRunway()) {
                continue;
            }
            BlockPos square = BlockPos.containing(candidate.position());
            if (Airfield.parkingSpotProblem(level, airfield, square) == null) {
                return Airfield.standBlock(level, square.getX(), square.getZ());
            }
        }
        return null;
    }

    /**
     * Says so when the survey moved a threshold off the block that was clicked.
     *
     * <p>Silently relocating the thing the player just pointed at would be worse than the bug it
     * fixes, and the number is the one that matters: half a runway width of correction is the
     * difference between rolling down the middle and rolling along the edge. Nothing is printed when
     * the clicks were already on the centreline, which is the case on any strip whose edges the
     * survey cannot see (see {@code Airfield#centreOnStrip}).
     */
    private static void reportCentring(AutopilotOutput output, BlockPos first, BlockPos second,
                                       Airfield airfield) {
        // survey() keeps the order of the two clicks, and it only ever moves a threshold sideways,
        // so this distance is the lateral correction and nothing else.
        double moved = Math.max(horizontal(first, airfield.thresholdA()),
            horizontal(second, airfield.thresholdB()));
        if (moved >= 0.5) {
            output.line(String.format("  centreline moved %.0f blocks: the thresholds are on the"
                + " middle of the strip, not on the blocks that were clicked", moved));
        }
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
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
        report(output, level, airfield, null);
    }

    /**
     * As {@link #report(AutopilotOutput, Level, Airfield)}, saying so when the survey supplied the
     * stand itself.
     *
     * @param derivedStand the stand {@link #deriveStand} just worked out, or null when every stand
     *                     on this airfield was marked by hand — which is the case for a re-survey
     *                     and for every caller outside {@code surveyAndRegister}
     */
    public static void report(AutopilotOutput output, Level level, Airfield airfield,
                              @Nullable BlockPos derivedStand) {
        report(output, level, airfield, derivedStand, airfield.surfaceProblem(level), true);
    }

    /**
     * The whole report, for a strip that was registered and for one that was refused.
     *
     * @param surfaceProblem what {@link Airfield#surfaceProblem(Level)} said about this strip, or
     *                       null when it said nothing. Passed in rather than measured here because
     *                       the caller that refuses a strip has already asked, and asking costs a few
     *                       thousand block reads.
     * @param registered     whether the airfield this describes is actually in the registry. A
     *                       refused strip prints the same measurements — they are what tells the
     *                       player whether the thing they marked was the thing they meant — under a
     *                       heading that does not claim it was registered, and without the parking
     *                       lines, which are about an airfield that exists.
     */
    private static void report(AutopilotOutput output, Level level, Airfield airfield,
                               @Nullable BlockPos derivedStand, @Nullable String surfaceProblem,
                               boolean registered) {
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

        if (registered) {
            output.success("Airfield " + airfield.name() + " registered (" + airfield.designators() + ")");
        } else {
            output.warn("Not a usable runway (" + airfield.designators() + ", centred on "
                + BlockPos.containing(airfield.centre()).toShortString() + "):");
        }
        output.line(String.format("  length %.0f, width %d, slope %.1f deg",
            airfield.length(), airfield.width(), airfield.slopeDegrees()));
        output.line(String.format("  threshold %s elevation %.0f, heading %03.0f deg",
            endA.designator(), endA.elevation(), AutopilotMath.compassHeading(endA.landingHeading())));
        output.line(String.format("  threshold %s elevation %.0f, heading %03.0f deg",
            endB.designator(), endB.elevation(), AutopilotMath.compassHeading(endB.landingHeading())));
        output.line(String.format("  surface roughness %.2f blocks (0 is perfectly flat, %.2f is the"
            + " most this registers)", airfield.roughness(level), AutopilotConfig.RUNWAY_MAX_ROUGHNESS));
        // Said on every report, pass or fail, in the same place and the same words, so that the line
        // a player looks for after clearing a strip is the line that told them to clear it.
        if (surfaceProblem == null) {
            output.line("  surface: clear - nothing but air, snow or grass over the strip, across its"
                + " whole width");
        } else {
            output.warn("  REFUSED: " + surfaceProblem);
        }
        output.line("  approach obstacles: " + endA.designator() + " -> " + obstaclesA
            + ", " + endB.designator() + " -> " + obstaclesB
            + " (of " + (AutopilotConfig.SURVEY_APPROACH_LENGTH / AutopilotConfig.SURVEY_APPROACH_STEP) + " samples)");
        output.line("  preferred landing direction: " + best.designator());
        if (!registered) {
            // Deliberately nothing about parking here. Every one of those lines is advice about an
            // airfield that exists, and this one does not.
            reportLength(output, airfield);
            return;
        }
        if (derivedStand != null) {
            // Said out loud, and said as a derivation rather than as a decision. The square passed
            // the same four tests a stand marked by hand passes, so it is a stand and not a guess —
            // but it is a stand the player did not choose, and the one thing the tests cannot check
            // is whether it is where they wanted it. So the coordinate is printed, and so is the way
            // to move it.
            output.success("  stand derived at " + derivedStand.toShortString()
                + ": level ground beside a threshold, checked exactly as a stand you mark yourself"
                + " is checked. " + airfield.name() + " is ready to fly from.");
            output.line("  Somewhere else would suit you better? Sneak + right-click that stand with"
                + " the Runway Survey Tool in parking mode to drop it, then right-click where you"
                + " want it — or /autopilot airfields unpark \"" + airfield.name() + "\" <x y z>.");
        } else if (!airfield.parkingSpots().isEmpty()) {
            output.line("  marked parking spots: " + airfield.parkingSpots().size());
        } else if (airfield.standsMissing()) {
            // The survey is not the end of the job any more, so it does not print as though it were.
            // Two lines: what is missing, and how to supply it — in the same words the browser and a
            // stopped arrival already use, with the command form alongside because this same report
            // is what the headless rig reads.
            //
            // What the second line deliberately does not name is the mode-switch gesture. The tool
            // puts itself into parking mode as soon as this report returns and announces it in the
            // very next line of the same chat, so "sneak + right-click the air to put the tool into
            // parking mode" was an instruction to toggle straight back out of the mode the following
            // line says the player is now in. Stating the requirement rather than the gesture is
            // right on both paths: on the tool path the next line tells the player they are already
            // there, and on the command path — where this is also printed by resurvey — there need
            // not be a tool in hand at all.
            output.warn("  NOT FINISHED: no parking marked. A runway with nowhere to park is one an"
                + " aircraft departs from a square nobody surveyed and lands on with nowhere to go,"
                + " so sorties to and from " + airfield.name() + " are refused until a stand exists.");
            output.line("  Next: mark a stand beside the runway with the Runway Survey Tool in"
                + " parking mode, or: /autopilot airfields park \"" + airfield.name() + "\" <x y z>");
        } else {
            output.line("  no marked parking; departures use the apron derived from the survey");
        }
        reportLength(output, airfield);
    }

    /** The two length-and-slope warnings, which read the same whether or not the strip registered. */
    private static void reportLength(AutopilotOutput output, Airfield airfield) {
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
