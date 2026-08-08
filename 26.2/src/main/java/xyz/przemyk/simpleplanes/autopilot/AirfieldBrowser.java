package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The airfield browser: what {@code /autopilot airfields} prints, and everything reachable from it.
 *
 * <p>Three rules shape all of it.
 *
 * <p><b>Every row has to read correctly as plain text.</b> The whole feature is tested from a
 * headless server console, where {@link AutopilotOutput#component} flattens components to their
 * string. Click and hover events are therefore only ever shortcuts to something the row already
 * says — never the only place a value appears.
 *
 * <p><b>Sorted by distance from whoever asked.</b> A player gets distances from where they are
 * standing; a command block or the server console has no position of its own, so the source's
 * origin (the world spawn) is used and the header says so, because "1.2 km away" from an unstated
 * point is worse than useless.
 *
 * <p><b>The list is a list, not a survey.</b> Twenty airfields' worth of slope, roughness, threshold
 * elevations and obstacle counts is not something anyone reads in chat, so the full survey lives in
 * {@link #detail} behind one click, and the list carries only what decides whether you want to open
 * it: how far, which way, how big, whether it is free, and whether an aircraft can use it at all.
 */
public final class AirfieldBrowser {

    private AirfieldBrowser() {}

    // ------------------------------------------------------------------ list

    /**
     * @param origin     where distances and bearings are measured from
     * @param originName how to describe that point in the header
     */
    public static int list(AutopilotOutput output, ServerLevel level, Vec3 origin, String originName) {
        List<Airfield> airfields = AutopilotSavedData.get(level).airfieldList();
        if (airfields.isEmpty()) {
            output.component(AutopilotText.tr("browser.empty",
                "No airfields registered in this dimension. Survey one with the Runway Survey Tool"
                    + " or /autopilot survey.").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        airfields.sort(Comparator.comparingDouble(
            airfield -> AutopilotMath.horizontalDistance(origin, airfield.centre())));

        output.component(AutopilotText.tr("browser.header",
            "%s airfields, nearest first, from %s:", airfields.size(), originName)
            .withStyle(ChatFormatting.GOLD));
        for (Airfield airfield : airfields) {
            output.component(row(level, airfield, origin));
        }
        return airfields.size();
    }

    /**
     * One line per airfield. Reads, in order: name, designators, size, how far and which way, who
     * has it reserved, and whether it is long enough to be used at all.
     */
    private static MutableComponent row(Level level, Airfield airfield, Vec3 origin) {
        double distance = AutopilotMath.horizontalDistance(origin, airfield.centre());
        int bearing = AutopilotMath.compassDisplay(AutopilotMath.headingTo(origin, airfield.centre()));

        MutableComponent line = Component.literal("  ")
            .append(name(airfield))
            .append(Component.literal(String.format(" %s  %.0fx%d  %s brg %03d",
                airfield.designators(), airfield.length(), airfield.width(),
                distanceText(distance), bearing)).withStyle(ChatFormatting.GRAY));

        if (!airfield.parkingSpots().isEmpty()) {
            line.append(AutopilotText.tr("browser.parking_count", "  parking %s",
                airfield.parkingSpots().size()).withStyle(ChatFormatting.AQUA));
        }

        PlaneEntity holder = RunwayOccupancy.holder(level, airfield.name());
        if (holder != null) {
            line.append(AutopilotText.tr("browser.reserved", "  reserved by #%s", holder.getId())
                .withStyle(ChatFormatting.YELLOW));
        }
        if (!isUsable(airfield)) {
            line.append(AutopilotText.tr("browser.too_short", "  TOO SHORT")
                .withStyle(ChatFormatting.RED));
        }
        return line;
    }

    /** Blocks under a kilometre, kilometres above it — a browser sorted by distance has to be read. */
    private static String distanceText(double distance) {
        return distance >= 1000 ? String.format("%.1fkm", distance / 1000.0)
            : String.format("%.0f blocks", distance);
    }

    /** The airfield's name, clickable through to its detail view. */
    private static MutableComponent name(Airfield airfield) {
        return Component.literal(airfield.name()).withStyle(style -> style
            .withColor(ChatFormatting.WHITE)
            .withClickEvent(new ClickEvent.RunCommand("/autopilot airfields info \"" + airfield.name() + "\""))
            .withHoverEvent(new HoverEvent.ShowText(AutopilotText.tr("browser.name_hover",
                "Click for the full survey of %s", airfield.name()))));
    }

    // ------------------------------------------------------------------ detail

    /** Everything the survey measured about one airfield, plus its live state. */
    public static void detail(AutopilotOutput output, ServerLevel level, Airfield airfield,
                              Vec3 origin, String originName) {
        RunwayEnd endA = airfield.endA();
        RunwayEnd endB = airfield.endB();
        double distance = AutopilotMath.horizontalDistance(origin, airfield.centre());
        int bearing = AutopilotMath.compassDisplay(AutopilotMath.headingTo(origin, airfield.centre()));

        output.component(AutopilotText.tr("detail.title", "%s (%s)",
            airfield.name(), airfield.designators()).withStyle(ChatFormatting.GOLD)
            .append(Component.literal("  "))
            .append(showButton(airfield)));
        output.component(AutopilotText.tr("detail.position", "  %s brg %s from %s",
            distanceText(distance), String.format("%03d", bearing), originName));
        output.component(AutopilotText.tr("detail.size",
            "  length %s, width %s, slope %s deg, roughness %s",
            String.format("%.0f", airfield.length()), airfield.width(),
            String.format("%.1f", airfield.slopeDegrees()),
            String.format("%.2f", airfield.roughness(level))));

        thresholdLine(output, level, airfield, endA);
        thresholdLine(output, level, airfield, endB);
        centrelineLine(output, level, airfield);

        output.component(AutopilotText.tr("detail.preferred", "  preferred landing direction %s",
            airfield.bestEnd(level).designator()));

        // Where an arrival will actually put its wheels, and how much of the strip that leaves. The
        // aim point is derived from the length rather than fixed, so a player who has just built a
        // long runway has no other way to find out where the aircraft is going to touch down, and
        // "it lands on the very edge" was a real complaint about the version that always did.
        double aim = endA.aimOffset();
        output.component(AutopilotText.tr("detail.touchdown",
            "  touchdown aim %s blocks in, stopping by about %s of %s",
            String.format("%.0f", aim),
            String.format("%.0f", aim + AutopilotConfig.LANDING_STOP_RESERVE),
            String.format("%.0f", airfield.length())));

        // What actually decides whether a sortie may be launched into this field.
        if (isUsable(airfield)) {
            output.component(AutopilotText.tr("detail.usable",
                "  usable: needs %s blocks, has %s",
                String.format("%.0f", AutopilotConfig.MIN_USABLE_RUNWAY_LENGTH),
                String.format("%.0f", airfield.length())).withStyle(ChatFormatting.GREEN));
        } else {
            output.component(AutopilotText.tr("detail.unusable",
                "  TOO SHORT: needs %s blocks, has %s - sorties into it are refused",
                String.format("%.0f", AutopilotConfig.MIN_USABLE_RUNWAY_LENGTH),
                String.format("%.0f", airfield.length())).withStyle(ChatFormatting.RED));
        }

        PlaneEntity holder = RunwayOccupancy.holder(level, airfield.name());
        output.component(holder == null
            ? AutopilotText.tr("detail.free", "  runway free").withStyle(ChatFormatting.GREEN)
            : AutopilotText.tr("detail.reserved", "  runway reserved by plane #%s", holder.getId())
                .withStyle(ChatFormatting.YELLOW));

        parkingLines(output, level, airfield);
    }

    /**
     * The obstacle count shown is the one recorded by the survey, not a fresh measurement. Recounting
     * here would read terrain that is almost certainly unloaded — the browser is normally used from
     * somewhere else entirely — and report a clear approach for ground nobody has looked at. Only an
     * airfield stored before the counts were recorded is measured live, and it is labelled.
     */
    private static void thresholdLine(AutopilotOutput output, Level level, Airfield airfield, RunwayEnd end) {
        boolean surveyed = airfield.hasSurveyedApproaches();
        int obstacles = surveyed
            ? (end.threshold().equals(airfield.pointA())
                ? airfield.approachObstaclesA() : airfield.approachObstaclesB())
            : Airfield.countApproachObstacles(level, end);
        MutableComponent line = AutopilotText.tr("detail.threshold",
            "  threshold %s: elevation %s, heading %s deg, approach obstacles %s of %s",
            end.designator(), String.format("%.0f", end.elevation()),
            String.format("%03.0f", AutopilotMath.compassHeading(end.landingHeading())),
            obstacles,
            AutopilotConfig.SURVEY_APPROACH_LENGTH / AutopilotConfig.SURVEY_APPROACH_STEP);
        if (!surveyed) {
            line.append(AutopilotText.tr("detail.threshold_unsurveyed", " (measured now, not surveyed)")
                .withStyle(ChatFormatting.GRAY));
        }
        output.component(line.append(Component.literal("  "))
            .append(copyable(BlockPos.containing(end.threshold()))));
    }

    /**
     * Whether this airfield's stored centreline is actually down the middle of its strip, and what
     * to do about it if not.
     *
     * <p>The survey only started centring the thresholds recently, and airfields already on disk
     * were deliberately left exactly as they were saved — nothing reinterprets a stored threshold.
     * The consequence is that an old airfield can sit half a runway width off its own strip with
     * nothing anywhere saying so, and every arrival into it will fly that line perfectly. This is
     * where it says so. The measurement is live, so it needs the runway's chunks loaded and it is
     * silent when they are not: an unloaded strip reads as having no edges, and an airfield nobody
     * is standing near must not be accused of being crooked on no evidence.
     */
    private static void centrelineLine(AutopilotOutput output, Level level, Airfield airfield) {
        double offset = airfield.centrelineOffset(level);
        if (offset < 1.0) {
            return;
        }
        output.component(AutopilotText.tr("detail.off_centre",
            "  centreline is %s blocks off the middle of the strip - run"
                + " /autopilot airfields resurvey \"%s\" while standing near it",
            String.format("%.0f", offset), airfield.name()).withStyle(ChatFormatting.YELLOW));
    }

    private static void parkingLines(AutopilotOutput output, Level level, Airfield airfield) {
        if (airfield.parkingSpots().isEmpty()) {
            output.component(AutopilotText.tr("detail.parking_none",
                "  no marked parking; a departure uses the apron worked out from the survey")
                .withStyle(ChatFormatting.GRAY));
            return;
        }
        output.component(AutopilotText.tr("detail.parking_header", "  parking spots (%s):",
            airfield.parkingSpots().size()));
        for (BlockPos spot : airfield.parkingSpots()) {
            String problem = Airfield.parkingSpotProblem(level, airfield.withParkingSpots(List.of()), spot);
            MutableComponent line = Component.literal("    ").append(copyable(spot));
            if (problem != null) {
                line.append(AutopilotText.tr("detail.parking_problem", "  UNUSABLE: %s", problem)
                    .withStyle(ChatFormatting.RED));
            } else if (!Airfield.isParkingSpotFree(level,
                new Vec3(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5))) {
                line.append(AutopilotText.tr("detail.parking_occupied", "  occupied")
                    .withStyle(ChatFormatting.YELLOW));
            } else {
                line.append(AutopilotText.tr("detail.parking_free", "  free")
                    .withStyle(ChatFormatting.GREEN));
            }
            if (airfield.isOnStrip(spot)) {
                line.append(AutopilotText.tr("detail.parking_on_strip", "  on the strip")
                    .withStyle(ChatFormatting.YELLOW));
            }
            output.component(line);
        }
    }

    /** Coordinates that copy to the clipboard on click, and read as coordinates without one. */
    private static MutableComponent copyable(BlockPos pos) {
        String text = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        return Component.literal(text).withStyle(style -> style
            .withColor(ChatFormatting.DARK_AQUA)
            .withClickEvent(new ClickEvent.CopyToClipboard(text))
            .withHoverEvent(new HoverEvent.ShowText(
                AutopilotText.tr("browser.copy_hover", "Click to copy these coordinates"))));
    }

    private static MutableComponent showButton(Airfield airfield) {
        return AutopilotText.tr("browser.show", "[show in world]").withStyle(style -> style
            .withColor(ChatFormatting.AQUA)
            .withClickEvent(new ClickEvent.RunCommand("/autopilot airfields show \"" + airfield.name() + "\""))
            .withHoverEvent(new HoverEvent.ShowText(AutopilotText.tr("browser.show_hover",
                "Draw the centreline, thresholds and parking spots with particles"))));
    }

    // ------------------------------------------------------------------ usability

    /**
     * Whether an aircraft can actually complete a flight into this runway.
     *
     * <p>Take-off is not the constraint and the arithmetic says so: the ground roll to
     * {@link AutopilotConfig#ROTATE_SPEED} is 3.8 blocks at throttle 5 and 1.9 at the booster's
     * throttle 10, and the roll-out from touchdown speed is 1.2 measured on the rig. The landing is
     * the constraint, and within the landing it is not the braking but the aiming — the aircraft
     * flies at a point {@link RunwayEnd#aimOffset()} blocks down the strip, which on the shortest
     * usable strip is {@link AutopilotConfig#TOUCHDOWN_AIM_MIN}, and needs
     * {@link AutopilotConfig#LANDING_STOP_RESERVE} behind it. See
     * {@link AutopilotConfig#MIN_USABLE_RUNWAY_LENGTH}.
     */
    public static boolean isUsable(Airfield airfield) {
        return airfield.length() >= AutopilotConfig.MIN_USABLE_RUNWAY_LENGTH;
    }

    /** The refusal message for a runway too short to fly into, or null when it is fine. */
    public static @Nullable Component usabilityRefusal(Airfield airfield) {
        if (isUsable(airfield)) {
            return null;
        }
        return AutopilotText.tr("browser.refuse_short",
            "%s is %s blocks long; an aircraft needs %s to land on it. Survey a longer strip.",
            airfield.name(), String.format("%.0f", airfield.length()),
            String.format("%.0f", AutopilotConfig.MIN_USABLE_RUNWAY_LENGTH));
    }

    // ------------------------------------------------------------------ management

    public static boolean remove(AutopilotOutput output, ServerLevel level, String name) {
        AutopilotSavedData data = AutopilotSavedData.get(level);
        if (data.get(name) == null) {
            output.component(unknown(name).withStyle(ChatFormatting.RED));
            return false;
        }
        if (RunwayOccupancy.holder(level, name) != null) {
            output.component(AutopilotText.tr("manage.busy",
                "%s is in use by an aircraft; try again when the runway is free.", name)
                .withStyle(ChatFormatting.RED));
            return false;
        }
        data.remove(name);
        output.component(AutopilotText.tr("manage.removed", "Removed airfield %s.", name)
            .withStyle(ChatFormatting.GREEN));
        return true;
    }

    public static boolean rename(AutopilotOutput output, ServerLevel level, String from, String to) {
        AutopilotSavedData data = AutopilotSavedData.get(level);
        Airfield airfield = data.get(from);
        if (airfield == null) {
            output.component(unknown(from).withStyle(ChatFormatting.RED));
            return false;
        }
        if (to.isBlank()) {
            output.component(AutopilotText.tr("manage.blank_name", "An airfield name cannot be blank.")
                .withStyle(ChatFormatting.RED));
            return false;
        }
        if (data.get(to) != null) {
            output.component(AutopilotText.tr("manage.name_taken",
                "There is already an airfield called %s.", to).withStyle(ChatFormatting.RED));
            return false;
        }
        // A flight in progress holds the field by name — in the flight plan, in the runway
        // reservation and in the report it will print when it lands — so renaming under it would
        // strand the aircraft looking for a field that no longer exists. Departures hold a
        // reservation too now, so this also covers renaming a field an aircraft is taxiing out of.
        if (RunwayOccupancy.holder(level, from) != null) {
            output.component(AutopilotText.tr("manage.busy",
                "%s is in use by an aircraft; try again when the runway is free.", from)
                .withStyle(ChatFormatting.RED));
            return false;
        }
        data.remove(from);
        data.put(airfield.withName(to));
        output.component(AutopilotText.tr("manage.renamed", "Renamed %s to %s.", from, to)
            .withStyle(ChatFormatting.GREEN));
        return true;
    }

    /**
     * Measures an already-registered airfield again from its own stored thresholds, keeping its name
     * and its parking spots.
     *
     * <p><b>This exists because nothing else re-reads a stored threshold, and nothing else should.</b>
     * The survey now puts the thresholds on the middle of the strip rather than on the blocks that
     * were clicked, which moves the take-off lineup, the aim point, the glide slope and the landing
     * gates together. Applying that to airfields already on disk on load would silently move every
     * runway in every existing world, and this codebase has been bitten by silently reinterpreting
     * persisted data before. So stored airfields keep exactly the geometry they were saved with, and
     * this is the one command that changes it — deliberately, by name, from a player who asked.
     *
     * <p>Re-clicking both ends with the survey tool already does the same thing, because a survey
     * whose thresholds land within the re-survey tolerance of a registered pair replaces it. This
     * only removes the need to be standing on the right blocks: the stored ones are the right blocks.
     * It still needs the runway loaded, for the same reason {@code /autopilot survey} does — a survey
     * of unloaded ground registers a runway made of nothing.
     */
    public static boolean resurvey(AutopilotOutput output, ServerLevel level, String name) {
        AutopilotSavedData data = AutopilotSavedData.get(level);
        Airfield airfield = data.get(name);
        if (airfield == null) {
            output.component(unknown(name).withStyle(ChatFormatting.RED));
            return false;
        }
        if (RunwayOccupancy.holder(level, name) != null) {
            output.component(AutopilotText.tr("manage.busy",
                "%s is in use by an aircraft; try again when the runway is free.", name)
                .withStyle(ChatFormatting.RED));
            return false;
        }
        BlockPos a = airfield.thresholdA();
        BlockPos b = airfield.thresholdB();
        if (!level.hasChunkAt(a) || !level.hasChunkAt(b)) {
            output.component(AutopilotText.tr("manage.resurvey_unloaded",
                "%s is not loaded, and a survey measures real blocks. Go to the runway, or"
                    + " force-load it, and try again.", name).withStyle(ChatFormatting.RED));
            return false;
        }
        Airfield fresh = Airfield.survey(level, name, a, b)
            .withParkingSpots(airfield.parkingSpots());
        data.put(fresh);
        double moved = Math.max(distance(a, fresh.thresholdA()), distance(b, fresh.thresholdB()));
        output.component(moved < 0.5
            ? AutopilotText.tr("manage.resurveyed_unchanged",
                "Re-surveyed %s; its centreline was already down the middle of the strip.", name)
                .withStyle(ChatFormatting.GREEN)
            : AutopilotText.tr("manage.resurveyed",
                "Re-surveyed %s: the centreline moved %s blocks onto the middle of the strip."
                    + " Everything an arrival is flown to moves with it.",
                name, String.format("%.0f", moved)).withStyle(ChatFormatting.GREEN));
        AirfieldReport.report(output, level, fresh);
        AirfieldReport.highlight(level, fresh);
        return true;
    }

    private static double distance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Marks a parking spot, or explains why that place will not do. */
    public static boolean park(AutopilotOutput output, ServerLevel level, String name, BlockPos spot) {
        AutopilotSavedData data = AutopilotSavedData.get(level);
        Airfield airfield = data.get(name);
        if (airfield == null) {
            output.component(unknown(name).withStyle(ChatFormatting.RED));
            return false;
        }
        String problem = Airfield.parkingSpotProblem(level, airfield, spot);
        if (problem != null) {
            output.component(AutopilotText.tr("manage.park_refused",
                "Cannot park an aircraft at %s: %s.", spot.toShortString(), problem)
                .withStyle(ChatFormatting.RED));
            return false;
        }
        List<BlockPos> spots = new ArrayList<>(airfield.parkingSpots());
        // The surface block, exactly as a threshold is stored, so a click on the side of a block and
        // a click on top of it produce the same spot.
        int surface = TerrainScanner.surfaceHeight(level, spot.getX() + 0.5, spot.getZ() + 0.5);
        BlockPos stored = new BlockPos(spot.getX(), surface - 1, spot.getZ());
        spots.add(stored);
        data.put(airfield.withParkingSpots(spots));
        output.component(AutopilotText.tr("manage.parked",
            "Parking spot %s marked at %s (%s in total).",
            spots.size(), stored.toShortString(), spots.size()).withStyle(ChatFormatting.GREEN));
        if (airfield.isOnStrip(stored)) {
            output.component(AutopilotText.tr("manage.park_on_strip",
                "  Note: that spot is on the runway itself, so an aircraft waiting there stands on"
                    + " the landing area.").withStyle(ChatFormatting.YELLOW));
        }
        AirfieldReport.highlightParking(level, stored);
        return true;
    }

    /** Removes the marked spot nearest {@code near}, so a click anywhere on it works. */
    public static boolean unpark(AutopilotOutput output, ServerLevel level, String name, BlockPos near) {
        AutopilotSavedData data = AutopilotSavedData.get(level);
        Airfield airfield = data.get(name);
        if (airfield == null) {
            output.component(unknown(name).withStyle(ChatFormatting.RED));
            return false;
        }
        BlockPos closest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos spot : airfield.parkingSpots()) {
            double distance = spot.distSqr(near);
            if (distance < best) {
                best = distance;
                closest = spot;
            }
        }
        if (closest == null || best > AutopilotConfig.PARKING_MAX_TAXI_DISTANCE
            * AutopilotConfig.PARKING_MAX_TAXI_DISTANCE) {
            output.component(AutopilotText.tr("manage.unpark_none",
                "No parking spot of %s near %s.", name, near.toShortString())
                .withStyle(ChatFormatting.RED));
            return false;
        }
        List<BlockPos> spots = new ArrayList<>(airfield.parkingSpots());
        spots.remove(closest);
        data.put(airfield.withParkingSpots(spots));
        output.component(AutopilotText.tr("manage.unparked",
            "Removed the parking spot at %s (%s left).", closest.toShortString(), spots.size())
            .withStyle(ChatFormatting.GREEN));
        return true;
    }

    public static MutableComponent unknown(String name) {
        return AutopilotText.tr("browser.unknown",
            "No airfield called %s. Use /autopilot airfields to list them.", name);
    }
}
