package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the aircraft has been told to do. Fully codec-serialisable, so it round-trips through the
 * plane entity's save data and survives a server restart mid-flight.
 */
public class FlightPlan {

    public enum Kind {
        /** Fly the waypoint list, then land. */
        ROUTE,
        /** One-way run at a fixed point at maximum speed. */
        STRIKE,
        /**
         * Helipad to helipad, flown by {@link HelicopterAutopilot}.
         *
         * <p>Its own kind rather than a {@link #ROUTE} with a rotorcraft aboard, because the kind is
         * what {@code PlaneAutopilot#start} and {@code PlaneAutopilot#load} branch on to decide which
         * flight director takes the aircraft — and a plan reloaded off disk has no aircraft to look
         * at yet. Reading the entity type instead would also mean a helicopter a player left lying
         * about with a fixed-wing route on it silently became a rotorcraft flight.
         */
        HELI;

        // Unknown names fall back to ROUTE, which is what every plan written before STRIKE existed
        // relied on and what keeps this codec able to read a save older than the current build.
        public static final Codec<Kind> CODEC = Codec.STRING.xmap(
            name -> switch (name) {
                case "strike" -> STRIKE;
                case "heli" -> HELI;
                default -> ROUTE;
            },
            kind -> switch (kind) {
                case STRIKE -> "strike";
                case HELI -> "heli";
                default -> "route";
            });
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
        BlockPos.CODEC.optionalFieldOf("strike_target").forGetter(plan -> Optional.ofNullable(plan.strikeTarget)),
        Codec.STRING.optionalFieldOf("departure").forGetter(plan -> Optional.ofNullable(plan.departureAirfield)),
        // Both new fields are optional with the historic value as the default, so a flight plan
        // written by an older build loads unchanged rather than failing the codec.
        Blast.CODEC.optionalFieldOf("blast", Blast.DEFAULT).forGetter(plan -> plan.blast),
        Codec.DOUBLE.optionalFieldOf("cruise_speed", AutopilotConfig.CRUISE_SPEED)
            .forGetter(plan -> plan.cruiseSpeed),
        Codec.INT.optionalFieldOf("departure_delay", 0).forGetter(plan -> plan.departureDelayTicks),
        // Dispatch legs: unregistered landing zones and the order they belong to. Optional.
        DispatchLeg.CODEC.optionalFieldOf("dispatch").forGetter(plan -> Optional.ofNullable(plan.dispatchLeg()))
    ).apply(instance, FlightPlan::new));

    /** The dispatch part of a plan, grouped so the record codec stays within its field limit. */
    record DispatchLeg(Optional<Helipad> from, Optional<Helipad> to, boolean provisional, UUID order) {
        static final Codec<DispatchLeg> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Helipad.CODEC.optionalFieldOf("ad_hoc_from").forGetter(DispatchLeg::from),
            Helipad.CODEC.optionalFieldOf("ad_hoc_to").forGetter(DispatchLeg::to),
            Codec.BOOL.optionalFieldOf("provisional", false).forGetter(DispatchLeg::provisional),
            UUIDUtil.STRING_CODEC.fieldOf("order").forGetter(DispatchLeg::order)
        ).apply(instance, DispatchLeg::new));
    }

    private @org.jspecify.annotations.Nullable DispatchLeg dispatchLeg() {
        return orderId == null ? null
            : new DispatchLeg(Optional.ofNullable(adHocFrom), Optional.ofNullable(adHocTo), provisional, orderId);
    }

    private final Kind kind;
    private final List<BlockPos> waypoints;
    private int index;
    private int direction;
    private int legsFlown;
    private final int maxLegs;
    private int cruiseAltitude;
    private String airfieldName;
    private final BlockPos strikeTarget;
    private final String departureAirfield;
    /** How hard this aircraft goes off when it stops flying. Never null. */
    private final Blast blast;
    /**
     * Commanded speed on the cruise leg, in blocks/tick.
     *
     * <p><b>Cruise only.</b> The approach is not flown at this speed and never inherits it: the
     * glide slope, the landing gates and the flare are all tuned around
     * {@link AutopilotConfig#APPROACH_SPEED}, and a fast arrival simply cannot be flared. The
     * aircraft sheds the difference during the last part of the cruise leg, over the distance the
     * drag curve actually needs — see {@link AutopilotMath#speedSchedule}.
     */
    private final double cruiseSpeed;
    /**
     * Ticks the aircraft waits on its parking spot before it asks for the runway, 0 for none.
     *
     * <p>Part of the plan rather than of the flight director because it is an order, not a state:
     * the number is what the launch command asked for and never changes. How much of it is left is
     * {@code PlaneAutopilot}'s business.
     */
    private final int departureDelayTicks;

    /** Unregistered departure pad (a landing zone the previous leg ended on), or null. */
    private @org.jspecify.annotations.Nullable Helipad adHocFrom;
    /** Unregistered destination pad, or null when {@link #airfieldName} names a registered one. */
    private @org.jspecify.annotations.Nullable Helipad adHocTo;
    /** True while {@link #adHocTo} is only the search target: hold near it, do not land. */
    private boolean provisional;
    /** Dispatch order this leg belongs to, or null. */
    private @org.jspecify.annotations.Nullable UUID orderId;

    public FlightPlan(Kind kind, List<BlockPos> waypoints, int index, int direction, int legsFlown, int maxLegs,
                      int cruiseAltitude, Optional<String> airfieldName, Optional<BlockPos> strikeTarget,
                      Optional<String> departureAirfield, Blast blast, double cruiseSpeed,
                      int departureDelayTicks, Optional<DispatchLeg> leg) {
        this(kind, waypoints, index, direction, legsFlown, maxLegs, cruiseAltitude, airfieldName, strikeTarget,
            departureAirfield, blast, cruiseSpeed, departureDelayTicks);
        leg.ifPresent(dispatch -> {
            this.adHocFrom = dispatch.from().orElse(null);
            this.adHocTo = dispatch.to().orElse(null);
            this.provisional = dispatch.provisional();
            this.orderId = dispatch.order();
        });
    }

    public FlightPlan(Kind kind, List<BlockPos> waypoints, int index, int direction, int legsFlown, int maxLegs,
                      int cruiseAltitude, Optional<String> airfieldName, Optional<BlockPos> strikeTarget,
                      Optional<String> departureAirfield, Blast blast, double cruiseSpeed,
                      int departureDelayTicks) {
        this.kind = kind;
        this.waypoints = List.copyOf(waypoints);
        this.index = index;
        this.direction = direction == 0 ? 1 : direction;
        this.legsFlown = legsFlown;
        this.maxLegs = maxLegs;
        this.cruiseAltitude = cruiseAltitude;
        this.airfieldName = airfieldName.orElse(null);
        this.strikeTarget = strikeTarget.orElse(null);
        this.departureAirfield = departureAirfield.orElse(null);
        this.blast = blast == null ? Blast.DEFAULT : blast;
        // Clamped here as well as at the command, so a hand-edited save cannot produce an
        // aircraft that is commanded a speed the airframe cannot fly. Which range that is depends on
        // the airframe: a helicopter's useful band starts well below a plane's stall speed and ends
        // well below its cruise, so putting a rotorcraft plan through the fixed-wing clamp would
        // quietly raise every slow helicopter flight to 0.40.
        this.cruiseSpeed = kind == Kind.HELI
            ? RotorcraftConfig.clampCruiseSpeed(cruiseSpeed)
            : AutopilotConfig.clampCruiseSpeed(cruiseSpeed);
        this.departureDelayTicks = Math.max(0, departureDelayTicks);
    }

    public static FlightPlan strike(BlockPos target, Blast blast) {
        return new FlightPlan(Kind.STRIKE, List.of(), 0, 1, 0, 0,
            (int) AutopilotConfig.DEFAULT_CRUISE_ALTITUDE, Optional.empty(), Optional.of(target),
            Optional.empty(), blast, AutopilotConfig.CRUISE_SPEED, 0);
    }

    public static FlightPlan route(List<BlockPos> waypoints, int cruiseAltitude, int maxLegs, String airfieldName,
                                   double cruiseSpeed, Blast blast) {
        return new FlightPlan(Kind.ROUTE, waypoints, 0, 1, 0, maxLegs, cruiseAltitude,
            Optional.ofNullable(airfieldName), Optional.empty(), Optional.empty(), blast, cruiseSpeed, 0);
    }

    /**
     * A one-way sortie: fly the single waypoint, then land at the named airfield.
     *
     * <p>This is what {@code route} could not express. A route is always out-and-back — it bounces
     * off the end of its waypoint list and returns — and it picks the landing field by proximity to
     * where it <em>departed</em>, which only makes sense for a round trip. An aircraft starting a
     * long way from its destination therefore always ended in an improvised field landing rather
     * than an approach to the runway it was sent to. Here the destination is named outright, and
     * {@code maxLegs = 1} means arriving at the waypoint completes the flight.
     *
     * @param departureAirfield airfield to taxi out from, or null to launch from where it stands
     * @param departureDelayTicks how long to sit on the parking spot before asking for the runway
     */
    public static FlightPlan sortie(BlockPos destination, int cruiseAltitude,
                                    String destinationAirfield, String departureAirfield,
                                    double cruiseSpeed, Blast blast, int departureDelayTicks) {
        return new FlightPlan(Kind.ROUTE, List.of(destination), 0, 1, 0, 1, cruiseAltitude,
            Optional.ofNullable(destinationAirfield), Optional.empty(),
            Optional.ofNullable(departureAirfield), blast, cruiseSpeed, departureDelayTicks);
    }

    /**
     * A helicopter sortie between two registered helipads.
     *
     * <p>The two pad names go in the same two fields a fixed-wing sortie's airfield names go in, and
     * that is not laziness: they are both "the named place this flight is leaving" and "the named
     * place it is going to", they are looked up in the same {@link AutopilotSavedData}, and giving
     * them their own codec fields would mean two more optional keys that no plan ever sets at the
     * same time as the first two. What tells them apart is {@link Kind#HELI}, which is the only
     * thing that decides which registry the names are looked up in.
     *
     * @param departurePad the pad to lift off from, or null for a flight that starts in the air
     */
    public static FlightPlan heliSortie(BlockPos destination, int cruiseAltitude,
                                        String destinationPad, String departurePad,
                                        double cruiseSpeed, int departureDelayTicks) {
        return new FlightPlan(Kind.HELI, List.of(destination), 0, 1, 0, 1, cruiseAltitude,
            Optional.ofNullable(destinationPad), Optional.empty(), Optional.ofNullable(departurePad),
            Blast.DEFAULT, cruiseSpeed, departureDelayTicks);
    }

    /**
     * One dispatch leg between two pads, either of which may be an unregistered landing zone.
     *
     * @param from registered departure pad name, or null
     * @param adHocFrom unregistered departure pad, or null
     * @param to registered destination pad name, or null
     * @param adHocTo unregistered destination (or search target while provisional), or null
     */
    public static FlightPlan dispatchLeg(@org.jspecify.annotations.Nullable String from,
                                         @org.jspecify.annotations.Nullable Helipad adHocFrom,
                                         @org.jspecify.annotations.Nullable String to,
                                         @org.jspecify.annotations.Nullable Helipad adHocTo,
                                         boolean provisional, int cruiseAltitude, double cruiseSpeed,
                                         UUID orderId) {
        BlockPos aim = adHocTo != null ? adHocTo.centre() : BlockPos.ZERO;
        return new FlightPlan(Kind.HELI, List.of(aim), 0, 1, 0, 1, cruiseAltitude,
            Optional.ofNullable(to), Optional.empty(), Optional.ofNullable(from), Blast.DEFAULT,
            cruiseSpeed, 0, Optional.of(new DispatchLeg(Optional.ofNullable(adHocFrom),
                Optional.ofNullable(adHocTo), provisional, orderId)));
    }

    public @org.jspecify.annotations.Nullable Helipad adHocFrom() {
        return adHocFrom;
    }

    public @org.jspecify.annotations.Nullable Helipad adHocTo() {
        return adHocTo;
    }

    public boolean provisional() {
        return provisional;
    }

    public @org.jspecify.annotations.Nullable UUID orderId() {
        return orderId;
    }

    public void setCruiseAltitude(int cruiseAltitude) {
        this.cruiseAltitude = cruiseAltitude;
    }

    /** Re-aims a dispatch leg; the plan is what a restart resumes from. */
    public void retarget(@org.jspecify.annotations.Nullable String to,
                         @org.jspecify.annotations.Nullable Helipad adHocTo, boolean provisional) {
        this.airfieldName = to;
        this.adHocTo = adHocTo;
        this.provisional = provisional;
    }

    /** Ticks to wait on the parking spot before asking for the runway; 0 for an immediate departure. */
    public int departureDelayTicks() {
        return departureDelayTicks;
    }

    /** How hard this aircraft goes off when it stops flying. */
    public Blast blast() {
        return blast;
    }

    /** Commanded speed on the cruise leg only — see the field comment. */
    public double cruiseSpeed() {
        return cruiseSpeed;
    }

    /** Airfield this flight taxied out from, or null when it did not start on a runway. */
    public String departureAirfield() {
        return departureAirfield;
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

    /**
     * True when reaching the current waypoint ends the route and starts the landing.
     *
     * <p>Used to decide where to begin bleeding the cruise speed off. Deliberately conservative:
     * for the out-and-back case ({@code maxLegs = 2}) this is false on the way out — that waypoint
     * is a turn, not an arrival — and true on the way back.
     */
    public boolean onFinalLeg() {
        return legsFlown + 1 >= Math.max(1, maxLegs);
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
            return "strike -> " + (strikeTarget == null ? "?" : strikeTarget.toShortString())
                + ", blast " + blast.describe();
        }
        if (kind == Kind.HELI) {
            String from = adHocFrom != null ? adHocFrom.name() : departureAirfield;
            String to = adHocTo != null ? adHocTo.name() + (provisional ? " (searching)" : "") : airfieldName;
            return (from == null ? "inbound" : "helipad " + from)
                + " -> " + (to == null ? "?" : to)
                + " alt " + cruiseAltitude + String.format(", cruise %.2f", cruiseSpeed)
                + (departureDelayTicks > 0 ? ", delay " + departureDelayTicks / 20 + "s" : "");
        }
        List<String> parts = new ArrayList<>();
        for (BlockPos pos : waypoints) {
            parts.add(pos.toShortString());
        }
        return (departureAirfield == null ? "route [" : "sortie from " + departureAirfield + " [")
            + String.join(" -> ", parts) + "] alt " + cruiseAltitude
            + String.format(", cruise %.2f", cruiseSpeed)
            + (departureDelayTicks > 0 ? ", delay " + departureDelayTicks / 20 + "s" : "")
            + (airfieldName == null ? ", improvised landing" : ", landing at " + airfieldName);
    }
}
