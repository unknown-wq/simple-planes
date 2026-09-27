package xyz.przemyk.simpleplanes.missile;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.api.BlastGuard;
import xyz.przemyk.simpleplanes.api.BlastGuards;
import xyz.przemyk.simpleplanes.autopilot.Blast;

import java.util.ArrayList;
import java.util.List;

/**
 * Test stand-in for a land-claim mod, driven by {@code /missile guard}. It is an ordinary {@link BlastGuard}, so it
 * sees aircraft and missile blasts alike. A blast whose damage radius ({@code 2 x power}) reaches a protected box
 * loses its block damage and fire, or is suppressed outright if the box says so. Registered on first use only, so a
 * server that never runs the command has no guard; the boxes are forgotten at a restart.
 */
final class MissileTestGuard implements BlastGuard {

    record Zone(ResourceKey<Level> dimension, AABB box, boolean suppress) {}

    private static final List<Zone> ZONES = new ArrayList<>();
    private static boolean registered;

    private MissileTestGuard() {}

    static void add(Zone zone) {
        if (!registered) {
            BlastGuards.register(new MissileTestGuard());
            registered = true;
        }
        ZONES.add(zone);
    }

    static int clear() {
        int n = ZONES.size();
        ZONES.clear();
        return n;
    }

    static List<Zone> zones() {
        return List.copyOf(ZONES);
    }

    @Override
    public Blast guardBlast(ServerLevel level, Entity source, Vec3 at, Blast blast) {
        AABB reach = new AABB(at, at).inflate(2.0 * blast.power());
        for (Zone zone : ZONES) {
            if (zone.dimension() != level.dimension() || !zone.box().intersects(reach)) continue;
            if (zone.suppress()) return null;
            // keeps a piercing blast piercing: it has no block damage or fire to take away
            blast = new Blast(blast.power(), false, false, blast.pierce());
        }
        return blast;
    }
}
