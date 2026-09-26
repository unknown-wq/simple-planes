package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Follows a {@link TaxiPlanner.Route} on the ground: pure pursuit for the heading, a speed limit
 * from the corners, the heading error and the distance left, and two guards that stop the aircraft
 * rather than let it drop off level ground or touch another aircraft.
 *
 * <p>Produces a commanded heading and speed each tick; {@code PlaneAutopilot} turns them into
 * nosewheel and throttle exactly as for any other ground phase. A speed of zero means brakes.
 */
final class TaxiDriver {

    enum Status {
        /** Rolling or turning along the route. */
        DRIVE,
        /** Stopped for traffic or ground ahead; {@link #holdReason} says which. */
        HOLD,
        /** The route no longer fits the world; plan again now. */
        REPLAN
    }

    private final Airfield airfield;
    private final TaxiPlanner.Dims dims;
    private final List<Vec3> points;
    private final double[] cumulative;
    private final double[] cornerSpeed;
    private final boolean stopAtEnd;
    final double plannedLength;

    private int segment;
    private double progress;
    private double crossTrack;

    /** Outputs. */
    double heading;
    double speed;

    int holdTicks;
    int stuckTicks;
    int movingTicks;
    @Nullable String holdReason;
    int blocker;

    TaxiDriver(Airfield airfield, TaxiPlanner.Dims dims, List<Vec3> points, double[] cornerSpeed,
               boolean stopAtEnd, double plannedLength) {
        this.airfield = airfield;
        this.dims = dims;
        this.points = List.copyOf(points);
        this.cornerSpeed = cornerSpeed.length == points.size() ? cornerSpeed : padded(cornerSpeed, points.size());
        this.stopAtEnd = stopAtEnd;
        this.plannedLength = plannedLength;
        this.cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            cumulative[i] = cumulative[i - 1] + AutopilotMath.horizontalDistance(points.get(i - 1), points.get(i));
        }
    }

    private static double[] padded(double[] speeds, int size) {
        double[] out = new double[size];
        java.util.Arrays.fill(out, AutopilotConfig.TAXI_SPEED);
        System.arraycopy(speeds, 0, out, 0, Math.min(speeds.length, size));
        return out;
    }

    double total() {
        return cumulative[cumulative.length - 1];
    }

    double remaining() {
        return Math.max(0.0, total() - progress);
    }

    double progress() {
        return progress;
    }

    double crossTrack() {
        return crossTrack;
    }

    Vec3 end() {
        return points.get(points.size() - 1);
    }

    /** The next {@code distance} blocks of the route, sampled every two blocks, for other planners. */
    List<Vec3> ahead(double distance) {
        List<Vec3> out = new ArrayList<>();
        for (double s = progress; s <= Math.min(total(), progress + distance); s += 2.0) {
            out.add(pointAt(s));
        }
        return out;
    }

    Vec3 pointAt(double s) {
        if (s <= 0) {
            return points.get(0);
        }
        for (int i = 1; i < points.size(); i++) {
            if (s <= cumulative[i]) {
                double length = cumulative[i] - cumulative[i - 1];
                double t = length < 1.0E-6 ? 1.0 : (s - cumulative[i - 1]) / length;
                Vec3 a = points.get(i - 1);
                Vec3 b = points.get(i);
                return new Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
            }
        }
        Vec3 last = points.get(points.size() - 1);
        if (points.size() >= 2) {
            double heading = AutopilotMath.headingTo(points.get(points.size() - 2), last);
            return AutopilotMath.pointAlong(last, heading, s - total());
        }
        return last;
    }

    private void project(Vec3 position) {
        double best = Double.MAX_VALUE;
        int bestSegment = segment;
        double bestProgress = progress;
        double bestCross = 0;
        int lastSegment = Math.min(points.size() - 2, segment + 3);
        for (int i = segment; i <= lastSegment; i++) {
            Vec3 a = points.get(i);
            Vec3 b = points.get(i + 1);
            double length = cumulative[i + 1] - cumulative[i];
            double abx = b.x - a.x;
            double abz = b.z - a.z;
            double t = length < 1.0E-6 ? 0.0
                : ((position.x - a.x) * abx + (position.z - a.z) * abz) / (length * length);
            t = Math.max(0.0, Math.min(1.0, t));
            double px = a.x + abx * t;
            double pz = a.z + abz * t;
            double distance = Math.sqrt((position.x - px) * (position.x - px) + (position.z - pz) * (position.z - pz));
            if (distance < best - 1.0E-6) {
                best = distance;
                bestSegment = i;
                bestProgress = cumulative[i] + length * t;
                bestCross = distance;
            }
        }
        segment = bestSegment;
        progress = Math.max(progress, bestProgress);
        crossTrack = bestCross;
    }

    /**
     * One tick of driving.
     *
     * @param holdsPriority true when this aircraft holds the runway reservation; others give way to it
     */
    Status tick(PlaneEntity plane, boolean holdsPriority) {
        Vec3 position = plane.position();
        double v = plane.getDeltaMovement().horizontalDistance();
        double yaw = plane.getYRot();
        if (points.size() < 2) {
            heading = yaw;
            speed = 0;
            return Status.DRIVE;
        }
        project(position);

        double lookahead = Math.max(2.0, Math.min(6.0, 1.5 + 0.5 * dims.turnRadius(Math.max(v, 0.05))));
        Vec3 target = pointAt(progress + lookahead);
        heading = AutopilotMath.headingTo(position, target);

        double limit = AutopilotConfig.TAXI_SPEED;
        for (int k = segment + 1; k < points.size() - 1; k++) {
            double distance = cumulative[k] - progress;
            if (distance > 30.0) {
                break;
            }
            double lead = dims.turnRadius(cornerSpeed[k]) * 0.5;
            limit = Math.min(limit, cornerSpeed[k]
                + AutopilotConfig.TAXI_BRAKE_RAMP * Math.max(0.0, distance - lead));
        }
        if (stopAtEnd) {
            limit = Math.min(limit, 0.02 + AutopilotConfig.TAXI_BRAKE_RAMP * Math.max(0.0, remaining() - 0.5));
        }
        double error = Math.abs(AutopilotMath.angleDelta(yaw, heading));
        if (error > 75.0) {
            limit = 0.0;
        } else if (error > 45.0) {
            limit = Math.min(limit, AutopilotConfig.TAXI_CREEP_SPEED);
        } else if (error > 20.0) {
            limit = Math.min(limit, 0.08);
        }
        if (crossTrack > 1.5) {
            limit = Math.min(limit, 0.08);
        }
        speed = limit;

        // ---- ground guard: never roll onto ground the route did not plan for
        double stopping = v / AutopilotConfig.TAXI_BRAKE_RAMP + 1.0;
        TaxiPlanner.Grid grid = TaxiPlanner.grid(plane.level(), airfield, List.of(position));
        if (grid != null && v > 0.02) {
            Vec3 motion = plane.getDeltaMovement();
            double mx = motion.x / v;
            double mz = motion.z / v;
            // Braking distance under the wheel brakes, about v / 0.12 blocks, plus half a block.
            double braking = v / 0.12 + 0.5;
            for (double d = 0.5; d <= braking; d += 0.5) {
                double x = position.x + mx * d;
                double z = position.z + mz * d;
                if (!grid.liveClear(x, z, dims.bboxHalf(), position.y)) {
                    return hold("uneven ground ahead", 0, holdTicks >= AutopilotConfig.TAXI_REPLAN_HOLD_TICKS);
                }
            }
            // The route itself, a little further on: terrain changed since it was planned.
            for (double d = 1.0; d <= stopping + 1.0 && (!stopAtEnd || d <= remaining()); d += 1.0) {
                Vec3 point = pointAt(progress + d);
                if (!grid.liveClear(point.x, point.z, dims.bboxHalf(), point.y)) {
                    TaxiPlanner.invalidate(plane.level(), airfield);
                    return hold("route no longer level", 0, true);
                }
            }
        }

        // ---- traffic guard
        String traffic = trafficConflict(plane, position, v, stopping, holdsPriority);
        if (traffic != null) {
            boolean stationary = blocker != 0;
            if (stationary && holdTicks >= AutopilotConfig.TAXI_REPLAN_HOLD_TICKS) {
                return hold(traffic, blocker, true);
            }
            return hold(traffic, blocker, false);
        }

        if (holdTicks > 0) {
            holdTicks = 0;
            holdReason = null;
        }
        // Stuck against something the guards do not see.
        if (speed >= 0.06 && v < 0.015) {
            if (++stuckTicks > 60) {
                stuckTicks = 0;
                holdReason = "not moving";
                return Status.REPLAN;
            }
        } else {
            stuckTicks = 0;
        }
        if (v > 0.02) {
            movingTicks++;
        }
        return Status.DRIVE;
    }

    private Status hold(String reason, int by, boolean replan) {
        speed = 0.0;
        holdTicks++;
        holdReason = reason;
        blocker = by;
        return replan ? Status.REPLAN : Status.HOLD;
    }

    /**
     * The first aircraft this one would come too close to on the next stretch of route, or null.
     * Sets {@link #blocker} to the id when that aircraft is standing still (so a new route around it
     * is worth planning), and to 0 when it is moving and this one is only giving way.
     */
    private @Nullable String trafficConflict(PlaneEntity plane, Vec3 position, double v, double stopping,
                                             boolean holdsPriority) {
        Level level = plane.level();
        double look = stopping + 2.0 + dims.sweep() * 0.5;
        List<Vec3> corridor = new ArrayList<>();
        for (double s = progress + 0.5; s <= progress + look; s += 0.5) {
            corridor.add(pointAt(Math.min(s, stopAtEnd ? total() : s)));
        }
        double wing = dims.sweep() + 0.5;
        double contact = dims.bboxHalf() + 0.3;
        for (PlaneEntity other : level.getEntitiesOfClass(PlaneEntity.class,
            plane.getBoundingBox().inflate(look + 16.0, 4.0, look + 16.0),
            p -> p != plane && p.isAlive() && !p.isRemoved())) {
            if (Math.abs(other.getY() - plane.getY()) > 3.0) {
                continue;
            }
            TaxiPlanner.Obstacle obstacle = TaxiPlanner.obstacle(other);
            double now = obstacle.distance(position.x, position.z);
            double otherSpeed = other.getDeltaMovement().horizontalDistance();
            boolean stationary = otherSpeed < 0.02;
            // Where it stands now.
            double nearest = Double.MAX_VALUE;
            for (Vec3 point : corridor) {
                nearest = Math.min(nearest, obstacle.distance(point.x, point.z));
            }
            boolean closing = nearest < now - 0.05;
            // Beside the start and the stand the planner allowed wings to overlap; only contact counts there.
            boolean relaxed = progress < AutopilotConfig.TAXI_RELAX_RADIUS + 1.0
                || (stopAtEnd && remaining() < AutopilotConfig.TAXI_RELAX_RADIUS + 1.0);
            if (nearest < contact || (nearest < wing && closing && !relaxed)) {
                blocker = stationary ? other.getId() : 0;
                return stationary ? "blocked by #" + other.getId() : "giving way to #" + other.getId();
            }
            if (stationary) {
                continue;
            }
            // Where it will be in the next second or two, for traffic that is rolling.
            if (!mustYield(plane, other, holdsPriority)) {
                continue;
            }
            Vec3 motion = other.getDeltaMovement();
            for (int k = 10; k <= 40; k += 10) {
                TaxiPlanner.Obstacle ahead = obstacle.shifted(motion.x * k, motion.z * k);
                for (Vec3 point : corridor) {
                    if (ahead.distance(point.x, point.z) < wing) {
                        blocker = 0;
                        return "giving way to #" + other.getId();
                    }
                }
            }
        }
        return null;
    }

    /** Right of way: the runway holder first, then anyone not on autopilot, then the lower id. */
    private boolean mustYield(PlaneEntity self, PlaneEntity other, boolean selfHoldsRunway) {
        if (selfHoldsRunway) {
            return false;
        }
        PlaneAutopilot autopilot = other.getAutopilot();
        if (autopilot == null || !autopilot.isActive()) {
            return true;
        }
        if (autopilot.holdsRunway(airfield.name())) {
            return true;
        }
        return other.getId() < self.getId();
    }
}
