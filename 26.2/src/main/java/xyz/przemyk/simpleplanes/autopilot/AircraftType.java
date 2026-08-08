package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.EntityType;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;

import java.util.function.Supplier;

/**
 * Which airframe an autopilot sortie flies.
 *
 * <p>Helicopters are deliberately absent, and not by oversight: {@code HelicopterEntity} overrides
 * {@code tickPitch}, {@code tickRoll}, {@code tickRotateMotion} and {@code getTickPush}, so none of
 * the control laws in {@link PlaneAutopilot} describe it. Dispatching one would not fly it badly —
 * it would fly something the flight director has no model of at all. See {@code AUTOPILOT.md} §9.
 *
 * <p>The three fixed-wing airframes are not interchangeable. {@code getRotationSpeedMultiplier}
 * scales both the pitch and the yaw ramp, so a large plane turns at half the rate of the starter
 * plane and a cargo plane at a fifth:
 *
 * <table border="1">
 *   <caption>Control authority by airframe</caption>
 *   <tr><th>airframe</th><th>multiplier</th><th>max yaw rate</th><th>max pitch rate</th></tr>
 *   <tr><td>plane</td><td>1.0</td><td>2.5 deg/tick</td><td>5.0 deg/tick</td></tr>
 *   <tr><td>large</td><td>0.5</td><td>1.25 deg/tick</td><td>2.5 deg/tick</td></tr>
 *   <tr><td>cargo</td><td>0.2</td><td>0.5 deg/tick</td><td>1.0 deg/tick</td></tr>
 * </table>
 *
 * <p>The autopilot already reads that multiplier where it computes a turn radius, so a heavier
 * airframe gets a wider waypoint arrival radius rather than orbiting a point it cannot turn tightly
 * enough to reach. Everything measured about these airframes lives in {@code AUTOPILOT.md}.
 */
public enum AircraftType implements StringRepresentable {

    PLANE("plane"),
    LARGE("large"),
    CARGO("cargo"),
    /** Chosen when the aircraft is created, from the three above, never a helicopter. */
    RANDOM("random");

    /** The three real airframes, in the order {@link #RANDOM} draws from. */
    private static final AircraftType[] FLYABLE = {PLANE, LARGE, CARGO};

    public static final Codec<AircraftType> CODEC = StringRepresentable.fromEnum(AircraftType::values);

    private final String name;

    AircraftType(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public static AircraftType byName(String name) {
        for (AircraftType type : values()) {
            if (type.name.equalsIgnoreCase(name)) {
                return type;
            }
        }
        return PLANE;
    }

    /**
     * The airframe to actually build. Resolving {@link #RANDOM} here rather than at the command
     * means a saved flight plan records what was ordered, not what was drawn — so a restart does not
     * silently reroll the aircraft, and a route ordered as "random" reads as "random" in the plan.
     */
    public AircraftType resolve(RandomSource random) {
        return this == RANDOM ? FLYABLE[random.nextInt(FLYABLE.length)] : this;
    }

    public Supplier<? extends EntityType<? extends PlaneEntity>> entityType() {
        return switch (this) {
            case LARGE -> SimplePlanesEntities.LARGE_PLANE;
            case CARGO -> SimplePlanesEntities.CARGO_PLANE;
            // RANDOM only reaches here if resolve() was skipped; the starter plane is the safe answer.
            default -> SimplePlanesEntities.PLANE;
        };
    }
}
