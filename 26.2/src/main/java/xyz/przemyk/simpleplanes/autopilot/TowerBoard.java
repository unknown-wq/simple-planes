package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tower board: what every runway in a dimension is doing right now.
 *
 * <p><b>Read-only, on purpose.</b> Nothing here reserves, releases, orders or times out anything.
 * Occupancy is asked of {@link RunwayOccupancy#holder}, which validates the holder rather than
 * trusting the map, so the board cannot show a runway as busy because of an aircraft that crashed;
 * and it cannot disagree with the answer the aircraft themselves get, because it is the same call.
 * Durations come from {@link TowerWatch}, which only watches.
 *
 * <h2>Why the text is built the way it is</h2>
 * Sentences use {@link Component#translatableWithFallback}, so a player reading the board in chat
 * gets their own language while the dedicated server console — which has no mod language files and
 * would otherwise print raw keys — gets the English fallback. The tabular part (the padded name and
 * designator columns, {@code FREE}/{@code OCCUPIED}, aircraft ids, mode names, clocks) is left as
 * literal text: those are the same tokens {@code /autopilot status} prints, and a translated word of
 * a different length would break the column alignment that makes the board readable at a glance.
 *
 * <h2>What the board deliberately does not claim</h2>
 * <ul>
 *   <li><b>No queue numbers.</b> There is no queue in the code today — an aircraft in
 *       {@link AutopilotMode#HOLD} or {@link AutopilotMode#PARKED} polls a free runway every 20
 *       ticks and whoever polls first takes it, arrivals and departures alike. Numbering them would
 *       draw an order that does not exist, so they are listed longest-wait-first with the poll rule
 *       stated.</li>
 * </ul>
 *
 * <p>Departures <em>are</em> shown, which they were not: a reservation used to be taken only for the
 * field an aircraft was landing at, so a strip with an aircraft taxiing onto it read FREE. It now
 * holds a reservation from the start of the taxi to the climb-out, and aircraft still standing on
 * their parking spots are listed under the field they are waiting to leave, with what they are
 * waiting for.
 *
 * <p>Each aircraft's row also carries the end it picked and a one-phrase account of how it intends
 * to get there ({@code straight in}, {@code extended final 600}, {@code orbit to lose 120},
 * {@code around left 30 deg}). Both come from the flight director's own state rather than being
 * re-derived here, so the board cannot describe an arrival differently from the way it is flown.
 */
public final class TowerBoard {

    /** Longest airfield name the first column will pad to, so one silly name cannot skew the board. */
    private static final int MAX_NAME_COLUMN = 24;

    private TowerBoard() {}

    /** One aircraft's involvement with a runway. */
    private record Traffic(PlaneEntity plane, TowerWatch.Role role, String airfield) {}

    /** Everything the board knows about one runway. */
    private record Stand(String name, @Nullable Airfield airfield, @Nullable PlaneEntity occupant,
                         List<PlaneEntity> holding, List<PlaneEntity> waiting, List<PlaneEntity> inbound) {

        static Stand of(ServerLevel level, String name, @Nullable Airfield airfield) {
            return new Stand(name, airfield, RunwayOccupancy.holder(level, name),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    // ------------------------------------------------------------------ the board

    /** The whole dimension, one line per runway plus the aircraft holding for it. */
    public static List<Component> board(ServerLevel level) {
        List<Stand> stands = stands(level);
        List<Component> lines = new ArrayList<>();
        if (stands.isEmpty()) {
            lines.add(text("empty", "No airfields registered in this dimension, and no aircraft holding for one."));
            return lines;
        }

        int occupied = 0;
        int holding = 0;
        int waiting = 0;
        for (Stand stand : stands) {
            if (stand.occupant() != null) {
                occupied++;
            }
            holding += stand.holding().size();
            waiting += stand.waiting().size();
        }
        lines.add(text("summary", "%s runways in this dimension, %s occupied, %s holding, %s waiting to depart.",
            stands.size(), occupied, holding, waiting));

        int width = nameColumn(stands);
        for (Stand stand : stands) {
            lines.add(standLine(stand, width));
            lines.addAll(holdingLines(stand));
            lines.addAll(waitingLines(stand));
        }
        return lines;
    }

    /**
     * One runway in detail: the same state line, then the geometry, everyone holding, and everyone
     * else on the way in. Returns an empty list when the name is neither registered nor in use.
     */
    public static List<Component> board(ServerLevel level, String name) {
        for (Stand stand : stands(level)) {
            if (!stand.name().equals(name)) {
                continue;
            }
            List<Component> lines = new ArrayList<>();
            lines.add(standLine(stand, stand.name().length()));
            Airfield airfield = stand.airfield();
            if (airfield == null) {
                lines.add(indent(2, text("unregistered_note", "not registered in this dimension - traffic is "
                    + "flying to a strip it surveyed for itself, or to one that has since been removed")));
            } else {
                lines.add(indent(2, text("geometry", "runway %s, %s blocks long, %s wide, slope %s deg",
                    airfield.designators(), Math.round(airfield.length()), airfield.width(),
                    String.format(Locale.ROOT, "%+.1f", airfield.slopeDegrees()))));
                for (RunwayEnd end : airfield.ends()) {
                    Vec3 threshold = end.threshold();
                    lines.add(indent(4, text("threshold", "%s  heading %s  threshold %s",
                        end.designator(),
                        String.format(Locale.ROOT, "%03d", AutopilotMath.compassDisplay(end.landingHeading())),
                        String.format(Locale.ROOT, "%.0f, %.0f, %.0f", threshold.x, threshold.y, threshold.z))));
                }
            }
            lines.addAll(holdingLines(stand));
            lines.addAll(waitingLines(stand));
            if (!stand.inbound().isEmpty()) {
                lines.add(indent(2, text("inbound_header", "inbound:")));
                for (PlaneEntity plane : sortedByDistance(stand.inbound(), stand.airfield())) {
                    lines.add(indent(4, trafficLine(plane, stand, false)));
                }
            }
            return lines;
        }
        return List.of();
    }

    /** The refusal for a name that is neither registered nor being flown to. */
    public static Component unknownAirfield(String name) {
        return text("unknown", "No such airfield: %s, and nothing is flying to one by that name. "
            + "Use /autopilot airfields to list them.", name);
    }

    // ------------------------------------------------------------------ lines

    private static Component standLine(Stand stand, int width) {
        String designators = stand.airfield() == null ? "--/--" : stand.airfield().designators();
        String state = stand.occupant() != null ? "OCCUPIED" : "FREE";
        Component detail;
        if (stand.occupant() != null) {
            detail = trafficLine(stand.occupant(), stand, true);
        } else if (!stand.holding().isEmpty()) {
            detail = text("holding_only", "%s holding, none cleared yet", stand.holding().size());
        } else if (!stand.waiting().isEmpty()) {
            detail = text("waiting_only", "%s waiting to depart, none cleared yet", stand.waiting().size());
        } else {
            detail = text("no_traffic", "no traffic");
        }

        // Padded columns stay literal: translated words of a different length would break them.
        MutableComponent line = Component.literal(String.format(Locale.ROOT,
                "%-" + Math.max(1, width) + "s  %-5s  %-8s  ", stand.name(), designators, state))
            .append(detail);
        if (stand.airfield() == null) {
            line.append(Component.literal("  ")).append(text("not_registered", "(not registered)"));
        }
        return line;
    }

    private static List<Component> holdingLines(Stand stand) {
        if (stand.holding().isEmpty()) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        // No ordinals: whichever aircraft next polls a free runway takes it, so any numbering here
        // would be an order the code does not implement.
        lines.add(indent(2, text("holding_header",
            "holding (no sequence: the first to poll a free runway takes it):")));
        for (PlaneEntity plane : sortedByWait(stand.holding())) {
            lines.add(indent(4, trafficLine(plane, stand, true)));
        }
        return lines;
    }

    /**
     * Aircraft standing on a parking spot at this field. Same "no sequence" caveat as the holding
     * list, and for exactly the same reason — they poll the same runway on the same rule.
     */
    private static List<Component> waitingLines(Stand stand) {
        if (stand.waiting().isEmpty()) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        lines.add(indent(2, text("waiting_header",
            "waiting to depart (no sequence: the first to poll a free runway takes it):")));
        for (PlaneEntity plane : sortedByWait(stand.waiting())) {
            lines.add(indent(4, trafficLine(plane, stand, true)));
        }
        return lines;
    }

    /**
     * {@code #12 arrival 09, final, 0:14, 288 blocks out [straight in]} — the same shape for every
     * role, arrival or departure.
     *
     * <p>The trailing bracket is the flight director's own account of the arrival: which geometry it
     * chose and why. It is the answer to "why is that aircraft circling", which is the question a
     * board full of holding traffic exists to raise, and it comes from the aircraft rather than
     * being re-derived here, so the board cannot disagree with it.
     */
    private static Component trafficLine(PlaneEntity plane, Stand stand, boolean withElapsed) {
        PlaneAutopilot autopilot = plane.getAutopilot();
        boolean departure = autopilot != null && stand.name().equals(autopilot.departureAirfieldName());
        // The end is only meaningful for an arrival: a departure's runway end is chosen by the
        // taxi, and printing a landing designator beside it would read as a clearance it does not
        // have.
        String end = departure || autopilot == null ? null : autopilot.landingDesignator();
        MutableComponent line = Component.literal("#" + plane.getId() + " ")
            .append(departure ? text("departure", "departure") : text("arrival", "arrival"))
            .append(Component.literal((end == null ? "" : " " + end)
                + ", " + (autopilot == null ? "?" : autopilot.getMode().getName())));
        if (withElapsed) {
            line.append(Component.literal(", " + TowerWatch.elapsed(plane)));
        }
        // What an aircraft on the ground is waiting for, and a distance for one that is not. A wait
        // that cannot say which of the two gates it is behind reads exactly like a hang.
        int held = autopilot == null ? -1 : autopilot.departureHoldTicks();
        if (held > 0) {
            line.append(Component.literal(", "))
                .append(text("wait_clock", "%s on the clock", TowerWatch.clock(held)));
        } else if (held == 0) {
            line.append(Component.literal(", ")).append(text("wait_runway", "waiting for the runway"));
        } else if (!departure) {
            Double distance = distanceTo(plane, stand.airfield());
            if (distance != null) {
                line.append(Component.literal(", "))
                    .append(text("blocks_out", "%s blocks out", Math.round(distance)));
            }
        }
        if (autopilot != null) {
            line.append(Component.literal(" [")).append(autopilot.planComponent()).append(Component.literal("]"));
        }
        return line;
    }

    // ------------------------------------------------------------------ gathering

    /**
     * Every runway worth a line: the registered ones, plus any name that still has traffic flying to
     * it — an improvised strip, or a field that was removed while an aircraft was already inbound.
     */
    private static List<Stand> stands(ServerLevel level) {
        AutopilotSavedData data = AutopilotSavedData.get(level);
        Map<String, Stand> stands = new LinkedHashMap<>();
        for (Airfield airfield : data.airfieldList()) {
            stands.put(airfield.name(), Stand.of(level, airfield.name(), airfield));
        }

        for (Traffic traffic : traffic(level)) {
            Stand stand = stands.computeIfAbsent(traffic.airfield(),
                name -> Stand.of(level, name, data.get(name)));
            if (traffic.role() == TowerWatch.Role.HOLDING) {
                stand.holding().add(traffic.plane());
            } else if (traffic.role() == TowerWatch.Role.WAITING) {
                stand.waiting().add(traffic.plane());
            }
            // An occupant is not recorded here: the occupant on the row always comes from
            // RunwayOccupancy.holder(), so the board and the aircraft cannot be looking at two
            // different answers. Walking it here only ensures the row exists at all when the field
            // it is landing at is not registered.
        }

        // Aircraft on the way to a field but not yet holding or cleared: detail view only, but
        // gathered here so both views agree on which names exist at all.
        for (PlaneEntity plane : AutopilotRegistry.active()) {
            if (plane.level() != level) {
                continue;
            }
            String name = TowerWatch.airfieldOf(plane);
            if (name == null || TowerWatch.roleOf(plane, name) != null) {
                continue;
            }
            Stand stand = stands.get(name);
            if (stand != null) {
                stand.inbound().add(plane);
            }
        }
        return new ArrayList<>(stands.values());
    }

    private static List<Traffic> traffic(ServerLevel level) {
        List<Traffic> traffic = new ArrayList<>();
        for (PlaneEntity plane : AutopilotRegistry.active()) {
            if (plane.level() != level) {
                continue;
            }
            String airfield = TowerWatch.airfieldOf(plane);
            TowerWatch.Role role = TowerWatch.roleOf(plane, airfield);
            if (role != null) {
                traffic.add(new Traffic(plane, role, airfield));
            }
        }
        return traffic;
    }

    // ------------------------------------------------------------------ small helpers

    /**
     * Translated in chat, English on the console. A dedicated server loads no mod language files, so
     * a plain {@code translatable} would print its raw key exactly where this board is read most.
     */
    private static MutableComponent text(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("simpleplanes.tower." + key, fallback, args);
    }

    private static MutableComponent indent(int spaces, Component line) {
        return Component.literal(" ".repeat(spaces)).append(line);
    }

    private static @Nullable Double distanceTo(PlaneEntity plane, @Nullable Airfield airfield) {
        return airfield == null ? null : AutopilotMath.horizontalDistance(plane.position(), airfield.centre());
    }

    /** Longest wait first: the aircraft that has been up there longest is the one to look at. */
    private static List<PlaneEntity> sortedByWait(List<PlaneEntity> planes) {
        List<PlaneEntity> sorted = new ArrayList<>(planes);
        sorted.sort(Comparator.comparingLong(TowerWatch::ticksInRole).reversed());
        return sorted;
    }

    private static List<PlaneEntity> sortedByDistance(List<PlaneEntity> planes, @Nullable Airfield airfield) {
        List<PlaneEntity> sorted = new ArrayList<>(planes);
        sorted.sort(Comparator.comparingDouble(plane -> {
            Double distance = distanceTo(plane, airfield);
            return distance == null ? Double.MAX_VALUE : distance;
        }));
        return sorted;
    }

    private static int nameColumn(List<Stand> stands) {
        int width = 1;
        for (Stand stand : stands) {
            width = Math.max(width, stand.name().length());
        }
        return Math.min(width, MAX_NAME_COLUMN);
    }
}
