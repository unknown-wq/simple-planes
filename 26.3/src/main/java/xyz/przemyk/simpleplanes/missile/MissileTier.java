package xyz.przemyk.simpleplanes.missile;

import xyz.przemyk.simpleplanes.autopilot.Blast;
import xyz.przemyk.simpleplanes.autopilot.PiercingBlast;

/**
 * Per-tier geometry and flight parameters. Distances in blocks, speeds in blocks per tick, angles in degrees.
 * Geometry mirrors {@code MissileModel} and {@code LaunchTubeModel} (see MISSILES-MODEL.md); it is repeated here
 * because those are client-only classes.
 */
public enum MissileTier {
    //    len  hitbox footprint seat   cruise accel  range   minRange cruiseAgl midTurn termTurn hatchTicks warhead, piercing warhead
    T1(1, 1.0, 0.4F,  1, 1.25, 2.0, 0.10, 1200.0,  24.0, 24.0, 6.0, 12.0, 25, new Blast(2.0F, true, false), pierce(16.0F)),
    T2(2, 2.0, 0.5F,  1, 2.25, 2.5, 0.10, 2500.0,  32.0, 32.0, 5.0, 10.0, 30, new Blast(Blast.DEFAULT_POWER, true, false), pierce(24.0F)),
    T3(3, 3.0, 0.625F, 2, 3.25, 3.0, 0.10, 5000.0, 48.0, 48.0, 4.0,  9.0, 35, new Blast(8.0F, true, true), pierce(40.0F)),
    T4(4, 4.0, 1.0F,  2, 4.25, 4.0, 0.12, 10000.0, 64.0, 64.0, 3.5,  8.0, 40, new Blast(Blast.MAX_POWER, true, true), pierce(Blast.MAX_PIERCE_POWER));

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
    /**
     * What the missile does on arrival or terrain impact, unless the {@code missile_explosions} game rule is off. Also
     * the air-defence interceptor's proximity warhead, whatever the silo's warhead setting.
     */
    public final Blast warhead;
    /**
     * The strike warhead of a piercing launch ({@link Blast#pierce()}): entities only, armour ignored, no block broken
     * and no fire. Tier 4 is the aircraft's own ceiling, {@link Blast#MAX_PIERCE_POWER}. See
     * {@code design/MISSILE-PIERCE.md} for the scale.
     */
    public final Blast pierceWarhead;

    MissileTier(int tier, double length, float hitbox, int footprint, double seatDepth, double cruiseSpeed, double accel,
                double maxRange, double minRange, double cruiseAgl, double midcourseTurn, double terminalTurn, int hatchTicks,
                Blast warhead, Blast pierceWarhead) {
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
        this.pierceWarhead = pierceWarhead;
    }

    private static Blast pierce(float power) {
        return new Blast(power, false, false, true);
    }

    /** The strike warhead for a launch: {@link #pierceWarhead} when {@code pierce}, else {@link #warhead}. */
    public Blast strikeWarhead(boolean pierce) {
        return pierce ? pierceWarhead : warhead;
    }

    /** Damage radius of the piercing warhead, {@code 2 x power}: 32, 48, 80 and 128 blocks. */
    public double pierceRadius() {
        // the constant, not PiercingBlast.radius(): a tooltip must not initialise the server-side blast class
        return PiercingBlast.RADIUS_PER_POWER * pierceWarhead.power();
    }

    /**
     * Minimum horizontal range of a piercing launch: the tier's {@link #minRange}, raised to {@link #pierceRadius} so
     * that the silo is never inside its own missile's radius. 32, 48, 80 and 128 blocks.
     */
    public double pierceMinRange() {
        return Math.max(minRange, pierceRadius());
    }

    /** Minimum horizontal range of a strike launch with this warhead. */
    public double minRange(boolean pierce) {
        return pierce ? pierceMinRange() : minRange;
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
