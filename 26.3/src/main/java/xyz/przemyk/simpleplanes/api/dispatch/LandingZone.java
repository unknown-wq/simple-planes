package xyz.przemyk.simpleplanes.api.dispatch;

/**
 * A landing zone found by the search.
 *
 * @param x            centre column x
 * @param groundY      y of the top solid block the skids rest on; the aircraft stands at {@code groundY + 1}
 * @param z            centre column z
 * @param radius       footprint half-size
 * @param clearSectors bitmask of clear approach sectors, bit i = bearing {@code i * 45} degrees (Minecraft yaw)
 * @param distance     horizontal distance from the search target
 */
public record LandingZone(int x, int groundY, int z, int radius, int clearSectors, double distance) {}
