package xyz.przemyk.simpleplanes.combat;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * What is underneath the aircraft: how far down it is, and whether it is something you can put skids
 * on.
 *
 * <h2>Why this exists instead of a call into {@code autopilot.TerrainScanner}</h2>
 * That class answers the same two questions and answers them well, and an earlier draft of this
 * feature simply called it. It was dropped for one reason: {@code autopilot/} is being rewritten in
 * parallel, and a weapon system that stops compiling because a flight-director helper changed its
 * signature is a weapon system with a dependency it never needed. Everything here is four heightmap
 * lookups; there is nothing to share.
 *
 * <h2>Two heightmaps, and the difference between them is the whole point</h2>
 * <ul>
 *   <li>{@link Heightmap.Types#MOTION_BLOCKING} tests {@code blocksMotion() || !getFluidState().isEmpty()},
 *       so over a lake it reports the <b>waterline</b>. That is the right answer for "how far below me
 *       is the first thing I would touch", which is what the altitude report and the descent floor
 *       want.</li>
 *   <li>{@link Heightmap.Types#OCEAN_FLOOR} ignores fluids, so over the same lake it reports the
 *       <b>bed</b>. It carries {@code Usage.LIVE_WORLD}, so unlike the worldgen-only heightmaps it is
 *       maintained on a running server and costs the same single O(1) lookup.</li>
 * </ul>
 * They are equal exactly where there is no fluid standing on the terrain, so comparing them is a
 * landability test that needs no block lookup at all. {@link #isLandable} is what stops the sortie
 * reporting a ditching as a landing.
 *
 * <p>An unloaded column answers {@link #UNKNOWN} for the heights and <em>false</em> for landability,
 * on purpose: everything that consults landability is deciding whether to commit to a touchdown, and
 * "the server has not loaded that ground" must never be the answer that lets the commitment be made.
 */
public final class Ground {

    /** Returned when the column is not loaded, or lies outside the buildable world. */
    public static final int UNKNOWN = Integer.MIN_VALUE;

    private Ground() {}

    /** Y of the first free block above whatever the aircraft would touch, fluids included. */
    public static int surfaceHeight(Level level, double x, double z) {
        return heightAt(level, Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    /** Y of the first free block above the solid terrain, fluids ignored. */
    public static int landableSurfaceHeight(Level level, double x, double z) {
        return heightAt(level, Heightmap.Types.OCEAN_FLOOR, x, z);
    }

    /**
     * Whether the surface under a column is ground an aircraft can come to rest on, as opposed to the
     * top of a body of water or lava.
     */
    public static boolean isLandable(Level level, double x, double z) {
        int clearance = surfaceHeight(level, x, z);
        return clearance != UNKNOWN && clearance == landableSurfaceHeight(level, x, z);
    }

    private static int heightAt(Level level, Heightmap.Types type, double x, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        if (!level.hasChunkAt(blockX, blockZ)) {
            return UNKNOWN;
        }
        int height = level.getHeight(type, blockX, blockZ);
        return height <= level.getMinY() ? UNKNOWN : height;
    }
}
