package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Defensive manoeuvring for an autopilot fighter under air-defence missile attack. Called once per autopilot
 * tick after the mode has produced its command; while a threat is being defeated it returns a {@link Command}
 * that replaces the heading and power (and, in a break, the altitude), and returns null again once the
 * threat is gone, so whatever the mode was flying (route, strike run-in, hold) simply carries on.
 *
 * <p>What works against this guidance was measured, not assumed (design/FIGHTER-EVASION.md): the missile
 * knows the aircraft's true position every tick and close in may turn 40 deg/t, so the only thing that
 * defeats it is kinematics, i.e. making it fly out its motor path. Hence:
 * <ul>
 *   <li>{@link Manoeuvre#DRAG}: put the missile at six o'clock, full power and booster;</li>
 *   <li>{@link Manoeuvre#BREAK}: in the last {@link #BREAK_TICKS} ticks, when the missile will get there, a
 *       hard turn across its line of sight with a climb. No measured run was saved by it (a missile with the
 *       motor to arrive arrives); it is the last-ditch state a pilot would fly, and costs nothing.</li>
 * </ul>
 * {@link Manoeuvre#BEAM} is only flown when forced for measurement: it lost to the drag in every trial.
 * Altitude is left to the mode (and to terrain following) except in the break, which only ever climbs.
 */
public final class MissileEvasion {

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes-autopilot");

    public enum Manoeuvre {
        DRAG("drag"), BEAM("beam"), BREAK("break");

        final String word;

        Manoeuvre(String word) {
            this.word = word;
        }
    }

    /**
     * What the flight director flies instead of the mode's command.
     *
     * @param heading    Minecraft yaw
     * @param climb      blocks to add to the commanded altitude (0 keeps the mode's)
     * @param bankLimit  degrees
     */
    public record Command(double heading, double climb, double bankLimit) {}

    /**
     * Ticks between a launch being seen and the manoeuvre starting. The balance knob: at 10 a fighter outruns
     * every T2 and T3 shot, at 30 (1.5 s) it defeats most T2, a few T3 and no T4 (FIGHTER-EVASION.md).
     */
    public static final int REACTION_TICKS = Integer.getInteger("simpleplanes.evasion.reaction", 30);
    /** Time to impact under which a reachable missile gets the last-ditch break. */
    public static final double BREAK_TICKS = 25.0;
    /** Climb commanded in a break, blocks above the mode's altitude. */
    public static final double BREAK_CLIMB = 30.0;
    /** Bank allowed while defending; see FIGHTER-EVASION.md for the turn rates measured against it. */
    public static final double EVASION_BANK = Double.parseDouble(System.getProperty("simpleplanes.evasion.bank", "60"));
    /**
     * Test hook: {@code -Dsimpleplanes.evasion=off|drag|beam|break} disables evasion or forces one manoeuvre;
     * anything else is the normal choice.
     */
    private static final String FORCED = System.getProperty("simpleplanes.evasion", "auto");

    private @Nullable Manoeuvre manoeuvre;
    private int missileId = -1;
    private double tti = Double.POSITIVE_INFINITY;
    private int clock;
    /** First tick each missile was seen, for the reaction delay. */
    private final Map<Integer, Integer> seen = new HashMap<>();
    private int evasions;
    private int ticks;

    /**
     * @param committed the mode has committed to a manoeuvre of its own that must not be interrupted (the
     *                  strike dive)
     */
    public @Nullable Command tick(PlaneEntity plane, AutopilotMode mode, boolean committed, @Nullable Player owner) {
        ticks++;
        if ("off".equals(FORCED) || AircraftType.of(plane) != AircraftType.FIGHTER || committed || !defends(mode)
            || plane.getOnGround()) {
            end(plane, owner, null);
            return null;
        }
        List<MissileThreat> threats = MissileThreat.inbound(plane);
        MissileThreat threat = null;
        for (MissileThreat t : threats) {
            seen.putIfAbsent(t.missile().getId(), ticks);
            if (threat == null && t.canReach() && ticks - seen.get(t.missile().getId()) >= REACTION_TICKS) {
                threat = t;
            }
        }
        if (threats.isEmpty()) {
            seen.clear();
        }
        if (threat == null) {
            end(plane, owner, threats.isEmpty() ? "clear" : "out of reach");
            return null;
        }

        Manoeuvre next = choose(threat);
        double away = AutopilotMath.headingTo(threat.at(), plane.position());
        double beam = beamHeading(plane.getYRot(), away);
        Command command = switch (next) {
            case DRAG -> new Command(away, 0.0, EVASION_BANK);
            case BEAM -> new Command(beam, 0.0, EVASION_BANK);
            case BREAK -> new Command(beam, BREAK_CLIMB, EVASION_BANK);
        };
        if (next != manoeuvre || threat.missile().getId() != missileId) {
            if (manoeuvre == null) {
                evasions++;
            }
            LOGGER.info(String.format(Locale.ROOT, "[evasion] #%d %s missile #%d T%d range=%.0f closing=%.2f tti=%s left=%.0f clock=%d",
                plane.getId(), next.word, threat.missile().getId(), threat.tier(), threat.range(), threat.closing(),
                seconds(threat.tti()), threat.remaining(), threat.clock(plane)));
            if (manoeuvre == null) {
                AutopilotFeedback.overlay(owner, "Plane #" + plane.getId() + ": missile warning, " + next.word);
            }
        }
        manoeuvre = next;
        missileId = threat.missile().getId();
        tti = threat.tti();
        clock = threat.clock(plane);
        return command;
    }

    private static Manoeuvre choose(MissileThreat threat) {
        switch (FORCED) {
            case "drag": return Manoeuvre.DRAG;
            case "beam": return Manoeuvre.BEAM;
            case "break": return threat.tti() < BREAK_TICKS ? Manoeuvre.BREAK : Manoeuvre.DRAG;
            default: break;
        }
        // The break only when the missile will actually arrive: a tail chase at the end of its motor path also
        // shows a short time to impact, and turning across it there would hand it the shortcut it needs.
        if (threat.tti() < BREAK_TICKS && threat.remaining() >= threat.speed() * threat.tti()) {
            return Manoeuvre.BREAK;
        }
        return Manoeuvre.DRAG;
    }

    /** The perpendicular to the missile's line of sight nearer the current heading. */
    private static double beamHeading(double yaw, double away) {
        double right = away + 90.0;
        double left = away - 90.0;
        return Math.abs(AutopilotMath.angleDelta(yaw, right)) <= Math.abs(AutopilotMath.angleDelta(yaw, left)) ? right : left;
    }

    private static boolean defends(AutopilotMode mode) {
        return switch (mode) {
            case CLIMB, CRUISE, STRIKE, DESCENT, HOLD, GO_AROUND -> true;
            default -> false;
        };
    }

    private void end(PlaneEntity plane, @Nullable Player owner, @Nullable String why) {
        if (manoeuvre == null) {
            return;
        }
        LOGGER.info(String.format(Locale.ROOT, "[evasion] #%d resume (%s) after missile #%d",
            plane.getId(), why == null ? "stood down" : why, missileId));
        AutopilotFeedback.overlay(owner, "Plane #" + plane.getId() + ": threat " + (why == null ? "ignored" : why) + ", resuming");
        manoeuvre = null;
        missileId = -1;
        tti = Double.POSITIVE_INFINITY;
    }

    public boolean isEvading() {
        return manoeuvre != null;
    }

    /** Defensive manoeuvres flown this flight. */
    public int evasions() {
        return evasions;
    }

    /** {@code /autopilot status} fragment, empty when not defending. */
    public String statusFragment() {
        if (manoeuvre == null) {
            return "";
        }
        return String.format(Locale.ROOT, " evading(%s, missile #%d, tti=%s, %d o'clock)", manoeuvre.word, missileId, seconds(tti), clock);
    }

    private static String seconds(double ticks) {
        return Double.isInfinite(ticks) ? "-" : String.format(Locale.ROOT, "%.1fs", ticks / 20.0);
    }
}
