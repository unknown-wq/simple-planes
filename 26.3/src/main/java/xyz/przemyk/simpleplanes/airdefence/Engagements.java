package xyz.przemyk.simpleplanes.airdefence;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Who is engaging which aircraft. A claim is held by a silo in its launch sequence or by a missile in flight,
 * and lapses on its own unless renewed, so a silo that goes to sleep in an unloaded chunk or a missile that
 * vanishes never blocks a target for good. {@link InterceptorSpec#MAX_PER_TARGET} caps the claims per target.
 */
public final class Engagements {

    /** A missile renews its claim every tick; this is how long a claim outlives its last renewal. */
    public static final int MISSILE_CLAIM_TICKS = 40;

    public sealed interface Engager permits SiloEngager, MissileEngager {}
    public record SiloEngager(ResourceKey<Level> dimension, BlockPos pos) implements Engager {}
    public record MissileEngager(int id) implements Engager {}

    private record Claim(UUID target, long expires) {}

    private static final Map<Engager, Claim> CLAIMS = new HashMap<>();

    private Engagements() {}

    static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> CLAIMS.clear());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> CLAIMS.clear());
    }

    public static SiloEngager silo(ServerLevel level, BlockPos pos) {
        return new SiloEngager(level.dimension(), pos.immutable());
    }

    public static void claim(Engager engager, UUID target, long now, int ticks) {
        CLAIMS.put(engager, new Claim(target, now + ticks));
    }

    public static void release(Engager engager) {
        CLAIMS.remove(engager);
    }

    public static @Nullable UUID targetOf(Engager engager, long now) {
        Claim c = CLAIMS.get(engager);
        return c == null || c.expires < now ? null : c.target;
    }

    /** Live claims on {@code target}, not counting {@code except}. */
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

    public static int size() {
        return CLAIMS.size();
    }
}
