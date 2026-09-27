package xyz.przemyk.simpleplanes.aviation;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.api.map.LaunchResult;
import xyz.przemyk.simpleplanes.api.map.SiloAction;
import xyz.przemyk.simpleplanes.missile.LaunchSiloBlockEntity;
import xyz.przemyk.simpleplanes.missile.MissileTier;
import xyz.przemyk.simpleplanes.missile.Missiles;
import xyz.przemyk.simpleplanes.missile.SiloStructure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Launches from the map for a silo whose chunk is not loaded. Documented in {@code MISSILES.md}
 * ("Remote launch").
 *
 * <p>The request is judged first against the silo index (strike mode, a missile loaded, target in range), so a
 * doomed request loads nothing. Then the silo's chunk gets a {@code TicketType.ENDER_PEARL} ticket of radius 3,
 * renewed every tick: the silo's chunk and its neighbours become entity-ticking, the same bubble a missile in flight
 * keeps. Once the chunk ticks blocks and the block entity is there, the normal launch path runs
 * ({@link AviationService#launchFrom}), with every check {@code /missile launch} makes. An accepted launch holds its
 * own {@code PORTAL} ticket ({@code MissileTracker#holdSilo}), so this ticket is dropped at once, as it is on any
 * refusal or when the chunk has not come up in {@link #LOAD_TIMEOUT_TICKS} ticks.
 *
 * <p>Ticks are the silo level's ticks. A paused single-player server does not tick, so a request sent from the
 * paused map is carried out once the game runs again.
 */
final class RemoteLaunch {

    private RemoteLaunch() {}

    /** How many level ticks the silo's chunk gets to load and tick before the request is refused. */
    static final int LOAD_TIMEOUT_TICKS = 100;
    /** Same radius as a missile's own tickets: the centre chunk and its 3x3 neighbours entity-ticking. */
    private static final int TICKET_RADIUS = 3;

    private static final class Job {
        final UUID player;
        final String name;
        final BlockPos silo;
        final int x;
        final int y;
        final int z;
        int ticks;

        Job(UUID player, String name, BlockPos silo, int x, int y, int z) {
            this.player = player;
            this.name = name;
            this.silo = silo;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final Map<ResourceKey<Level>, Map<BlockPos, Job>> JOBS = new HashMap<>();

    /** True while a remote launch is loading the chunk of the silo at {@code pos}. */
    static boolean pending(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Job> jobs = JOBS.get(level.dimension());
        return jobs != null && jobs.containsKey(pos);
    }

    /**
     * Starts a remote launch of the indexed silo at {@code pos}, whose chunk is not loaded. Permission, rate limit
     * and world border are already checked. Returns the pending answer, or a refusal.
     */
    static LaunchResult start(ServerLevel level, ServerPlayer player, BlockPos pos, AviationPayloads.LaunchRequest request) {
        SiloIndex index = SiloIndex.peek(level);
        SiloIndex.Entry entry = index == null ? null : index.get(pos);
        if (entry == null) {
            return AviationService.refused(player, pos, SiloAction.LAUNCH,
                AviationPayloads.text("refuse.no_silo", "there is no silo at %s", pos.toShortString()));
        }
        String problem = null;
        MissileTier tier = MissileTier.of(Mth.clamp(entry.tier(), 1, 4));
        if (pending(level, pos)) problem = "busy (loading the silo's chunk)";
        else if (!entry.strike()) problem = "the silo is in air-defence mode (last known state)";
        else if (!entry.loaded() && !level.getGameRules().get(Missiles.INFINITE)) problem = "no missile loaded (last known state)";
        else problem = LaunchSiloBlockEntity.rangeProblem(pos, tier, new Vec3(request.x() + 0.5, 0, request.z() + 0.5));
        if (problem != null) {
            return AviationService.refused(player, pos, SiloAction.LAUNCH, AviationPayloads.text("refuse.silo",
                "silo at %s cannot launch: %s", pos.toShortString(), problem));
        }
        Job job = new Job(player.getUUID(), player.getName().getString(), pos, request.x(), request.y(), request.z());
        JOBS.computeIfAbsent(level.dimension(), k -> new LinkedHashMap<>()).put(pos, job);
        ticket(level, pos);
        double distance = player.position().distanceTo(SiloStructure.mouth(pos, tier));
        AviationService.LOGGER.info("[aviation] {} asked for a remote launch of silo {} T{} ({} blocks away); loading its chunk",
            job.name, pos.toShortString(), tier.tier, (int) Math.round(distance));
        Component message = AviationPayloads.text("remote.pending", "Remote launch: loading the chunk of the silo at %s...",
            pos.toShortString());
        player.sendOverlayMessage(message.copy().withStyle(ChatFormatting.YELLOW));
        return new LaunchResult(pos, false, message, 0, 0, 0, SiloAction.LAUNCH, true);
    }

    /** From the level tick: keeps each job's chunk ticket alive and finishes the jobs whose silo is ticking. */
    static void tick(ServerLevel level) {
        Map<BlockPos, Job> jobs = JOBS.get(level.dimension());
        if (jobs == null || jobs.isEmpty()) return;
        for (Job job : new ArrayList<>(jobs.values())) {
            job.ticks++;
            boolean ticking = level.isLoaded(job.silo) && level.shouldTickBlocksAt(ChunkPos.pack(job.silo));
            if (!ticking && job.ticks < LOAD_TIMEOUT_TICKS) {
                ticket(level, job.silo);
                continue;
            }
            jobs.remove(job.silo);
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(job.player);
            LaunchResult result = ticking ? finish(level, job, player)
                : refuse(level, job, player, "the silo's chunk did not load in " + LOAD_TIMEOUT_TICKS + " ticks");
            // An accepted launch holds its own PORTAL ticket by now.
            level.getChunkSource().removeTicketWithRadius(TicketType.ENDER_PEARL, ChunkPos.containing(job.silo), TICKET_RADIUS);
            if (player != null) AviationService.reply(player, result);
        }
    }

    private static LaunchResult finish(ServerLevel level, Job job, @Nullable ServerPlayer player) {
        if (!(level.getBlockEntity(job.silo) instanceof LaunchSiloBlockEntity be)) {
            SiloIndex index = SiloIndex.peek(level);
            if (index != null && index.remove(job.silo)) {
                AviationService.LOGGER.info("[aviation] silo index: {} is gone, entry dropped (remote launch)", job.silo.toShortString());
            }
            return refuse(level, job, player, "there is no silo at " + job.silo.toShortString() + " any more");
        }
        return AviationService.launchFrom(level, player, job.name, be, job.silo, job.x, job.y, job.z,
            "remote, chunk loaded in " + job.ticks + " ticks");
    }

    private static LaunchResult refuse(ServerLevel level, Job job, @Nullable ServerPlayer player, String problem) {
        return AviationService.refused(player, job.name, job.silo, SiloAction.LAUNCH,
            AviationPayloads.text("refuse.silo", "silo at %s cannot launch: %s", job.silo.toShortString(), problem));
    }

    private static void ticket(ServerLevel level, BlockPos pos) {
        level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, ChunkPos.containing(pos), TICKET_RADIUS);
    }

    /** Server stopping: forget every job; the tickets expire with the level. */
    static void clear() {
        JOBS.clear();
    }

}
