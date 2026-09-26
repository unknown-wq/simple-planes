package xyz.przemyk.simpleplanes.missile;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Consumer;

/**
 * The launch silo as an item. Used on the top of natural ground it sinks a tier-1 silo into it; used on any part of
 * an existing silo it raises that silo one tier, up to 4. One item per tier level, which is also what breaking the
 * silo gives back. The geometry rules are in {@link SiloStructure}; loading and launching stay command-based.
 */
public class LaunchSiloItem extends BlockItem {

    public LaunchSiloItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        BlockPos clicked = context.getClickedPos();
        if (player != null && !player.mayUseItemAt(clicked, context.getClickedFace(), stack)) return InteractionResult.FAIL;

        Component message;
        BlockPos soundAt;
        BlockPos master = SiloStructure.masterOf(level, clicked);
        if (master != null) {
            SiloStructure.Upgrade up = SiloStructure.upgrade(level, master, player == null ? null : player.getLookAngle(), player);
            if (up.problem() != null) return refuse(player, Component.translatable("simpleplanes.silo.cannot_upgrade", up.problem()));
            message = Component.translatable("simpleplanes.silo.upgraded", up.tier().tier, up.tier().footprint, up.tier().footprint,
                up.tier().depthBlocks(), up.grewToward());
            soundAt = up.master();
        } else {
            BlockState state = level.getBlockState(clicked);
            BlockPos top = clicked;
            if (state.canBeReplaced() && state.getFluidState().isEmpty()) {
                top = clicked.below();
            } else if (context.getClickedFace() != Direction.UP) {
                return refuse(player, Component.translatable("simpleplanes.silo.cannot_place", "use it on the top face of the ground"));
            }
            String problem = SiloStructure.checkItemPlacement(level, top, MissileTier.T1, player);
            if (problem != null) return refuse(player, Component.translatable("simpleplanes.silo.cannot_place", problem));
            SiloStructure.place(level, top, MissileTier.T1);
            message = Component.translatable("simpleplanes.silo.placed", top.toShortString());
            soundAt = top;
        }
        stack.consume(1, player);
        level.playSound(null, soundAt, SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 1.0F, 0.8F);
        if (player != null) player.sendOverlayMessage(message);
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult refuse(Player player, Component message) {
        if (player != null) player.sendOverlayMessage(message.copy().withStyle(ChatFormatting.RED));
        return InteractionResult.FAIL;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable("simpleplanes.silo.tooltip.place").withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("simpleplanes.silo.tooltip.upgrade").withStyle(ChatFormatting.GRAY));
    }
}
