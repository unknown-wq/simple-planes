package xyz.przemyk.simpleplanes.airdefence;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Every {@link PlaneEntity} currently loaded, per dimension, so target selection is a walk over a handful of
 * aircraft instead of an entity search over a large box. Only loaded aircraft are listed: an aircraft in an
 * unloaded chunk cannot be seen, and neither can a silo in one.
 */
public final class AircraftRoster {

    private static final Map<ResourceKey<Level>, Set<PlaneEntity>> LOADED = new HashMap<>();

    private AircraftRoster() {}

    static void init() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof PlaneEntity plane) LOADED.computeIfAbsent(level.dimension(), k -> new LinkedHashSet<>()).add(plane);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof PlaneEntity plane) {
                Set<PlaneEntity> set = LOADED.get(level.dimension());
                if (set != null) set.remove(plane);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LOADED.clear());
    }

    public static List<PlaneEntity> loaded(ServerLevel level) {
        Set<PlaneEntity> set = LOADED.get(level.dimension());
        if (set == null) return List.of();
        set.removeIf(p -> p.isRemoved() || p.level() != level);
        return new ArrayList<>(set);
    }

    /** A hostile aircraft AD may engage: alive, loaded, in this level. */
    public static boolean isEngageable(PlaneEntity plane, ServerLevel level) {
        return plane.isHostile() && plane.isAlive() && !plane.isRemoved() && plane.level() == level;
    }

    /** Nearest engageable hostile aircraft within {@code radius} of {@code from} (3D), or null. */
    public static @Nullable PlaneEntity nearestHostile(ServerLevel level, Vec3 from, double radius, Predicate<PlaneEntity> filter) {
        PlaneEntity best = null;
        double bestD2 = radius * radius;
        for (PlaneEntity p : loaded(level)) {
            if (!isEngageable(p, level) || !filter.test(p)) continue;
            double d2 = aimPoint(p).distanceToSqr(from);
            if (d2 <= bestD2) {
                bestD2 = d2;
                best = p;
            }
        }
        return best;
    }

    /** The point missiles aim at and measure miss distance to: the centre of the bounding box. */
    public static Vec3 aimPoint(PlaneEntity plane) {
        return plane.getBoundingBox().getCenter();
    }
}
