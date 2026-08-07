package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the aircraft has been told to do. Fully codec-serialisable, so it round-trips through the
 * plane entity's save data and survives a server restart mid-flight.
 */
public class FlightPlan {

    public enum Kind {
        /** Fly the waypoint list, then land. */
        ROUTE,
        /** One-way run at a fixed point at maximum speed. */
        STRIKE;

        public static final Codec<Kind> CODEC = Codec.STRING.xmap(
            name -> "strike".equals(name) ? STRIKE : ROUTE,
            kind -> kind == STRIKE ? "strike" : "route");
    }

    public static final Codec<FlightPlan> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Kind.CODEC.fieldOf("kind").forGetter(plan -> plan.kind),
        BlockPos.CODEC.listOf().optionalFieldOf("waypoints", List.<BlockPos>of()).forGetter(plan -> plan.waypoints),
        Codec.INT.optionalFieldOf("index", 0).forGetter(plan -> plan.index),
        Codec.INT.optionalFieldOf("direction", 1).forGetter(plan -> plan.direction),
        Codec.INT.optionalFieldOf("legs_flown", 0).forGetter(plan -> plan.legsFlown),
        Codec.INT.optionalFieldOf("max_legs", 2).forGetter(plan -> plan.maxLegs),
        Codec.INT.optionalFieldOf("cruise_altitude", (int) AutopilotConfig.DEFAULT_CRUISE_ALTITUDE)
            .forGetter(plan -> plan.cruiseAltitude),
        Codec.STRING.optionalFieldOf("airfield").forGetter(plan -> Optional.ofNullable(plan.airfieldName)),
        BlockPos.CODEC.optionalFieldOf("strike_target").forGetter(plan -> Optional.ofNullable(plan.strikeTarget))
    ).apply(instance, FlightPlan::new));

    private final Kind kind;
    private final List<BlockPos> waypoints;
    private int index;
    private int direction;
    private int legsFlown;
    private final int maxLegs;
    private final int cruiseAltitude;
    private String airfieldName;
    private final BlockPos strikeTarget;

    public FlightPlan(Kind kind, List<BlockPos> waypoints, int index, int direction, int legsFlown, int maxLegs,
                      int cruiseAltitude, Optional<String> airfieldName, Optional<BlockPos> strikeTarget) {
        this.kind = kind;
        this.waypoints = List.copyOf(waypoints);
        this.index = index;
        this.direction = direction == 0 ? 1 : direction;
        this.legsFlown = legsFlown;
        this.maxLegs = maxLegs;
        this.cruiseAltitude = cruiseAltitude;
        this.airfieldName = airfieldName.orElse(null);
        this.strikeTarget = strikeTarget.orElse(null);
    }

    public static FlightPlan strike(BlockPos target) {
        return new FlightPlan(Kind.STRIKE, List.of(), 0, 1, 0, 0,
            (int) AutopilotConfig.DEFAULT_CRUISE_ALTITUDE, Optional.empty(), Optional.of(target));
    }

    public static FlightPlan route(List<BlockPos> waypoints, int cruiseAltitude, int maxLegs, String airfieldName) {
        return new FlightPlan(Kind.ROUTE, waypoints, 0, 1, 0, maxLegs, cruiseAltitude,
            Optional.ofNullable(airfieldName), Optional.empty());
    }

    public Kind kind() {
        return kind;
    }

    public List<BlockPos> waypoints() {
        return waypoints;
    }

    public int cruiseAltitude() {
        return cruiseAltitude;
    }

    public String airfieldName() {
        return airfieldName;
    }

    public void setAirfieldName(String airfieldName) {
        this.airfieldName = airfieldName;
    }

    public BlockPos strikeTarget() {
        return strikeTarget;
    }

    public Vec3 strikeTargetVec() {
        return strikeTarget == null ? null
            : new Vec3(strikeTarget.getX() + 0.5, strikeTarget.getY() + 0.5, strikeTarget.getZ() + 0.5);
    }

    public boolean hasWaypoints() {
        return !waypoints.isEmpty();
    }

    /** Current waypoint as a flyable point, at the plan's cruise altitude. */
    public Vec3 currentWaypoint() {
        if (waypoints.isEmpty()) {
            return null;
        }
        int clamped = Math.max(0, Math.min(index, waypoints.size() - 1));
        BlockPos pos = waypoints.get(clamped);
        return new Vec3(pos.getX() + 0.5, cruiseAltitude, pos.getZ() + 0.5);
    }

    /** Ground position of the current waypoint, ignoring the cruise altitude. */
    public Vec3 currentWaypointGround() {
        if (waypoints.isEmpty()) {
            return null;
        }
        int clamped = Math.max(0, Math.min(index, waypoints.size() - 1));
        BlockPos pos = waypoints.get(clamped);
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    /**
     * Marks the current waypoint as reached and steps to the next one, bouncing off the ends of the
     * list so a two-point route becomes A to B to A.
     *
     * @return true when the route is complete and the aircraft should proceed to land
     */
    public boolean advance() {
        if (waypoints.size() <= 1) {
            legsFlown++;
            return legsFlown >= Math.max(1, maxLegs);
        }
        int next = index + direction;
        if (next >= waypoints.size()) {
            direction = -1;
            next = waypoints.size() - 2;
            legsFlown++;
        } else if (next < 0) {
            direction = 1;
            next = 1;
            legsFlown++;
        }
        index = next;
        return maxLegs > 0 && legsFlown >= maxLegs;
    }

    public int legsFlown() {
        return legsFlown;
    }

    public int maxLegs() {
        return maxLegs;
    }

    /** Where the aircraft should try to land if no airfield was named. */
    public Vec3 fallbackLandingArea() {
        if (waypoints.isEmpty()) {
            return null;
        }
        BlockPos pos = waypoints.get(0);
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    public String describe() {
        if (kind == Kind.STRIKE) {
            return "strike -> " + (strikeTarget == null ? "?" : strikeTarget.toShortString());
        }
        List<String> parts = new ArrayList<>();
        for (BlockPos pos : waypoints) {
            parts.add(pos.toShortString());
        }
        return "route [" + String.join(" -> ", parts) + "] alt " + cruiseAltitude
            + (airfieldName == null ? ", improvised landing" : ", landing at " + airfieldName);
    }
}
