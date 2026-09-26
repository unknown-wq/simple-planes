package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.WeakHashMap;

/**
 * Ground route planner for taxiing: A* over a one-block grid of the airfield surface.
 *
 * <p>A cell is passable when every column within the aircraft's collision half-width plus
 * {@link AutopilotConfig#TAXI_TERRAIN_MARGIN} is solid, dry, has headroom and lies within
 * {@link AutopilotConfig#TAXI_MAX_STEP} of the centre column. Neighbouring cells may differ by at most
 * the same step, so a route never steps down into a pit or up a full block. Other aircraft are
 * obstacles sized by both wingspans. Everything here runs on the server thread; the sampled grid is
 * cached per airfield for {@link AutopilotConfig#TAXI_GRID_TTL} ticks.
 *
 * <p>See {@code design/TAXI.md} for the measurements behind the parameters.
 */
public final class TaxiPlanner {

    private TaxiPlanner() {
    }

    // ------------------------------------------------------------------ airframe geometry

    /**
     * What the planner needs to know about an airframe.
     *
     * @param bboxHalf     half the collision box width, the part that touches the ground and other boxes
     * @param halfLength   half the fuselage length
     * @param halfWidth    half the fuselage width
     * @param halfSpan     half the wingspan
     * @param takeOffSpeed ground steering reaches full authority at this speed
     * @param requiredRun  runway ahead of the entry point this airframe needs to depart
     */
    public record Dims(double bboxHalf, double halfLength, double halfWidth, double halfSpan,
                       double takeOffSpeed, double requiredRun) {

        /** Radius swept by the airframe as it turns: the larger of span and length. */
        public double sweep() {
            return Math.max(halfSpan, halfLength);
        }

        /** Terrain clearance radius. */
        public double body() {
            return bboxHalf + AutopilotConfig.TAXI_TERRAIN_MARGIN;
        }

        double wingHalfChord() {
            return Math.max(0.6, halfLength * 0.3);
        }

        /**
         * Ground turn radius at {@code speed}. {@code PlaneEntity#tickRoll} turns 3 deg/tick scaled by
         * speed / take-off speed with a floor of 0.2, so the radius is constant at 19.1 x take-off
         * speed above that floor and shrinks with speed below it.
         */
        public double turnRadius(double speed) {
            double floor = 0.2 * takeOffSpeed;
            if (speed >= floor) {
                return takeOffSpeed * 180.0 / (3.0 * Math.PI);
            }
            return speed / Math.toRadians(0.6);
        }
    }

    /** Geometry by airframe type; the collision box always comes from the entity itself. */
    public static Dims dims(PlaneEntity plane) {
        Dims table = dims(AircraftType.of(plane));
        return new Dims(plane.getBbWidth() / 2.0, table.halfLength, table.halfWidth, table.halfSpan,
            table.takeOffSpeed, table.requiredRun);
    }

    /**
     * Geometry by airframe type. Spans and lengths are from the models; the required run is the
     * measured distance from brake release to 5 blocks above the runway at full boosted throttle,
     * plus a quarter.
     */
    public static Dims dims(@Nullable AircraftType type) {
        if (type == null) {
            return new Dims(1.5, 4.0, 1.5, 5.0, 0.3, 50);
        }
        return switch (type) {
            case LARGE -> new Dims(1.5, 4.0, 1.0, 3.8, 0.3, 42);
            case CARGO -> new Dims(1.5, 6.5, 1.3, 8.5, 0.3, 45);
            case FIGHTER -> new Dims(1.5, 3.7, 0.8, 2.8, 0.45, 38);
            case AIRLINER -> new Dims(1.5, 5.85, 1.8, 6.6, 0.60, 53);
            case REGIONAL_AIRLINER -> new Dims(1.1, 6.0, 1.25, 5.0, 0.54, 50);
            case HELICOPTER -> new Dims(1.25, 4.0, 1.0, 4.0, 0.3, 50);
            default -> new Dims(1.25, 2.9, 0.8, 3.4, 0.3, 42);
        };
    }

    // ------------------------------------------------------------------ grid

    /** Sentinel ground height: no usable ground in this column. */
    private static final float NO_GROUND = Float.NEGATIVE_INFINITY;
    private static final byte PAVED = 2;
    private static final byte STRIP = 4;

    private static final Map<Level, Map<String, Grid>> GRIDS = new WeakHashMap<>();

    /** One airfield's surface, sampled lazily. */
    public static final class Grid {
        final Level level;
        final Airfield airfield;
        final int minX;
        final int minZ;
        final int sizeX;
        final int sizeZ;
        final int refY;
        final long built;
        private final float[] ground;
        private final byte[] kind;
        private final Map<Integer, byte[]> clearCache = new HashMap<>();

