package xyz.przemyk.simpleplanes.api.map;

import org.jspecify.annotations.Nullable;

/**
 * What a map asks a silo to do. Every action needs operator permission and a player near the silo; the server
 * decides. Part of the stable map API (since {@link AviationMap#API_VERSION} 2).
 */
public enum SiloAction {
    /** Start a strike launch at a target column ({@link AviationMap#requestLaunch}). */
    LAUNCH,
    /** Load a missile of the silo's own tier, like {@code /missile silo load} ({@link AviationMap#requestLoad}). */
    LOAD,
    /** Take the missile out, like {@code /missile silo unload} ({@link AviationMap#requestUnload}). */
    UNLOAD;

    private static final SiloAction[] VALUES = values();

    /** @return the action with this ordinal, or null for an unknown one (a newer client). */
    public static @Nullable SiloAction byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : null;
    }
}
