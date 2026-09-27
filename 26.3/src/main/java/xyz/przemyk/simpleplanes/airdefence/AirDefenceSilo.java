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
import xyz.przemyk.simpleplanes.missile.MissileEntity;
import xyz.przemyk.simpleplanes.missile.MissileItem;
import xyz.przemyk.simpleplanes.missile.MissileTracker;
import xyz.przemyk.simpleplanes.missile.Missiles;
import xyz.przemyk.simpleplanes.missile.SiloStructure;

import java.util.UUID;

/**
 * The silo side of air defence: the idle scan, keeping or replacing the target while the hatch opens, and the
 * right-click toggle. Nothing here holds a chunk ticket; a silo in an unloaded chunk does not tick and does nothing.
 *
 * <p>A silo claims its aircraft when it starts the launch sequence ({@link Engagements}), so no other silo starts
 * one at the same aircraft, and hands the claim to its missile at ignition. If the aircraft was engaged before by a
 * missile that ended without killing it, the claim carries that reason and the engage line logs it.
 */
public final class AirDefenceSilo {

    private AirDefenceSilo() {}

    /** Called from the silo's server tick while it is idle in air-defence mode. */
    public static void idleTick(ServerLevel level, LaunchSiloBlockEntity silo) {
        BlockPos pos = silo.getBlockPos();
        // spread silos over the scan interval
        if (Math.floorMod(level.getGameTime() + Mth.getSeed(pos), InterceptorSpec.SCAN_INTERVAL) != 0) return;
        if (!silo.hasMissile()) return;
        PlaneEntity target = TargetSelector.select(level, pos, silo.tier(), Engagements.silo(level, pos));
        if (target == null) return;
        if (silo.launchAirDefence(level, target) == null) {
            Engagements.Claim claim = Engagements.claimOf(Engagements.silo(level, pos), level.getGameTime());
            MissileTracker.LOGGER.info("[airdefence] silo {} T{} engaging #{} at {} blocks{}", pos.toShortString(), silo.tier().tier,
                target.getId(), String.format(java.util.Locale.ROOT, "%.1f",
                    AircraftRoster.aimPoint(target).distanceTo(SiloStructure.mouth(pos, silo.tier()))),
                claim != null && claim.note() != null ? " (" + claim.note() + ")" : " (first shot)");
        }
    }

    /** While the hatch opens: keep the target, or re-target if it is gone. False aborts the launch. */
    public static boolean keepTarget(ServerLevel level, LaunchSiloBlockEntity silo) {
        BlockPos pos = silo.getBlockPos();
        Engagements.SiloEngager self = Engagements.silo(level, pos);
        InterceptorSpec spec = InterceptorSpec.of(silo.tier());
        long now = level.getGameTime();
        PlaneEntity current = resolve(level, silo.airDefenceTarget());
        // another engager holds it: this silo's own claim lapsed (its chunk slept) and someone else took the aircraft
        if (current != null && Engagements.saturated(current.getUUID(), now, self)) {
            MissileTracker.LOGGER.info("[airdefence] silo {} target #{} taken by another engager meanwhile", pos.toShortString(), current.getId());
            current = null;
        }
        if (current == null) {
            PlaneEntity next = TargetSelector.select(level, pos, silo.tier(), self);
            if (next == null) {
                Engagements.release(self);
                MissileTracker.LOGGER.info("[airdefence] silo {} target gone, launch aborted, missile kept", pos.toShortString());
                return false;
            }
            silo.retarget(next.getUUID());
            claim(level, pos, next, spec);
            return true;
        }
        Engagements.claim(self, current.getUUID(), current.getId(), now, spec.hatchTicks + Engagements.MISSILE_CLAIM_TICKS);
        return true;
    }

    /** Claims {@code target} for this silo's launch sequence; a remembered miss on it becomes the follow-up note. */
    public static void claim(ServerLevel level, BlockPos pos, PlaneEntity target, InterceptorSpec spec) {
        Engagements.SiloEngager self = Engagements.silo(level, pos);
        long now = level.getGameTime();
        Engagements.claim(self, target.getUUID(), target.getId(), now, spec.hatchTicks + Engagements.MISSILE_CLAIM_TICKS);
        Engagements.followUp(self, target.getUUID(), target.getId(), now);
    }

    /** At ignition: the missile holds its own claim now; the silo's goes, its follow-up note passes to the missile. */
    public static void handOver(ServerLevel level, BlockPos pos, MissileEntity missile) {
        Interceptor i = missile.interceptor();
        if (i == null) release(level, pos);
        else Engagements.handOver(Engagements.silo(level, pos), i.engager());
    }

    /** Status fragment: {@code , holding #<aircraft>} and the follow-up reason, or empty when the silo holds no claim. */
    public static String holding(ServerLevel level, BlockPos pos) {
        Engagements.Claim c = Engagements.claimOf(Engagements.silo(level, pos), level.getGameTime());
        if (c == null) return "";
        return ", holding #" + c.targetEntityId() + (c.note() == null ? "" : " (" + c.note() + ")");
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
     * Right-click on any part of a silo. The silo item and the missile items pass through to their own {@code useOn}
     * (upgrade, load); sneaking with empty hands unloads; any other hand, empty included, toggles strike / air defence.
     */
    public static InteractionResult use(ItemStack stack, Level level, BlockPos pos, Player player) {
        if (stack.is(Missiles.LAUNCH_SILO_ITEM) || stack.getItem() instanceof MissileItem) return InteractionResult.PASS;
        // empty main hand, missile in the off hand: let the off hand load instead of toggling
        if (stack.isEmpty() && player.getOffhandItem().getItem() instanceof MissileItem) return InteractionResult.PASS;
        if (!player.mayBuild()) return InteractionResult.PASS;
        if (!(level instanceof ServerLevel server)) return InteractionResult.SUCCESS;
        BlockPos master = SiloStructure.masterOf(server, pos);
        if (master == null || !(server.getBlockEntity(master) instanceof LaunchSiloBlockEntity silo)) return InteractionResult.PASS;
        if (stack.isEmpty() && player.isSecondaryUseActive()) return MissileItem.unload(server, master, silo, player);
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
            : silo.warhead().pierce()
            ? Component.translatableWithFallback(SimplePlanesMod.MODID + ".silo.mode.strike_pierce",
                "Silo mode: strike (launch to coordinates), piercing warhead").withStyle(ChatFormatting.AQUA)
            : Component.translatableWithFallback(SimplePlanesMod.MODID + ".silo.mode.strike",
                "Silo mode: strike (launch to coordinates)").withStyle(ChatFormatting.AQUA));
        server.playSound(null, master, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 1.0F, ad ? 1.2F : 0.8F);
        MissileTracker.LOGGER.info("[airdefence] silo {} switched to {} by {}", master.toShortString(), silo.mode().label,
            player.getName().getString());
        return InteractionResult.SUCCESS_SERVER;
    }
}
