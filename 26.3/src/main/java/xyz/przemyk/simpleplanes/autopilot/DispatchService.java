package xyz.przemyk.simpleplanes.autopilot;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.airdefence.Allegiance;
import xyz.przemyk.simpleplanes.api.dispatch.AircraftStatus;
import xyz.przemyk.simpleplanes.api.dispatch.AircraftStatus.Phase;
import xyz.przemyk.simpleplanes.api.dispatch.DispatchEvent;
import xyz.przemyk.simpleplanes.api.dispatch.DispatchListener;
import xyz.przemyk.simpleplanes.api.dispatch.DispatchOrder;
import xyz.przemyk.simpleplanes.api.dispatch.DispatchReasons;
import xyz.przemyk.simpleplanes.api.dispatch.DispatchResult;
import xyz.przemyk.simpleplanes.api.dispatch.LandingZoneSpec;
import xyz.przemyk.simpleplanes.api.dispatch.PadInfo;
import xyz.przemyk.simpleplanes.autopilot.DispatchSavedData.Sortie;
import xyz.przemyk.simpleplanes.entities.MiniHelicopterEntity;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Out-and-back rotorcraft orders to an arbitrary position: the implementation behind
 * {@code api.dispatch.RotorcraftDispatch}.
 *
 * <p>Each order is a sequence of {@link HelicopterAutopilot} legs carrying the order id. The legs
 * report back through {@link #legEvent}; those reports are queued and handled at the end of the
 * level tick, never inside the aircraft's own tick. State lives in {@link DispatchSavedData}, so an
 * order resumes after a restart, and events wait in its outbox until a listener takes them.
 *
 * <p>Chunk loading: a flying leg is kept loaded by {@link AutopilotRegistry}. On top of that this
 * class holds a ticket on the search area while the landing zone is being looked for, on the
 * aircraft while it holds at the target, and on the last known position of any aircraft in an order
 * that cannot be resolved (the restart case), which is what brings it back.
 */
public final class DispatchService {

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes-dispatch");

    /** What a leg reports to the order it belongs to. */
    public enum LegEvent { LIFTED_OFF, LANDED, LANDED_OFF, FAILED, CEILING, NO_DESTINATION, NO_LANDING_ZONE, PAD_BUSY }

    private record PendingLeg(ServerLevel level, UUID aircraft, UUID orderId, LegEvent event, @Nullable String detail) {}

    private static final int SERVICE_INTERVAL = 20;
    private static final int HOLD_TICKET_RADIUS = 2;
    private static final int WAKE_TICKET_RADIUS = 2;
    /** Ticks an in-order aircraft may stay unresolvable, with a ticket on its last position, before it is lost. */
    static final int LOST_AFTER_TICKS = 2400;
    /** Longest wait for the search area to load before searching what is there. */
    private static final int SEARCH_LOAD_WAIT = 600;
    private static final int RETURN_RETRIES = 3;
    private static final int EMERGENCY_RADIUS = 48;
    private static final long SYNC_SEARCH_BUDGET = 250_000;
    /** A passenger must be this close to be loaded. */
    private static final double LOAD_DISTANCE = 16.0;

    private static final List<PendingLeg> PENDING = new ArrayList<>();
    private static final Map<String, DispatchListener> LISTENERS = new ConcurrentHashMap<>();
    private static final Set<UUID> STOWING = new HashSet<>();

    // Transient per-aircraft state, rebuilt after a restart.
    private static final Map<UUID, LandingZoneFinder> FINDERS = new HashMap<>();
    private static final Map<UUID, Long> SEARCH_SINCE = new HashMap<>();
    private static final Map<UUID, Long> UNRESOLVED_SINCE = new HashMap<>();

    private DispatchService() {}

    public static void init() {
        ServerTickEvents.END_LEVEL_TICK.register(DispatchService::onLevelTick);
        DispatchCommand.init();
    }

    // ------------------------------------------------------------------ listeners

    public static void registerListener(String ownerId, DispatchListener listener) {
        LISTENERS.put(ownerId, listener);
    }

    public static void unregisterListener(String ownerId) {
        LISTENERS.remove(ownerId);
    }

    // ------------------------------------------------------------------ hooks from the flight code

    /** Called by a leg of an order. Queued; handled at the end of the level tick. */
    static void legEvent(PlaneEntity plane, UUID orderId, LegEvent event, @Nullable String detail) {
        if (plane.level() instanceof ServerLevel level) {
            PENDING.add(new PendingLeg(level, plane.getUUID(), orderId, event, detail));
        }
    }

    /** Called from {@code PlaneEntity#remove} for a removal that destroys the entity. */
    public static void aircraftRemoved(PlaneEntity plane, Entity.RemovalReason reason) {
        if (!(plane.level() instanceof ServerLevel level) || STOWING.contains(plane.getUUID())) {
            return;
        }
        if (!(plane instanceof MiniHelicopterEntity mini) || !mini.isDispatchManaged()) {
            return;
        }
        DispatchSavedData data = DispatchSavedData.get(level);
        Sortie s = data.sortie(plane.getUUID());
        if (s == null || s.phase == Phase.LOST) {
            return;
        }
        s.lastKnown = plane.blockPosition();
        lose(level, data, s, reason == Entity.RemovalReason.KILLED ? DispatchReasons.DESTROYED
            : reason == Entity.RemovalReason.CHANGED_DIMENSION ? DispatchReasons.CHANGED_DIMENSION
            : DispatchReasons.REMOVED);
    }

    /** Whether the service owns this airframe; reuse and schedules must leave it alone. */
    public static boolean manages(ServerLevel level, UUID aircraft) {
        Sortie s = DispatchSavedData.get(level).sortie(aircraft);
        return s != null && s.phase != Phase.LOST;
    }

    // ------------------------------------------------------------------ API operations

    public static List<PadInfo> padsNear(ServerLevel level, int x, int z, int radius) {
        Vec3 from = new Vec3(x + 0.5, 0, z + 0.5);
        List<PadInfo> pads = new ArrayList<>();
        for (Helipad pad : AutopilotSavedData.get(level).helipadList()) {
            double distance = AutopilotMath.horizontalDistance(from, pad.touchdown());
            if (radius <= 0 || distance <= radius) {
                BlockPos c = pad.centre();
                pads.add(new PadInfo(pad.name(), c.getX(), c.getY(), c.getZ(), pad.radius(),
                    pad.free(level, null), distance));
            }
        }
        pads.sort(Comparator.comparingDouble(PadInfo::distance));
        return pads;
    }

    public static boolean isDispatchable(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == SimplePlanesItems.MINI_HELICOPTER_ITEM.get();
    }

    public static DispatchResult deploy(ServerLevel level, ItemStack stack, String padName, @Nullable String ownerId) {
        if (!isDispatchable(stack)) {
            return DispatchResult.refused(DispatchReasons.NOT_DISPATCHABLE, "not a mini helicopter item");
        }
        Helipad pad = AutopilotSavedData.get(level).helipad(padName);
        if (pad == null) {
            return DispatchResult.refused(DispatchReasons.UNKNOWN_PAD, "no helipad named " + padName);
        }
        if (!level.hasChunkAt(pad.centre())) {
            return DispatchResult.refused(DispatchReasons.NOT_LOADED, pad.name() + " is not loaded");
        }
        if (!pad.free(level, null)) {
            return DispatchResult.refused(DispatchReasons.PAD_OCCUPIED, pad.name() + " is occupied");
        }
        MiniHelicopterEntity plane = SimplePlanesEntities.MINI_HELICOPTER.get().create(level, EntitySpawnReason.SPAWN_ITEM_USE);
        if (plane == null) {
            return DispatchResult.refused(DispatchReasons.SPAWN_FAILED, "entity could not be created");
        }
        Vec3 touchdown = pad.touchdown();
        plane.snapTo(touchdown.x, touchdown.y + 0.05, touchdown.z, 0.0f, 0.0f);
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        if (name != null) {
            plane.setCustomName(name);
        }
        CompoundTag entityTag = stack.get(SimplePlanesComponents.ENTITY_TAG.get());
        if (entityTag != null) {
            plane.loadFromItemTag(entityTag);
        }
        plane.setDispatchManaged(true);
        plane.setAllegiance(Allegiance.FRIENDLY);
        if (!level.addFreshEntity(plane)) {
            return DispatchResult.refused(DispatchReasons.SPAWN_FAILED, "level refused the entity");
        }
        stack.shrink(1);
        StandOccupancy.take(level, pad.name(), pad.centre(), plane);
        DispatchSavedData data = DispatchSavedData.get(level);
        Sortie s = new Sortie(plane.getUUID(), pad.name());
        s.ownerId = ownerId;
        s.lastKnown = plane.blockPosition();
        data.put(s);
        return DispatchResult.success(plane.getUUID(), "deployed on " + pad.name());
    }

    public static ItemStack stow(ServerLevel level, UUID aircraft) {
        DispatchSavedData data = DispatchSavedData.get(level);
        Sortie s = data.sortie(aircraft);
        MiniHelicopterEntity plane = resolve(level, aircraft);
        if (plane == null || (s != null && s.inOrder()) || !plane.onGround()
            || (plane.getAutopilot() != null && plane.getAutopilot().isActive())) {
            return ItemStack.EMPTY;
        }
        for (Entity passenger : List.copyOf(plane.getPassengers())) {
            if (!plane.unloadRider(passenger)) {
                passenger.stopRiding();
            }
        }
        plane.setDispatchManaged(false);
        ItemStack stack = plane.getItemStack();
        if (s != null) {
            Helipad home = AutopilotSavedData.get(level).helipad(s.homePad);
            if (home != null && aircraft.equals(StandOccupancy.heldBy(level, home.name(), home.centre()))) {
                StandOccupancy.release(level, home.name(), home.centre());
            }
        }
        STOWING.add(aircraft);
        try {
            plane.discard();
        } finally {
            STOWING.remove(aircraft);
        }
        data.remove(aircraft);
        forgetTransient(aircraft);
        return stack;
    }

    public static DispatchResult dispatch(ServerLevel level, UUID aircraft, DispatchOrder order) {
        MiniHelicopterEntity plane = resolve(level, aircraft);
        if (plane == null) {
            return DispatchResult.refused(DispatchReasons.NOT_LOADED, "aircraft is not loaded");
        }
        DispatchSavedData data = DispatchSavedData.get(level);
        Sortie s = data.sortie(aircraft);
        if (s != null && (s.inOrder() || s.phase == Phase.LOST)) {
            return DispatchResult.refused(DispatchReasons.BUSY, "aircraft is " + s.phase.name().toLowerCase());
        }
        if (plane.getAutopilot() != null && plane.getAutopilot().isActive()) {
            return DispatchResult.refused(DispatchReasons.BUSY, "aircraft is already flying an autopilot leg");
        }
        if (!plane.onGround() && !plane.isOnWater()) {
            return DispatchResult.refused(DispatchReasons.AIRBORNE, "aircraft is not on the ground");
        }
        for (Entity passenger : plane.getPassengers()) {
            if (passenger instanceof Player) {
                return DispatchResult.refused(DispatchReasons.BUSY, "a player is aboard");
            }
        }
        Helipad home = AutopilotSavedData.get(level).helipad(order.homePad());
        if (home == null) {
            return DispatchResult.refused(DispatchReasons.UNKNOWN_PAD, "no helipad named " + order.homePad());
        }
        if (order.targetY() < level.getMinY() || order.targetY() > level.getMaxY()) {
            return DispatchResult.refused(DispatchReasons.OUT_OF_WORLD, "target y is outside the world");
        }
        if (!AutopilotRegistry.canActivateAnother()) {
            return DispatchResult.refused(DispatchReasons.TOO_MANY_FLIGHTS, "autopilot limit reached");
        }
        if (s == null) {
            s = new Sortie(aircraft, home.name());
        }
        s.clearOrder();
        s.homePad = home.name();
        s.ownerId = order.ownerId();
        s.orderId = UUID.randomUUID();
        s.target = new BlockPos(order.targetX(), order.targetY(), order.targetZ());
        s.searchRadius = order.searchRadius();
        s.groundHoldTicks = order.groundHoldTicks();
        s.returnHome = order.returnHome();
        s.userData = order.userData();
        s.cruiseSpeed = order.cruiseSpeed();
        s.lastKnown = plane.blockPosition();
        s.phase = Phase.OUTBOUND;
        plane.setDispatchManaged(true);
        plane.setAllegiance(Allegiance.FRIENDLY);

        String refusal = startLeg(level, plane, s, null, provisionalTarget(level, s), true);
        if (refusal != null) {
            s.clearOrder();
            s.phase = Phase.IDLE;
            data.put(s);
            return DispatchResult.refused(refusal, "could not start the outbound leg");
        }
        data.put(s);
        forgetTransient(aircraft);
        LOGGER.info("Dispatch {}: aircraft {} to {} for {}, home {}", s.orderId, aircraft, s.target, s.ownerId, s.homePad);
        return DispatchResult.success(s.orderId, "outbound to " + s.target.toShortString());
    }

    public static DispatchResult loadPassenger(ServerLevel level, UUID aircraft, Entity entity, int seat) {
        MiniHelicopterEntity plane = resolve(level, aircraft);
        if (plane == null) {
            return DispatchResult.refused(DispatchReasons.NOT_LOADED, "aircraft is not loaded");
        }
        if (!plane.onGround() && !plane.isOnWater()) {
            return DispatchResult.refused(DispatchReasons.AIRBORNE, "aircraft is not on the ground");
        }
        if (entity instanceof Player || entity instanceof PlaneEntity || !entity.isAlive() || entity.level() != level) {
            return DispatchResult.refused(DispatchReasons.INVALID_PASSENGER, "entity cannot ride");
        }
        if (entity.distanceTo(plane) > LOAD_DISTANCE) {
            return DispatchResult.refused(DispatchReasons.TOO_FAR, "entity is more than " + (int) LOAD_DISTANCE + " blocks away");
        }
        if (entity.getVehicle() == plane) {
            return DispatchResult.success(entity.getUUID(), "already aboard");
        }
        if (entity.isPassenger()) {
            entity.stopRiding();
        }
        if (!plane.loadRider(entity, seat)) {
            return DispatchResult.refused(DispatchReasons.NO_SEAT, "no free seat (capacity " + plane.riderCapacity() + ")");
        }
        return DispatchResult.success(entity.getUUID(), "seat " + plane.seatOf(entity));
    }

    public static boolean unloadPassenger(ServerLevel level, UUID aircraft, UUID entity) {
        MiniHelicopterEntity plane = resolve(level, aircraft);
        if (plane == null || (!plane.onGround() && !plane.isOnWater())) {
            return false;
        }
        for (Entity passenger : List.copyOf(plane.getPassengers())) {
            if (passenger.getUUID().equals(entity) && !(passenger instanceof Player)) {
                return plane.unloadRider(passenger) || unmount(passenger);
            }
        }
        return false;
    }

    private static boolean unmount(Entity passenger) {
        passenger.stopRiding();
        return true;
    }

    public static int unloadAll(ServerLevel level, UUID aircraft) {
        MiniHelicopterEntity plane = resolve(level, aircraft);
        if (plane == null || (!plane.onGround() && !plane.isOnWater())) {
            return 0;
        }
        int count = 0;
        for (Entity passenger : List.copyOf(plane.getPassengers())) {
            if (!(passenger instanceof Player) && (plane.unloadRider(passenger) || unmount(passenger))) {
                count++;
            }
        }
        return count;
    }

    public static List<UUID> passengers(ServerLevel level, UUID aircraft) {
        MiniHelicopterEntity plane = resolve(level, aircraft);
        if (plane == null) {
            return List.of();
        }
        List<Entity> aboard = new ArrayList<>(plane.getPassengers());
        aboard.sort(Comparator.comparingInt(plane::seatOf));
        return aboard.stream().map(Entity::getUUID).toList();
    }

    public static boolean extendHold(ServerLevel level, UUID aircraft, int ticks) {
        DispatchSavedData data = DispatchSavedData.get(level);
        Sortie s = data.sortie(aircraft);
        if (s == null || ticks <= 0) {
            return false;
        }
        int add = Math.min(ticks, DispatchOrder.MAX_HOLD_TICKS);
        if (s.phase == Phase.AT_TARGET) {
            long now = level.getGameTime();
            s.holdUntil = Math.max(s.holdUntil, now) + add;
        } else if (s.phase == Phase.OUTBOUND) {
            s.groundHoldTicks = Math.min(s.groundHoldTicks + add, DispatchOrder.MAX_HOLD_TICKS * 2);
        } else {
            return false;
        }
        data.changed();
        return true;
    }

    /** Ends the hold now: the aircraft leaves on the next service tick. */
    public static boolean release(ServerLevel level, UUID aircraft) {
        DispatchSavedData data = DispatchSavedData.get(level);
        Sortie s = data.sortie(aircraft);
        if (s == null || s.phase != Phase.AT_TARGET) {
            return false;
        }
        s.holdUntil = level.getGameTime();
        data.changed();
        MiniHelicopterEntity plane = resolve(level, aircraft);
        if (plane != null) {
            leaveTarget(level, data, s, plane);
        }
        return true;
    }

    /** Aborts the order and brings the aircraft home; also sends an idle aircraft that is away back. */
    public static boolean recall(ServerLevel level, UUID aircraft) {
        DispatchSavedData data = DispatchSavedData.get(level);
        Sortie s = data.sortie(aircraft);
        MiniHelicopterEntity plane = resolve(level, aircraft);
        if (s == null || plane == null) {
            return false;
        }
        switch (s.phase) {
            case OUTBOUND -> {
                abort(level, data, s, DispatchReasons.RECALLED, "recalled");
                return goHome(level, data, s, plane);
            }
            case AT_TARGET -> {
                abort(level, data, s, DispatchReasons.RECALLED, "recalled");
                s.returnHome = true;
                s.holdUntil = level.getGameTime();
                leaveTarget(level, data, s, plane);
                return true;
            }
            case IDLE -> {
                if (atHome(level, s, plane)) {
                    return false;
                }
                if (s.orderId == null) {
                    s.orderId = UUID.randomUUID();
                }
                s.returnHome = true;
                return goHome(level, data, s, plane);
            }
            default -> {
                return false;
            }
        }
    }

    public static List<UUID> aircraftOf(ServerLevel level, String ownerId) {
        List<UUID> out = new ArrayList<>();
        for (Sortie s : DispatchSavedData.get(level).sorties()) {
            if (ownerId.equals(s.ownerId)) {
                out.add(s.aircraft);
            }
        }
        return out;
    }

    public static List<UUID> allAircraft(ServerLevel level) {
        return DispatchSavedData.get(level).sortieList().stream().map(s -> s.aircraft).toList();
    }

    public static @Nullable AircraftStatus status(ServerLevel level, UUID aircraft) {
        Sortie s = DispatchSavedData.get(level).sortie(aircraft);
        MiniHelicopterEntity plane = resolve(level, aircraft);
        if (s == null && plane == null) {
            return null;
        }
        Phase phase = s == null ? Phase.IDLE : s.phase;
        Vec3 pos = plane != null ? plane.position()
            : s != null ? Vec3.atBottomCenterOf(s.lastKnown) : Vec3.ZERO;
        boolean onGround = plane != null && plane.onGround();
        boolean atHome = plane != null && s != null && atHome(level, s, plane);
        Helipad zone = s == null ? null : s.zone;
        int holdLeft = s != null && s.phase == Phase.AT_TARGET
            ? (int) Math.max(0, s.holdUntil - level.getGameTime()) : -1;
        String mode = plane != null && plane.getAutopilot() != null && plane.getAutopilot().isActive()
            ? plane.getAutopilot().getMode().getName() : "idle";
        return new AircraftStatus(aircraft, phase, plane != null, s == null ? null : s.ownerId,
            s == null ? "" : s.homePad, s == null ? null : s.orderId, pos.x, pos.y, pos.z, onGround, atHome,
            zone != null, zone == null ? 0 : zone.centre().getX(), zone == null ? 0 : zone.centre().getY(),
            zone == null ? 0 : zone.centre().getZ(), holdLeft, passengers(level, aircraft),
            plane == null ? 0 : plane.riderCapacity(), s == null ? null : s.abortReason, mode);
    }

    /** Synchronous landing-zone search, capped at {@code SYNC_SEARCH_BUDGET} column reads. */
    public static LandingZoneFinder findNow(ServerLevel level, int x, int z, int radius, LandingZoneSpec spec) {
        LandingZoneFinder finder = new LandingZoneFinder(level, x, z, radius, spec);
        finder.step(SYNC_SEARCH_BUDGET);
        return finder;
    }

    // ------------------------------------------------------------------ the level tick

    private static void onLevelTick(ServerLevel level) {
        try {
            tick(level);
        } catch (Exception e) {
            LOGGER.error("Dispatch service failed in {}", level.dimension().identifier(), e);
        }
    }

    private static void tick(ServerLevel level) {
        boolean pending = !PENDING.isEmpty();
        long now = level.getGameTime();
        boolean service = now % SERVICE_INTERVAL == 0;
        if (!pending && FINDERS.isEmpty() && !service) {
            return;
        }
        DispatchSavedData data = DispatchSavedData.get(level);
        if (pending) {
            processLegEvents(level, data);
        }
        if (data.isEmpty()) {
            return;
        }
        for (Sortie s : data.sortieList()) {
            try {
                if (s.phase == Phase.OUTBOUND && s.zone == null && !s.searchFailed) {
                    search(level, data, s, now, service);
                }
                if (service) {
                    service(level, data, s, now);
                }
            } catch (Exception e) {
                LOGGER.error("Dispatch of aircraft {} failed while being serviced", s.aircraft, e);
            }
        }
        deliver(level, data);
    }

    private static void processLegEvents(ServerLevel level, DispatchSavedData data) {
        List<PendingLeg> mine = new ArrayList<>();
        for (Iterator<PendingLeg> it = PENDING.iterator(); it.hasNext(); ) {
            PendingLeg leg = it.next();
            if (leg.level() == level) {
                mine.add(leg);
                it.remove();
            }
        }
        for (PendingLeg leg : mine) {
            Sortie s = data.sortie(leg.aircraft());
            if (s == null || !leg.orderId().equals(s.orderId)) {
                continue;
            }
            MiniHelicopterEntity plane = resolve(level, leg.aircraft());
            try {
                onLeg(level, data, s, plane, leg.event(), leg.detail());
            } catch (Exception e) {
                LOGGER.error("Dispatch of aircraft {}: handling {} failed", s.aircraft, leg.event(), e);
            }
            data.changed();
        }
    }

    private static void onLeg(ServerLevel level, DispatchSavedData data, Sortie s, @Nullable MiniHelicopterEntity plane,
                              LegEvent event, @Nullable String detail) {
        if (plane != null) {
            s.lastKnown = plane.blockPosition();
        }
        switch (s.phase) {
            case OUTBOUND -> onOutboundLeg(level, data, s, plane, event, detail);
            case RETURNING -> onReturnLeg(level, data, s, plane, event, detail);
            default -> {
                // AT_TARGET / IDLE / LOST: no leg should be flying; a late report is ignored.
            }
        }
    }

    private static void onOutboundLeg(ServerLevel level, DispatchSavedData data, Sortie s,
                                      @Nullable MiniHelicopterEntity plane, LegEvent event, @Nullable String detail) {
        switch (event) {
            case LIFTED_OFF -> {
                if (!s.departed) {
                    s.departed = true;
                    emit(level, data, s, DispatchEvent.Type.DEPARTED, null);
                }
            }
            case LANDED, LANDED_OFF -> {
                if (plane == null) {
                    return;
                }
                if (plane.isOnWater() || plane.isInWater()) {
                    abort(level, data, s, DispatchReasons.LANDED_IN_WATER, detail);
                    goHome(level, data, s, plane);
                    return;
                }
                s.phase = Phase.AT_TARGET;
                s.holdUntil = level.getGameTime() + s.groundHoldTicks;
                emit(level, data, s, DispatchEvent.Type.LANDED_AT_TARGET,
                    event == LegEvent.LANDED_OFF ? "off zone: " + detail : null);
                if (s.groundHoldTicks <= 0) {
                    leaveTarget(level, data, s, plane);
                }
            }
            case CEILING -> {
                abort(level, data, s, DispatchReasons.CEILING, detail);
                if (plane != null) {
                    goHome(level, data, s, plane);
                }
            }
            case NO_LANDING_ZONE -> {
                abort(level, data, s, DispatchReasons.NO_LANDING_ZONE, detail);
                if (plane != null) {
                    goHome(level, data, s, plane);
                }
            }
            default -> {
                abort(level, data, s, DispatchReasons.FLIGHT_FAILED, event + ": " + detail);
                if (plane != null) {
                    goHome(level, data, s, plane);
                }
            }
        }
    }

    private static void onReturnLeg(ServerLevel level, DispatchSavedData data, Sortie s,
                                    @Nullable MiniHelicopterEntity plane, LegEvent event, @Nullable String detail) {
        switch (event) {
            case LIFTED_OFF -> {
            }
            case LANDED, LANDED_OFF -> {
                if (plane == null) {
                    return;
                }
                boolean home = s.returnTo == null && atHome(level, s, plane);
                boolean emergency = s.returnTo != null && !plane.isInWater();
                if (home || emergency || (event == LegEvent.LANDED && s.returnTo == null)) {
                    finishReturn(level, data, s, emergency ? DispatchReasons.RETURN_FAILED
                        : event == LegEvent.LANDED_OFF ? "off pad: " + detail : null);
                } else {
                    retryReturn(level, data, s, plane, detail);
                }
            }
            case NO_DESTINATION -> {
                // Home pad removed mid-leg.
                if (s.abortReason == null) {
                    abort(level, data, s, DispatchReasons.HOME_PAD_UNAVAILABLE, detail);
                }
                if (plane != null) {
                    emergencyLanding(level, data, s, plane, detail);
                }
            }
            default -> {
                if (plane != null) {
                    retryReturn(level, data, s, plane, event + ": " + detail);
                }
            }
        }
    }

    private static void finishReturn(ServerLevel level, DispatchSavedData data, Sortie s, @Nullable String note) {
        s.phase = Phase.IDLE;
        emit(level, data, s, DispatchEvent.Type.RETURNED, s.abortReason != null && note == null ? s.abortReason : note);
        LOGGER.info("Dispatch {}: aircraft {} back{}", s.orderId, s.aircraft, note == null ? "" : " (" + note + ")");
        s.clearOrder();
        forgetTransient(s.aircraft);
        data.changed();
    }

    private static void retryReturn(ServerLevel level, DispatchSavedData data, Sortie s, MiniHelicopterEntity plane,
                                    @Nullable String detail) {
        s.retries++;
        LOGGER.warn("Dispatch {}: return leg of {} failed ({}), attempt {}", s.orderId, s.aircraft, detail, s.retries);
        if (s.retries <= RETURN_RETRIES && s.returnTo == null) {
            String refusal = startLeg(level, plane, s, s.homePad, null, false);
            if (refusal == null) {
                return;
            }
            detail = refusal;
        }
        emergencyLanding(level, data, s, plane, detail);
    }

    private static void emergencyLanding(ServerLevel level, DispatchSavedData data, Sortie s,
                                         MiniHelicopterEntity plane, @Nullable String detail) {
        if (s.abortReason == null) {
            abort(level, data, s, DispatchReasons.RETURN_FAILED, detail);
        }
        if (plane.onGround() && !plane.isInWater()) {
            finishReturn(level, data, s, DispatchReasons.RETURN_FAILED);
            return;
        }
        LandingZoneFinder finder = findNow(level, Mth.floor(plane.getX()), Mth.floor(plane.getZ()),
            EMERGENCY_RADIUS, LandingZoneSpec.DEFAULT);
        Helipad zone = finder.zone();
        if (zone != null && s.retries <= RETURN_RETRIES * 2) {
            s.returnTo = zone;
            s.retries++;
            if (startLeg(level, plane, s, null, zone, false) == null) {
                return;
            }
        }
        // Nothing more to try: leave it where it is and say so.
        LOGGER.warn("Dispatch {}: aircraft {} stranded ({})", s.orderId, s.aircraft, finder.diagnostics());
        finishReturn(level, data, s, DispatchReasons.RETURN_FAILED);
    }

    // ------------------------------------------------------------------ search

    private static void search(ServerLevel level, DispatchSavedData data, Sortie s, long now, boolean service) {
        BlockPos target = s.target;
        if (target == null) {
            return;
        }
        LandingZoneSpec spec = LandingZoneSpec.DEFAULT;
        int reach = s.searchRadius + spec.footprintRadius() + spec.ringWidth() + spec.approachLength() + 2;
        if (service) {
            level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, ChunkPos.containing(target),
                searchTicketRadius(reach));
        }
        LandingZoneFinder finder = FINDERS.get(s.aircraft);
        if (finder == null) {
            long since = SEARCH_SINCE.computeIfAbsent(s.aircraft, k -> now);
            if (!service || (!areaLoaded(level, target, reach) && now - since < SEARCH_LOAD_WAIT)) {
                return;
            }
            finder = new LandingZoneFinder(level, target.getX(), target.getZ(), s.searchRadius, spec);
            FINDERS.put(s.aircraft, finder);
        }
        LandingZoneFinder.State state = finder.step(spec.budgetPerTick());
        if (state == LandingZoneFinder.State.RUNNING) {
            return;
        }
        FINDERS.remove(s.aircraft);
        SEARCH_SINCE.remove(s.aircraft);
        LOGGER.info("Dispatch {}: landing-zone search at {} r{}: {} ({})", s.orderId, target.toShortString(),
            s.searchRadius, state, finder.diagnostics());
        if (state == LandingZoneFinder.State.FOUND) {
            s.zone = finder.zone();
            data.changed();
            MiniHelicopterEntity plane = resolve(level, s.aircraft);
            if (plane != null) {
                aimAtZone(plane, s);
            }
        } else {
            s.searchFailed = true;
            abort(level, data, s, DispatchReasons.NO_LANDING_ZONE, finder.dominantRefusal());
            MiniHelicopterEntity plane = resolve(level, s.aircraft);
            if (plane != null) {
                goHome(level, data, s, plane);
            }
        }
    }

    private static int searchTicketRadius(int reachBlocks) {
        // An ENDER_PEARL ticket of radius r makes chunks up to r + 2 away FULL (heightmaps readable).
        return Mth.clamp((reachBlocks + 15) / 16 - 1, 1, 6);
    }

    private static boolean areaLoaded(ServerLevel level, BlockPos target, int reach) {
        int minX = (target.getX() - reach) >> 4;
        int maxX = (target.getX() + reach) >> 4;
        int minZ = (target.getZ() - reach) >> 4;
        int maxZ = (target.getZ() + reach) >> 4;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (!level.getChunkSource().hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Points a leg that is still flying at the provisional target to the landing zone. */
    private static void aimAtZone(MiniHelicopterEntity plane, Sortie s) {
        PlaneAutopilot autopilot = plane.getAutopilot();
        HelicopterAutopilot rotor = autopilot == null ? null : autopilot.rotorcraft();
        if (rotor != null && rotor.provisional() && s.zone != null) {
            rotor.retarget(plane, null, s.zone, false);
            LOGGER.info("Dispatch {}: landing zone {} ({} blocks from target)", s.orderId,
                s.zone.centre().toShortString(),
                s.target == null ? 0 : Math.round(Math.sqrt(s.zone.centre().distSqr(s.target))));
        }
    }

    // ------------------------------------------------------------------ periodic service

    private static void service(ServerLevel level, DispatchSavedData data, Sortie s, long now) {
        if (!s.inOrder()) {
            return;
        }
        MiniHelicopterEntity plane = resolve(level, s.aircraft);
        if (plane == null) {
            // The restart case: put a ticket on the last known position until it loads again.
            level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, ChunkPos.containing(s.lastKnown),
                WAKE_TICKET_RADIUS);
            long since = UNRESOLVED_SINCE.computeIfAbsent(s.aircraft, k -> now);
            if (now - since > LOST_AFTER_TICKS && level.areEntitiesLoaded(ChunkPos.containing(s.lastKnown).pack())) {
                lose(level, data, s, DispatchReasons.VANISHED);
            }
            return;
        }
        UNRESOLVED_SINCE.remove(s.aircraft);
        if (!s.lastKnown.equals(plane.blockPosition())) {
            s.lastKnown = plane.blockPosition();
            data.changed();
        }
        PlaneAutopilot autopilot = plane.getAutopilot();
        boolean flying = autopilot != null && autopilot.isActive();
        switch (s.phase) {
            case AT_TARGET -> {
                level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, ChunkPos.containing(s.lastKnown),
                    HOLD_TICKET_RADIUS);
                if (now >= s.holdUntil) {
                    leaveTarget(level, data, s, plane);
                }
            }
            case OUTBOUND -> {
                if (!flying) {
                    resumeLeg(level, data, s, plane);
                } else if (s.zone != null) {
                    aimAtZone(plane, s);
                }
            }
            case RETURNING -> {
                if (!flying) {
                    resumeLeg(level, data, s, plane);
                }
            }
            default -> {
            }
        }
    }

    /** An order whose aircraft is loaded but has no running leg (refused on reload, or a failed start). */
    private static void resumeLeg(ServerLevel level, DispatchSavedData data, Sortie s, MiniHelicopterEntity plane) {
        if (!AutopilotRegistry.canActivateAnother()) {
            return;
        }
        if (s.phase == Phase.OUTBOUND) {
            if (s.zone != null) {
                startLeg(level, plane, s, null, s.zone, false);
            } else {
                startLeg(level, plane, s, null, provisionalTarget(level, s), true);
            }
        } else if (s.returnTo != null) {
            startLeg(level, plane, s, null, s.returnTo, false);
        } else if (atHome(level, s, plane) && plane.onGround()) {
            finishReturn(level, data, s, null);
        } else if (startLeg(level, plane, s, s.homePad, null, false) != null) {
            emergencyLanding(level, data, s, plane, "home leg could not be started");
        }
    }

    private static void leaveTarget(ServerLevel level, DispatchSavedData data, Sortie s, MiniHelicopterEntity plane) {
        if (s.phase != Phase.AT_TARGET) {
            return;
        }
        if (!s.returnHome) {
            s.phase = Phase.IDLE;
            s.clearOrder();
            data.changed();
            return;
        }
        String refusal = startLeg(level, plane, s, s.homePad, null, false);
        if (refusal != null) {
            // Stays on the ground at the target; a later recall can try again.
            abort(level, data, s, DispatchReasons.HOME_PAD_UNAVAILABLE.equals(refusal) ? refusal
                : DispatchReasons.FLIGHT_FAILED, "could not start the return leg: " + refusal);
            s.phase = Phase.IDLE;
            s.clearOrder();
            data.changed();
            return;
        }
        s.phase = Phase.RETURNING;
        emit(level, data, s, DispatchEvent.Type.LEFT_TARGET, null);
        data.changed();
    }

    /** Turns an outbound order round: retarget a flying leg, or start a new one from where it is. */
    private static boolean goHome(ServerLevel level, DispatchSavedData data, Sortie s, MiniHelicopterEntity plane) {
        FINDERS.remove(s.aircraft);
        SEARCH_SINCE.remove(s.aircraft);
        Helipad home = AutopilotSavedData.get(level).helipad(s.homePad);
        if (home == null) {
            s.phase = Phase.RETURNING;
            emergencyLanding(level, data, s, plane, "home pad missing");
            return true;
        }
        PlaneAutopilot autopilot = plane.getAutopilot();
        boolean flying = autopilot != null && autopilot.isActive();
        if (atHome(level, s, plane) && plane.onGround()) {
            if (flying) {
                autopilot.stop(plane);
            }
            s.phase = Phase.RETURNING;
            finishReturn(level, data, s, null);
            return true;
        }
        s.phase = Phase.RETURNING;
        HelicopterAutopilot rotor = flying ? autopilot.rotorcraft() : null;
        if (rotor != null) {
            rotor.retarget(plane, home.name(), home, false);
            data.changed();
            return true;
        }
        String refusal = startLeg(level, plane, s, s.homePad, null, false);
        if (refusal != null) {
            emergencyLanding(level, data, s, plane, refusal);
        }
        data.changed();
        return true;
    }

    // ------------------------------------------------------------------ legs

    /**
     * Starts one autopilot leg for this order from wherever the aircraft is.
     *
     * @return null when started, otherwise a {@link DispatchReasons} code
     */
    private static @Nullable String startLeg(ServerLevel level, MiniHelicopterEntity plane, Sortie s,
                                             @Nullable String toPad, @Nullable Helipad adHocTo, boolean provisional) {
        AutopilotSavedData pads = AutopilotSavedData.get(level);
        Helipad destination = adHocTo != null ? adHocTo : toPad == null ? null : pads.helipad(toPad);
        if (destination == null) {
            return DispatchReasons.HOME_PAD_UNAVAILABLE;
        }
        PlaneAutopilot current = plane.getAutopilot();
        boolean running = current != null && current.isActive();
        if (!running && !AutopilotRegistry.canActivateAnother()) {
            return DispatchReasons.TOO_MANY_FLIGHTS;
        }
        String fromName = null;
        Helipad adHocFrom = null;
        Helipad fromPad;
        boolean grounded = plane.onGround() || plane.isOnWater();
        Helipad home = pads.helipad(s.homePad);
        if (grounded && home != null && atHome(level, s, plane)) {
            fromName = home.name();
            fromPad = home;
        } else if (grounded) {
            adHocFrom = s.zone != null && s.zone.covers(plane.position(), 1.0) ? s.zone : here(plane);
            fromPad = adHocFrom;
        } else {
            fromPad = here(plane);
        }
        RotorcraftProfile profile = RotorcraftProfile.of(plane);
        int altitude = profile.cruiseAltitude(level, fromPad, destination);
        if (altitude == Integer.MAX_VALUE) {
            return DispatchReasons.CEILING;
        }
        double speed = profile.clampCruiseSpeed(s.cruiseSpeed > 0 ? s.cruiseSpeed : profile.defaultCruiseSpeed());
        FlightPlan plan = FlightPlan.dispatchLeg(fromName, adHocFrom, adHocTo == null ? destination.name() : null,
            adHocTo, provisional, altitude, speed, s.orderId);
        if (running) {
            current.stop(plane);
        }
        PlaneAutopilot autopilot = new PlaneAutopilot();
        plane.setAutopilot(autopilot);
        autopilot.start(plane, plan, true, true, null);
        return null;
    }

    /** The target as a pad, for the provisional part of the outbound leg. */
    private static Helipad provisionalTarget(ServerLevel level, Sortie s) {
        BlockPos target = s.target == null ? s.lastKnown : s.target;
        int surface = TerrainScanner.surfaceHeight(level, target.getX() + 0.5, target.getZ() + 0.5);
        int ground = surface == TerrainScanner.UNKNOWN_HEIGHT ? target.getY() - 1 : surface - 1;
        return new Helipad("target", new BlockPos(target.getX(), ground, target.getZ()), 2, Helipad.allSectors());
    }

    /** Where the aircraft stands (or hangs), as a pad. */
    private static Helipad here(PlaneEntity plane) {
        return new Helipad("here", new BlockPos(Mth.floor(plane.getX()), Mth.floor(plane.getY() - 0.01) - 1,
            Mth.floor(plane.getZ())), 1, Helipad.allSectors());
    }

    private static boolean atHome(ServerLevel level, Sortie s, PlaneEntity plane) {
        Helipad home = AutopilotSavedData.get(level).helipad(s.homePad);
        return home != null && home.covers(plane.position(), 1.0)
            && Math.abs(plane.getY() - home.elevation()) <= AutopilotConfig.LANDING_ELEVATION_TOLERANCE;
    }

    // ------------------------------------------------------------------ outcomes and events

    private static void abort(ServerLevel level, DispatchSavedData data, Sortie s, String reason, @Nullable String detail) {
        if (s.abortReason != null) {
            return;
        }
        s.abortReason = reason;
        LOGGER.info("Dispatch {}: aircraft {} aborted: {} ({})", s.orderId, s.aircraft, reason, detail);
        emit(level, data, s, DispatchEvent.Type.ABORTED, reason);
        data.changed();
    }

    private static void lose(ServerLevel level, DispatchSavedData data, Sortie s, String reason) {
        s.phase = Phase.LOST;
        s.lostReason = reason;
        LOGGER.info("Dispatch {}: aircraft {} lost: {}", s.orderId, s.aircraft, reason);
        emit(level, data, s, DispatchEvent.Type.LOST, reason);
        forgetTransient(s.aircraft);
        data.changed();
    }

    private static void forgetTransient(UUID aircraft) {
        FINDERS.remove(aircraft);
        SEARCH_SINCE.remove(aircraft);
        UNRESOLVED_SINCE.remove(aircraft);
    }

    private static void emit(ServerLevel level, DispatchSavedData data, Sortie s, DispatchEvent.Type type,
                             @Nullable String reason) {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", type.name());
        tag.store("aircraft", UUIDUtil.CODEC, s.aircraft);
        if (s.orderId != null) {
            tag.store("order", UUIDUtil.CODEC, s.orderId);
        }
        tag.putString("owner", s.ownerId == null ? "" : s.ownerId);
        if (reason != null) {
            tag.putString("reason", reason);
        }
        tag.store("pos", BlockPos.CODEC, s.lastKnown);
        tag.putString("dimension", level.dimension().identifier().toString());
        tag.putLong("time", level.getGameTime());
        tag.putBoolean("aborted", s.abortReason != null);
        tag.put("user_data", s.userData.copy());
        data.post(tag);
    }

    private static void deliver(ServerLevel level, DispatchSavedData data) {
        List<CompoundTag> outbox = data.outbox();
        if (outbox.isEmpty() || LISTENERS.isEmpty()) {
            return;
        }
        List<CompoundTag> ready = new ArrayList<>();
        for (Iterator<CompoundTag> it = outbox.iterator(); it.hasNext(); ) {
            CompoundTag tag = it.next();
            if (LISTENERS.containsKey(tag.getStringOr("owner", ""))) {
                ready.add(tag);
                it.remove();
            }
        }
        if (ready.isEmpty()) {
            return;
        }
        data.changed();
        for (CompoundTag tag : ready) {
            DispatchEvent event = decode(tag);
            DispatchListener listener = event == null ? null : LISTENERS.get(event.ownerId());
            if (listener == null) {
                continue;
            }
            try {
                listener.onEvent(event);
            } catch (Throwable t) {
                LOGGER.error("Dispatch listener for {} threw on {}", event.ownerId(), event.type(), t);
            }
        }
    }

    private static @Nullable DispatchEvent decode(CompoundTag tag) {
        DispatchEvent.Type type;
        try {
            type = DispatchEvent.Type.valueOf(tag.getStringOr("type", ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
        UUID aircraft = tag.read("aircraft", UUIDUtil.CODEC).orElse(null);
        if (aircraft == null) {
            return null;
        }
        BlockPos pos = tag.read("pos", BlockPos.CODEC).orElse(BlockPos.ZERO);
        return new DispatchEvent(type, aircraft, tag.read("order", UUIDUtil.CODEC).orElse(null),
            tag.getStringOr("owner", ""), tag.getString("reason").orElse(null), pos.getX(), pos.getY(), pos.getZ(),
            tag.getStringOr("dimension", ""), tag.getLongOr("time", 0), tag.getBooleanOr("aborted", false),
            tag.getCompoundOrEmpty("user_data"));
    }

    static @Nullable MiniHelicopterEntity resolve(ServerLevel level, UUID aircraft) {
        return level.getEntity(aircraft) instanceof MiniHelicopterEntity plane
            && plane.isAlive() && !plane.isRemoved() ? plane : null;
    }

    /** Number of undelivered events in this dimension, for the command. */
    public static int undelivered(ServerLevel level) {
        return DispatchSavedData.get(level).outbox().size();
    }

    public static Set<String> listenerOwners() {
        return Set.copyOf(LISTENERS.keySet());
    }
}
