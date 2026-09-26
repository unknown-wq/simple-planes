package xyz.przemyk.simpleplanes.missile;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The silo multiblock: a master block plus dependents.
 *
 * <p>The master ({@link LaunchSiloBlock}) is the top-layer block at the minimum X/Z corner; its top face is the
 * ground surface. The volume is {@code footprint x footprint x (tier + 1)} blocks going down from there, and every
 * other block in it is a {@link LaunchSiloCasingBlock} whose state points back at the master. Removing any part
 * removes the whole structure.
 */
public final class SiloStructure {

    /** Largest possible volume around a master: 2 x 2 footprint, 5 layers. */
    private static final int MAX_FOOTPRINT = 2;
    private static final int MAX_LAYERS = 5;

    private static boolean dismantling;

    private SiloStructure() {}

    public static List<BlockPos> volume(BlockPos master, MissileTier tier) {
        List<BlockPos> out = new ArrayList<>();
        for (int dy = 0; dy < tier.depthBlocks(); dy++)
            for (int dx = 0; dx < tier.footprint; dx++)
                for (int dz = 0; dz < tier.footprint; dz++)
                    out.add(master.offset(dx, -dy, dz));
        return out;
    }

    /** The surface point on the tube axis: centre of the footprint, on the master's top face. */
    public static Vec3 mouth(BlockPos master, MissileTier tier) {
        double half = tier.footprint / 2.0;
        return new Vec3(master.getX() + half, master.getY() + 1.0, master.getZ() + half);
    }

    /** Why a silo cannot be placed here, or null if it can. */
    public static @Nullable String checkPlacement(ServerLevel level, BlockPos master, MissileTier tier) {
        if (master.getY() - tier.tier < level.getMinY()) return "the shaft would reach below the bottom of the world";
        if (master.getY() + 1 > level.getMaxY()) return "above the build limit";
        for (BlockPos p : volume(master, tier)) {
            BlockState state = level.getBlockState(p);
            if (state.getBlock() instanceof LaunchSiloBlock || state.getBlock() instanceof LaunchSiloCasingBlock)
                return "overlaps another silo at " + p.toShortString();
            if (state.hasBlockEntity()) return "a block entity is in the way at " + p.toShortString();
            if (state.getDestroySpeed(level, p) < 0) return "an unbreakable block is in the way at " + p.toShortString();
        }
        return null;
    }

    /** Places the structure, replacing whatever was in its volume (checked by {@link #checkPlacement} first). */
    public static void place(ServerLevel level, BlockPos master, MissileTier tier) {
        for (BlockPos p : volume(master, tier)) {
            if (p.equals(master)) continue;
            BlockState casing = Missiles.LAUNCH_SILO_CASING.defaultBlockState()
                .setValue(LaunchSiloCasingBlock.DX, p.getX() - master.getX())
                .setValue(LaunchSiloCasingBlock.DY, master.getY() - p.getY())
                .setValue(LaunchSiloCasingBlock.DZ, p.getZ() - master.getZ());
            level.setBlock(p, casing, Block.UPDATE_CLIENTS);
        }
        level.setBlock(master, Missiles.LAUNCH_SILO.defaultBlockState().setValue(LaunchSiloBlock.TIER, tier.tier), Block.UPDATE_ALL);
    }

    public static boolean isIntact(ServerLevel level, BlockPos master, MissileTier tier) {
        BlockState top = level.getBlockState(master);
        if (!(top.getBlock() instanceof LaunchSiloBlock) || top.getValue(LaunchSiloBlock.TIER) != tier.tier) return false;
        for (BlockPos p : volume(master, tier)) {
            if (p.equals(master)) continue;
            BlockState state = level.getBlockState(p);
            if (!(state.getBlock() instanceof LaunchSiloCasingBlock) || !LaunchSiloCasingBlock.masterOf(p, state).equals(master)) return false;
        }
        return true;
    }

    /** Resolves any silo part to its master, or null if the position is not part of a silo. */
    public static @Nullable BlockPos masterOf(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof LaunchSiloBlock) return pos;
        if (state.getBlock() instanceof LaunchSiloCasingBlock) {
            BlockPos master = LaunchSiloCasingBlock.masterOf(pos, state);
            return level.getBlockState(master).getBlock() instanceof LaunchSiloBlock ? master : null;
        }
        return null;
    }

    static void onPartRemoved(ServerLevel level, BlockPos master) {
        if (!dismantling) dismantle(level, master);
    }

    /** Removes every remaining part of the silo whose master is (or was) at {@code master}. Returns the count. */
    public static int dismantle(ServerLevel level, BlockPos master) {
        int removed = 0;
        boolean outer = !dismantling;
        dismantling = true;
        try {
            for (int dy = 0; dy < MAX_LAYERS; dy++)
                for (int dx = 0; dx < MAX_FOOTPRINT; dx++)
                    for (int dz = 0; dz < MAX_FOOTPRINT; dz++) {
                        BlockPos p = master.offset(dx, -dy, dz);
                        BlockState state = level.getBlockState(p);
                        boolean part = p.equals(master)
                            ? state.getBlock() instanceof LaunchSiloBlock
                            : state.getBlock() instanceof LaunchSiloCasingBlock && LaunchSiloCasingBlock.masterOf(p, state).equals(master);
                        if (part) {
                            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                            removed++;
                        }
                    }
        } finally {
            if (outer) dismantling = false;
        }
        MissileTracker.releaseSilo(level, master);
        return removed;
    }
}
