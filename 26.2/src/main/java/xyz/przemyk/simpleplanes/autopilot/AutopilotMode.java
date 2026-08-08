package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;

/**
 * Flight-director state machine states.
 *
 * <pre>
 * IDLE ─► TAXI ─► TAKEOFF ─► CLIMB ─► CRUISE ─► DESCENT ─► APPROACH ─► FINAL ─► FLARE ─► ROLLOUT ─► IDLE
 *                                        │          ▲         │  ▲                │
 *                                        │          └─ HOLD ◄─┘  └──── GO_AROUND ◄─┘
 *                                        └──► STRIKE (one-way attack run, no landing)
 * </pre>
 *
 * <p>{@code TAXI} is only entered by a sortie that starts parked at a registered airfield; an
 * aircraft launched in the air begins at {@code CLIMB} and one placed on open ground at
 * {@code TAKEOFF}.
 */
public enum AutopilotMode {
    IDLE("idle"),
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

    /** Modes in which the aircraft is committed to a runway and must hold the reservation. */
    public boolean usesRunway() {
        return this == APPROACH || this == FINAL || this == FLARE || this == ROLLOUT || this == TAKEOFF;
    }

    /** Modes where the wings must stay level (ground roll, touchdown). */
    public boolean requiresWingsLevel() {
        return this == FINAL || this == FLARE || this == ROLLOUT || this == TAKEOFF || this == TAXI;
    }

    /** Modes flown with the wheels on the ground, where there is no flight path to speak of. */
    public boolean isGroundPhase() {
        return this == TAXI || this == TAKEOFF || this == ROLLOUT;
    }
}
