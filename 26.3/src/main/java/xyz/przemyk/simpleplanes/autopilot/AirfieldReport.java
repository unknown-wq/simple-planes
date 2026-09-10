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
     * Surveys the strip spanned by two opposite corners, registers it and says so in one line.
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
            // One line, and it is the one blocking thing plus what to do about it. This used to be
            // eleven: a heading that said the strip was not usable, every measurement that had
            // passed, the refusal itself, and then a third sentence saying nothing had been
            // registered — three phrasings of one fact, with the only actionable sentence buried in
            // the middle of them.
            output.warn((existing == null
                ? "Not registered: "
                : existing.name() + " unchanged: ") + surface
                + ". Clear it and mark the two corners again.");
            return null;
        }

        BlockPos derived = deriveStand(level, airfield, existing == null);
        if (derived != null) {
            airfield = airfield.withParkingSpots(List.of(derived));
        }
        data.put(airfield);
        registered(output, airfield, derived, existing != null);
        highlight(level, airfield);
        return airfield;
    }

    /**
     * The whole of what a successful registration says: one line.
     *
     * <p>It used to be fifteen — the measurements, the surface verdict, both approach counts, the
     * preferred direction, and a four-line essay on how to move the stand — printed every time
     * anybody marked a runway. All of it except the stand is already in
     * {@code /autopilot airfields info}, which is where somebody who wants it goes and which this
     * line names so that they know it is there. What stays here is what a player checks against what
     * they just marked: the name, the two designators, the size, and where the aircraft will stand.
     *
     * <p>The two clauses that are not measurements are kept because they are refusals in waiting: a
     * strip too short for a sortie, and a field with no stand, both mean the airfield exists and
     * cannot be flown. Neither is discoverable anywhere the player is looking.
     */
    private static void registered(AutopilotOutput output, Airfield airfield,
                                   @Nullable BlockPos derived, boolean replacing) {
        StringBuilder line = new StringBuilder();
        line.append("Airfield ").append(airfield.name())
            .append(replacing ? " re-surveyed (" : " registered (")
            .append(airfield.designators()).append("), ")
            .append(String.format("%.0f", airfield.length())).append("x").append(airfield.width());
        if (derived != null) {
            line.append(", stand at ").append(derived.toShortString());
        } else if (airfield.standsMissing()) {
            line.append(", no stand yet");
        }
        if (!AirfieldBrowser.isUsable(airfield)) {
            line.append(String.format(", TOO SHORT for a sortie (needs %.0f)",
                AutopilotConfig.MIN_USABLE_RUNWAY_LENGTH));
        }
        line.append(". /autopilot airfields info \"").append(airfield.name()).append("\"");
        output.success(line.toString());
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
     * on weaker evidence than theirs: those tests are the ways the ground handling gets stuck plus
     * the two the strip itself is held to — no fluid over the square, and clear air above it — and
     * passing them is what "marked" has always meant in this code. What a human click
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
