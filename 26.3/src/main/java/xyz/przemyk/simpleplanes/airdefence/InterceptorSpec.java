package xyz.przemyk.simpleplanes.airdefence;

import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.autopilot.Blast;
import xyz.przemyk.simpleplanes.missile.MissileTier;

/**
 * Air-defence flight parameters per missile tier. Speed and acceleration are the tier's own
 * ({@link MissileTier#cruiseSpeed}, {@link MissileTier#accel}); everything else is specific to pursuit.
 * Distances in blocks, speeds in blocks per tick, turn rates in degrees per tick. See MISSILES.md, "Air defence".
 */
public enum InterceptorSpec {
    //   range  fuse  turn  hatch
    T1(MissileTier.T1, 400.0, 3.0, 12.0, 10),
    T2(MissileTier.T2, 600.0, 4.0, 10.0, 12),
    T3(MissileTier.T3, 900.0, 5.0, 9.0, 14),
    T4(MissileTier.T4, 1400.0, 6.0, 8.0, 16);

    /** Detection radius as a fraction of the range: leaves room for a tail chase against slower aircraft. */
    public static final double DETECTION_FRACTION = 0.25;
    /**
     * Missiles in flight (or in a launch sequence) against one aircraft, across all silos. One: a second missile
     * is fired only once the first has ended without killing the aircraft (see {@link Engagements}).
     */
    public static final int MAX_PER_TARGET = 1;
    /**
     * What an interceptor that ran out of fuel does when it falls on something: nothing (a puff), like a powered
     * interceptor hitting terrain, so spent interceptors raining on the defended area do no harm. A small blast
     * would be {@code new Blast(1.0F, false, false)}: hurts entities within 2 blocks, no blocks broken.
     */
    public static final @Nullable Blast SPENT_WARHEAD = null;
    /** Silo scan interval while idle and loaded. */
    public static final int SCAN_INTERVAL = 10;
    /** Fuse arms this many blocks of path after leaving the tube. */
    public static final double ARMING_PATH = 6.0;
    /** A missile whose target is gone looks for another hostile within this fraction of its detection radius. */
    public static final double RETARGET_FRACTION = 1.0;

    public final MissileTier tier;
    /** Fuel: the motor path past the tube. Once it is flown the motor stops and the missile falls unguided. */
    public final double range;
    public final double fuseRadius;
    public final double turnRate;
    /** AD hatch opening time, faster than a strike launch. */
    public final int hatchTicks;

    InterceptorSpec(MissileTier tier, double range, double fuseRadius, double turnRate, int hatchTicks) {
        this.tier = tier;
        this.range = range;
        this.fuseRadius = fuseRadius;
        this.turnRate = turnRate;
        this.hatchTicks = hatchTicks;
    }

    public double speed() {
        return tier.cruiseSpeed;
    }

    public double detectionRadius() {
        return range * DETECTION_FRACTION;
    }

    /** Powered flight time limit (the motor path at full speed plus the climb out); the motor stops after it. */
    public int maxFlightTicks() {
        return (int) Math.ceil(range / tier.cruiseSpeed) + 80;
    }

    public static InterceptorSpec of(MissileTier tier) {
        return values()[tier.ordinal()];
    }
}