        Grid(Level level, Airfield airfield, int minX, int minZ, int maxX, int maxZ) {
            this.level = level;
            this.airfield = airfield;
            this.minX = minX;
            this.minZ = minZ;
            this.sizeX = maxX - minX + 1;
            this.sizeZ = maxZ - minZ + 1;
            this.refY = (int) Math.round((airfield.pointA().y + airfield.pointB().y) / 2.0);
            this.built = level.getGameTime();
            this.ground = new float[sizeX * sizeZ];
            this.kind = new byte[sizeX * sizeZ];
            java.util.Arrays.fill(ground, Float.NaN);
        }

        int size() {
            return ground.length;
        }

        boolean contains(int x, int z) {
            return x >= minX && z >= minZ && x < minX + sizeX && z < minZ + sizeZ;
        }

        boolean containsPoint(Vec3 point, int margin) {
            int x = (int) Math.floor(point.x);
            int z = (int) Math.floor(point.z);
            return contains(x - margin, z - margin) && contains(x + margin, z + margin);
        }

        int index(int x, int z) {
            return (z - minZ) * sizeX + (x - minX);
        }

        int indexOf(double x, double z) {
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            return contains(bx, bz) ? index(bx, bz) : -1;
        }

        int cellX(int index) {
            return minX + index % sizeX;
        }

        int cellZ(int index) {
            return minZ + index / sizeX;
        }

        double centreX(int index) {
            return cellX(index) + 0.5;
        }

        double centreZ(int index) {
            return cellZ(index) + 0.5;
        }

        float ground(int index) {
            float g = ground[index];
            if (Float.isNaN(g)) {
                sample(index);
                g = ground[index];
            }
            return g;
        }

        boolean walkable(int index) {
            return ground(index) != NO_GROUND;
        }

        boolean paved(int index) {
            ground(index);
            return (kind[index] & PAVED) != 0;
        }

        boolean strip(int index) {
            ground(index);
            return (kind[index] & STRIP) != 0;
        }

        private void sample(int index) {
            int x = cellX(index);
            int z = cellZ(index);
            Column column = column(level, x, z, refY);
            ground[index] = column.ground;
            byte flags = 1;
            if (column.paved) {
                flags |= PAVED;
            }
            if (airfield.isOnStrip(new Vec3(x + 0.5, 0, z + 0.5), 0.0)) {
                flags |= STRIP | PAVED;
            }
            kind[index] = flags;
        }

        /**
         * Whether a disc of {@code radius} round this cell's centre is all level ground: every
         * column it touches is walkable and within {@link AutopilotConfig#TAXI_MAX_STEP} of the centre.
         */
        public boolean clear(int index, double radius) {
            int key = (int) Math.round(radius * 4.0);
            byte[] cache = clearCache.computeIfAbsent(key, k -> new byte[ground.length]);
            byte cached = cache[index];
            if (cached != 0) {
                return cached == 1;
            }
            boolean ok = computeClear(index, key / 4.0);
            cache[index] = ok ? (byte) 1 : (byte) 2;
            return ok;
        }

        private boolean computeClear(int index, double radius) {
            float centre = ground(index);
            if (centre == NO_GROUND) {
                return false;
            }
            int x = cellX(index);
            int z = cellZ(index);
            int reach = (int) Math.ceil(radius + 0.5);
            double r2 = radius * radius;
            for (int dz = -reach; dz <= reach; dz++) {
                double ez = Math.max(Math.abs(dz) - 0.5, 0.0);
                for (int dx = -reach; dx <= reach; dx++) {
                    double ex = Math.max(Math.abs(dx) - 0.5, 0.0);
                    if (ex * ex + ez * ez > r2) {
                        continue;
                    }
                    if (!contains(x + dx, z + dz)) {
                        return false;
                    }
                    float g = ground(index(x + dx, z + dz));
                    if (g == NO_GROUND || Math.abs(g - centre) > AutopilotConfig.TAXI_MAX_STEP) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** {@link #clear} at a point, reading the terrain fresh rather than from the cache. */
        boolean liveClear(double x, double z, double radius, double expectedGround) {
            double[][] probes = {{0, 0}, {radius, 0}, {-radius, 0}, {0, radius}, {0, -radius}};
            for (double[] probe : probes) {
                Column column = column(level, (int) Math.floor(x + probe[0]), (int) Math.floor(z + probe[1]), refY);
                if (column.ground == NO_GROUND
                    || Math.abs(column.ground - expectedGround) > AutopilotConfig.TAXI_MAX_STEP + 0.1) {
                    return false;
                }
            }
            return true;
        }

        double groundAt(double x, double z, double fallback) {
            int index = indexOf(x, z);
            if (index < 0) {
                return fallback;
            }
            float g = ground(index);
            return g == NO_GROUND ? fallback : g;
        }
    }

    private record Column(float ground, boolean paved) {
    }

    /**
     * Top of the ground an aircraft would roll on in this column: the highest block with a collision
     * shape no more than 8 blocks above the runway, with headroom above it. Liquid on top, an
     * unloaded chunk or no floor within 8 blocks below the runway reads as no ground.
     */
    private static Column column(Level level, int x, int z, int refY) {
        int top = TerrainScanner.surfaceHeight(level, x + 0.5, z + 0.5);
        if (top == TerrainScanner.UNKNOWN_HEIGHT) {
            return new Column(NO_GROUND, false);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int from = Math.min(top, refY + 8) - 1;
        for (int y = from; y >= refY - 8; y--) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) {
                return new Column(NO_GROUND, false);
            }
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                continue;
            }
            double surface = y + shape.max(Direction.Axis.Y);
            if (y + 1 < top) {
                for (int h = 1; h <= 3; h++) {
                    pos.set(x, y + h, z);
                    if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                        return new Column(NO_GROUND, false);
                    }
                }
            }
            boolean soil = state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL);
            return new Column((float) surface, !soil);
        }
        return new Column(NO_GROUND, false);
    }

