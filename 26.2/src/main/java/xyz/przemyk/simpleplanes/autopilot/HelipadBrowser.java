package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.Comparator;
import java.util.List;

/**
 * The helipad browser: what {@code /autopilot helipads} prints.
 *
 * <p>Follows the same three rules as {@link AirfieldBrowser} — every row reads correctly as plain
 * text because the tests are run from a console, rows are sorted by distance from whoever asked, and
 * the list carries only what decides whether you want the detail. It is a separate class rather
 * than a mode of that one because the columns are different: a pad has no designators, no length
 * and no width, and it does have a set of approach bearings, which is the number a pilot actually
 * wants and which a runway has no equivalent of.
 */
public final class HelipadBrowser {

    private HelipadBrowser() {}

    public static int list(AutopilotOutput output, ServerLevel level, Vec3 origin, String originName) {
        List<Helipad> pads = AutopilotSavedData.get(level).helipadList();
        if (pads.isEmpty()) {
            output.component(AutopilotText.tr("helipad.browser_empty",
                "No helipads registered in this dimension. Mark one with the Helipad Marker or"
                    + " /autopilot helipad survey.").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        pads.sort(Comparator.comparingDouble(pad -> AutopilotMath.horizontalDistance(origin, pad.touchdown())));
        output.component(AutopilotText.tr("helipad.browser_header",
            "%s helipads, nearest first, from %s:", pads.size(), originName)
            .withStyle(ChatFormatting.GOLD));
        for (Helipad pad : pads) {
            output.component(row(level, pad, origin));
        }
        return pads.size();
    }

    private static MutableComponent row(ServerLevel level, Helipad pad, Vec3 origin) {
        double distance = AutopilotMath.horizontalDistance(origin, pad.touchdown());
        int bearing = AutopilotMath.compassDisplay(AutopilotMath.headingTo(origin, pad.touchdown()));
        MutableComponent line = Component.literal("  ")
            .append(name(pad))
            .append(Component.literal(String.format("  %dx%d  %s  %s brg %03d  %d/%d approaches",
                pad.size(), pad.size(), pad.centre().toShortString(),
                distanceText(distance), bearing, pad.clearSectorCount(),
                RotorcraftConfig.APPROACH_SECTORS)).withStyle(ChatFormatting.GRAY));
        if (!pad.free(level, null)) {
            line.append(AutopilotText.tr("helipad.occupied", "  OCCUPIED")
                .withStyle(ChatFormatting.YELLOW));
        }
        return line;
    }

    private static String distanceText(double distance) {
        return distance >= 1000 ? String.format("%.1fkm", distance / 1000.0)
            : String.format("%.0f blocks", distance);
    }

    private static MutableComponent name(Helipad pad) {
        return Component.literal(pad.name()).withStyle(style -> style
            .withColor(ChatFormatting.WHITE)
            .withClickEvent(new ClickEvent.RunCommand("/autopilot helipads info \"" + pad.name() + "\""))
            .withHoverEvent(new HoverEvent.ShowText(AutopilotText.tr("helipad.name_hover",
                "Click for the full survey of %s", pad.name()))));
    }

    /**
     * Everything stored about one pad, plus a live re-measurement of the things that can change.
     *
     * <p>The stored approach bearings and a fresh count are printed side by side when they disagree.
     * That is the same judgement {@link Airfield#approachObstacles} makes — a survey is a photograph,
     * trustworthy about the moment it was taken and silent about a wall built afterwards — except
     * that here it can be shown rather than only reasoned about, because a player asking for the
     * detail is standing near the pad.
     */
    public static void detail(AutopilotOutput output, ServerLevel level, Helipad pad,
                              Vec3 origin, String originName) {
        double distance = AutopilotMath.horizontalDistance(origin, pad.touchdown());
        int bearing = AutopilotMath.compassDisplay(AutopilotMath.headingTo(origin, pad.touchdown()));
        Vec3 point = pad.touchdown();

        output.component(AutopilotText.tr("helipad.detail_title", "%s (%sx%s pad)",
            pad.name(), pad.size(), pad.size()).withStyle(ChatFormatting.GOLD)
            .append(Component.literal("  ")).append(showButton(pad)));
        output.line(String.format("  touchdown %.1f, %.1f, %.1f (centre block %s)",
            point.x, point.y, point.z, pad.centre().toShortString()));
        output.line(String.format("  %s brg %03d from %s", distanceText(distance), bearing, originName));
        output.line(String.format("  landing tolerance %.1f blocks from the centre", pad.landingTolerance()));
        output.line("  clear approach bearings when surveyed: " + sectors(pad.clearSectors()));

        Helipad.Survey now = resurveyed(level, pad);
        if (now != null) {
            int mask = 0;
            for (int i = 0; i < now.sectors().length; i++) {
                if (now.sectors()[i]) {
                    mask |= 1 << i;
                }
            }
            if (mask != pad.clearSectors()) {
                output.warn("  clear approach bearings now: " + sectors(mask)
                    + " - run /autopilot helipads resurvey \"" + pad.name() + "\" to store this");
            }
            for (String refusal : now.refusals()) {
                output.warn("  the pad no longer passes its own survey: " + refusal);
            }
        }
        PlaneEntity holder = occupant(level, pad);
        if (holder != null) {
            output.line("  occupied by #" + holder.getId());
        }
    }

    private static String sectors(int mask) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < RotorcraftConfig.APPROACH_SECTORS; i++) {
            if ((mask & (1 << i)) == 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(String.format("%03d", AutopilotMath.compassDisplay(Helipad.sectorHeading(i))));
        }
        return builder.length() == 0 ? "none" : builder.toString();
    }

    /**
     * A fresh survey of the same ground, or null when the pad's chunks are not loaded.
     *
     * <p>Explicitly null rather than "no sectors clear": an unloaded column is not an obstacle, and
     * a pad nobody is standing near must not be accused of having grown a wall on no evidence. The
     * same rule the airfield browser applies to a centreline it cannot see.
     */
    private static Helipad.@org.jspecify.annotations.Nullable Survey resurveyed(ServerLevel level, Helipad pad) {
        if (!level.hasChunkAt(pad.centre().getX(), pad.centre().getZ())) {
            return null;
        }
        int r = pad.radius();
        return Helipad.survey(level, pad.name(),
            pad.centre().offset(-r, 0, -r), pad.centre().offset(r, 0, r));
    }

    private static @org.jspecify.annotations.Nullable PlaneEntity occupant(ServerLevel level, Helipad pad) {
        List<? extends PlaneEntity> found = HelipadReport.occupants(level, pad);
        return found.isEmpty() ? null : found.get(0);
    }

    private static MutableComponent showButton(Helipad pad) {
        return AutopilotText.tr("helipad.show", "[show]").withStyle(style -> style
            .withColor(ChatFormatting.AQUA)
            .withClickEvent(new ClickEvent.RunCommand("/autopilot helipads show \"" + pad.name() + "\"")));
    }

    // ------------------------------------------------------------------ management

    public static boolean remove(AutopilotOutput output, ServerLevel level, String name) {
        AutopilotSavedData data = AutopilotSavedData.get(level);
        if (data.helipad(name) == null) {
            output.component(unknown(name));
            return false;
        }
        data.removeHelipad(name);
        StandOccupancy.forget(level, name);
        output.success("Removed " + name + ".");
        return true;
    }

    public static boolean rename(AutopilotOutput output, ServerLevel level, String from, String to) {
        AutopilotSavedData data = AutopilotSavedData.get(level);
        Helipad pad = data.helipad(from);
        if (pad == null) {
            output.component(unknown(from));
            return false;
        }
        if (data.helipad(to) != null) {
            output.warn("There is already a helipad called " + to + ".");
            return false;
        }
        data.removeHelipad(from);
        StandOccupancy.forget(level, from);
        data.put(pad.withName(to));
        output.success("Renamed " + from + " to " + to + ".");
        return true;
    }

    /**
     * Re-measures a registered pad from its own stored centre and extent, keeping its name.
     *
     * <p>The one command that rewrites saved pad geometry, and the reason it exists is the same one
     * that produced {@code /autopilot airfields resurvey}: the world changes, and a stored survey is
     * a photograph of the moment it was taken. Refuses on unloaded ground for the same reason the
     * survey does — an unloaded pad reads as having no surface at all, and this would then store
     * that as the truth.
     */
    public static boolean resurvey(AutopilotOutput output, ServerLevel level, String name) {
        AutopilotSavedData data = AutopilotSavedData.get(level);
        Helipad pad = data.helipad(name);
        if (pad == null) {
            output.component(unknown(name));
            return false;
        }
        if (!level.hasChunkAt(pad.centre().getX(), pad.centre().getZ())) {
            output.warn(name + " is not loaded; go and stand on it, or load its chunks, and try again.");
            return false;
        }
        if (!pad.free(level, null)) {
            output.warn(name + " has an aircraft on it; move it first.");
            return false;
        }
        int r = pad.radius();
        Helipad.Survey survey = Helipad.survey(level, name,
            pad.centre().offset(-r, 0, -r), pad.centre().offset(r, 0, r));
        HelipadReport.report(output, survey, name);
        if (!survey.accepted()) {
            output.warn(name + " was left exactly as it was; nothing has been overwritten.");
            return false;
        }
        data.put(survey.pad().withName(name));
        HelipadReport.highlight(level, survey.pad().withName(name));
        return true;
    }

    public static MutableComponent unknown(String name) {
        return AutopilotText.tr("helipad.unknown",
            "No helipad called %s. Use /autopilot helipads to list them.", name)
            .withStyle(ChatFormatting.RED);
    }
}
