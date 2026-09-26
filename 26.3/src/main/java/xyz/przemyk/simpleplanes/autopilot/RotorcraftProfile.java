package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.world.level.Level;
import xyz.przemyk.simpleplanes.entities.MiniHelicopterEntity;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

/**
 * Per-airframe numbers for {@link HelicopterAutopilot}. Everything else in {@link RotorcraftConfig}
 * is shared: the control laws close on measured quantities, so only the envelope differs.
 *
 * <p>The mini helicopter (DESIGN.md 5b.3) tops out at 0.75 blocks/tick and loses thrust above
 * y 100: hover needs notch 3 at y 130, notch 4 at y 150 and notch 5 at y 160. It flies without a
 * booster, so that ceiling is real, and the cruise is capped at y 130 where full forward cyclic
 * still has a margin. A leg whose terrain needs more than {@link #hardCeiling} is refused rather
 * than flown into the ceiling.
 *
 * @param defaultCruiseSpeed cruise used when the order does not name one, blocks/tick
 * @param maxCruiseSpeed     fastest cruise this airframe can make good, blocks/tick
 * @param departureHeight    vertical climb above the pad before any translation
 * @param cruiseClearance    height over the highest ground on the leg / ahead
 * @param altitudeCap        highest cruise altitude commanded while terrain allows
 * @param minClearance       least height over terrain ahead the cap may squeeze the cruise to
 * @param hardCeiling        above this nothing is commanded; a leg that needs more is refused
 * @param boosted            whether the autopilot fits a booster
 */
public record RotorcraftProfile(String label, double defaultCruiseSpeed, double maxCruiseSpeed,
                                double departureHeight, double cruiseClearance, double altitudeCap,
                                double minClearance, double hardCeiling, boolean boosted) {

    public static final RotorcraftProfile HELICOPTER = new RotorcraftProfile("Helicopter",
        RotorcraftConfig.CRUISE_SPEED, RotorcraftConfig.MAX_CRUISE_SPEED, RotorcraftConfig.DEPARTURE_HEIGHT,
        RotorcraftConfig.CRUISE_CLEARANCE, Double.POSITIVE_INFINITY, RotorcraftConfig.CRUISE_CLEARANCE,
        Double.POSITIVE_INFINITY, true);

    public static final RotorcraftProfile MINI = new RotorcraftProfile("Mini helicopter",
        0.70, 0.75, 20.0, 25.0, 130.0, 12.0, 150.0, false);

    public static RotorcraftProfile of(PlaneEntity plane) {
        return plane instanceof MiniHelicopterEntity ? MINI : HELICOPTER;
    }

    public static RotorcraftProfile of(AircraftType type) {
        return type == AircraftType.MINI_HELICOPTER ? MINI : HELICOPTER;
    }

    public double clampCruiseSpeed(double speed) {
        return Math.min(RotorcraftConfig.clampCruiseSpeed(speed), maxCruiseSpeed);
    }

    /** Cruise altitude for a leg, or {@code Integer.MAX_VALUE} when the terrain is above the ceiling. */
    public int cruiseAltitude(Level level, Helipad from, Helipad to) {
        double highest = Helipad.highestGround(level, from, to);
        if (highest + minClearance > hardCeiling) {
            return Integer.MAX_VALUE;
        }
        double wanted = Math.min(highest + cruiseClearance, level.getMaxY() - 10);
        return (int) Math.max(Math.min(wanted, altitudeCap), Math.min(highest + minClearance, wanted));
    }

    /** Clamps a commanded altitude under the ceiling, never below {@code terrain + minClearance}. */
    public int capAltitude(int altitude) {
        return (int) Math.min(altitude, Math.min(altitudeCap, hardCeiling));
    }
}
