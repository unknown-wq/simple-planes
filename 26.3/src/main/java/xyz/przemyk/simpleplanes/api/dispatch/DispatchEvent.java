package xyz.przemyk.simpleplanes.api.dispatch;

import net.minecraft.nbt.CompoundTag;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Something that happened to a dispatched aircraft. Events are persisted until a listener for
 * {@link #ownerId} takes them, so none are lost across a restart; they are delivered on the server
 * thread at the end of a level tick, in order.
 *
 * @param type      what happened
 * @param aircraft  aircraft entity UUID
 * @param orderId   order this belongs to, null for events outside an order
 * @param ownerId   the order's owner
 * @param reason    {@link DispatchReasons} code for ABORTED and LOST, a short note otherwise, or null
 * @param x         aircraft block position when it happened
 * @param y         aircraft block position when it happened
 * @param z         aircraft block position when it happened
 * @param dimension dimension id, e.g. {@code minecraft:overworld}
 * @param gameTime  level game time when it happened
 * @param aborted   the order has been aborted (set on ABORTED and on everything after it)
 * @param userData  copy of the order's user data
 */
public record DispatchEvent(Type type, UUID aircraft, @Nullable UUID orderId, String ownerId,
                            @Nullable String reason, int x, int y, int z, String dimension, long gameTime,
                            boolean aborted, CompoundTag userData) {

    public enum Type {
        /** Lifted off from the home pad on the outbound leg. */
        DEPARTED,
        /** On the ground at the landing zone. The hold has started; see {@link AircraftStatus#holdTicksLeft}. */
        LANDED_AT_TARGET,
        /** Hold over (expired or released); leaving the landing zone for home. */
        LEFT_TARGET,
        /** Landed back on the home pad (or, with {@code aborted} and RETURN_FAILED, short of it). */
        RETURNED,
        /** The order was given up; the aircraft is now flying home or has stayed where it is. */
        ABORTED,
        /** The aircraft is gone: destroyed, removed, or not found. Terminal. */
        LOST
    }

    @Override
    public CompoundTag userData() {
        return userData.copy();
    }

    /** Everything as plain Java values, for callers that reach this by reflection. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type.name());
        map.put("aircraft", aircraft);
        map.put("orderId", orderId);
        map.put("ownerId", ownerId);
        map.put("reason", reason);
        map.put("x", x);
        map.put("y", y);
        map.put("z", z);
        map.put("dimension", dimension);
        map.put("gameTime", gameTime);
        map.put("aborted", aborted);
        map.put("userData", userData.copy());
        return map;
    }
}
