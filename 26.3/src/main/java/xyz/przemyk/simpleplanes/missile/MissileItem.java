package xyz.przemyk.simpleplanes.missile;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.airdefence.InterceptorSpec;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * A missile of one tier. Used on any part of a silo of the same tier it loads that silo, in strike or air-defence
 * mode alike; the silo blocks pass this item through to here. Sneaking empty-handed on a silo takes it back out
 * ({@link #unload}).
 */
public class MissileItem extends Item {

    public final MissileTier tier;

    public MissileItem(MissileTier tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        BlockPos master = SiloStructure.masterOf(level, context.getClickedPos());
        if (master == null || !(level.getBlockEntity(master) instanceof LaunchSiloBlockEntity silo)) return InteractionResult.PASS;
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        if (player != null && !player.mayUseItemAt(context.getClickedPos(), context.getClickedFace(), stack)) return InteractionResult.FAIL;

        MissileTier siloTier = silo.tier();
        if (!SiloStructure.isIntact(level, master, siloTier)) return refuse(player, Component.translatable("simpleplanes.missile.damaged"));
        if (busy(silo)) return refuse(player, Component.translatable("simpleplanes.missile.busy", phase(silo)));
        if (siloTier != tier)
            return refuse(player, Component.translatable("simpleplanes.missile.wrong_tier", siloTier.tier, tier.tier));
        if (silo.isLoaded()) return refuse(player, Component.translatable("simpleplanes.missile.already_loaded"));
        String problem = silo.load();
        if (problem != null) return refuse(player, Component.translatable("simpleplanes.missile.cannot_load", problem));

        stack.consume(1, player);
        level.playSound(null, master, SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 1.0F, 1.3F);
        if (player != null) player.sendOverlayMessage(silo.mode() == LaunchSiloBlockEntity.Mode.AIR_DEFENCE
            ? Component.translatable("simpleplanes.missile.loaded_ad", tier.tier, (int) InterceptorSpec.of(tier).detectionRadius())
            : Component.translatable("simpleplanes.missile.loaded", tier.tier));
        MissileTracker.LOGGER.info("[missile] silo {} T{} loaded by {}", master.toShortString(), tier.tier,
            player == null ? "?" : player.getName().getString());
        return InteractionResult.SUCCESS_SERVER;
    }

    /** Sneak + empty hand on a silo: the loaded missile goes back into the player's main hand. */
    public static InteractionResult unload(ServerLevel level, BlockPos master, LaunchSiloBlockEntity silo, Player player) {
        if (!silo.isLoaded()) return refuse(player, Component.translatable("simpleplanes.missile.not_loaded"));
        if (busy(silo)) return refuse(player, Component.translatable("simpleplanes.missile.busy", phase(silo)));
        MissileTier loaded = silo.tier();
        String problem = silo.unload();
        if (problem != null) return refuse(player, Component.translatable("simpleplanes.missile.cannot_unload", problem));
        ItemStack out = new ItemStack(Missiles.missileItem(loaded));
        if (player.getMainHandItem().isEmpty()) player.setItemInHand(InteractionHand.MAIN_HAND, out);
        else if (!player.getInventory().add(out)) Block.popResource(level, master.above(), out);
        level.playSound(null, master, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.6F, 0.8F);
        player.sendOverlayMessage(Component.translatable("simpleplanes.missile.unloaded", loaded.tier));
        MissileTracker.LOGGER.info("[missile] silo {} T{} unloaded by {}", master.toShortString(), loaded.tier, player.getName().getString());
        return InteractionResult.SUCCESS_SERVER;
    }

    /** The hatch is moving or a missile is leaving; loading is fine again once it has closed (cooldown included). */
    private static boolean busy(LaunchSiloBlockEntity silo) {
        LaunchSiloBlockEntity.Phase phase = silo.phase();
        return phase != LaunchSiloBlockEntity.Phase.IDLE && phase != LaunchSiloBlockEntity.Phase.COOLDOWN;
    }

    private static String phase(LaunchSiloBlockEntity silo) {
        return silo.phase().name().toLowerCase(Locale.ROOT);
    }

    private static InteractionResult refuse(@Nullable Player player, Component message) {
        if (player != null) player.sendOverlayMessage(message.copy().withStyle(ChatFormatting.RED));
        return InteractionResult.FAIL;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable("simpleplanes.missile.tooltip.fits", tier.tier, tier.footprint, tier.footprint)
            .withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable(tier.warhead.fire() ? "simpleplanes.missile.tooltip.warhead_fire"
            : "simpleplanes.missile.tooltip.warhead", String.format(Locale.ROOT, "%.0f", tier.warhead.power()))
            .withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("simpleplanes.missile.tooltip.range", (int) tier.minRange, (int) tier.maxRange)
            .withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("simpleplanes.missile.tooltip.use").withStyle(ChatFormatting.DARK_GRAY));
    }
}
