package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.SimplePlanesMod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-dimension persistent store of surveyed airfields, helipads, stand bookings and scheduled
 * shuttles. Written to
 * {@code <world>/<dimension>/data/simpleplanes/airfields.dat} by vanilla's
 * {@link net.minecraft.world.level.storage.SavedDataStorage}, so airfields survive a restart.
 *
 * <p>The {@link DataFixTypes} argument is required by {@link SavedDataType} and is only consulted
 * when the stored data version differs from the current one; since this file is always written by
 * the current version, the fixer is a no-op for us.
 *
 * <h2>What one malformed entry costs, established rather than assumed</h2>
 * A review claimed that a single bad {@code stands} entry throws the whole file away, and the
 * counter-claim was that {@code optionalFieldOf} swallows the error and loses only the bookings.
 * <b>Both are wrong</b>, and the answer is worth writing down here because it is the thing that
 * decides whether these {@code optionalFieldOf} defaults are a safety net or a hazard.
 *
 * <ul>
 *   <li>{@code Codec#optionalFieldOf(String, A)} is <b>not</b> lenient — it is
 *       {@code lenientOptionalFieldOf} that swallows a parse failure. The default covers an
 *       <em>absent</em> key, which is what it is here for, and nothing else. A malformed value makes
 *       this codec's parse an error, so nothing is silent: the failure is logged.</li>
 *   <li>The error carries a <b>partial</b> result all the way up. {@code ListCodec} keeps every
 *       element it did parse, {@code OptionalFieldCodec} sets that partial list on the error, and
 *       {@code RecordCodecBuilder}'s applicative keeps the partial while combining the fields — so
 *       the partial is a fully built instance with every other field intact.</li>
 *   <li>{@code SavedDataStorage#readSavedData} reads it with
 *       {@code parse(...).resultOrPartial(LOGGER::error).orElse(null)}, so it takes that partial.</li>
 * </ul>
 *
 * <p>The upshot: one malformed booking costs <b>that booking</b>. Every well-formed booking beside
 * it in the same list, and every airfield, helipad and shuttle in the file, load unchanged, and one
 * {@code Failed to parse saved data} line goes to the log. The file is only lost outright if the
 * failure is one that leaves no partial at all — the NBT itself being unreadable, which is caught
 * and logged by the same method. Verified against DataFixerUpper 10.0.21, the version this game
 * version ships, with a malformed element, a missing required key inside an element, and a
 * {@code stands} value that is not a list: error in all three, partial preserved in all three.
 */
public class AutopilotSavedData extends SavedData {

    public static final Codec<AutopilotSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Airfield.CODEC.listOf().optionalFieldOf("airfields", List.<Airfield>of()).forGetter(AutopilotSavedData::airfieldList),
        // Beside the runways rather than among them - see Helipad for why a pad is not an airfield.
        // Optional with an empty default, so every world saved before helipads existed loads
        // unchanged and a world with no pads writes no key at all.
        Helipad.CODEC.listOf().optionalFieldOf("helipads", List.<Helipad>of()).forGetter(AutopilotSavedData::helipadList),
        // Which stands have an aircraft standing on them. Named by the aircraft's UUID, because
        // that is the one thing about a parked aircraft that can still be checked when the aircraft
        // itself is on disk in an unloaded chunk -- see StandOccupancy for the whole argument.
        // Optional with an empty default, so a world saved before this field existed loads unchanged
        // and a world with nothing parked writes no key at all. The default is for an absent key and
        // not for a broken one: a malformed entry is an error that is logged, and costs that entry
        // only -- see the class javadoc, which establishes it rather than guessing at it.
        StandOccupancy.Booking.CODEC.listOf().optionalFieldOf("stands", List.<StandOccupancy.Booking>of())
            .forGetter(AutopilotSavedData::standList),
        // Scheduled shuttles. Optional with an empty default for the same reason as the two above:
        // a world saved before shuttles existed loads unchanged, and a world with none writes no
        // key. Per-dimension like everything else here, which is what makes a shuttle between two
        // dimensions unrepresentable rather than merely refused - see Shuttle.
        Shuttle.CODEC.listOf().optionalFieldOf("shuttles", List.<Shuttle>of())
            .forGetter(AutopilotSavedData::shuttleList)
    ).apply(instance, AutopilotSavedData::new));

    public static final SavedDataType<AutopilotSavedData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "airfields"),
        AutopilotSavedData::new,
        CODEC,
        DataFixTypes.LEVEL);

    private final Map<String, Airfield> airfields = new LinkedHashMap<>();

    /**
     * Helicopter landing sites, in their own map.
     *
     * <p>A separate namespace as well as a separate list: pads are named {@code helipad-N} and
     * runways {@code airfield-N}, so {@code /autopilot heliflight "airfield-1" …} cannot silently
     * pick up a runway and no command has to disambiguate.
     */
    private final Map<String, Helipad> helipads = new LinkedHashMap<>();

    /**
     * Stand bookings, keyed by field name and square.
     *
     * <p>Insertion-ordered so the file is stable between saves and a diff of two worlds is
     * readable. Reached only through {@link StandOccupancy}, which owns the rule about when a
     * booking may be believed; the accessors below are deliberately dumb.
     */
    private final Map<StandOccupancy.Stand, StandOccupancy.Held> stands = new LinkedHashMap<>();

    /**
     * Scheduled shuttles, keyed by their small per-dimension id.
     *
     * <p>Insertion-ordered so the listing and the file both read in the order they were created.
     * Reached only through {@link AutopilotDispatcher}, which owns every rule about how a shuttle
     * changes; the accessors below are as dumb as the stand ones.
     */
    private final Map<Integer, Shuttle> shuttles = new LinkedHashMap<>();

    public AutopilotSavedData() {
    }

    public AutopilotSavedData(List<Airfield> airfields) {
        this(airfields, List.of());
    }

    public AutopilotSavedData(List<Airfield> airfields, List<Helipad> helipads) {
        this(airfields, helipads, List.of());
    }

    public AutopilotSavedData(List<Airfield> airfields, List<Helipad> helipads,
                              List<StandOccupancy.Booking> stands) {
        this(airfields, helipads, stands, List.of());
    }

    public AutopilotSavedData(List<Airfield> airfields, List<Helipad> helipads,
                              List<StandOccupancy.Booking> stands, List<Shuttle> shuttles) {
        for (Shuttle shuttle : shuttles) {
            this.shuttles.put(shuttle.id(), shuttle);
        }
        for (Airfield airfield : airfields) {
            this.airfields.put(airfield.name(), airfield);
        }
        for (Helipad helipad : helipads) {
            this.helipads.put(helipad.name(), helipad);
        }
        for (StandOccupancy.Booking booking : stands) {
            // emptySince starts at zero on every load. It is the confirmation clock, not part of the
            // booking: a restored record has not been looked at yet, so it has not started reading
            // empty yet either.
            this.stands.put(booking.stand(), new StandOccupancy.Held(booking.aircraft(), 0));
        }
    }

    public static AutopilotSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<Airfield> airfieldList() {
        return new ArrayList<>(airfields.values());
    }

    public void put(Airfield airfield) {
        airfields.put(airfield.name(), airfield);
        setDirty();
    }

    public Airfield get(String name) {
        return airfields.get(name);
    }

    public boolean remove(String name) {
        boolean removed = airfields.remove(name) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public List<String> names() {
        return new ArrayList<>(airfields.keySet());
    }

    public boolean isEmpty() {
        return airfields.isEmpty();
    }

    // ------------------------------------------------------------------ helipads

    public List<Helipad> helipadList() {
        return new ArrayList<>(helipads.values());
    }

    public void put(Helipad helipad) {
        helipads.put(helipad.name(), helipad);
        setDirty();
    }

    public Helipad helipad(String name) {
        return helipads.get(name);
    }

    public boolean removeHelipad(String name) {
        boolean removed = helipads.remove(name) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public boolean hasHelipads() {
        return !helipads.isEmpty();
    }

    /** Nearest helipad to a point, or null if none is within {@code maxDistance}. */
    public Helipad nearestHelipad(double x, double z, double maxDistance) {
        Helipad best = null;
        double bestDistance = maxDistance * maxDistance;
        for (Helipad pad : helipads.values()) {
            double dx = pad.centre().getX() + 0.5 - x;
            double dz = pad.centre().getZ() + 0.5 - z;
            double distance = dx * dx + dz * dz;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = pad;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ stand bookings

    /** Every booking, flattened for the codec. */
    public List<StandOccupancy.Booking> standList() {
        List<StandOccupancy.Booking> out = new ArrayList<>(stands.size());
        stands.forEach((stand, held) ->
            out.add(new StandOccupancy.Booking(stand.airfield(), stand.spot(), held.aircraft())));
        return out;
    }

    /** The raw booking on a stand, or null when there is none. */
    public StandOccupancy.@Nullable Held stand(StandOccupancy.Stand stand) {
        return stands.get(stand);
    }

    /**
     * Books a stand for an aircraft, replacing whatever was there — and dropping any other stand the
     * same aircraft was booked onto.
     *
     * <p><b>One aircraft, one stand.</b> Nothing else enforces it: the two writers are the end of a
     * taxi in, and a claim releases the one stand it took the airframe off, so an aircraft that was
     * booked onto a square, flown away by a player and later flown back by this mod onto a different
     * square held both. The stale one is not harmless — {@code StandOccupancy#isTaken} answers
     * "taken" for a booking it cannot see, so a stand nobody can look at stayed shut for good, and
     * the departure search would offer the same airframe twice.
     *
     * <p>Swept here rather than at the call sites because this is the only moment the second booking
     * comes into existence, and a rule enforced where the state changes cannot be forgotten by a
     * third writer later. At most {@link AutopilotConfig#MAX_PARKING_SPOTS} entries per field, walked
     * once per landing.
     */
    public void bookStand(StandOccupancy.Stand stand, UUID aircraft) {
        boolean dropped = stands.entrySet().removeIf(entry ->
            !entry.getKey().equals(stand) && entry.getValue().aircraft().equals(aircraft));
        StandOccupancy.Held previous = stands.put(stand, new StandOccupancy.Held(aircraft, 0));
        if (dropped || previous == null || !previous.aircraft().equals(aircraft)) {
            setDirty();
        }
    }

    /**
     * Records that the square has been reading empty since {@code gameTime}, or clears the stamp
     * with 0.
     *
     * <p><b>Deliberately does not mark the file dirty.</b> The stamp is the confirmation clock and
     * is not written: it exists to stop the very first look at a cold chunk from throwing a good
     * booking away, and a booking reloaded from disk has to start that clock afresh anyway.
     */
    public void stampStand(StandOccupancy.Stand stand, long gameTime) {
        StandOccupancy.Held held = stands.get(stand);
        if (held != null) {
            stands.put(stand, new StandOccupancy.Held(held.aircraft(), gameTime));
        }
    }

    public void releaseStand(StandOccupancy.Stand stand) {
        if (stands.remove(stand) != null) {
            setDirty();
        }
    }

    /** Drops every booking for a field, so removing or renaming one leaves nothing behind. */
    public void forgetStands(String airfield) {
        if (stands.keySet().removeIf(stand -> stand.airfield().equals(airfield))) {
            setDirty();
        }
    }

    // ------------------------------------------------------------------ shuttles

    /** Every shuttle, in creation order. A copy: callers walk it while the service rewrites it. */
    public List<Shuttle> shuttleList() {
        return new ArrayList<>(shuttles.values());
    }

    /**
     * The live collection, for the per-tick reads that only look.
     *
     * <p>Unmodifiable and not copied, because the chunk-ticket renewal runs several times a second
     * and allocating a list to find out that there is nothing to do is the one cost this feature
     * pays whether or not anybody is using it.
     */
    public Collection<Shuttle> shuttles() {
        return Collections.unmodifiableCollection(shuttles.values());
    }

    public boolean hasShuttles() {
        return !shuttles.isEmpty();
    }

    public @Nullable Shuttle shuttle(int id) {
        return shuttles.get(id);
    }

    /** Writes a shuttle back, creating it if it is new. */
    public void putShuttle(Shuttle shuttle) {
        Shuttle previous = shuttles.put(shuttle.id(), shuttle);
        if (!shuttle.equals(previous)) {
            setDirty();
        }
    }

    public boolean removeShuttle(int id) {
        boolean removed = shuttles.remove(id) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /** The lowest id not in use, so a stopped shuttle's number is available again. */
    public int nextShuttleId() {
        int id = 1;
        while (shuttles.containsKey(id)) {
            id++;
        }
        return id;
    }

    /** Nearest airfield to a point, or null if none is within {@code maxDistance}. */
    public Airfield nearest(double x, double z, double maxDistance) {
        Airfield best = null;
        double bestDistance = maxDistance * maxDistance;
        for (Airfield airfield : airfields.values()) {
            double dx = airfield.centre().x - x;
            double dz = airfield.centre().z - z;
            double distance = dx * dx + dz * dz;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = airfield;
            }
        }
        return best;
    }
}
