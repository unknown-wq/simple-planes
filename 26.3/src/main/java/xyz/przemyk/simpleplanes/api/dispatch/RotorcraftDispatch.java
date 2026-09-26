package xyz.przemyk.simpleplanes.api.dispatch;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.autopilot.DispatchService;
import xyz.przemyk.simpleplanes.autopilot.LandingZoneFinder;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Stable entry point for sending a mini helicopter out to an arbitrary position and back.
 *
 * <p>Server thread only. Every method takes the {@link ServerLevel} the aircraft is in; aircraft
 * are identified by entity UUID. Nothing here runs at class-load time and no listener is assumed to
 * exist: events for an owner without a listener are kept (persisted) until one registers.
 *
 * <p>Typical use: {@link #deploy} a mini helicopter item onto a registered pad once, then
 * {@link #dispatch} it with a {@link DispatchOrder}; load and unload passengers while it is on the
 * ground ({@link AircraftStatus.Phase#IDLE} or {@link AircraftStatus.Phase#AT_TARGET}); end the
 * ground hold early with {@link #release} or lengthen it with {@link #extendHold}.
 *
 * <p>Compatibility: {@link #API_VERSION} is bumped only for additive changes; existing signatures,
 * event types and reason codes are not changed or removed within a major version. See
 * {@code design/DISPATCH-API.md}.
 */
public final class RotorcraftDispatch {

    /** Version of this API. 1: initial. */
    public static final int API_VERSION = 1;

    /** Front seat. */
    public static final int SEAT_FRONT = 0;
    /** Litter on the right skid; medical livery only. */
    public static final int SEAT_LITTER = 1;
    /** First free seat, front first. */
    public static final int SEAT_ANY = -1;

    private RotorcraftDispatch() {}

    /** Same as {@link #API_VERSION}; a method for reflective callers. */
    public static int apiVersion() {
        return API_VERSION;
    }

    // ------------------------------------------------------------------ pads and landing zones

    /** Registered helipads within {@code radius} blocks (horizontal), nearest first; radius 0 for all. */
    public static List<PadInfo> padsNear(ServerLevel level, int x, int y, int z, int radius) {
        return DispatchService.padsNear(level, x, z, radius);
    }

    /**
     * One-shot landing-zone search around a position, nearest first. Bounded to a fixed number of
     * column reads; unloaded ground never qualifies.
     *
     * @param spec null for {@link LandingZoneSpec#DEFAULT}
     * @return the zone, or null when none qualifies within {@code radius}
     */
    public static @Nullable LandingZone findLandingZone(ServerLevel level, int x, int y, int z, int radius,
                                                        @Nullable LandingZoneSpec spec) {
        LandingZoneFinder finder = DispatchService.findNow(level, x, z, radius,
            spec == null ? LandingZoneSpec.DEFAULT : spec);
        return finder.result();
    }

    // ------------------------------------------------------------------ aircraft

    /** Whether this item can be deployed as a dispatch aircraft (a mini helicopter). */
    public static boolean isDispatchable(ItemStack stack) {
        return DispatchService.isDispatchable(stack);
    }

    /**
     * Places the aircraft in {@code stack} on a free registered pad and takes one item from the stack.
     *
     * @return the aircraft UUID, or null when refused (see {@link #tryDeploy} for the reason)
     */
    public static @Nullable UUID deploy(ServerLevel level, ItemStack stack, String padName) {
        return tryDeploy(level, stack, padName, null).id();
    }

    /** {@link #deploy} with the refusal reason and an owner recorded up front. */
    public static DispatchResult tryDeploy(ServerLevel level, ItemStack stack, String padName, @Nullable String ownerId) {
        return DispatchService.deploy(level, stack, Objects.requireNonNull(padName), ownerId);
    }

    /**
     * Takes an idle aircraft off the ground and returns it as an item (passengers are put off first).
     *
     * @return the item, or {@link ItemStack#EMPTY} when it is not loaded, not idle, or not on the ground
     */
    public static ItemStack stow(ServerLevel level, UUID aircraft) {
        return DispatchService.stow(level, aircraft);
    }

    /** Current state, or null for an aircraft the service does not know and cannot see. */
    public static @Nullable AircraftStatus status(ServerLevel level, UUID aircraft) {
        return DispatchService.status(level, aircraft);
    }

    /** Aircraft whose current or last order belongs to {@code ownerId}, including deployed idle ones. */
    public static List<UUID> aircraftOf(ServerLevel level, String ownerId) {
        return DispatchService.aircraftOf(level, ownerId);
    }

    // ------------------------------------------------------------------ orders

    /**
     * Sends an idle, loaded aircraft on an order. Passengers already aboard (a crew member) fly with it.
     *
     * @return {@code ok} with the order id in {@link DispatchResult#id()}, or a refusal reason
     */
    public static DispatchResult dispatch(ServerLevel level, UUID aircraft, DispatchOrder order) {
        return DispatchService.dispatch(level, aircraft, Objects.requireNonNull(order));
    }

    /** Lengthens the ground hold at the target (or, while outbound, the hold it will start with). */
    public static boolean extendHold(ServerLevel level, UUID aircraft, int ticks) {
        return DispatchService.extendHold(level, aircraft, ticks);
    }

    /** Ends the ground hold now; the aircraft leaves for home (or the order ends if it does not return). */
    public static boolean release(ServerLevel level, UUID aircraft) {
        return DispatchService.release(level, aircraft);
    }

    /** Alias of {@link #release}. */
    public static boolean departNow(ServerLevel level, UUID aircraft) {
        return release(level, aircraft);
    }

    /** Aborts the current order (reason RECALLED) and flies home; also brings an idle aircraft home. */
    public static boolean recall(ServerLevel level, UUID aircraft) {
        return DispatchService.recall(level, aircraft);
    }

    // ------------------------------------------------------------------ passengers

    /** {@link #loadPassenger(ServerLevel, UUID, Entity, int)} into the first free seat. */
    public static DispatchResult loadPassenger(ServerLevel level, UUID aircraft, Entity entity) {
        return loadPassenger(level, aircraft, entity, SEAT_ANY);
    }

    /**
     * Seats a non-player entity within 16 blocks of the grounded aircraft. It never steers.
     *
     * @param seat {@link #SEAT_FRONT}, {@link #SEAT_LITTER} or {@link #SEAT_ANY}
     */
    public static DispatchResult loadPassenger(ServerLevel level, UUID aircraft, Entity entity, int seat) {
        return DispatchService.loadPassenger(level, aircraft, Objects.requireNonNull(entity), seat);
    }

    /** Puts one passenger off beside the grounded aircraft. */
    public static boolean unloadPassenger(ServerLevel level, UUID aircraft, UUID entity) {
        return DispatchService.unloadPassenger(level, aircraft, entity);
    }

    /** Puts every non-player passenger off; returns how many. */
    public static int unloadAll(ServerLevel level, UUID aircraft) {
        return DispatchService.unloadAll(level, aircraft);
    }

    /** Entities aboard, front seat first. */
    public static List<UUID> passengers(ServerLevel level, UUID aircraft) {
        return DispatchService.passengers(level, aircraft);
    }

    // ------------------------------------------------------------------ events

    /** Replaces the listener for {@code ownerId}. Queued events for that owner are delivered next tick. */
    public static void registerListener(String ownerId, DispatchListener listener) {
        DispatchService.registerListener(Objects.requireNonNull(ownerId), Objects.requireNonNull(listener));
    }

    public static void unregisterListener(String ownerId) {
        DispatchService.unregisterListener(ownerId);
    }
}
