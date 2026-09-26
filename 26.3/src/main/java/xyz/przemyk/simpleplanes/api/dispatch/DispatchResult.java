package xyz.przemyk.simpleplanes.api.dispatch;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Outcome of a call that can be refused.
 *
 * @param ok     true when the call took effect
 * @param id     the order id for {@code dispatch}, the aircraft for {@code deploy}, otherwise null
 * @param reason one of the {@link DispatchReasons} refusal codes when {@code ok} is false
 * @param detail human-readable text for logs, never parsed
 */
public record DispatchResult(boolean ok, @Nullable UUID id, @Nullable String reason, String detail) {

    public static DispatchResult success(@Nullable UUID id, String detail) {
        return new DispatchResult(true, id, null, detail);
    }

    public static DispatchResult refused(String reason, String detail) {
        return new DispatchResult(false, null, reason, detail);
    }
}
