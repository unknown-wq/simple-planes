package xyz.przemyk.simpleplanes.api.dispatch;

import net.minecraft.nbt.CompoundTag;
import org.jspecify.annotations.Nullable;

/**
 * One out-and-back (or one-way) job for a dispatchable rotorcraft.
 *
 * <p>The target needs no helipad: a landing zone is searched for within {@code searchRadius}
 * blocks of it while the aircraft is outbound. {@code userData} is stored with the order, survives
 * a restart and is handed back on every {@link DispatchEvent}.
 *
 * @param ownerId         caller-chosen key; events go to the listener registered under it
 * @param homePad         registered helipad the aircraft returns to
 * @param targetX         target block x
 * @param targetY         target block y; only a hint, the landing zone is on the surface
 * @param targetZ         target block z
 * @param searchRadius    landing-zone search radius in blocks, 0 for the default
 * @param groundHoldTicks ticks to wait on the ground at the landing zone before leaving
 * @param returnHome      fly back to {@code homePad} when the hold ends
 * @param cruiseSpeed     blocks/tick, 0 for the airframe's default
 * @param userData        opaque caller data, never read by Simple Planes
 */
public record DispatchOrder(String ownerId, String homePad, int targetX, int targetY, int targetZ,
                            int searchRadius, int groundHoldTicks, boolean returnHome, double cruiseSpeed,
                            CompoundTag userData) {

    public static final int DEFAULT_SEARCH_RADIUS = 32;
    public static final int MAX_SEARCH_RADIUS = 96;
    public static final int DEFAULT_HOLD_TICKS = 1200;
    /** Upper bound for one hold or one extension: two in-game days. */
    public static final int MAX_HOLD_TICKS = 48000;

    public DispatchOrder {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId is required");
        }
        if (homePad == null || homePad.isBlank()) {
            throw new IllegalArgumentException("homePad is required");
        }
        searchRadius = searchRadius <= 0 ? DEFAULT_SEARCH_RADIUS : Math.min(searchRadius, MAX_SEARCH_RADIUS);
        groundHoldTicks = Math.max(0, Math.min(groundHoldTicks, MAX_HOLD_TICKS));
        cruiseSpeed = Double.isFinite(cruiseSpeed) && cruiseSpeed > 0 ? cruiseSpeed : 0;
        userData = userData == null ? new CompoundTag() : userData.copy();
    }

    /** Out and back with the default search radius and speed. */
    public static DispatchOrder of(String ownerId, String homePad, int x, int y, int z, int groundHoldTicks) {
        return new DispatchOrder(ownerId, homePad, x, y, z, 0, groundHoldTicks, true, 0, null);
    }

    public DispatchOrder withSearchRadius(int radius) {
        return new DispatchOrder(ownerId, homePad, targetX, targetY, targetZ, radius, groundHoldTicks,
            returnHome, cruiseSpeed, userData);
    }

    public DispatchOrder withReturnHome(boolean value) {
        return new DispatchOrder(ownerId, homePad, targetX, targetY, targetZ, searchRadius, groundHoldTicks,
            value, cruiseSpeed, userData);
    }

    public DispatchOrder withCruiseSpeed(double speed) {
        return new DispatchOrder(ownerId, homePad, targetX, targetY, targetZ, searchRadius, groundHoldTicks,
            returnHome, speed, userData);
    }

    public DispatchOrder withUserData(@Nullable CompoundTag data) {
        return new DispatchOrder(ownerId, homePad, targetX, targetY, targetZ, searchRadius, groundHoldTicks,
            returnHome, cruiseSpeed, data);
    }

    /** A defensive copy; the record's own tag is never handed out for mutation. */
    @Override
    public CompoundTag userData() {
        return userData.copy();
    }
}
