package xyz.przemyk.simpleplanes.setup;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.entities.*;

import java.util.function.Supplier;

/**
 * The mod's entity types, and how far away the server bothers to tell a client they exist.
 *
 * <h2>Tracking range</h2>
 * {@code clientTrackingRange} is in <em>chunks</em>, and it is the radius inside which a player is
 * sent an entity at all. Outside it the entity is not merely unrendered — the client has never heard
 * of it, so there is nothing to draw, nothing to hear and nothing to aim at.
 *
 * <p>Both numbers used to be {@link EntityType.Builder}'s untouched defaults, 5 chunks and a 3-tick
 * update interval. The 3 is a good default and is kept; the 5 never was one for this mod, it was
 * simply never set. Eighty blocks is shorter than the world a default server sends: a client at the
 * default view distance is being streamed terrain to 160 blocks, so an aircraft used to wink out of
 * existence over ground that was still being drawn, and a player watching one take off lost it after
 * about a minute of flight — or, for the autopilot, could not see an unmanned aircraft coming until
 * it was almost overhead.
 *
 * <p>It bit unmanned aircraft and nothing else, which is why it survived so long. Vanilla's
 * {@code ChunkMap.TrackedEntity#getEffectiveRange} takes the <em>largest</em> tracking range among an
 * entity and all of its passengers, and a player's own type asks for 32 chunks. Any aircraft with a
 * player aboard was therefore already tracked to the view-distance limit, and only the ones flying
 * themselves were held to 80 blocks — exactly the aircraft nobody is sitting in to notice.
 *
 * <h2>Why 10 and not more</h2>
 * Ten chunks is what vanilla gives a boat, and an aircraft is not a thing you want to see from nearer
 * than a boat. It is also the ceiling that actually applies: {@code updatePlayer} clamps the range to
 * {@code min(effectiveRange, playerViewDistance * 16)}, and {@code playerViewDistance} is the
 * server's own view distance (10 by default) capped against what the client asked for. On a stock
 * server every value of 10 or above therefore behaves identically, so 10 buys the whole of the
 * available fix and nothing beyond it can be spent. Going higher would only take effect on servers
 * whose operator has already raised the view distance, and would put a fast entity's update stream on
 * players several hundred blocks away without being asked to.
 *
 * <p>The result is the honest rule: <b>an aircraft is visible exactly as far as the ground it is
 * flying over.</b>
 *
 * <h2>What it costs</h2>
 * Per tracking player, an aircraft is one position packet every {@code updateInterval} ticks. A
 * cruising aircraft moves under 8 blocks in 3 ticks and so fits {@code ClientboundMoveEntityPacket},
 * about 12 bytes; one at full throttle (2.8 blocks/tick, i.e. 8.4 blocks per interval) overruns the
 * 1/4096-block short that packet encodes deltas in, trips {@code deltaTooBig} and falls back to
 * {@code ClientboundEntityPositionSyncPacket} — two {@code Vec3}s and two floats, about 60 bytes.
 * Worst case is therefore 60 bytes per 3 ticks, <b>400 bytes/second per player per aircraft</b>.
 *
 * <p>Raising the radius does not change that rate; it changes how many players pay it. Area grows
 * with the square, so at worst four times as many players are in range as at 80 blocks. Four players
 * around one aircraft is 1.6 kB/s, and {@link xyz.przemyk.simpleplanes.autopilot.AutopilotConfig#MAX_ACTIVE_AUTOPILOTS}
 * caps the fleet at 24, so the absolute ceiling — 24 aircraft simultaneously flat out, each with four
 * players inside 160 blocks — is about 38 kB/s across the whole server. That bound is unreachable in
 * practice and is still less than the server spends sending a single chunk. The realistic figure, a
 * flight or two with a player or two watching, is under 2 kB/s.
 *
 * <h2>Why the update interval stays at 3</h2>
 * A wide radius with a slow interval is what produces stutter at distance, so it is worth saying why
 * 3 is not slow. It matches vanilla's own vehicles, and these types are not on the exclusion list in
 * {@code EntityType#trackDeltas}, so each aircraft's velocity is broadcast alongside its position and
 * the client extrapolates between updates rather than waiting for the next one. Movement therefore
 * reads as continuous at 6.7 updates/second. Lowering it to 2 would buy nothing visible for 50% more
 * packets; raising it is what would cause the stutter this note is about.
 */
