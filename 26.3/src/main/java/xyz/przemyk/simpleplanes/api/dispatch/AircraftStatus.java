package xyz.przemyk.simpleplanes.api.dispatch;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Snapshot of one dispatchable aircraft.
 *
 * @param aircraft      entity UUID
 * @param phase         where it is in its order
 * @param loaded        whether the entity is loaded now; when false the position is the last known one
 * @param ownerId       owner of the current or last order, or null
 * @param homePad       pad it belongs to
 * @param orderId       current order, or null when idle
 * @param x             position
 * @param y             position
 * @param z             position
 * @param onGround      standing on the ground
 * @param atHome        on the ground at the home pad
 * @param hasLandingZone whether a landing zone is known for the current order
 * @param zoneX         landing zone centre, valid when {@code hasLandingZone}
 * @param zoneY         landing zone ground y, valid when {@code hasLandingZone}
 * @param zoneZ         landing zone centre, valid when {@code hasLandingZone}
 * @param holdTicksLeft ticks until the aircraft leaves the landing zone; -1 when not holding
 * @param passengers    entities aboard, in seat order where known
 * @param capacity      how many non-player riders it can take (2 for the medical livery)
 * @param abortReason   {@link DispatchReasons} code once the current order is aborted, else null
 * @param flightMode    autopilot mode name, for display only
 */
public record AircraftStatus(UUID aircraft, Phase phase, boolean loaded, @Nullable String ownerId, String homePad,
                             @Nullable UUID orderId, double x, double y, double z, boolean onGround, boolean atHome,
                             boolean hasLandingZone, int zoneX, int zoneY, int zoneZ, int holdTicksLeft,
                             List<UUID> passengers, int capacity, @Nullable String abortReason,
                             String flightMode) {

    public enum Phase {
        /** On the ground, no order. May be away from home if the last order did not return. */
        IDLE,
        /** Flying to the target; the landing zone may still be searched for. */
        OUTBOUND,
        /** On the ground at the landing zone, holding. Load and unload passengers now. */
        AT_TARGET,
        /** Flying home (or to an emergency landing zone after an abort). */
        RETURNING,
        /** Gone. The record is kept so the owner can still read the outcome. */
        LOST
    }

    public String phaseName() {
        return phase.name();
    }
}
