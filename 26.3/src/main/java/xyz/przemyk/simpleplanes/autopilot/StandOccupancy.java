package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.HashMap;
import java.util.Map;
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
 * <p>Runtime-only, like {@link RunwayOccupancy} and for a weaker version of the same reason. A
 * restart forgets every record, after which the plain entity search is back in charge and is right
 * whenever the chunk happens to be loaded. That is a real hole and it is the cheap side of the trade:
 * the alternative is persisting an occupancy that nothing can validate on load.
 *
 * <p>All access is from the server thread, so a plain map is fine.
 */
public final class StandOccupancy {

    private record Key(ResourceKey<Level> dimension, String airfield, BlockPos spot) {}

    /**
     * By UUID rather than by reference — the entity itself is removed when its chunk unloads — plus
     * the game time the square first read empty while its chunk claimed to be loaded, or 0.
     */
    private record Held(UUID aircraft, long emptySince) {}

    private static final Map<Key, Held> STANDS = new HashMap<>();

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
        STANDS.put(new Key(level.dimension(), airfield, spot), new Held(plane.getUUID(), 0));
    }

    /**
     * Whether something is standing on this stand.
     *
     * @param asker excluded, so an aircraft can ask about the stand it already owns
     */
    public static boolean isTaken(Level level, String airfield, BlockPos spot, @Nullable PlaneEntity asker) {
        Key key = new Key(level.dimension(), airfield, spot);
        Held held = STANDS.get(key);
        if (held == null || (asker != null && held.aircraft().equals(asker.getUUID()))) {
            return false;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return true;
        }
        Entity entity = serverLevel.getEntity(held.aircraft());
        if (entity instanceof PlaneEntity plane && plane.isAlive() && !plane.isRemoved()) {
            // Loaded and alive: believe where it actually is rather than where it was left. A stand
            // whose aircraft has been flown away is free, and nothing else would ever free it.
            if (AutopilotMath.horizontalDistance(plane.position(),
                new Vec3(spot.getX() + 0.5, plane.getY(), spot.getZ() + 0.5))
                <= AutopilotConfig.PARKING_SPOT_CLEARANCE) {
                STANDS.put(key, new Held(held.aircraft(), 0));
                return true;
            }
            STANDS.remove(key);
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
            STANDS.put(key, new Held(held.aircraft(), now));
            return true;
        }
        if (now - held.emptySince() < EMPTY_CONFIRM_TICKS) {
            return true;
        }
        STANDS.remove(key);
        return false;
    }

    /** Forgets every record for an airfield, so removing or renaming one leaves nothing behind. */
    public static void forget(Level level, String airfield) {
        STANDS.keySet().removeIf(key -> key.dimension().equals(level.dimension())
            && key.airfield().equals(airfield));
    }
}
