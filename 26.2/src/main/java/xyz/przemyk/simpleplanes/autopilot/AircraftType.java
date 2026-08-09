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
 * <p><b>Helicopters are here now, and the reason they used to be excluded has changed.</b> The old
 * reason was that {@code HelicopterEntity} had no server-reachable controls at all: its translation
 * came from {@code TempMotionVars.moveForward} / {@code moveStrafing}, which are fed from
 * {@code Player.zza} / {@code Player.xxa}, and those are written in exactly one place in 26.2 —
 * {@code LocalPlayer}, on the client. An unmanned helicopter could not be moved, only watched. That
 * is fixed in the airframe rather than here: {@code HelicopterEntity} now has a synched, persisted,
 * server-settable control surface — collective, two cyclic axes and a pedal — documented in
 * {@code HELICOPTER-PHYSICS.md}.
 *
 * <p>What has <em>not</em> changed is that none of the control laws in {@link PlaneAutopilot}
 * describe it. It overrides all six flight hooks, so not one line of the fixed-wing flight model
 * runs on it: no wing, no lift, no stall speed, no take-off speed. So it is not flown by
 * {@link PlaneAutopilot} at all, but by {@link HelicopterAutopilot}, between {@link Helipad}s rather
 * than between {@link Airfield}s. What that means for this enum is that {@link #HELICOPTER} is a
 * value {@link #of} can return and {@link #tag} can print, so a status line and a tower board can
 * name the thing they are looking at — and that it is <em>not</em> in {@link #FLYABLE}, so
 * {@code random} never draws one and {@code /autopilot flight … type helicopter} is refused rather
 * than producing a rotorcraft on a runway.
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
    /**
     * A rotorcraft. Selectable on the helicopter commands and refused on the fixed-wing ones — see
     * {@link #isRotorcraft()}.
     */
    HELICOPTER("helicopter"),
    /** Chosen when the aircraft is created, from the three fixed-wing airframes only. */
    RANDOM("random");

    /**
     * The three fixed-wing airframes, in the order {@link #RANDOM} draws from.
     *
     * <p>{@link #HELICOPTER} is deliberately not in this list. It is what {@link #of} matches
     * against, so putting a rotorcraft in it would make {@code random} occasionally dispatch one
     * onto a runway; it is matched separately below instead.
     */
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
            case HELICOPTER -> SimplePlanesEntities.HELICOPTER;
            // RANDOM only reaches here if resolve() was skipped; the starter plane is the safe answer.
            default -> SimplePlanesEntities.PLANE;
        };
    }

    /** True for the one airframe the fixed-wing commands refuse and the helicopter commands require. */
    public boolean isRotorcraft() {
        return this == HELICOPTER;
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
        // Matched on the EntityType and never on the class. HelicopterEntity used to extend
        // LargePlaneEntity and now extends LargeAirframeEntity beside it, so any instanceof written
        // against either would have been silently wrong on one side of that change. An EntityType
        // comparison was right before it and is right after it.
        if (type == HELICOPTER.entityType().get()) {
            return HELICOPTER;
        }
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
