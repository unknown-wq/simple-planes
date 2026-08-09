package xyz.przemyk.simpleplanes.combat;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The live gunships, and the tick that drives them.
 *
 * <h2>Why the pump is here and not on the entity</h2>
 * {@code PlaneEntity} has exactly one per-tick hook for a server-side controller —
 * {@code setAutopilot(PlaneAutopilot)} — and that hook belongs to the fixed-wing flight director
 * another agent is rewriting. Rather than take it, or add a second hook to a file this feature must
 * not edit, the gunship is driven from the server tick: it sets the same synched controls a player
 * would (throttle, and the helicopter's vertical-flight flag) <em>before</em> the entity list is
 * ticked, so the physics acts on them in the same tick.
 *
 * <p>{@code START_LEVEL_TICK}, not {@code END_LEVEL_TICK}, for exactly that reason. Set from the end
 * of the tick, every control input would be one tick stale — which the altitude loop would survive
 * and the gunnery, which reads the target's position at the moment it fires, would not.
 *
 * <h2>Chunk tickets</h2>
 * Same trap the autopilot documents: an entity only ticks while its chunk is at entity-ticking
 * level, so a gunship ordered to a spot nobody is standing near would freeze mid-climb. The ticket
 * is renewed from here, over strong references, so a gunship that <em>has</em> stopped ticking is
 * still picked up and thawed — renewing it from the aircraft's own tick would be circular.
 */
public final class GunshipRegistry {

    /**
     * Ticket radius around the aircraft.
     *
     * <p><b>This is not the number of chunks that tick, and getting that wrong cost most of a day.</b>
     * {@code TicketStorage#addTicketWithRadius} puts a ticket of level
     * {@code ChunkLevel.byStatus(FULL) - radius} on the <em>centre</em> chunk and lets it propagate
     * outwards one level per chunk. FULL is 33, BLOCK_TICKING 32 and ENTITY_TICKING 31, so a radius
     * of {@code r} makes chunks out to {@code r - 2} entity-ticking and the rest merely resident.
     * A radius of 2 — copied from the ender-pearl default — therefore ticks entities in exactly
     * <em>one</em> chunk.
     *
     * <p>What that looks like is not an error. The gunship, sitting in the middle of its own chunk,
     * behaved perfectly; its arrows froze in mid-air the instant they crossed a chunk boundary, kept
     * their velocity to sixteen decimal places for ever, and reported no hits. Measured with
     * {@code tick freeze} and single-stepping: a round fired from x=3000.5 flew five clean ticks and
     * stopped dead at x=3008.109, which is 3008 — the first block of the next chunk. Fired from
     * x=4000.5, where the whole trajectory stays inside chunk 250, the same shot flew to the target.
     * The identical trap is documented for the autopilot in {@code AutopilotConfig.CHUNK_TICKET_RADIUS}
     * and this feature walked straight into it anyway.
     *
     * <p>6 gives an entity-ticking area 4 chunks — 64 blocks — in every direction, which covers the
     * {@link GunshipSortie#ENGAGEMENT_RADIUS} of 40 blocks and everything a round that misses does
     * afterwards.
     */
    private static final int TICKET_RADIUS = 6;
    /** Ticks between ticket renewals. Well inside the ~40-tick unload delay. */
    private static final int TICKET_INTERVAL = 20;
    /** Sorties allowed at once. Each one holds a chunk bubble. */
    public static final int MAX_ACTIVE = 16;

    private static final List<GunshipSortie> ACTIVE = new ArrayList<>();

    private GunshipRegistry() {}

    public static void init() {
        ServerTickEvents.START_LEVEL_TICK.register(GunshipRegistry::onLevelTick);
    }

    public static boolean canLaunchAnother() {
        prune();
        return ACTIVE.size() < MAX_ACTIVE;
    }

    public static void add(GunshipSortie sortie) {
        ACTIVE.add(sortie);
    }

    public static List<GunshipSortie> active() {
        prune();
        return List.copyOf(ACTIVE);
    }

    /** Recalls every gunship. Returns how many were stopped. */
    public static int stopAll() {
        prune();
        List<GunshipSortie> snapshot = List.copyOf(ACTIVE);
        for (GunshipSortie sortie : snapshot) {
            sortie.abort();
        }
        prune();
        return snapshot.size();
    }

    private static void prune() {
        ACTIVE.removeIf(GunshipSortie::isFinished);
    }

    private static void onLevelTick(ServerLevel level) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        boolean renewTickets = level.getGameTime() % TICKET_INTERVAL == 0;
        // Copied: a sortie can finish, discard its aircraft and load a chunk inside its own tick.
        for (Iterator<GunshipSortie> it = List.copyOf(ACTIVE).iterator(); it.hasNext(); ) {
            GunshipSortie sortie = it.next();
            if (sortie.helicopter().level() != level) {
                continue;
            }
            if (renewTickets && !sortie.isFinished()) {
                keepChunksLoaded(level, sortie.helicopter().position());
            }
            sortie.tick();
        }
        prune();
    }

    /**
     * Makes the chunks around a point resident, synchronously.
     *
     * <p>{@code getChunk} generates and returns the chunk on the spot; a ticket on its own only
     * <em>schedules</em> the load, which is not enough at spawn time — an entity added to a chunk
     * that does not exist yet does not tick, it simply hangs there.
     */
    public static void keepChunksLoaded(ServerLevel level, Vec3 position) {
        int chunkX = Mth.floor(position.x) >> 4;
        int chunkZ = Mth.floor(position.z) >> 4;
        level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(chunkX, chunkZ), TICKET_RADIUS);
    }

    /** The stronger form used once, before an aircraft is put into the world. */
    public static void loadAround(ServerLevel level, BlockPos position) {
        int chunkX = position.getX() >> 4;
        int chunkZ = position.getZ() >> 4;
        level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(chunkX, chunkZ), TICKET_RADIUS);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.getChunk(chunkX + dx, chunkZ + dz);
            }
        }
    }
}
