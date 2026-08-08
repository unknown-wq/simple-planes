package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.Nullable;
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
 * <p>That multiplier is not a detail the arrival can ignore, and for a while it did. Turn radius is
 * {@code v / omega}, so a cargo plane's is five times the starter plane's at the same speed, and two
 * pieces of the arrival are sized in blocks rather than in radii:
 *
 * <ul>
 *   <li>{@code PlaneAutopilot#turnLimitedSpeed} bounds the descent speed by the turn still to be
 *       flown. Without it a cargo plane could not turn tightly enough to reach the approach fix, so
 *       the distance to the fix never fell, so the deceleration schedule never braked it — a latch
 *       that orbited the field for ever and produced no outcome at all.</li>
 *   <li>{@link AutopilotConfig#minimumInterceptDistance} gives the 180-degree join at the fix room
 *       proportional to the radius it actually needs. The join throws every airframe 2.5 radii off
 *       the centreline; only the room to recover was a constant.</li>
 * </ul>
 *
 * <p>Both are floors, so the starter plane's geometry is untouched. Everything measured about these
 * airframes lives in {@code AUTOPILOT.md}, "Which airframe flies".
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

    /**
     * Which airframe an aircraft actually is, read off the entity rather than off the flight plan.
     *
     * <p>Off the entity on purpose: a plan ordered as {@code random} records {@code random}, and a
     * plan loaded from an older save records nothing at all, so the plan is not a reliable answer to
     * "what is that thing". The entity always is. Anything that is not one of the three fixed-wing
     * airframes — a helicopter a player left lying about with an autopilot attached — reads as
     * {@code null} rather than being guessed at.
     */
    public static @Nullable AircraftType of(PlaneEntity plane) {
        EntityType<?> type = plane.getType();
        for (AircraftType candidate : FLYABLE) {
            if (type == candidate.entityType().get()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The airframe as it appears in {@code /autopilot status} and on the tower board, or an empty
     * string for the starter plane.
     *
     * <p>Empty for {@link #PLANE} so that a readout with no mixed traffic in it is byte-identical to
     * what it printed before airframes existed — the starter plane is the regression baseline for
     * the assertions in {@code TESTING.md} as well as for the flying. A board of nothing but
     * {@code Plane #12} is unreadable once three airframes share a circuit, and the two that need
     * saying are the two that fly differently.
     */
    public static String tag(PlaneEntity plane) {
        AircraftType type = of(plane);
        return type == null || type == PLANE ? "" : " " + type.name;
    }

    /**
     * {@link #tag} for the tower board, which is translated where {@code /autopilot status} is not.
     *
     * <p>The split is the existing convention rather than a new one: the board is prose a player
     * reads and every word of it goes through {@link AutopilotText}, while the status line is a
     * fixed-format telemetry dump that the headless tests assert on and that stays English on
     * purpose. Both are driven from the same enum, so they cannot come to disagree about what an
     * aircraft is.
     */
    public static Component tagText(PlaneEntity plane) {
        AircraftType type = of(plane);
        if (type == null || type == PLANE) {
            return Component.empty();
        }
        return Component.literal(" ").append(AutopilotText.tr("airframe." + type.name, type.name));
    }
}
