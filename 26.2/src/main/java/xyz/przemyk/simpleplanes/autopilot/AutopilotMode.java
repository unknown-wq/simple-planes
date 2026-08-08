package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;

/**
 * Flight-director state machine states.
 *
 * <pre>
 * IDLE ─► PARKED ─► TAXI ─► TAKEOFF ─► CLIMB ─► CRUISE ─► DESCENT ─► APPROACH ─► FINAL ─► FLARE ─► ROLLOUT ─► IDLE
 *                                                 │          ▲         │  ▲                │
 *                                                 │          └─ HOLD ◄─┘  └──── GO_AROUND ◄─┘
 *                                                 └──► STRIKE (one-way attack run, no landing)
 * </pre>
 *
 * <p>{@code PARKED} and {@code TAXI} are only entered by a sortie that starts at a registered
 * airfield; an aircraft launched in the air begins at {@code CLIMB} and one placed on open ground at
 * {@code TAKEOFF}.
 */
public enum AutopilotMode {
    IDLE("idle"),
    PARKED("parked"),
    TAXI("taxi"),
    TAKEOFF("takeoff"),
    CLIMB("climb"),
    CRUISE("cruise"),
    DESCENT("descent"),
    APPROACH("approach"),
    FINAL("final"),
    FLARE("flare"),
    ROLLOUT("rollout"),
    HOLD("hold"),
    GO_AROUND("go_around"),
    STRIKE("strike");

    private static final AutopilotMode[] BY_ID = values();
    public static final Codec<AutopilotMode> CODEC = Codec.STRING.xmap(AutopilotMode::byName, AutopilotMode::getName);

    private final String name;

    AutopilotMode(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static AutopilotMode byName(String name) {
        for (AutopilotMode mode : BY_ID) {
            if (mode.name.equals(name)) {
                return mode;
            }
        }
        return IDLE;
    }

    /** Modes in which an <em>arriving</em> aircraft is committed to a runway and holds its reservation. */
    public boolean usesRunway() {
        return this == APPROACH || this == FINAL || this == FLARE || this == ROLLOUT || this == TAKEOFF;
    }

    /**
     * Modes in which a <em>departing</em> aircraft holds the reservation on the field it is leaving.
     *
     * <p>{@code PARKED} is deliberately not one of them: waiting on the spot is exactly the state of
     * not having the runway yet, and an aircraft that already held it would be gating itself.
     * {@code TAKEOFF} ends at {@link AutopilotConfig#TAKEOFF_CLEAR_HEIGHT} above the ground and past
     * the far threshold, which is where the strip is genuinely free again.
     */
    public boolean holdsDepartureRunway() {
        return this == TAXI || this == TAKEOFF;
    }

    /** Modes where the wings must stay level (ground roll, touchdown). */
    public boolean requiresWingsLevel() {
        return this == FINAL || this == FLARE || this == ROLLOUT || this == TAKEOFF || this == TAXI;
    }

    /** Modes flown with the wheels on the ground, where there is no flight path to speak of. */
    public boolean isGroundPhase() {
        return this == PARKED || this == TAXI || this == TAKEOFF || this == ROLLOUT;
    }
}
