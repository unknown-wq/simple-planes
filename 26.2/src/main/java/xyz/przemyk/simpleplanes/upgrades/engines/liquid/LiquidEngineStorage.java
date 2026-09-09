package xyz.przemyk.simpleplanes.upgrades.engines.liquid;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.FilteringStorage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

/**
 * Lets a fluid pipe or tank standing next to a parked aircraft refuel it.
 *
 * <p><b>Why a block lookup and not an entity one.</b> A plane is an entity, and the obvious thing to
 * want is {@code FluidStorage.ENTITY}. There is no such lookup: {@code fabric-transfer-api-v1} 8.0.11
 * exposes fluid storages on <em>blocks</em> ({@link FluidStorage#SIDED}) and on <em>items</em>
 * ({@link FluidStorage#ITEM}), and nothing else. {@code fabric-api-lookup-api-v1} does have an
 * {@code EntityApiLookup}, but a lookup only works if both sides agree on it, and no other mod
 * queries one this mod invents. So the aircraft is offered where other mods already look: at the
 * block positions its hull occupies.
 *
 * <p><b>Not sided.</b> The same tank answers from every direction. A plane has no faces worth
 * distinguishing — it is not built out of a block whose orientation a player can see and plan
 * around, and its heading changes every time it lands.
 *
 * <p><b>A fallback, and what it costs.</b> The provider runs only where the lookup found nothing
 * else, and returns immediately unless the position is air with no block entity — a block that owns
 * a storage keeps it, and an aircraft cannot be inside a solid block anyway. Only then does it ask
 * for entities in that one block, which is what a neighbouring pipe pays: one small entity query
 * per adjacent air block it polls.
 *
 * <p><b>Parked, and only parked.</b> {@link #isParked} is the whole safety story. The block a pipe
 * polls is an ordinary air block, and an aircraft in flight passes through air blocks all the time:
 * without this test any pipe under a flight path would answer for the aeroplane crossing it, and
 * would refuel — or, worse, drain — an aircraft with a pilot in it a hundred blocks up. So the
 * aircraft has to be standing on the ground (or floating, for a plane that landed on water) with its
 * throttle closed, which is the state a plane is left in: {@code getDismountLocationForPassenger}
 * zeroes the throttle when the last passenger gets out, and a plane cannot take off without opening
 * it again. A pilot may sit in the cockpit while the tank fills — throttle 0 on the ground is parked
 * whether or not anyone is aboard, and refuelling from the seat is the point of a fuel apron.
 *
 * <p><b>Why the test is repeated inside the view and not only here.</b> A consumer is allowed to
 * hold on to a {@code Storage} it found — {@code BlockApiCache} exists precisely so a pipe can keep
 * one across ticks — so a handle obtained while the plane was parked would otherwise keep working
 * after take-off. {@link RefuellingView} therefore re-tests on every single operation, which makes
 * the gate impossible to outlive.
 *
 * <p><b>Insertion only.</b> What is published here is a refuelling port, not a fuel dump: it accepts
 * fuel and never gives any back. Extraction is the half of the contract that can kill somebody — an
 * engine that cuts out is an aircraft that falls — and nothing needs it. Emptying the tank is
 * already possible from the aircraft's own input slot, where an empty bucket or any modded fluid
 * container is filled from {@link PlaneFluidTank} directly (see {@code LiquidEngineUpgrade}); that
 * path is the pilot's own deliberate act, it is unaffected by this, and it is a better answer than a
 * stranger's pipe siphoning a parked plane dry. The contents stay <em>readable</em> — the view
 * forwards the tank's storage view — so a gauge or a pipe can still see what is in there and how
 * full it is.
 *
 * <p><b>Server side only.</b> A transfer committed on a client would write fuel the server never
 * hears about, and the next sync packet would silently undo it. A client-side reader loses nothing
 * that matters: the gauge in the plane's own screen reads the synced copy directly.
 */
public final class LiquidEngineStorage {

    private LiquidEngineStorage() {}

    public static void register() {
        FluidStorage.SIDED.registerFallback((level, pos, state, blockEntity, direction) -> {
            if (!(level instanceof ServerLevel) || blockEntity != null || !state.isAir()) {
                return null;
            }
            for (PlaneEntity plane : level.getEntitiesOfClass(PlaneEntity.class, new AABB(pos), LiquidEngineStorage::isParked)) {
                if (plane.engineUpgrade instanceof LiquidEngineUpgrade liquidEngine) {
                    return new RefuellingView(plane, liquidEngine.fluidTank);
                }
            }
            return null;
        });
    }

    /**
     * Whether the aircraft is standing still on the ground or on the water with the engine idle —
     * the only state in which a neighbouring machine may touch its tank.
     */
    private static boolean isParked(PlaneEntity plane) {
        return plane.getThrottle() == 0 && (plane.getOnGround() || plane.isOnWater());
    }

    /**
     * The tank as the rest of the world sees it: insert while parked, never extract, always
     * readable. The parked test is re-run on every operation, so a handle cached while the aircraft
     * stood still is worth nothing the moment it rolls.
     *
     * <p>{@link FilteringStorage} is used rather than a hand-written wrapper because it also wraps
     * the {@code StorageView}s its iterator yields, and a bare {@code SingleVariantStorage} hands
     * <em>itself</em> out as its own view — a wrapper that guarded only {@code extract} would be
     * walked straight around by anything that iterates the storage first.
     */
    private static final class RefuellingView extends FilteringStorage<FluidVariant> {

        private final PlaneEntity plane;

        private RefuellingView(PlaneEntity plane, PlaneFluidTank tank) {
            super(tank);
            this.plane = plane;
        }

        @Override
        protected boolean canInsert(FluidVariant variant) {
            return isParked(plane);
        }

        @Override
        protected boolean canExtract(FluidVariant variant) {
            return false;
        }

        /** Say so up front, so a pipe looking for a source never picks this as one. */
        @Override
        public boolean supportsExtraction() {
            return false;
        }
    }
}
