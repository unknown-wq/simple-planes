package xyz.przemyk.simpleplanes.missile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The silo multiblock: a master block plus dependents.
 *
 * <p>The master ({@link LaunchSiloBlock}) is the top-layer block at the minimum X/Z corner; its top face is the
 * ground surface. The volume is {@code footprint x footprint x (tier + 1)} blocks going down from there, and every
 * other block in it is a {@link LaunchSiloCasingBlock} whose state points back at the master. Removing any part
 * removes the whole structure and puts back the blocks the silo displaced, which its block entity records.
 */
public final class SiloStructure {

    /** Blocks the silo item may dig through, besides replaceable ones such as air, water and plants. */
    public static final TagKey<Block> GROUND = TagKey.create(Registries.BLOCK, Missiles.id("silo_ground"));

    /** Largest possible volume around a master: 2 x 2 footprint, 5 layers. */
    private static final int MAX_FOOTPRINT = 2;
    private static final int MAX_LAYERS = 5;
    /** Filler for a removed silo that has no record of what it displaced (built before records existed). */
    private static final BlockState FALLBACK = Blocks.DIRT.defaultBlockState();

    /** Suppresses the removal cascade while the structure itself is being rebuilt or dismantled. */
    private static boolean dismantling;
    private static @Nullable Stash stash;
    private static @Nullable PendingDrop pendingDrop;

    private record Stash(ResourceKey<Level> dimension, BlockPos master, Map<BlockPos, BlockState> record) {}

    private record PendingDrop(ResourceKey<Level> dimension, BlockPos broken, BlockPos master, int tier) {}

    private record Deferred(ResourceKey<Level> dimension, BlockPos pos, BlockState state) {}

    /** The broken part itself is put back on the next level tick, so the removal that broke it still reports success. */
    private static final List<Deferred> DEFERRED = new ArrayList<>();

    /** Outcome of an upgrade: a problem, or null with the new master, tier and the direction the shaft grew. */
    public record Upgrade(@Nullable String problem, BlockPos master, MissileTier tier, String grewToward) {
        static Upgrade refused(String problem, BlockPos master, MissileTier tier) {
            return new Upgrade(problem, master, tier, "");
        }
    }

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

    private static boolean isPart(BlockState state) {
        return state.getBlock() instanceof LaunchSiloBlock || state.getBlock() instanceof LaunchSiloCasingBlock;
    }

    /** Why {@code /missile silo place} cannot build here, or null if it can. Replaces anything breakable. */
    public static @Nullable String checkPlacement(ServerLevel level, BlockPos master, MissileTier tier) {
        if (master.getY() - tier.tier < level.getMinY()) return "the shaft would reach below the bottom of the world";
        if (master.getY() + 1 > level.getMaxY()) return "above the build limit";
        for (BlockPos p : volume(master, tier)) {
            BlockState state = level.getBlockState(p);
            if (isPart(state)) return "overlaps another silo at " + p.toShortString();
            if (state.hasBlockEntity()) return "a block entity is in the way at " + p.toShortString();
            if (state.getDestroySpeed(level, p) < 0) return "an unbreakable block is in the way at " + p.toShortString();
        }
        return null;
    }

    /** Why the silo item cannot build a silo here, or null if it can. Only natural ground and replaceable blocks are dug. */
    public static @Nullable String checkItemPlacement(ServerLevel level, BlockPos master, MissileTier tier, @Nullable Player player) {
        if (master.getY() + 1 > level.getMaxY()) return "above the build limit";
        List<BlockPos> cells = volume(master, tier);
        for (BlockPos p : cells) {
            String problem = checkCell(level, p, player);
            if (problem != null) return problem;
        }
        return checkEntities(level, cells);
    }

    private static @Nullable String checkCell(ServerLevel level, BlockPos p, @Nullable Player player) {
        if (p.getY() < level.getMinY()) return "the shaft would reach below the bottom of the world";
        BlockState state = level.getBlockState(p);
        String where = name(state) + " at " + p.toShortString();
        if (isPart(state)) return "another silo is in the way at " + p.toShortString();
        if (state.hasBlockEntity()) return where + " is a block entity";
        if (state.getDestroySpeed(level, p) < 0) return where + " is unbreakable";
        if (!state.canBeReplaced() && !state.is(GROUND))
            return where + " is not natural ground (only earth, stone, sand, ores and the like are dug out)";
        if (player != null && !level.mayInteract(player, p)) return "the ground at " + p.toShortString() + " is protected";
        return null;
    }

