package xyz.przemyk.simpleplanes.api.dispatch;

/**
 * A registered helipad.
 *
 * @param name     pad name, as used by {@code homePad}
 * @param x        centre block x
 * @param groundY  y of the pad surface block; aircraft stand at {@code groundY + 1}
 * @param z        centre block z
 * @param radius   pad half-size
 * @param free     nothing stands on it and nothing is inbound to it (may read true when unloaded)
 * @param distance horizontal distance from the query point
 */
public record PadInfo(String name, int x, int groundY, int z, int radius, boolean free, double distance) {}