@SuppressWarnings("unused")
public class SimplePlanesEntities {

    /**
     * Tracking radius for aircraft, in chunks — 160 blocks. See the class note: this is vanilla's
     * boat range, and on a default server it is also the largest value that has any effect.
     */
    private static final int AIRCRAFT_TRACKING_RANGE = 10;

    /**
     * Tracking radius for a parachute, in chunks — 128 blocks.
     *
     * <p>Less than an aircraft's, deliberately. A parachute is a one-block object that descends at a
     * tenth of a block per tick; there is no approach to watch and nothing about it needs to be
     * picked out of the sky from 160 blocks, so it does not get the aircraft number just for being in
     * the same file.
     *
     * <p>It does need more than the old 5, and for a reason particular to how it is used rather than
     * a general wish for more range. Because {@code getEffectiveRange} maximises over passengers, an
     * occupied parachute was never limited to 80 blocks in the first place: one under a player was
     * tracked at the player's 32 chunks and one under a mob at that mob's own range, which for the
     * raiding mobs likely to be dropped under it is 8. The only parachutes that ever vanished at 80
     * blocks were therefore the <em>empty</em> ones — the canopy left behind when a rider dismounts,
     * and one whose rider was killed on the way down — and they disappeared at a different distance
     * from the identical parachute next to them with somebody still on it. 8 chunks removes that
     * inconsistency rather than inventing a number, and it costs almost nothing: at 0.1 blocks/tick a
     * parachute's 3-tick delta is 0.3 blocks, always the compact 12-byte packet, for the few hundred
     * ticks it takes to reach the ground.
     */
    private static final int PARACHUTE_TRACKING_RANGE = 8;

    /**
     * Ticks between position updates. Vanilla's default and vanilla's vehicle value; see the class
     * note for why it is not raised or lowered.
     */
    private static final int UPDATE_INTERVAL = 3;

    /** Class-load hook — entity types are registered eagerly below (contract C1). */
    public static void init() {
    }

    public static ResourceKey<EntityType<?>> entityKey(String name) {
        return ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, name));
    }

    private static <T extends Entity> Supplier<EntityType<T>> register(String name, EntityType.EntityFactory<T> factory, float width, float height) {
        return register(name, factory, width, height, AIRCRAFT_TRACKING_RANGE);
    }

    private static <T extends Entity> Supplier<EntityType<T>> register(String name, EntityType.EntityFactory<T> factory, float width, float height,
                                                                       int trackingRange) {
        EntityType<T> value = Registry.register(BuiltInRegistries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, name),
            EntityType.Builder.of(factory, MobCategory.MISC)
                .sized(width, height)
                .clientTrackingRange(trackingRange)
                .updateInterval(UPDATE_INTERVAL)
                .build(entityKey(name)));
        return () -> value;
    }

    public static final Supplier<EntityType<PlaneEntity>> PLANE = register("plane", PlaneEntity::new, 2.5F, 1.8F);
    public static final Supplier<EntityType<LargePlaneEntity>> LARGE_PLANE = register("large_plane", LargePlaneEntity::new, 3F, 2.3F);
    public static final Supplier<EntityType<CargoPlaneEntity>> CARGO_PLANE = register("cargo_plane", CargoPlaneEntity::new, 3F, 2.3F);
    public static final Supplier<EntityType<HelicopterEntity>> HELICOPTER = register("helicopter", HelicopterEntity::new, 2.5F, 2.2F);

    public static final Supplier<EntityType<ParachuteEntity>> PARACHUTE =
        register("parachute", ParachuteEntity::new, 1.0F, 1.0F, PARACHUTE_TRACKING_RANGE);
}