    private static @Nullable String checkEntities(ServerLevel level, List<BlockPos> cells) {
        if (cells.isEmpty()) return null;
        AABB all = new AABB(cells.getFirst());
        for (BlockPos p : cells) all = all.minmax(new AABB(p));
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, all.deflate(1.0E-3), e -> e.isAlive() && !e.isSpectator())) {
            for (BlockPos p : cells) {
                if (e.getBoundingBox().intersects(new AABB(p).deflate(1.0E-3)))
                    return e.getName().getString() + " is in the way at " + p.toShortString();
            }
        }
        return null;
    }

    private static String name(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /** Places the structure, replacing whatever was in its volume and recording it (checked beforehand). */
    public static void place(ServerLevel level, BlockPos master, MissileTier tier) {
        Map<BlockPos, BlockState> record = capture(level, volume(master, tier));
        build(level, master, tier);
        if (level.getBlockEntity(master) instanceof LaunchSiloBlockEntity be) be.recordDisplaced(record);
    }

    private static Map<BlockPos, BlockState> capture(ServerLevel level, List<BlockPos> cells) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (BlockPos p : cells) out.put(p.immutable(), level.getBlockState(p));
        return out;
    }

    private static void build(ServerLevel level, BlockPos master, MissileTier tier) {
        boolean outer = !dismantling;
        dismantling = true;
        try {
            for (BlockPos p : volume(master, tier)) {
                if (p.equals(master)) continue;
                BlockState casing = Missiles.LAUNCH_SILO_CASING.defaultBlockState()
                    .setValue(LaunchSiloCasingBlock.DX, p.getX() - master.getX())
                    .setValue(LaunchSiloCasingBlock.DY, master.getY() - p.getY())
                    .setValue(LaunchSiloCasingBlock.DZ, p.getZ() - master.getZ());
                level.setBlock(p, casing, Block.UPDATE_CLIENTS);
            }
            level.setBlock(master, Missiles.LAUNCH_SILO.defaultBlockState().setValue(LaunchSiloBlock.TIER, tier.tier), Block.UPDATE_ALL);
        } finally {
            if (outer) dismantling = false;
        }
    }

    /**
     * Raises the silo one tier. 1 to 2 and 3 to 4 deepen the shaft by one layer under the same footprint; 2 to 3
     * widens the 1x1 column to 2x2 with the old column as one corner. The corner is chosen from {@code look} (the
     * new columns grow the way the player faces), falling back to the other three. Only natural ground and
     * replaceable blocks are dug, and everything dug is recorded so removal puts it back.
     */
    public static Upgrade upgrade(ServerLevel level, BlockPos master, @Nullable Vec3 look, @Nullable Player player) {
        if (!(level.getBlockEntity(master) instanceof LaunchSiloBlockEntity be)) return Upgrade.refused("no silo there", master, MissileTier.T1);
        MissileTier tier = be.tier();
        if (tier == MissileTier.T4) return Upgrade.refused("it is already tier 4, the largest", master, tier);
        if (!isIntact(level, master, tier)) return Upgrade.refused("the silo structure is damaged", master, tier);
        if (be.phase() != LaunchSiloBlockEntity.Phase.IDLE)
            return Upgrade.refused("it is busy (" + be.phase().name().toLowerCase(Locale.ROOT) + ")", master, tier);
        if (be.isLoaded()) return Upgrade.refused("a missile is loaded; unload it first", master, tier);
        MissileTier next = MissileTier.of(tier.tier + 1);
        Set<BlockPos> old = new HashSet<>(volume(master, tier));

        String firstProblem = null;
        for (int[] offset : corners(tier, next, look)) {
            BlockPos candidate = master.offset(offset[0], 0, offset[1]);
            List<BlockPos> fresh = new ArrayList<>();
            for (BlockPos p : volume(candidate, next)) if (!old.contains(p)) fresh.add(p);
            String problem = null;
            for (BlockPos p : fresh) {
                problem = checkCell(level, p, player);
                if (problem != null) break;
            }
            if (problem == null) problem = checkEntities(level, fresh);
            if (problem != null) {
                if (firstProblem == null) firstProblem = problem;
                continue;
            }
            Map<BlockPos, BlockState> record = capture(level, fresh);
            build(level, candidate, next);
            if (level.getBlockEntity(candidate) instanceof LaunchSiloBlockEntity grown) {
                if (grown != be) grown.adopt(be);
                grown.recordDisplaced(record);
            }
            String toward = next.footprint == tier.footprint ? "down"
                : (offset[1] == 0 ? "south" : "north") + "-" + (offset[0] == 0 ? "east" : "west");
            return new Upgrade(null, candidate, next, toward);
        }
        return Upgrade.refused(firstProblem, master, tier);
    }

    /** Master offsets to try, best first: only the current master for the same footprint, else the four corners. */
    private static List<int[]> corners(MissileTier from, MissileTier to, @Nullable Vec3 look) {
        List<int[]> out = new ArrayList<>();
        if (to.footprint == from.footprint) {
            out.add(new int[]{0, 0});
            return out;
        }
        double lx = look == null ? 1.0 : look.x;
        double lz = look == null ? 1.0 : look.z;
        for (int ox = 0; ox >= -1; ox--)
            for (int oz = 0; oz >= -1; oz--) out.add(new int[]{ox, oz});
        // offset 0 keeps the old column at the minimum corner, so the new columns go toward +X / +Z
        out.sort(Comparator.comparingDouble(o -> -((o[0] == 0 ? lx : -lx) + (o[1] == 0 ? lz : -lz))));
        return out;
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

    static void onPartRemoved(ServerLevel level, BlockPos master, BlockPos removed) {
        if (!dismantling) dismantle(level, master, removed);
    }

    /** The master's block entity is going away: keep its record for the dismantling that follows. */
    static void stashRecord(ServerLevel level, BlockPos master, Map<BlockPos, BlockState> record) {
        if (!dismantling) stash = new Stash(level.dimension(), master.immutable(), Map.copyOf(record));
    }

    public static int dismantle(ServerLevel level, BlockPos master) {
        return dismantle(level, master, null);
    }

    /**
     * Removes every remaining part of the silo whose master is (or was) at {@code master} and puts back what the
     * silo displaced, bottom layer first. {@code removed} is the part whose removal started this, already air.
     * Returns the number of blocks put back.
     */
    public static int dismantle(ServerLevel level, BlockPos master, @Nullable BlockPos removed) {
        Map<BlockPos, BlockState> record;
        if (level.getBlockEntity(master) instanceof LaunchSiloBlockEntity be) {
            record = be.displaced();
        } else if (stash != null && stash.dimension() == level.dimension() && stash.master().equals(master)) {
            record = stash.record();
        } else {
            record = Map.of();
        }
        stash = null;
        List<BlockPos> parts = new ArrayList<>();
        for (int dy = 0; dy < MAX_LAYERS; dy++)
            for (int dx = 0; dx < MAX_FOOTPRINT; dx++)
                for (int dz = 0; dz < MAX_FOOTPRINT; dz++) {
                    BlockPos p = master.offset(dx, -dy, dz);
                    BlockState state = level.getBlockState(p);
                    boolean part = p.equals(master)
                        ? state.getBlock() instanceof LaunchSiloBlock
                        : state.getBlock() instanceof LaunchSiloCasingBlock && LaunchSiloCasingBlock.masterOf(p, state).equals(master);
                    if (part || p.equals(removed) && state.isAir()) parts.add(p);
                }
        parts.sort(Comparator.comparingInt(BlockPos::getY));
        boolean outer = !dismantling;
        dismantling = true;
        try {
            for (BlockPos p : parts) {
                BlockState back = record.getOrDefault(p, FALLBACK);
                if (isPart(back)) back = Blocks.AIR.defaultBlockState();
                if (p.equals(removed)) DEFERRED.add(new Deferred(level.dimension(), p.immutable(), back));
                else level.setBlock(p, back, Block.UPDATE_ALL);
            }
        } finally {
            if (outer) dismantling = false;
        }
        MissileTracker.releaseSilo(level, master);
        return parts.size();
    }

    /** Puts back the broken parts of silos removed since the last tick, where nothing else has been put since. */
    static void flushDeferred(ServerLevel level) {
        if (DEFERRED.isEmpty()) return;
        for (Deferred d : List.copyOf(DEFERRED)) {
            if (d.dimension() != level.dimension()) continue;
            DEFERRED.remove(d);
            if (level.getBlockState(d.pos()).isAir()) level.setBlock(d.pos(), d.state(), Block.UPDATE_ALL);
        }
    }

    /** A player is about to break a silo part: remember the silo's tier for the drop. */
    static void noteBreak(ServerLevel level, BlockPos pos) {
        BlockPos master = masterOf(level, pos);
        pendingDrop = master == null ? null
            : new PendingDrop(level.dimension(), pos.immutable(), master, level.getBlockState(master).getValue(LaunchSiloBlock.TIER));
    }

    /** A player broke a silo part in survival: one silo item per tier comes back, at the silo mouth. */
    static void dropItems(ServerLevel level, BlockPos pos) {
        PendingDrop drop = pendingDrop;
        pendingDrop = null;
        if (drop == null || drop.dimension() != level.dimension() || !drop.broken().equals(pos)) return;
        Block.popResource(level, drop.master().above(), new ItemStack(Missiles.LAUNCH_SILO_ITEM, drop.tier()));
    }
}
