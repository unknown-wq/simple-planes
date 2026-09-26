package xyz.przemyk.simpleplanes.missile;

import xyz.przemyk.simpleplanes.autopilot.Blast;

/**
 * Per-tier geometry and flight parameters. Distances in blocks, speeds in blocks per tick, angles in degrees.
 * Geometry mirrors {@code MissileModel} and {@code LaunchTubeModel} (see MISSILES-MODEL.md); it is repeated here
 * because those are client-only classes.
 */
public enum MissileTier {
    //    len  hitbox footprint seat   cruise accel  range   minRange cruiseAgl midTurn termTurn hatchTicks warhead
    T1(1, 1.0, 0.4F,  1, 1.25, 2.0, 0.10, 1200.0,  24.0, 24.0, 6.0, 12.0, 25, new Blast(2.0F, true, false)),
    T2(2, 2.0, 0.5F,  1, 2.25, 2.5, 0.10, 2500.0,  32.0, 32.0, 5.0, 10.0, 30, new Blast(Blast.DEFAULT_POWER, true, false)),
    T3(3, 3.0, 0.625F, 2, 3.25, 3.0, 0.10, 5000.0, 48.0, 48.0, 4.0,  9.0, 35, new Blast(8.0F, true, true)),
    T4(4, 4.0, 1.0F,  2, 4.25, 4.0, 0.12, 10000.0, 64.0, 64.0, 3.5,  8.0, 40, new Blast(Blast.MAX_POWER, true, true));

    public final int tier;
    /** Nozzle exit to nose tip. */
    public final double length;
    /** Cube hitbox side ({@code sized(w, w)}). */
    public final float hitbox;
    /** Silo footprint side in blocks. */
    public final int footprint;
    /** Launch seat (missile base) below the surface. */
    public final double seatDepth;
    public final double cruiseSpeed;
    public final double accel;
    /** Maximum horizontal launch range. The fuel is {@link #fuel()}: 1.3x this path length plus 200. */
    public final double maxRange;
    public final double minRange;
    /** Cruise height above the higher of the launch surface and the target. */
    public final double cruiseAgl;
    public final double midcourseTurn;
    public final double terminalTurn;
    public final int hatchTicks;
    /** What the missile does on arrival or terrain impact, unless the {@code missile_explosions} game rule is off. */
    public final Blast warhead;

    MissileTier(int tier, double length, float hitbox, int footprint, double seatDepth, double cruiseSpeed, double accel,
                double maxRange, double minRange, double cruiseAgl, double midcourseTurn, double terminalTurn, int hatchTicks,
                Blast warhead) {
        this.tier = tier;
        this.length = length;
        this.hitbox = hitbox;
        this.footprint = footprint;
        this.seatDepth = seatDepth;
        this.cruiseSpeed = cruiseSpeed;
        this.accel = accel;
        this.maxRange = maxRange;
        this.minRange = minRange;
        this.cruiseAgl = cruiseAgl;
        this.midcourseTurn = midcourseTurn;
        this.terminalTurn = terminalTurn;
        this.hatchTicks = hatchTicks;
        this.warhead = warhead;
    }

    /**
     * Fuel of a strike missile, in blocks of powered flight past the tube: the path the climb-cruise-dive profile
     * needs for a target at {@link #maxRange}, with margin. The silo range check is what limits a launch; this only
     * decides where a missile that somehow flies further stops and falls.
     */
    public double fuel() {
        return maxRange * 1.3 + 200.0;
    }

    /** Powered flight time limit of a strike missile; the motor stops after it even with fuel left. */
    public int poweredTicks() {
        return (int) (maxRange * 1.3 / cruiseSpeed) + 600;
    }

    /** Silo layers: the top layer plus {@code tier} shaft layers, i.e. the tube is {@code tier + 1} blocks deep. */
    public int depthBlocks() {
        return tier + 1;
    }

    public static MissileTier of(int tier) {
        return values()[Math.max(1, Math.min(4, tier)) - 1];
    }

    // shared constants
    public static final double TUBE_ACCEL = 0.04;
    public static final double TUBE_MAX_SPEED = 1.0;
    public static final int FIN_DEPLOY_TICKS = 6;
    /** Tier 4: ticks after leaving the tube until the booster is dropped. */
    public static final int BOOSTER_BURN_TICKS = 60;
    public static final double ARRIVAL_RADIUS = 1.5;
    /** Line-of-sight depression at which the missile leaves cruise and dives, if the line of sight is clear. */
    public static final double DIVE_ANGLE = 45.0;
    public static final double MAX_CLIMB = 50.0;
    public static final double MAX_DESCENT = 30.0;
    public static final double TERRAIN_CLEARANCE = 12.0;
    public static final int HATCH_CLOSE_TICKS = 30;
    public static final int COOLDOWN_TICKS = 100;
}
