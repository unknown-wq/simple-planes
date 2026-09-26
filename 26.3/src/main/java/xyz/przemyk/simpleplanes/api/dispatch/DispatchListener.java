package xyz.przemyk.simpleplanes.api.dispatch;

/**
 * Receives {@link DispatchEvent}s for one owner id. Every method has a no-op default.
 *
 * <p>The service only ever calls {@link #onEvent}; its default forwards to the typed methods. A
 * caller implementing this through {@code java.lang.reflect.Proxy} therefore only has to handle
 * {@code onEvent}. Exceptions thrown here are logged and the event counts as delivered.
 */
public interface DispatchListener {

    default void onEvent(DispatchEvent event) {
        switch (event.type()) {
            case DEPARTED -> departed(event);
            case LANDED_AT_TARGET -> landedAtTarget(event);
            case LEFT_TARGET -> leftTarget(event);
            case RETURNED -> returned(event);
            case ABORTED -> aborted(event, event.reason() == null ? "" : event.reason());
            case LOST -> lost(event);
        }
    }

    default void departed(DispatchEvent event) {}

    default void landedAtTarget(DispatchEvent event) {}

    default void leftTarget(DispatchEvent event) {}

    default void returned(DispatchEvent event) {}

    default void aborted(DispatchEvent event, String reason) {}

    default void lost(DispatchEvent event) {}
}
