package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.StringRepresentable;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * One aircraft flying back and forth between two airfields for ever, with a turnaround wait at each
 * end.
 *
 * <p>The record only holds state; {@link AutopilotDispatcher} owns every rule about how it changes. It is
 * persisted in {@link AutopilotSavedData} beside the airfields and the stand bookings, which is what
 * makes a shuttle survive a restart, and which also settles the dimension question: the store is
 * per-dimension, so both fields a shuttle names are looked up in the one dimension it lives in and a
 * shuttle spanning two of them is not expressible.
 *
 * <h2>Why the schedule is one stamp and not a cadence</h2>
 * {@link #nextDeparture} is an absolute game time, written when the previous leg <em>arrives</em>,
 * and there is never more than one of them. That is the whole answer to "what does the delay mean
 * when the world was not running": {@code ServerLevel#getGameTime} does not advance while the server
 * is down, so a shuttle that had 400 ticks left to wait still has 400 ticks left on load, and a
 * shuttle that came due during the shutdown is due exactly once. There is no cadence to fall behind
 * and therefore no backlog to fire — the fifty-legs-at-once failure is not defended against, it is
 * unrepresentable.
 *
 * <p>The wall-clock consequence, stated rather than hidden: a shuttle's turnaround is measured in
 * ticks of a running world, so a world that spends an hour shut down does not advance it, and a
 * server running below 20 tps stretches it. That is the same clock every other duration in this
 * feature is measured on, including {@code /autopilot flight … delay}.
 *
 * <h2>Which way it is going</h2>
 * {@link #outbound} is about the <em>next</em> departure, not the last one: true means the aircraft
 * is at {@link #fieldA} and the next leg goes to {@link #fieldB}. It is recomputed from where the
 * aircraft actually ended up rather than being toggled blindly — see
 * {@link AutopilotDispatcher#arrived} — so a leg that ends at the field it started from does not leave
 * the shuttle trying to depart from a field its aircraft is not at.
 *
 * @param id            small per-dimension number a player types into {@code /autopilot shuttle stop}
 * @param fieldA        one end, as named in this dimension's airfield registry
 * @param fieldB        the other end
 * @param delayTicks    turnaround wait at each end
 * @param type          the airframe ordered, used only for the very first departure and as the
 *                      substitution guard when the aircraft is re-tasked
 * @param aircraft      the one airframe this shuttle owns, or empty before its first departure
 * @param parked        where the aircraft is waiting, so the chunk ticket has somewhere to go and so
 *                      a restart knows which chunk to warm; empty while flying
 * @param outbound      true when the next departure is {@code fieldA -> fieldB}
 * @param state         see {@link State}
 * @param nextDeparture game time the next departure is due; meaningless while flying
 * @param legs          completed legs, for the listing
 * @param misses        consecutive departures that could not be flown; reset by a successful one
 * @param owner         the player who created it, so reports have somewhere to go; empty for a
 *                      shuttle created from the console
 * @param note          the last thing that went wrong, or empty
 */
public record Shuttle(int id, String fieldA, String fieldB, int delayTicks, AircraftType type,
                      Optional<UUID> aircraft, Optional<BlockPos> parked, boolean outbound,
                      State state, long nextDeparture, int legs, int misses,
                      Optional<UUID> owner, String note) {

    /** Where a shuttle is in its cycle. */
    public enum State implements StringRepresentable {

        /** The aircraft is standing at one end, waiting for {@link Shuttle#nextDeparture}. */
        WAITING("waiting"),
        /** A leg is in the air. */
        FLYING("flying"),
        /**
         * Stopped by a fault, and not retrying.
         *
         * <p>Deliberately not self-clearing and deliberately not self-deleting. Every route into
         * this state is something a player has to know about — the field was removed, the aircraft
         * was destroyed, the airframe could not be found for three departures running — so the
         * record stays, holds its slot against {@link AutopilotConfig#MAX_SHUTTLES}, and prints its
         * reason in {@code /autopilot shuttle list} until somebody stops it. A paused shuttle
         * releases its chunk ticket immediately; what it keeps is its visibility.
         */
        PAUSED("paused");

        public static final Codec<State> CODEC = StringRepresentable.fromEnum(State::values);

        private final String name;

        State(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final Codec<Shuttle> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.INT.fieldOf("id").forGetter(Shuttle::id),
        Codec.STRING.fieldOf("from").forGetter(Shuttle::fieldA),
        Codec.STRING.fieldOf("to").forGetter(Shuttle::fieldB),
        Codec.INT.fieldOf("delay").forGetter(Shuttle::delayTicks),
        AircraftType.CODEC.optionalFieldOf("type", AircraftType.PLANE).forGetter(Shuttle::type),
        // As a string rather than UUIDUtil.CODEC's int array, for the same reason StandOccupancy
        // writes its bookings that way: this file is read by somebody working out why a shuttle has
        // stopped, and "which aircraft" is the question.
        UUIDUtil.STRING_CODEC.optionalFieldOf("aircraft").forGetter(Shuttle::aircraft),
        BlockPos.CODEC.optionalFieldOf("parked").forGetter(Shuttle::parked),
        Codec.BOOL.fieldOf("outbound").forGetter(Shuttle::outbound),
        State.CODEC.fieldOf("state").forGetter(Shuttle::state),
        Codec.LONG.fieldOf("next").forGetter(Shuttle::nextDeparture),
        Codec.INT.optionalFieldOf("legs", 0).forGetter(Shuttle::legs),
        Codec.INT.optionalFieldOf("misses", 0).forGetter(Shuttle::misses),
        UUIDUtil.STRING_CODEC.optionalFieldOf("owner").forGetter(Shuttle::owner),
        Codec.STRING.optionalFieldOf("note", "").forGetter(Shuttle::note)
    ).apply(instance, Shuttle::new));

    /** A brand new shuttle, due to depart immediately so a player sees it work. */
    public static Shuttle created(int id, String fieldA, String fieldB, int delayTicks,
                                  AircraftType type, @Nullable UUID owner, long now) {
        return new Shuttle(id, fieldA, fieldB, delayTicks, type, Optional.empty(), Optional.empty(),
            true, State.WAITING, now, 0, 0, Optional.ofNullable(owner), "");
    }

    /** The field the next departure leaves from. */
    public String from() {
        return outbound ? fieldA : fieldB;
    }

    /** The field the next departure is going to. */
    public String to() {
        return outbound ? fieldB : fieldA;
    }

    /** Whether this shuttle names {@code airfield} as one of its two ends. */
    public boolean uses(String airfield) {
        return fieldA.equals(airfield) || fieldB.equals(airfield);
    }

    public @Nullable UUID aircraftId() {
        return aircraft.orElse(null);
    }

    public @Nullable BlockPos parkedAt() {
        return parked.orElse(null);
    }

    public @Nullable UUID ownerId() {
        return owner.orElse(null);
    }

    // ------------------------------------------------------------------ transitions

    /** A leg has just been launched with this airframe. */
    public Shuttle departed(UUID airframe, long now) {
        return new Shuttle(id, fieldA, fieldB, delayTicks, type, Optional.of(airframe),
            Optional.empty(), outbound, State.FLYING, now, legs, 0, owner, "");
    }

    /**
     * A leg has ended at {@code airfield}, so the aircraft is standing there and the next departure
     * leaves from there.
     *
     * <p>{@code outbound} comes from where the aircraft actually is and not from flipping the old
     * value, which is what makes this heal: a leg that ends back at the field it left, or one whose
     * arrival is reported twice, still leaves the shuttle pointing at a departure it can fly.
     */
    public Shuttle arrivedAt(String airfield, BlockPos where, long now, String problem) {
        return new Shuttle(id, fieldA, fieldB, delayTicks, type, aircraft, Optional.of(where),
            fieldA.equals(airfield), State.WAITING, now + delayTicks, legs + 1, 0, owner, problem);
    }

    /** A due departure could not be flown; try again later and remember why. */
    public Shuttle deferred(long retryAt, String problem) {
        return new Shuttle(id, fieldA, fieldB, delayTicks, type, aircraft, parked, outbound,
            State.WAITING, retryAt, legs, misses + 1, owner, problem);
    }

    /** Stopped, with the reason kept for the listing. */
    public Shuttle paused(String problem) {
        return new Shuttle(id, fieldA, fieldB, delayTicks, type, aircraft, parked, outbound,
            State.PAUSED, nextDeparture, legs, misses, owner, problem);
    }

    /** The not-seen counter while a leg is in the air, reused as the lost-aircraft clock. */
    public Shuttle withMisses(int value) {
        return new Shuttle(id, fieldA, fieldB, delayTicks, type, aircraft, parked, outbound,
            state, nextDeparture, legs, value, owner, note);
    }
}
