package xyz.przemyk.simpleplanes.airdefence;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.missile.LaunchSiloBlockEntity;
import xyz.przemyk.simpleplanes.missile.MissileTracker;
import xyz.przemyk.simpleplanes.missile.Missiles;
import xyz.przemyk.simpleplanes.missile.SiloStructure;

import java.util.UUID;

/**
 * The silo side of air defence: the idle scan, keeping or replacing the target while the hatch opens, and the
 * right-click toggle. Nothing here holds a chunk ticket; a silo in an unloaded chunk does not tick and does nothing.
 */
public final class AirDefenceSilo {

    private AirDefenceSilo() {}

    /** Called from the silo's server tick while it is idle in air-defence mode. */
    public static void idleTick(ServerLevel level, LaunchSiloBlockEntity silo) {
        BlockPos pos = silo.getBlockPos();
        // spread silos over the scan interval
        if (Math.floorMod(level.getGameTime() + Mth.getSeed(pos), InterceptorSpec.SCAN_INTERVAL) != 0) return;
        if (!silo.isLoaded()) return;
        PlaneEntity target = TargetSelector.select(level, pos, silo.tier(), Engagements.silo(level, pos));
        if (target == null) return;
        if (silo.launchAirDefence(level, target) == null) {
            MissileTracker.LOGGER.info("[airdefence] silo {} T{} engaging #{} at {} blocks", pos.toShortString(), silo.tier().tier,
                target.getId(), String.format(java.util.Locale.ROOT, "%.1f",
                    AircraftRoster.aimPoint(target).distanceTo(SiloStructure.mouth(pos, silo.tier()))));
        }
    }

    /** While the hatch opens: keep the target, or re-target if it is gone. False aborts the launch. */
    public static boolean keepTarget(ServerLevel level, LaunchSiloBlockEntity silo) {
        BlockPos pos = silo.getBlockPos();
        Engagements.SiloEngager self = Engagements.silo(level, pos);
        InterceptorSpec spec = InterceptorSpec.of(silo.tier());
        PlaneEntity current = resolve(level, silo.airDefenceTarget());
        if (current == null) {
            PlaneEntity next = TargetSelector.select(level, pos, silo.tier(), self);
            if (next == null) {
                Engagements.release(self);
                MissileTracker.LOGGER.info("[airdefence] silo {} target gone, launch aborted, missile kept", pos.toShortString());
                return false;
            }
            silo.retarget(next.getUUID());
            current = next;
        }
        Engagements.claim(self, current.getUUID(), level.getGameTime(), spec.hatchTicks + Engagements.MISSILE_CLAIM_TICKS);
        return true;
    }

    public static void claim(ServerLevel level, BlockPos pos, UUID target, InterceptorSpec spec) {
        Engagements.claim(Engagements.silo(level, pos), target, level.getGameTime(), spec.hatchTicks + Engagements.MISSILE_CLAIM_TICKS);
    }

    public static void release(ServerLevel level, BlockPos pos) {
        Engagements.release(Engagements.silo(level, pos));
    }

    public static @Nullable PlaneEntity resolve(ServerLevel level, @Nullable UUID id) {
        if (id == null) return null;
        Entity e = level.getEntity(id);
        return e instanceof PlaneEntity p && AircraftRoster.isEngageable(p, level) ? p : null;
    }

    /**
     * Right-click on any part of a silo. The silo item passes through to its own {@code useOn} (upgrade); any other
     * hand, empty included, toggles strike / air defence.
     */
    public static InteractionResult use(ItemStack stack, Level level, BlockPos pos, Player player) {
        if (stack.is(Missiles.LAUNCH_SILO_ITEM)) return InteractionResult.PASS;
        if (!player.mayBuild()) return InteractionResult.PASS;
        if (!(level instanceof ServerLevel server)) return InteractionResult.SUCCESS;
        BlockPos master = SiloStructure.masterOf(server, pos);
        if (master == null || !(server.getBlockEntity(master) instanceof LaunchSiloBlockEntity silo)) return InteractionResult.PASS;
        String problem = silo.toggleMode();
        if (problem != null) {
            player.sendOverlayMessage(Component.translatableWithFallback(SimplePlanesMod.MODID + ".silo.cannot_toggle",
                "Cannot switch mode: %s", problem).withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        boolean ad = silo.mode() == LaunchSiloBlockEntity.Mode.AIR_DEFENCE;
        player.sendOverlayMessage(ad
            ? Component.translatableWithFallback(SimplePlanesMod.MODID + ".silo.mode.air_defence",
                "Silo mode: air defence (engages hostile aircraft within %s blocks)",
                (int) InterceptorSpec.of(silo.tier()).detectionRadius()).withStyle(ChatFormatting.GOLD)
            : Component.translatableWithFallback(SimplePlanesMod.MODID + ".silo.mode.strike",
                "Silo mode: strike (launch to coordinates)").withStyle(ChatFormatting.AQUA));
        server.playSound(null, master, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 1.0F, ad ? 1.2F : 0.8F);
        MissileTracker.LOGGER.info("[airdefence] silo {} switched to {} by {}", master.toShortString(), silo.mode().label,
            player.getName().getString());
        return InteractionResult.SUCCESS_SERVER;
    }
}
