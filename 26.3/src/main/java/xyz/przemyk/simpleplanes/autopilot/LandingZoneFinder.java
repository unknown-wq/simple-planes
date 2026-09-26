package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.api.dispatch.LandingZone;
import xyz.przemyk.simpleplanes.api.dispatch.LandingZoneSpec;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Incremental search for an unregistered landing zone near a target, from heightmaps only.
 *
 * <p>Candidates are the columns within {@code radius} of the target, nearest first. Each column is
 * read once (three O(1) heightmap lookups) into a grid and reused by every candidate that touches
 * it. A candidate is accepted when:
 * <ul>
 *   <li>its (2r+1)² footprint is loaded, dry (MOTION_BLOCKING = OCEAN_FLOOR), leaf-free
 *       (MOTION_BLOCKING = MOTION_BLOCKING_NO_LEAVES) and flat within {@code maxSpread};</li>
 *   <li>a ring of {@code ringWidth} around it is no higher than the zone plus one;</li>
 *   <li>at least {@code minClearSectors} of the 8 approach sectors are clear: along three lanes
 *       (centre and ±2) out to {@code approachLength}, sampled every 2 blocks, terrain stays under a
 *       45° surface rising from the ring edge and capped at {@code clearHeight}.</li>
 * </ul>
 * An unloaded column always counts as an obstacle. The heightmap is the top of each column, so
 * the space above the footprint is clear by construction.
 *
 * <p>Work is metered in column reads; {@link #step} stops when the budget is used and resumes on
 * the next call. Worst case per candidate is about 25 + 56 + 216 reads, but reads are cached and a
 * cheap centre-column test rejects water and canopy first.
 */
public final class LandingZoneFinder {

    public enum State { RUNNING, FOUND, FAILED }

    private static final int SECTORS = RotorcraftConfig.APPROACH_SECTORS;
    private static final int LANE_OFFSET = 2;
    private static final int STEP = 2;

    private static final byte READ = 1;
    private static final byte UNLOADED = 2;
    private static final byte WET = 4;
    private static final byte LEAVES = 8;

    private static final ConcurrentHashMap<Integer, int[]> OFFSETS = new ConcurrentHashMap<>();

    private final Level level;
    private final int centreX;
    private final int centreZ;
    private final int radius;
    private final LandingZoneSpec spec;
    private final int half;
    private final int side;
    private final int[] surface;
    private final byte[] flags;
    private final int[] offsets;

    private int next;
    private State state = State.RUNNING;
    private @Nullable Helipad found;
    private double foundDistance;

    // Diagnostics.
    private long reads;
    private int candidates;
    private int rejectedUnloaded;
    private int rejectedWet;
    private int rejectedLeaves;
    private int rejectedSlope;
    private int rejectedObstacle;
    private int rejectedApproach;
    private int rejectedElevation;

    public LandingZoneFinder(Level level, int x, int z, int radius, LandingZoneSpec spec) {
        this.level = level;
        this.centreX = x;
        this.centreZ = z;
        this.radius = Math.max(0, Math.min(radius, 96));
        this.spec = spec;
        this.half = this.radius + spec.footprintRadius() + spec.ringWidth() + spec.approachLength() + LANE_OFFSET;
        this.side = 2 * half + 1;
        this.surface = new int[side * side];
        this.flags = new byte[side * side];
        this.offsets = OFFSETS.computeIfAbsent(this.radius, LandingZoneFinder::buildOffsets);
    }

    /** (dx, dz) pairs within {@code r}, sorted by distance, packed two ints per entry. */
    private static int[] buildOffsets(int r) {
        int count = 0;
        long[] keyed = new long[(2 * r + 1) * (2 * r + 1)];
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 <= r * r) {
                    // distance², then a stable tie-break, then the packed offset
                    keyed[count++] = ((long) d2 << 32) | (((dx + 128) & 0xFFFF) << 16) | ((dz + 128) & 0xFFFF);
                }
            }
        }
        long[] sorted = Arrays.copyOf(keyed, count);
        Arrays.sort(sorted);
        int[] out = new int[count * 2];
        for (int i = 0; i < count; i++) {
            out[2 * i] = (int) ((sorted[i] >> 16) & 0xFFFF) - 128;
            out[2 * i + 1] = (int) (sorted[i] & 0xFFFF) - 128;
        }
        return out;
    }

    /**
     * Runs until {@code budget} column reads are spent or the search ends.
     *
     * @return the state after this step
     */
    public State step(long budget) {
        long stop = reads + Math.max(1, budget);
        while (state == State.RUNNING && reads < stop) {
            if (next >= offsets.length / 2) {
                state = State.FAILED;
                break;
            }
            int dx = offsets[2 * next];
            int dz = offsets[2 * next + 1];
            next++;
            candidates++;
            Helipad zone = evaluate(centreX + dx, centreZ + dz);
            if (zone != null) {
                found = zone;
                foundDistance = Math.sqrt((double) dx * dx + (double) dz * dz);
                state = State.FOUND;
            }
        }
        return state;
    }

    public State state() {
        return state;
    }

    public @Nullable Helipad zone() {
        return found;
    }

    public @Nullable LandingZone result() {
        if (found == null) {
            return null;
        }
        BlockPos c = found.centre();
        return new LandingZone(c.getX(), c.getY(), c.getZ(), found.radius(), found.clearSectors(), foundDistance);
    }

    public long reads() {
        return reads;
    }

    public int candidates() {
        return candidates;
    }

    /** One line on why the candidates were refused, for logs and the command. */
    public String diagnostics() {
        return String.format("%d candidates, %d column reads; refused: %d unloaded, %d water, %d leaves,"
                + " %d uneven, %d obstructed, %d no approach, %d too high",
            candidates, reads, rejectedUnloaded, rejectedWet, rejectedLeaves, rejectedSlope, rejectedObstacle,
            rejectedApproach, rejectedElevation);
    }

    /** The reason most candidates failed, for the abort detail. */
    public String dominantRefusal() {
        int[] counts = {rejectedUnloaded, rejectedWet, rejectedLeaves, rejectedSlope, rejectedObstacle,
            rejectedApproach, rejectedElevation};
        String[] names = {"unloaded ground", "water", "trees", "uneven ground", "obstructions",
            "no clear approach", "too high"};
        int best = 0;
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] > counts[best]) {
                best = i;
            }
        }
        return counts[best] == 0 ? "no candidates" : names[best];
    }

    // ------------------------------------------------------------------ evaluation

    private @Nullable Helipad evaluate(int x, int z) {
        int r = spec.footprintRadius();
        // Centre first: rejects open water and canopy for one read.
        int centre = index(x, z);
        read(centre, x, z);
        if (!footprintColumnOk(centre)) {
            return null;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int i = index(x + dx, z + dz);
                read(i, x + dx, z + dz);
                if (!footprintColumnOk(i)) {
                    return null;
                }
                min = Math.min(min, surface[i]);
                max = Math.max(max, surface[i]);
            }
        }
        if (max - min > spec.maxSpread()) {
            rejectedSlope++;
            return null;
        }
        int elevation = max;
        if (elevation > spec.maxElevation()) {
            rejectedElevation++;
            return null;
        }
        int outer = r + spec.ringWidth();
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) <= r) {
                    continue;
                }
                int i = index(x + dx, z + dz);
                read(i, x + dx, z + dz);
                if ((flags[i] & UNLOADED) != 0 || surface[i] > elevation + 1) {
                    rejectedObstacle++;
                    return null;
                }
            }
        }
        int clear = 0;
        int clearCount = 0;
        for (int sector = 0; sector < SECTORS; sector++) {
            if (sectorClear(x, z, elevation, outer, sector)) {
                clear |= 1 << sector;
                clearCount++;
            }
        }
        if (clearCount < spec.minClearSectors()) {
            rejectedApproach++;
            return null;
        }
        // Helipad convention: centre is the surface block, elevation = centre.y + 1.
        return new Helipad("lz " + x + " " + z, new BlockPos(x, elevation - 1, z), r, clear);
    }

    private boolean footprintColumnOk(int i) {
        byte f = flags[i];
        if ((f & UNLOADED) != 0) {
            rejectedUnloaded++;
            return false;
        }
        if ((f & WET) != 0) {
            rejectedWet++;
            return false;
        }
        if ((f & LEAVES) != 0) {
            rejectedLeaves++;
            return false;
        }
        return true;
    }

    private boolean sectorClear(int x, int z, int elevation, int start, int sector) {
        double heading = Helipad.sectorHeading(sector);
        Vec3 origin = new Vec3(x + 0.5, 0, z + 0.5);
        Vec3 across = AutopilotMath.pointAlong(Vec3.ZERO, heading + 90.0, 1.0);
        for (int d = start + 1; d <= spec.approachLength(); d += STEP) {
            Vec3 point = AutopilotMath.pointAlong(origin, heading, d);
            double allowed = elevation + Math.max(1, Math.min(spec.clearHeight(), d - start));
            for (int lane = -1; lane <= 1; lane++) {
                int px = (int) Math.floor(point.x + across.x * lane * LANE_OFFSET);
                int pz = (int) Math.floor(point.z + across.z * lane * LANE_OFFSET);
                int i = index(px, pz);
                read(i, px, pz);
                if ((flags[i] & UNLOADED) != 0 || surface[i] > allowed) {
                    return false;
                }
            }
        }
        return true;
    }

    private int index(int x, int z) {
        int gx = x - centreX + half;
        int gz = z - centreZ + half;
        return gx * side + gz;
    }

    private void read(int i, int x, int z) {
        if (flags[i] != 0) {
            return;
        }
        reads++;
        if (!level.hasChunkAt(x, z)) {
            flags[i] = READ | UNLOADED;
            return;
        }
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        if (top <= level.getMinY()) {
            flags[i] = READ | UNLOADED;
            return;
        }
        byte f = READ;
        if (top != level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z)) {
            f |= WET;
        } else if (top != level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)) {
            f |= LEAVES;
        }
        surface[i] = top;
        flags[i] = f;
    }
}
