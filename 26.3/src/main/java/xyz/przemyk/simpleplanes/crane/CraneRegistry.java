package xyz.przemyk.simpleplanes.crane;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.entities.QuadcopterEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Server-side set of cranes, and their rolling chunk tickets. */
public final class CraneRegistry {

    public static final int TICKET_RADIUS = 2;
    public static final int TICKET_INTERVAL = 5;
    public static final int TICKET_LEAD_TICKS = 20;

    private static final Set<QuadcopterEntity> CRANES = Collections.newSetFromMap(new IdentityHashMap<>());
    private static boolean initialised;

    private CraneRegistry() {}

    public static void init() {
        if (initialised) {
            return;
        }
        initialised = true;
        ServerTickEvents.END_LEVEL_TICK.register(CraneRegistry::onLevelTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> CRANES.clear());
    }

    public static void track(QuadcopterEntity crane) {
        if (!crane.level().isClientSide()) {
            CRANES.add(crane);
        }
    }

    /** Live cranes in this level, pruned and copied. */
    public static List<QuadcopterEntity> all(ServerLevel level) {
        CRANES.removeIf(QuadcopterEntity::isRemoved);
        List<QuadcopterEntity> out = new ArrayList<>();
        for (QuadcopterEntity crane : CRANES) {
            if (crane.level() == level) {
                out.add(crane);
            }
        }
        out.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        return out;
    }

    private static void onLevelTick(ServerLevel level) {
        if (CRANES.isEmpty() || level.getGameTime() % TICKET_INTERVAL != 0) {
            return;
        }
        for (QuadcopterEntity crane : all(level)) {
            if (crane.needsChunks()) {
                keepChunksLoaded(level, crane);
            }
        }
    }

    private static void keepChunksLoaded(ServerLevel level, QuadcopterEntity crane) {
        ChunkPos here = ChunkPos.containing(crane.blockPosition());
        level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, here, TICKET_RADIUS);
        Vec3 ahead = crane.position().add(crane.getDeltaMovement().scale(TICKET_LEAD_TICKS));
        ChunkPos next = new ChunkPos(Mth.floor(ahead.x) >> 4, Mth.floor(ahead.z) >> 4);
        if (!next.equals(here)) {
            level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, next, TICKET_RADIUS);
        }
    }
}