    /** The cached grid for an airfield, rebuilt when stale or when a point falls outside it. */
    public static @Nullable Grid grid(Level level, Airfield airfield, List<Vec3> points) {
        Map<String, Grid> byName = GRIDS.computeIfAbsent(level, l -> new HashMap<>());
        Grid cached = byName.get(airfield.name());
        long now = level.getGameTime();
        if (cached != null && cached.airfield.equals(airfield)
            && now - cached.built < AutopilotConfig.TAXI_GRID_TTL && now >= cached.built) {
            boolean inside = true;
            for (Vec3 point : points) {
                if (!cached.containsPoint(point, 8)) {
                    inside = false;
                    break;
                }
            }
            if (inside) {
                return cached;
            }
        }
        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        List<Vec3> extent = new ArrayList<>(points);
        double heading = AutopilotMath.headingTo(airfield.pointA(), airfield.pointB());
        double half = airfield.width() / 2.0;
        for (Vec3 end : new Vec3[] {airfield.pointA(), airfield.pointB()}) {
            extent.add(AutopilotMath.pointAlong(end, heading + 90.0, half));
            extent.add(AutopilotMath.pointAlong(end, heading - 90.0, half));
        }
        for (BlockPos stand : airfield.parkingSpots()) {
            extent.add(new Vec3(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5));
        }
        for (Vec3 point : extent) {
            minX = Math.min(minX, point.x);
            minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x);
            maxZ = Math.max(maxZ, point.z);
        }
        int margin = AutopilotConfig.TAXI_GRID_MARGIN;
        int x0 = (int) Math.floor(minX) - margin;
        int z0 = (int) Math.floor(minZ) - margin;
        int x1 = (int) Math.floor(maxX) + margin;
        int z1 = (int) Math.floor(maxZ) + margin;
        if (x1 - x0 + 1 > AutopilotConfig.TAXI_GRID_MAX_SIDE || z1 - z0 + 1 > AutopilotConfig.TAXI_GRID_MAX_SIDE) {
            return null;
        }
        Grid grid = new Grid(level, airfield, x0, z0, x1, z1);
        byName.put(airfield.name(), grid);
        return grid;
    }

    /** Drops the cached grid, after the driver found the ground not as sampled. */
    public static void invalidate(Level level, Airfield airfield) {
        Map<String, Grid> byName = GRIDS.get(level);
        if (byName != null) {
            byName.remove(airfield.name());
        }
    }

    // ------------------------------------------------------------------ traffic

    /**
     * Another aircraft as an obstacle: a hull rectangle and a wing rectangle about its centre.
     *
     * @param id     entity id, or -1 for a booked stand whose aircraft is not loaded
     * @param moving rolling now; its wings are avoided by cost rather than by rule
     * @param future the part of its own taxi route it will drive next, if it is taxiing on autopilot
     */
    public record Obstacle(int id, double x, double z, double fx, double fz, double halfLength,
                           double halfWidth, double halfSpan, double wingHalfChord, boolean moving,
                           List<Vec3> future) {

        /** Horizontal distance from a point to the nearest part of this aircraft's planform. */
        public double distance(double px, double pz) {
            double dx = px - x;
            double dz = pz - z;
            double along = dx * fx + dz * fz;
            double side = -dx * fz + dz * fx;
            return Math.min(rectDistance(along, side, halfLength, halfWidth),
                rectDistance(along, side, wingHalfChord, halfSpan));
        }

        /** The same aircraft moved by {@code (dx, dz)}. */
        Obstacle shifted(double dx, double dz) {
            return new Obstacle(id, x + dx, z + dz, fx, fz, halfLength, halfWidth, halfSpan,
                wingHalfChord, moving, future);
        }

        private static double rectDistance(double a, double b, double ha, double hb) {
            double da = Math.max(Math.abs(a) - ha, 0.0);
            double db = Math.max(Math.abs(b) - hb, 0.0);
            return Math.sqrt(da * da + db * db);
        }
    }

    /** Builds the obstacle for one aircraft. */
    public static Obstacle obstacle(PlaneEntity plane) {
        Dims dims = dims(plane);
        Vec3 forward = AutopilotMath.pointAlong(Vec3.ZERO, plane.getYRot(), 1.0);
        PlaneAutopilot autopilot = plane.getAutopilot();
        List<Vec3> future = autopilot == null ? List.of() : autopilot.taxiPathAhead(24.0);
        boolean moving = plane.getDeltaMovement().horizontalDistance() > 0.03 || !future.isEmpty();
        return new Obstacle(plane.getId(), plane.getX(), plane.getZ(), forward.x, forward.z,
            Math.max(dims.halfLength, dims.bboxHalf), Math.max(dims.halfWidth, dims.bboxHalf),
            dims.halfSpan, dims.wingHalfChord(), moving, future);
    }

    /**
     * Every aircraft on the ground at this airfield except {@code self}, plus a placeholder for each
     * booked stand whose aircraft is not loaded.
     */
    public static List<Obstacle> traffic(Level level, Grid grid, @Nullable PlaneEntity self) {
        AABB box = new AABB(grid.minX, grid.refY - 8, grid.minZ,
            grid.minX + grid.sizeX, grid.refY + 12, grid.minZ + grid.sizeZ);
        List<Obstacle> obstacles = new ArrayList<>();
        for (PlaneEntity plane : level.getEntitiesOfClass(PlaneEntity.class, box,
            p -> p != self && p.isAlive() && !p.isRemoved())) {
            double ground = grid.groundAt(plane.getX(), plane.getZ(), plane.getY());
            if (plane.getY() - ground > 3.0) {
                continue;
            }
            obstacles.add(obstacle(plane));
        }
        Airfield airfield = grid.airfield;
        for (BlockPos stand : airfield.parkingSpots()) {
            if (StandOccupancy.heldBy(level, airfield.name(), stand) == null) {
                continue;
            }
            double sx = stand.getX() + 0.5;
            double sz = stand.getZ() + 0.5;
            boolean seen = false;
            for (Obstacle obstacle : obstacles) {
                if (Math.abs(obstacle.x - sx) < 3.0 && Math.abs(obstacle.z - sz) < 3.0) {
                    seen = true;
                    break;
                }
            }
            if (self != null && Math.abs(self.getX() - sx) < 3.0 && Math.abs(self.getZ() - sz) < 3.0) {
                seen = true;
            }
            if (!seen) {
                obstacles.add(new Obstacle(-1, sx, sz, 0, 1, 5.0, 5.0, 5.0, 5.0, false, List.of()));
            }
        }
        return obstacles;
    }

    /** Obstacles rasterised for one airframe: hard cells (1 wing, 2 contact) and soft costs. */
    private static final class Field {
        final byte[] hard;
        final float[] soft;
        final int[] owner;

        Field(int size) {
            hard = new byte[size];
            soft = new float[size];
            owner = new int[size];
        }
    }

    private static Field rasterise(Grid grid, Dims me, List<Obstacle> obstacles) {
        Field field = new Field(grid.size());
        double contact = me.bboxHalf + 0.3;
        double wing = me.sweep() + AutopilotConfig.TAXI_WING_MARGIN;
        double soft = wing + AutopilotConfig.TAXI_TRAFFIC_SOFT_BAND;
        for (Obstacle obstacle : obstacles) {
            double reach = Math.max(obstacle.halfLength, obstacle.halfSpan) + soft + 1.0;
            int x0 = (int) Math.floor(obstacle.x - reach);
            int x1 = (int) Math.floor(obstacle.x + reach);
            int z0 = (int) Math.floor(obstacle.z - reach);
            int z1 = (int) Math.floor(obstacle.z + reach);
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    if (!grid.contains(x, z)) {
                        continue;
                    }
                    int index = grid.index(x, z);
                    double distance = obstacle.distance(x + 0.5, z + 0.5);
                    if (distance < contact) {
                        field.hard[index] = 2;
                        field.owner[index] = obstacle.id;
                    } else if (distance < wing) {
                        if (obstacle.moving) {
                            field.soft[index] += 6.0f;
                        } else if (field.hard[index] == 0) {
                            field.hard[index] = 1;
                            field.owner[index] = obstacle.id;
                        }
                    } else if (distance < soft) {
                        field.soft[index] += (float) (AutopilotConfig.TAXI_TRAFFIC_SOFT_COST
                            * (1.0 - (distance - wing) / AutopilotConfig.TAXI_TRAFFIC_SOFT_BAND));
                    }
                }
            }
            // Where a taxiing aircraft is about to be.
            double band = me.sweep() + obstacle.halfSpan;
            for (Vec3 point : obstacle.future) {
                int r = (int) Math.ceil(band);
                int cx = (int) Math.floor(point.x);
                int cz = (int) Math.floor(point.z);
                for (int dz = -r; dz <= r; dz += 1) {
                    for (int dx = -r; dx <= r; dx += 1) {
                        if (dx * dx + dz * dz > band * band || !grid.contains(cx + dx, cz + dz)) {
                            continue;
                        }
                        int index = grid.index(cx + dx, cz + dz);
                        field.soft[index] = Math.min(field.soft[index] + 0.5f, 12.0f);
                    }
                }
            }
        }
        return field;
    }

    // ------------------------------------------------------------------ search

    /**
     * Somewhere a route may end: a segment of runway centreline (any point on it, with a cost per
     * block along it) or a single stand.
     *
     * @param costPerAlong planner cost per block from {@code a} towards {@code b}
     * @param stand        the marked stand this goal is, or null for a runway entry
     */
    public record Goal(Vec3 a, Vec3 b, double costPerAlong, @Nullable BlockPos stand) {
    }

    /**
     * A planned route.
     *
     * @param points      from the aircraft's position to the goal point, smoothed
     * @param cornerSpeed highest speed each vertex of {@code points} can be turned through with the
     *                    swept arc still on clear ground
     * @param goal        which of the requested goals it reaches
     * @param end         the exact goal point (on the centreline, or the stand)
     */
    public record Route(List<Vec3> points, double[] cornerSpeed, double length, int goal, Vec3 end,
                        int expansions) {
    }

    /**
     * The result of a search.
     *
     * @param problem why there is no route, for a report; null with a route
     * @param blocker id of the aircraft in the way when traffic alone is the reason, else 0
     */
    public record Plan(@Nullable Route route, @Nullable String problem, int blocker) {

        static Plan none(String problem) {
            return new Plan(null, problem, 0);
        }
    }

    /**
     * Plans a route for {@code self} (or a planned airframe of {@code dims} when null) from
     * {@code from} to the nearest of {@code goals}.
     *
     * @param arrival true to charge {@link AutopilotConfig#TAXI_ARRIVAL_RUNWAY_COST} on the strip
     */
    public static Plan plan(Level level, Airfield airfield, @Nullable PlaneEntity self, Dims dims,
                            Vec3 from, List<Goal> goals, boolean arrival) {
        if (goals.isEmpty()) {
            return Plan.none("nowhere to go");
        }
        List<Vec3> points = new ArrayList<>();
        points.add(from);
        for (Goal goal : goals) {
            points.add(goal.a);
            points.add(goal.b);
        }
        Grid grid = grid(level, airfield, points);
        if (grid == null) {
            return Plan.none("too far from the runway to plan");
        }
        List<Obstacle> obstacles = traffic(level, grid, self);
        Field field = rasterise(grid, dims, obstacles);
        Search search = new Search(grid, dims, from, goals, field, arrival, true);
        Route route = search.run();
        if (route != null) {
            return new Plan(route, null, 0);
        }
        // Would it go without the traffic? Then an aircraft is the reason, and which one is worth saying.
        Search terrainOnly = new Search(grid, dims, from, goals, field, arrival, false);
        Route open = terrainOnly.run();
        if (open == null) {
            return Plan.none(terrainOnly.startBlocked ? "standing where it cannot move without dropping"
                : "no level ground route");
        }
        int blocker = 0;
        for (Vec3 point : open.points) {
            int index = grid.indexOf(point.x, point.z);
            if (index >= 0 && field.hard[index] != 0) {
                blocker = field.owner[index];
                break;
            }
        }
        if (blocker == 0) {
            // The smoothed line skipped the cell; any obstacle hard cell next to the open route will do.
            outer:
            for (int i = 1; i < open.points.size(); i++) {
                Vec3 a = open.points.get(i - 1);
                Vec3 b = open.points.get(i);
                double length = AutopilotMath.horizontalDistance(a, b);
                for (double t = 0; t <= length; t += 0.5) {
                    double f = length < 1.0E-6 ? 0 : t / length;
                    int index = grid.indexOf(a.x + (b.x - a.x) * f, a.z + (b.z - a.z) * f);
                    if (index >= 0 && field.hard[index] != 0) {
                        blocker = field.owner[index];
                        break outer;
                    }
                }
            }
        }
        return new Plan(null, blocker == -1 ? "blocked by an aircraft on a booked stand"
            : blocker != 0 ? "blocked by #" + blocker : "blocked by traffic", blocker);
    }

    /** Whether any route exists from {@code from} to one of {@code goals}, ignoring traffic. */
    public static boolean reachable(Level level, Airfield airfield, Dims dims, Vec3 from, List<Goal> goals) {
        List<Vec3> points = new ArrayList<>();
        points.add(from);
        for (Goal goal : goals) {
            points.add(goal.a);
            points.add(goal.b);
        }
        Grid grid = grid(level, airfield, points);
        if (grid == null) {
            return false;
        }
        return new Search(grid, dims, from, goals, new Field(grid.size()), false, false).run() != null;
    }

    /** Runway entry goal for departing from {@code end}: centreline from the threshold to the last usable entry. */
    public static Goal entryGoal(RunwayEnd end, Dims dims) {
        double length = end.length();
        double last = length - dims.requiredRun - AutopilotConfig.TAXI_LINEUP_ALLOWANCE;
        double heading = end.landingHeading();
        Vec3 a = end.threshold();
        Vec3 b = last <= 0 ? a : AutopilotMath.pointAlong(a, heading, last);
        return new Goal(a, new Vec3(b.x, a.y, b.z), AutopilotConfig.TAXI_ENTRY_COST, null);
    }

    /** The whole centreline, either direction, for "where is the runway nearest". */
    public static Goal centrelineGoal(Airfield airfield) {
        return new Goal(airfield.pointA(), airfield.pointB(), 0.0, null);
    }

    public static Goal standGoal(Vec3 position, BlockPos stand) {
        return new Goal(position, position, 0.0, stand);
    }

    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final double DIAGONAL = Math.sqrt(2.0);

    private static final class Search {
        final Grid grid;
        final Dims dims;
        final Vec3 from;
        final List<Goal> goals;
        final Field field;
        final boolean arrival;
        final boolean withTraffic;
        final int startIndex;
        final int n;
        final int[] goalOf;
        final float[] goalCost;
        boolean startBlocked;
        int expansions;

        Search(Grid grid, Dims dims, Vec3 from, List<Goal> goals, Field field, boolean arrival,
               boolean withTraffic) {
            this.grid = grid;
            this.dims = dims;
            this.from = from;
            this.goals = goals;
            this.field = field;
            this.arrival = arrival;
            this.withTraffic = withTraffic;
            this.n = grid.size();
            this.startIndex = grid.indexOf(from.x, from.z);
            this.goalOf = new int[n];
            this.goalCost = new float[n];
            java.util.Arrays.fill(goalOf, -1);
            for (int k = 0; k < goals.size(); k++) {
                Goal goal = goals.get(k);
                double length = AutopilotMath.horizontalDistance(goal.a, goal.b);
                int steps = Math.max(1, (int) Math.ceil(length / 0.5));
                for (int i = 0; i <= steps; i++) {
                    double t = (double) i / steps;
                    int index = grid.indexOf(goal.a.x + (goal.b.x - goal.a.x) * t,
                        goal.a.z + (goal.b.z - goal.a.z) * t);
                    if (index < 0 || goalOf[index] >= 0) {
                        continue;
                    }
                    goalOf[index] = k;
                    goalCost[index] = (float) (goal.costPerAlong * length * t);
                }
            }
        }

        boolean relaxed(int index) {
            double x = grid.centreX(index);
            double z = grid.centreZ(index);
            double r2 = AutopilotConfig.TAXI_RELAX_RADIUS * AutopilotConfig.TAXI_RELAX_RADIUS;
            if (sq(x - from.x) + sq(z - from.z) <= r2) {
                return true;
            }
            for (Goal goal : goals) {
                if (goal.stand != null && sq(x - goal.a.x) + sq(z - goal.a.z) <= r2) {
                    return true;
                }
            }
            return false;
        }

        boolean passable(int index) {
            if (index == startIndex) {
                return grid.walkable(index);
            }
            boolean relaxed = relaxed(index);
            if (!grid.clear(index, relaxed ? dims.bboxHalf : dims.body())) {
                return false;
            }
            if (withTraffic) {
                byte hard = field.hard[index];
                if (hard == 2 || (hard == 1 && !relaxed)) {
                    return false;
                }
            }
            return true;
        }

        double cost(int index) {
            double cost = 1.0;
            if (!grid.paved(index)) {
                cost += AutopilotConfig.TAXI_UNPAVED_COST;
            }
            if (arrival && grid.strip(index)) {
                cost += AutopilotConfig.TAXI_ARRIVAL_RUNWAY_COST;
            }
            if (!grid.clear(index, dims.body() + AutopilotConfig.TAXI_EDGE_BAND)) {
                cost += AutopilotConfig.TAXI_EDGE_COST;
            }
            if (withTraffic) {
                cost += field.soft[index];
                if (field.hard[index] == 1) {
                    cost += 8.0;
                }
            }
            return cost;
        }

        double heuristic(int index) {
            double x = grid.centreX(index);
            double z = grid.centreZ(index);
            double best = Double.MAX_VALUE;
            for (Goal goal : goals) {
                best = Math.min(best, segmentDistance(x, z, goal.a, goal.b));
            }
            return Math.max(0.0, best - 0.75);
        }

        boolean step(int a, int b) {
            return Math.abs(grid.ground(a) - grid.ground(b)) <= AutopilotConfig.TAXI_MAX_STEP;
        }

        @Nullable Route run() {
            if (startIndex < 0) {
                return null;
            }
            if (!grid.walkable(startIndex)) {
                startBlocked = true;
                return null;
            }
            float[] g = new float[n];
            java.util.Arrays.fill(g, Float.POSITIVE_INFINITY);
            int[] parent = new int[n];
            byte[] closed = new byte[n];
            PriorityQueue<Long> open = new PriorityQueue<>();
            g[startIndex] = 0;
            parent[startIndex] = -1;
            open.add(key(heuristic(startIndex), startIndex));
            int reached = -1;
            boolean anyNeighbour = false;
            while (!open.isEmpty()) {
                long entry = open.poll();
                int node = (int) (entry & 0xFFFFFFFFL);
                if (node >= n) {
                    reached = node - n;
                    break;
                }
                if (closed[node] != 0) {
                    continue;
                }
                closed[node] = 1;
                if (++expansions > AutopilotConfig.TAXI_MAX_EXPANSIONS) {
                    break;
                }
                if (goalOf[node] >= 0) {
                    open.add(key(g[node] + goalCost[node], n + node));
                }
                int x = grid.cellX(node);
                int z = grid.cellZ(node);
                for (int d = 0; d < 8; d++) {
                    int nx = x + DX[d];
                    int nz = z + DZ[d];
                    if (!grid.contains(nx, nz)) {
                        continue;
                    }
                    int next = grid.index(nx, nz);
                    if (closed[next] != 0 || !passable(next) || !step(node, next)) {
                        continue;
                    }
                    if (d >= 4) {
                        int sideA = grid.index(nx, z);
                        int sideB = grid.index(x, nz);
                        if (!passable(sideA) || !passable(sideB)) {
                            continue;
                        }
                    }
                    if (node == startIndex) {
                        anyNeighbour = true;
                    }
                    double tentative = g[node] + (d >= 4 ? DIAGONAL : 1.0) * cost(next);
                    if (tentative < g[next]) {
                        g[next] = (float) tentative;
                        parent[next] = node;
                        open.add(key(tentative + heuristic(next), next));
                    }
                }
            }
            if (reached < 0) {
                if (!anyNeighbour && goalOf[startIndex] < 0) {
                    startBlocked = true;
                }
                return null;
            }
            List<Integer> cells = new ArrayList<>();
            for (int node = reached; node >= 0; node = parent[node]) {
                cells.add(0, node);
            }
            return build(cells, g, reached);
        }

        private Route build(List<Integer> cells, float[] g, int reached) {
            int goalIndex = goalOf[reached];
            Goal goal = goals.get(goalIndex);
            Vec3 end;
            if (goal.stand != null) {
                end = goal.a;
            } else {
                Vec3 on = AutopilotMath.closestPointOnSegment(goal.a, goal.b,
                    new Vec3(grid.centreX(reached), goal.a.y, grid.centreZ(reached)));
                end = new Vec3(on.x, grid.groundAt(on.x, on.z, goal.a.y), on.z);
            }
            List<Vec3> points = new ArrayList<>();
            points.add(from);
            int i = 0;
            int last = cells.size() - 1;
            while (i < last) {
                int best = i + 1;
                for (int j = Math.min(last, i + 96); j > i + 1; j--) {
                    if (lineCost(cells.get(i), cells.get(j)) <= g[cells.get(j)] - g[cells.get(i)] + 0.05) {
                        best = j;
                        break;
                    }
                }
                if (best == last) {
                    break;
                }
                int cell = cells.get(best);
                points.add(new Vec3(grid.centreX(cell), grid.ground(cell), grid.centreZ(cell)));
                i = best;
            }
            points.add(end);
            // A goal point off the cell centre could hide a last-leg problem; walk it.
            if (points.size() >= 2 && !segmentOpen(points.get(points.size() - 2), end)) {
                int cell = cells.get(last);
                points.add(points.size() - 1, new Vec3(grid.centreX(cell), grid.ground(cell), grid.centreZ(cell)));
            }
            double length = 0;
            for (int k = 1; k < points.size(); k++) {
                length += AutopilotMath.horizontalDistance(points.get(k - 1), points.get(k));
            }
            return new Route(List.copyOf(points), cornerSpeeds(points), length, goalIndex, end, expansions);
        }

        /** Planner cost of the straight line between two cell centres, or infinity if it is not open. */
        private double lineCost(int a, int b) {
            double ax = grid.centreX(a);
            double az = grid.centreZ(a);
            double bx = grid.centreX(b);
            double bz = grid.centreZ(b);
            double length = Math.sqrt(sq(bx - ax) + sq(bz - az));
            int steps = Math.max(1, (int) Math.ceil(length / 0.25));
            double stepLength = length / steps;
            double total = 0;
            int previous = a;
            for (int s = 1; s <= steps; s++) {
                double t = (double) s / steps;
                int index = grid.indexOf(ax + (bx - ax) * t, az + (bz - az) * t);
                if (index < 0) {
                    return Double.POSITIVE_INFINITY;
                }
                if (index != previous) {
                    if (!passable(index) || !step(previous, index)) {
                        return Double.POSITIVE_INFINITY;
                    }
                    // A line through a cell corner must not slip between two blocked cells.
                    int px = grid.cellX(previous);
                    int pz = grid.cellZ(previous);
                    int cx = grid.cellX(index);
                    int cz = grid.cellZ(index);
                    if (px != cx && pz != cz
                        && (!passable(grid.index(cx, pz)) || !passable(grid.index(px, cz)))) {
                        return Double.POSITIVE_INFINITY;
                    }
                    previous = index;
                }
                total += stepLength * cost(index);
            }
            return total;
        }

        private boolean segmentOpen(Vec3 a, Vec3 b) {
            double length = AutopilotMath.horizontalDistance(a, b);
            int steps = Math.max(1, (int) Math.ceil(length / 0.25));
            int previous = grid.indexOf(a.x, a.z);
            for (int s = 1; s <= steps; s++) {
                double t = (double) s / steps;
                int index = grid.indexOf(a.x + (b.x - a.x) * t, a.z + (b.z - a.z) * t);
                if (index < 0) {
                    return false;
                }
                if (index != previous) {
                    if (!passable(index) || (previous >= 0 && !step(previous, index))) {
                        return false;
                    }
                    previous = index;
                }
            }
            return true;
        }

        /**
         * Highest speed at which each vertex can be turned through: the arc of the turn radius at that
         * speed, tangent to both legs, has to fit on the legs and lie on clear ground away from traffic.
         */
        private double[] cornerSpeeds(List<Vec3> points) {
            double[] speeds = new double[points.size()];
            java.util.Arrays.fill(speeds, AutopilotConfig.TAXI_SPEED);
            double[] candidates = {AutopilotConfig.TAXI_SPEED, 0.15, 0.10, 0.06, AutopilotConfig.TAXI_CREEP_SPEED};
            for (int k = 1; k < points.size() - 1; k++) {
                Vec3 a = points.get(k - 1);
                Vec3 v = points.get(k);
                Vec3 b = points.get(k + 1);
                double inHeading = AutopilotMath.headingTo(a, v);
                double outHeading = AutopilotMath.headingTo(v, b);
                double deflection = Math.abs(AutopilotMath.angleDelta(inHeading, outHeading));
                if (deflection < 15.0) {
                    continue;
                }
                double legs = Math.min(AutopilotMath.horizontalDistance(a, v), AutopilotMath.horizontalDistance(v, b));
                speeds[k] = AutopilotConfig.TAXI_CREEP_SPEED;
                for (double speed : candidates) {
                    double radius = dims.turnRadius(speed);
                    double tangent = radius * Math.tan(Math.toRadians(deflection) / 2.0);
                    if (tangent > legs + 0.5 && speed > AutopilotConfig.TAXI_CREEP_SPEED) {
                        continue;
                    }
                    if (arcOpen(v, inHeading, outHeading, deflection, radius, tangent)) {
                        speeds[k] = speed;
                        break;
                    }
                }
            }
            return speeds;
        }

        private boolean arcOpen(Vec3 vertex, double inHeading, double outHeading, double deflection,
                                double radius, double tangent) {
            Vec3 start = AutopilotMath.pointAlong(vertex, inHeading + 180.0, tangent);
            double sign = Math.signum(AutopilotMath.angleDelta(inHeading, outHeading));
            Vec3 centre = AutopilotMath.pointAlong(start, inHeading + 90.0 * sign, radius);
            double startBearing = AutopilotMath.headingTo(centre, start);
            int steps = Math.max(2, (int) Math.ceil(Math.toRadians(deflection) * radius / 0.5));
            for (int s = 0; s <= steps; s++) {
                double bearing = startBearing + sign * deflection * s / steps;
                Vec3 point = AutopilotMath.pointAlong(centre, bearing, radius);
                int index = grid.indexOf(point.x, point.z);
                if (index < 0) {
                    return false;
                }
                boolean relaxed = relaxed(index);
                if (index != startIndex && !grid.clear(index, relaxed ? dims.bboxHalf : dims.bboxHalf + 0.25)) {
                    return false;
                }
                if (withTraffic && field.hard[index] != 0 && !relaxed) {
                    return false;
                }
            }
            return true;
        }
    }

    private static long key(double f, int node) {
        return ((long) Float.floatToIntBits((float) Math.max(0.0, f)) << 32) | (node & 0xFFFFFFFFL);
    }

    private static double sq(double v) {
        return v * v;
    }

    static double segmentDistance(double x, double z, Vec3 a, Vec3 b) {
        double abx = b.x - a.x;
        double abz = b.z - a.z;
        double length2 = abx * abx + abz * abz;
        double t = length2 < 1.0E-9 ? 0 : ((x - a.x) * abx + (z - a.z) * abz) / length2;
        t = Math.max(0, Math.min(1, t));
        double px = a.x + abx * t;
        double pz = a.z + abz * t;
        return Math.sqrt(sq(x - px) + sq(z - pz));
    }
}
