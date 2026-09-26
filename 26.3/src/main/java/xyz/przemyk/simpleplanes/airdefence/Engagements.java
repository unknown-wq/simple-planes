package xyz.przemyk.simpleplanes.airdefence;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Who is engaging which aircraft. A claim is held by a silo in its launch sequence or by a guided missile in
 * flight; {@link InterceptorSpec#MAX_PER_TARGET} (1) caps the claims per target across all silos.
 *
 * <p>A claim lapses on its own unless renewed, so a silo that goes to sleep in an unloaded chunk never blocks a
 * target for good. A missile renews its claim every tick it is guided, and {@code MissileTracker} renews it again
 * from the level tick, so it cannot lapse while the missile exists and still has its motor.
 *
 * <p>When a missile gives up a claim while its aircraft is still alive (removed, out of fuel, lost, a fuse burst
 * that did not kill, and so on) the reason is kept as a {@link Miss}; the next silo to engage that aircraft reads
 * it, logs it as the reason for the second shot and shows it in {@code /airdefence engagements}.
 */
public final class Engagements {

    /** A missile renews its claim every tick; this is how long a claim outlives its last renewal. */
    public static final int MISSILE_CLAIM_TICKS = 40;
    /** How long a miss is remembered as the reason for a follow-up shot. */
    public static final int MISS_MEMORY_TICKS = 2400;
    private static final int MAX_FOLLOW_UPS = 16;

    public sealed interface Engager permits SiloEngager, MissileEngager {}
    public record SiloEngager(ResourceKey<Level> dimension, BlockPos pos) implements Engager {}
    public record MissileEngager(int id) implements Engager {}

    /** A live claim; {@code note} says why this is a follow-up shot, or is null for a first shot. */
    public record Claim(UUID target, int targetEntityId, long since, long expires, @Nullable String note) {}

    /** How a missile's engagement of a still-living aircraft ended. */
    public record Miss(int missileId, BlockPos silo, String reason, long at) {
        public String describe(long now) {
            return String.format(Locale.ROOT, "missile #%d from silo %s ended %s %.1f s earlier", missileId,
                silo.toShortString(), reason, (now - at) / 20.0);
        }
    }

    /** A follow-up shot, kept for {@code /airdefence engagements}. */
    public record FollowUp(BlockPos silo, int targetEntityId, String why, long at) {}

    private static final Map<Engager, Claim> CLAIMS = new LinkedHashMap<>();
    private static final Map<UUID, Miss> MISSES = new HashMap<>();
    private static final Deque<FollowUp> FOLLOW_UPS = new ArrayDeque<>();

    private Engagements() {}

    static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> clear());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
    }

    private static void clear() {
        CLAIMS.clear();
        MISSES.clear();
        FOLLOW_UPS.clear();
    }

    public static SiloEngager silo(ServerLevel level, BlockPos pos) {
        return new SiloEngager(level.dimension(), pos.immutable());
    }

    /** Takes or renews a claim. A renewal on the same target keeps its start time and follow-up note. */
    public static void claim(Engager engager, UUID target, int targetEntityId, long now, int ticks) {
        Claim old = CLAIMS.get(engager);
        boolean same = old != null && old.target.equals(target);
        CLAIMS.put(engager, new Claim(target, targetEntityId, same ? old.since : now, now + ticks, same ? old.note : null));
    }

    /** Extends a live claim without touching anything else; false if there is none. */
    public static boolean renew(Engager engager, long now, int ticks) {
        Claim c = CLAIMS.get(engager);
        if (c == null) return false;
        if (c.expires < now + ticks) CLAIMS.put(engager, new Claim(c.target, c.targetEntityId, c.since, now + ticks, c.note));
        return true;
    }

    private static void note(Engager engager, String note) {
        Claim c = CLAIMS.get(engager);
        if (c != null) CLAIMS.put(engager, new Claim(c.target, c.targetEntityId, c.since, c.expires, note));
    }

    /** Drops a silo's claim once its missile holds its own, carrying the follow-up note over. Same tick. */
    public static void handOver(Engager from, Engager to) {
        Claim c = CLAIMS.remove(from);
        Claim now = CLAIMS.get(to);
        if (c != null && now != null && now.target.equals(c.target) && c.note != null) note(to, c.note);
    }

    public static void release(Engager engager) {
        CLAIMS.remove(engager);
    }

    /** Releases a missile's claim and remembers why, for an aircraft that outlived the engagement. */
    public static void releaseMissed(MissileEngager engager, UUID target, BlockPos silo, String reason, long now) {
        CLAIMS.remove(engager);
        MISSES.put(target, new Miss(engager.id(), silo.immutable(), reason, now));
    }

    public static @Nullable Claim claimOf(Engager engager, long now) {
        Claim c = CLAIMS.get(engager);
        return c == null || c.expires < now ? null : c;
    }

    public static @Nullable UUID targetOf(Engager engager, long now) {
        Claim c = claimOf(engager, now);
        return c == null ? null : c.target;
    }

    /** The recent miss on {@code target} that a new engagement would follow up, or null. */
    public static @Nullable Miss lastMiss(UUID target, long now) {
        Miss m = MISSES.get(target);
        if (m != null && now - m.at > MISS_MEMORY_TICKS) {
            MISSES.remove(target);
            return null;
        }
        return m;
    }

    /**
     * Called when a silo has just claimed {@code target} for a launch: a remembered miss on it becomes the claim's
     * note and a {@link FollowUp}. Returns the reason, or null for a first shot.
     */
    public static @Nullable String followUp(SiloEngager silo, UUID target, int targetEntityId, long now) {
        Miss m = lastMiss(target, now);
        if (m == null) return null;
        MISSES.remove(target);
        String why = m.describe(now);
        note(silo, "second shot: " + why);
        FOLLOW_UPS.addLast(new FollowUp(silo.pos(), targetEntityId, why, now));
        while (FOLLOW_UPS.size() > MAX_FOLLOW_UPS) FOLLOW_UPS.removeFirst();
        return why;
    }

    /** Live claims on {@code target}, not counting {@code except}. Drops expired claims on the way. */
    public static int count(UUID target, long now, @Nullable Engager except) {
        int n = 0;
        for (Iterator<Map.Entry<Engager, Claim>> it = CLAIMS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Engager, Claim> e = it.next();
            if (e.getValue().expires < now) {
                it.remove();
                continue;
            }
            if (e.getValue().target.equals(target) && !e.getKey().equals(except)) n++;
        }
        return n;
    }

    public static boolean saturated(UUID target, long now, @Nullable Engager except) {
        return count(target, now, except) >= InterceptorSpec.MAX_PER_TARGET;
    }

    /** A live engager holding {@code target} other than {@code except}, or null. */
    public static @Nullable Engager holder(UUID target, long now, @Nullable Engager except) {
        for (Map.Entry<Engager, Claim> e : CLAIMS.entrySet()) {
            if (e.getValue().expires >= now && e.getValue().target.equals(target) && !e.getKey().equals(except)) return e.getKey();
        }
        return null;
    }

    /** The live claims, oldest first. */
    public static List<Map.Entry<Engager, Claim>> claims(long now) {
        CLAIMS.values().removeIf(c -> c.expires < now);
        return new ArrayList<>(CLAIMS.entrySet());
    }

    public static List<FollowUp> followUps() {
        return new ArrayList<>(FOLLOW_UPS);
    }

    public static int size() {
        return CLAIMS.size();
    }
}
