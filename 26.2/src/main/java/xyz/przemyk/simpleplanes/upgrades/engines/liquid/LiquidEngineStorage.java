package xyz.przemyk.simpleplanes.upgrades.engines.liquid;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
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
            for (PlaneEntity plane : level.getEntitiesOfClass(PlaneEntity.class, new AABB(pos))) {
                if (plane.engineUpgrade instanceof LiquidEngineUpgrade liquidEngine) {
                    return liquidEngine.fluidTank;
                }
            }
            return null;
        });
    }
}
