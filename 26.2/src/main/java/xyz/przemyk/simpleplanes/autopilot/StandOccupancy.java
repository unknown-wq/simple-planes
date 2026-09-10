package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.UUID;

/**
 * Which marked stands have an aircraft standing on them, for the stands whose aircraft cannot be
 * seen.
 *
 * <h2>Why this exists at all, when there is an entity search two lines away</h2>
 * "Is anything parked here" looks like a question {@code Level#getEntities} answers, and for as long
 * as the only aircraft that ever used a stand was one sitting on it at spawn time, it did. It stops
 * answering it the moment aircraft <em>arrive</em> at stands and are then left there: an aircraft
 * with no autopilot renews no chunk ticket, so 40 ticks after it parks its chunk unloads, the entity
 * is written to disk and removed from the level, and every search of that square comes back empty.
 *
 * <p>Measured on the rig, two sorties into one field with three stands marked and no force-loading:
 * the first landed, taxied to a stand and parked; the second landed 550 ticks later, searched the
 * same square, found nothing, and taxied on top of it. With the same two flights force-loaded, the
 * second correctly picked a different stand — which is the whole diagnosis in one pair of runs. It is
 * not enough to load the chunk before asking, either: block data comes back synchronously and
 * entities do not, so a search run in the tick a chunk is pulled in still finds an empty stand.
 *
 * <h2>The rule</h2>
 * A stand is remembered from the moment an aircraft finishes taxiing onto it — not from the moment
 * it sets off, which is what {@link PlaneAutopilot#claimsStand} covers, and that one has to stay
 * derived because an aircraft in transit is not on its stand and this registry's self-healing rule
 * would throw the record away.
 *
 * <p>When asked, the record is trusted <b>unless the level can actually see the square</b>:
 * {@code ServerLevel#areEntitiesLoaded} is exactly the vanilla predicate for "have this chunk's
 * entities been deserialised", so a search is only believed where it means something. Where it does
 * not, the answer is "taken" — the same rule the rest of this feature already applies to unknown
 * terrain, that <em>not loaded must never be the cheapest answer</em>. A stand nobody can look at
 * costs one aircraft a taxi in, and it stops on the runway and says so; a stand wrongly called free
 * costs two aircraft.
 *
 * <p>Self-healing, and it has to be, or an aircraft a player flies away leaves its stand blocked for
 * the rest of the session: the first look at a loaded, empty square forgets the record.
 *
 * <p><b>Asking {@link #isTaken} is therefore a write</b>, and only a caller deciding where to put an
 * aircraft has any business making one. Anything that is merely reporting occupancy — the marker
 * overlay, a listing — must use {@link #looksTaken}, which applies the same rule and changes
 * nothing; otherwise a poll that runs on a fixed interval becomes the thing that decides when a
 * booking dies.
 *
 * <p>"Still on its stand" is {@link AutopilotConfig#STAND_OCCUPIED_RADIUS} and not
 * {@link AutopilotConfig#PARKING_SPOT_CLEARANCE}: the latter is exactly how far apart two stands may
 * be marked, so it made an aircraft on the neighbouring stand keep this stand's booking alive.
 *
 * <h2>Why it is persisted now, when it deliberately was not</h2>
 * This class used to be runtime-only, and said so, on the grounds that "the alternative is
 * persisting an occupancy that nothing can validate on load". That objection is about what a record
 * <em>says</em>, and it is answered by changing that rather than by overruling it.
 *
 * <p>A booking has always named its aircraft by {@link UUID} — the entity itself is removed when its
 * chunk unloads, so a reference was never an option — and a UUID is precisely the part of a parked
 * aircraft that survives being on disk and can still be checked afterwards. Everything
 * {@link #isTaken} already does <em>is</em> that validation: resolve the UUID through
 * {@code ServerLevel#getEntity}, believe where the aircraft actually is rather than where it was
 * left, and where it cannot be resolved at all, ask {@code areEntitiesLoaded} and give the answer
 * {@link #EMPTY_CONFIRM_TICKS} to settle. So a booking read back from disk needs no load-time
 * validation pass of its own and does not get one: it is put into the same store the live ones live
 * in, is read by the same method, is trusted no further, and heals on exactly the same rule. What
 * persistence changes is a booking's lifetime, not its authority.
 *
 * <p>Restarting used to void every booking while leaving every aircraft on disk, which is the
 * dangerous half of the trade rather than the cheap one: the stand read free, the parked aircraft
 * was in an unloaded chunk, and the next sortie out of that field was spawned inside it. The
 * self-healing rule that made a stale in-memory record survivable makes a stale stored one
 * survivable in exactly the same way and for exactly as long.
 *
 * <p>The cost that remains, stated plainly: a booking whose square is never loaded again is never
 * looked at, so it is never healed. A stand whose aircraft is deleted out from under the game — a
 * chunk removed on disk, a world edit, another mod culling entities — stays booked until something
 * loads that square, and if nothing ever does, forever. That costs one stand of eight on one field,
 * against an aircraft spawned inside another, and it is the same side of the same trade the rest of
 * this feature already takes. Renaming or removing the field clears it outright; see {@link #forget}.
 *
 * <p>{@link RunwayOccupancy} stays runtime-only and should: a runway reservation is held by an
 * aircraft that is <em>flying</em>, and a restart genuinely does void it.
 *
 * <p>Stored in {@link AutopilotSavedData}, which is per-dimension, so the dimension is implicit in
 * which file a booking is in — and a booking can no longer outlive the world it was made in, which
 * a static map shared by every world loaded into one JVM could.
 *
 * <p>All access is from the server thread, so a plain map behind it is fine.
 */
