package xyz.przemyk.simpleplanes.autopilot;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.airdefence.Engagements;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.missile.MissileEntity;
import xyz.przemyk.simpleplanes.missile.MissileTracker;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Missile warning for the players aboard an aircraft an air-defence missile is chasing: an action-bar line
 * (tier, clock position, range, time to impact) and a beep that quickens as the missile closes. Warning only;
 * nothing steers a player's aircraft. Server side, from the missiles in flight, so it costs nothing while none is.
 */
public final class MissileWarning {

    private static final int INTERVAL = 5;
    /** Beep period by time to impact: slow, medium, fast. */
    private static final int BEEP_SLOW = 20;
    private static final int BEEP_MEDIUM = 10;
    private static final int BEEP_FAST = 5;

    private static final Map<UUID, Long> LAST_BEEP = new HashMap<>();

    private MissileWarning() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(MissileWarning::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST_BEEP.clear());
    }

    private static void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        if (now % INTERVAL != 0) {
            return;
        }
        List<MissileEntity> missiles = MissileTracker.active();
        if (missiles.isEmpty()) {
            LAST_BEEP.clear();
            return;
        }
        Set<PlaneEntity> targets = new HashSet<>();
        for (MissileEntity m : missiles) {
            if (m.interceptor() == null || m.isFinished() || !(m.level() instanceof ServerLevel level)) {
                continue;
            }
            UUID id = Engagements.targetOf(new Engagements.MissileEngager(m.getId()), level.getGameTime());
            Entity target = id == null ? null : level.getEntity(id);
            if (target instanceof PlaneEntity plane) {
                targets.add(plane);
            }
        }
        for (PlaneEntity plane : targets) {
            List<ServerPlayer> aboard = plane.getPassengers().stream()
                .filter(e -> e instanceof ServerPlayer).map(e -> (ServerPlayer) e).toList();
            if (aboard.isEmpty()) {
                continue;
            }
            List<MissileThreat> threats = MissileThreat.inbound(plane);
            if (threats.isEmpty()) {
                continue;
            }
            MissileThreat t = threats.get(0);
            Component line = message(plane, t, threats.size());
            int period = t.tti() > 100 ? BEEP_SLOW : t.tti() > 40 ? BEEP_MEDIUM : BEEP_FAST;
            for (ServerPlayer player : aboard) {
                player.sendOverlayMessage(line);
                Long last = LAST_BEEP.get(player.getUUID());
                if (last == null || now - last >= period) {
                    LAST_BEEP.put(player.getUUID(), now);
                    player.connection.send(new ClientboundSoundPacket(SoundEvents.NOTE_BLOCK_BIT, SoundSource.PLAYERS,
                        player.getX(), player.getY(), player.getZ(), 0.9F, t.tti() > 40 ? 1.5F : 2.0F, 0L));
                }
            }
        }
    }

    private static Component message(PlaneEntity plane, MissileThreat t, int count) {
        String more = count > 1 ? " (+" + (count - 1) + ")" : "";
        Component text = Double.isInfinite(t.tti())
            ? Component.translatableWithFallback(SimplePlanesMod.MODID + ".missile_warning.opening",
                "MISSILE T%s, %s o'clock, %s blocks, falling behind%s", t.tier(), t.clock(plane), Math.round(t.range()), more)
            : Component.translatableWithFallback(SimplePlanesMod.MODID + ".missile_warning",
                "MISSILE T%s, %s o'clock, %s blocks, impact in %ss%s", t.tier(), t.clock(plane), Math.round(t.range()),
                String.format(java.util.Locale.ROOT, "%.1f", t.tti() / 20.0), more);
        return text.copy().withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
    }
}