public final class StandOccupancy {

    /**
     * Which square of which field a booking is about.
     *
     * <p>No dimension: {@link AutopilotSavedData} is per-dimension already. Helipads share this
     * namespace with airfields deliberately — a pad is named {@code helipad-N} and a runway
     * {@code airfield-N}, so the two cannot collide, and {@link Helipad#free} gets the same
     * treatment as a stand for free.
     */
    public record Stand(String airfield, BlockPos spot) {}

    /**
     * By UUID rather than by reference — the entity itself is removed when its chunk unloads — plus
     * the game time the square first read empty while its chunk claimed to be loaded, or 0.
     *
     * <p>The stamp is not written to disk: see {@link AutopilotSavedData#stampStand}.
     */
    public record Held(UUID aircraft, long emptySince) {}

    /** One booking in the form it is saved in: the stand, and the aircraft that is on it. */
    public record Booking(String airfield, BlockPos spot, UUID aircraft) {

        public static final Codec<Booking> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("airfield").forGetter(Booking::airfield),
            BlockPos.CODEC.fieldOf("spot").forGetter(Booking::spot),
            // As a string rather than UUIDUtil.CODEC's int array: this file is read by people
            // debugging a field that will not accept arrivals, and "which aircraft" is the whole
            // question. A UUID in the same form the /data and /execute output shows is worth four
            // bytes a record.
            UUIDUtil.STRING_CODEC.fieldOf("aircraft").forGetter(Booking::aircraft)
        ).apply(instance, Booking::new));

        public Stand stand() {
            return new Stand(airfield, spot);
        }
    }

    /**
     * How long a stand must go on reading empty before the record is thrown away, in ticks.
     *
     * <p>Not zero, and this is the second half of the same asynchrony that made the registry
     * necessary. Loading a chunk gives back its blocks synchronously and its entities later, so the
     * very first look at a cold field — which is exactly what {@code /autopilot flight} does, since
     * {@code AutopilotSpawner.loadAirfield} pulls the stands in and asks about them in the same tick
     * — sees a chunk that is loaded and an aircraft that is not there yet. Measured: a departure
     * ordered onto a field whose chunks had been unloaded for half a minute forgot a perfectly good
     * record and was spawned on top of the aircraft parked there.
     *
     * <p>A second is long enough for the entity load to complete and short enough that a stand whose
     * aircraft has genuinely been destroyed frees itself before anybody notices.
     */
    private static final long EMPTY_CONFIRM_TICKS = 20;

    private StandOccupancy() {}

    /** Records that this aircraft has parked on the stand and is expected to stay there. */
    public static void take(Level level, String airfield, BlockPos spot, PlaneEntity plane) {
        if (level instanceof ServerLevel serverLevel) {
            AutopilotSavedData.get(serverLevel).bookStand(new Stand(airfield, spot), plane.getUUID());
        }
    }

    /**
     * The aircraft booked onto this stand, or null when the stand has no booking.
     *
     * <p>The raw record, with none of {@link #isTaken}'s validation applied: a caller that wants to
     * know <em>who</em> must still decide for itself what to do about an answer it cannot resolve.
     * A caller that wants to know <em>what is standing there</em> wants {@link #parkedOn} instead,
     * which is this plus the check that the aircraft has not moved.
     */
    public static @Nullable UUID heldBy(Level level, String airfield, BlockPos spot) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        Held held = AutopilotSavedData.get(serverLevel).stand(new Stand(airfield, spot));
        return held == null ? null : held.aircraft();
    }

    /**
     * The aircraft this mod parked on this stand and that is <b>still standing on it</b>, resolved
     * and alive, or null.
     *
     * <p>The whole of the "an aircraft with a booking is an aircraft this mod flew onto that square
     * itself" argument in {@link AircraftReuse}, in one place, because it was possible to have half
     * of it. A booking names a UUID and nothing more; resolving that UUID answers "where is this
     * aircraft now", not "is this aircraft on that square". A claim written as
     * {@code heldBy} + {@code getEntity} therefore re-tasked a fleet aircraft a player had flown
     * home and parked in their own hangar, cargo and all — the booking was stale, and the distance
     * to the stand was only ever consulted as a tie-break between candidates.
     *
     * <p>Where the aircraft has moved, the booking is <b>released</b> rather than merely refused:
     * that is the same self-healing rule {@link #isTaken} applies, and applying it here is what
     * finally heals a stale booking on a stand nothing else looks at. {@code Airfield}'s departure
     * search stops at the first usable stand, so before this the later stands' bookings were never
     * reached by anything that could throw them away.
     */
    public static @Nullable PlaneEntity parkedOn(ServerLevel level, String airfield, BlockPos spot) {
        AutopilotSavedData data = AutopilotSavedData.get(level);
        Stand stand = new Stand(airfield, spot);
        Held held = data.stand(stand);
        if (held == null) {
            return null;
        }
        if (!(level.getEntity(held.aircraft()) instanceof PlaneEntity plane)
            || plane.isRemoved() || !plane.isAlive()) {
            // Unresolvable is not "gone": it may simply be on disk. isTaken owns that distinction
            // and the clock that settles it; here it is enough that there is nothing to hand over.
            return null;
        }
        if (!onStand(plane, spot)) {
            data.releaseStand(stand);
            return null;
        }
        return plane;
    }

    /**
     * Whether the aircraft is close enough to the stand to count as standing on it.
     *
     * <p>Measured at the aircraft's own altitude, so a stand on sloping ground is not disqualified
     * by the y term. See {@link AutopilotConfig#STAND_OCCUPIED_RADIUS} for why the radius is not
     * {@link AutopilotConfig#PARKING_SPOT_CLEARANCE}: that one is the minimum gap between two
     * stands, so using it here made a machine on the neighbouring stand hold this stand's booking.
     */
    private static boolean onStand(PlaneEntity plane, BlockPos spot) {
        return AutopilotMath.horizontalDistance(plane.position(),
            new Vec3(spot.getX() + 0.5, plane.getY(), spot.getZ() + 0.5))
            <= AutopilotConfig.STAND_OCCUPIED_RADIUS;
    }

    /**
     * Whether something is standing on this stand.
     *
     * <p>This is also where a booking read back from disk is validated, because it is the only
     * reader: a restored record goes through the resolve / locate / confirm-empty sequence below
     * exactly as one made this session does.
     *
     * @param asker excluded, so an aircraft can ask about the stand it already owns
     */
    public static boolean isTaken(Level level, String airfield, BlockPos spot, @Nullable PlaneEntity asker) {
        // No store on a client level and there never was one worth reading: the bookings live in the
        // server's saved data. "No record" is the answer, and it is the same one a client got before
        // this was persisted, on every setup except a single-player world whose client happened to
        // share a JVM with its own server.
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        AutopilotSavedData data = AutopilotSavedData.get(serverLevel);
        Stand stand = new Stand(airfield, spot);
        Held held = data.stand(stand);
        if (held == null || (asker != null && held.aircraft().equals(asker.getUUID()))) {
            return false;
        }
        Entity entity = serverLevel.getEntity(held.aircraft());
        if (entity instanceof PlaneEntity plane && plane.isAlive() && !plane.isRemoved()) {
            // Loaded and alive: believe where it actually is rather than where it was left. A stand
            // whose aircraft has been flown away is free, and nothing else would ever free it.
            if (onStand(plane, spot)) {
                data.stampStand(stand, 0);
                return true;
            }
            data.releaseStand(stand);
            return false;
        }
        // Not in the level. Either it is standing there in a chunk nobody has loaded, or it is gone,
        // and those are indistinguishable from here — except by asking whether this chunk's entities
        // are loaded at all, and then giving the answer time to settle. See EMPTY_CONFIRM_TICKS.
        if (!serverLevel.areEntitiesLoaded(ChunkPos.pack(spot))) {
            return true;
        }
        long now = serverLevel.getGameTime();
        if (held.emptySince() == 0) {
            data.stampStand(stand, now);
            return true;
        }
        if (now - held.emptySince() < EMPTY_CONFIRM_TICKS) {
            return true;
        }
        data.releaseStand(stand);
        return false;
    }

    /**
     * {@link #isTaken}'s answer, without touching the record: for a reader that only draws.
     *
     * <p><b>Every other reader of this class is deciding where to put an aircraft, and this one is
     * not.</b> {@code AirfieldMarkerSync} rebuilds each nearby player's overlay once a second and
     * asks the same occupancy question the ground handling asks, deliberately, so that a marker
     * cannot disagree with where an aircraft will actually be sent. But {@link #isTaken} is not a
     * question — it stamps the confirmation clock and releases what has run out — so "a feature that
     * only draws" was writing to persisted saved data once a second per nearby player, and, since
     * its poll interval and {@link #EMPTY_CONFIRM_TICKS} are both 20 ticks off the same server tick,
     * it was also the thing deciding when a booking died: the clock started on one poll and expired
     * on the next, at the earliest instant the rule allows, whether or not anything had looked at
     * the field for a real reason.
     *
     * <p>So the drawing path gets the rule and not the side effects, which means it also gets the
     * rule's pessimism: a booking whose confirmation clock has never been started reads
     * <em>taken</em>, and nothing here will start it, so a stand whose aircraft was deleted out from
     * under the game keeps its marker until something asks about the square for a real reason — an
     * arrival choosing a stand, or a departure looking for an airframe. That is the same direction of
     * error the rest of this class takes, and the same cost {@link StandOccupancy} already documents
     * for a booking nothing ever looks at; what it buys is that a marker never contradicts where an
     * aircraft would actually be sent, which is the whole reason the overlay asks this question
     * rather than one of its own.
     */
    public static boolean looksTaken(Level level, String airfield, BlockPos spot) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        Held held = AutopilotSavedData.get(serverLevel).stand(new Stand(airfield, spot));
        if (held == null) {
            return false;
        }
        if (serverLevel.getEntity(held.aircraft()) instanceof PlaneEntity plane
            && plane.isAlive() && !plane.isRemoved()) {
            return onStand(plane, spot);
        }
        if (!serverLevel.areEntitiesLoaded(ChunkPos.pack(spot))) {
            return true;
        }
        // Unstamped means the clock has not been started by a reader that is allowed to start it,
        // so there is nothing yet to say the square has been empty long enough to believe.
        return held.emptySince() == 0
            || serverLevel.getGameTime() - held.emptySince() < EMPTY_CONFIRM_TICKS;
    }

    /**
     * Drops one stand's booking outright, for an aircraft that is knowingly leaving it.
     *
     * <p>The self-healing rule in {@link #isTaken} would get there on its own — the aircraft is
     * loaded and is about to be somewhere else, so the next look at the square releases it — but a
     * departure knows the answer now, and saying so is cheaper and clearer than waiting to be
     * noticed.
     */
    public static void release(Level level, String airfield, BlockPos spot) {
        if (level instanceof ServerLevel serverLevel) {
            AutopilotSavedData.get(serverLevel).releaseStand(new Stand(airfield, spot));
        }
    }

    /** Forgets every record for an airfield, so removing or renaming one leaves nothing behind. */
    public static void forget(Level level, String airfield) {
        if (level instanceof ServerLevel serverLevel) {
            AutopilotSavedData.get(serverLevel).forgetStands(airfield);
        }
    }
}
