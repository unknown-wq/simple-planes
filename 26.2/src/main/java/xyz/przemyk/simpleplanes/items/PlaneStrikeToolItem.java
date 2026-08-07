package xyz.przemyk.simpleplanes.items;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.autopilot.AutopilotComponents;
import xyz.przemyk.simpleplanes.autopilot.AutopilotConfig;
import xyz.przemyk.simpleplanes.autopilot.AutopilotFeedback;
import xyz.przemyk.simpleplanes.autopilot.AutopilotSpawner;
import xyz.przemyk.simpleplanes.autopilot.RunwayOccupancy;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.function.Consumer;

/**
 * Scripted attack run. Right-clicking a block spawns an aircraft the configured distance away and
 * sends it at that block at full throttle.
 *
 * <ul>
 *   <li>right-click a block — launch a strike at it</li>
 *   <li>right-click the air — status report</li>
 *   <li>sneak + right-click the air — cycle the spawn distance</li>
 * </ul>
 */
public class PlaneStrikeToolItem extends Item {

    private static final int[] DISTANCES = {100, 200, 400, 800};

    public PlaneStrikeToolItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    private static int getDistance(ItemStack stack) {
        Integer distance = stack.get(AutopilotComponents.STRIKE_DISTANCE);
        return distance == null ? AutopilotConfig.STRIKE_SPAWN_DISTANCE : distance;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (!RunwayOccupancy.canActivateAnother()) {
            AutopilotFeedback.warn(player, "Too many autopilot aircraft already flying ("
                + RunwayOccupancy.activeCount() + "/" + AutopilotConfig.MAX_ACTIVE_AUTOPILOTS + ").");
            return InteractionResult.CONSUME;
        }

        BlockPos target = context.getClickedPos();
        int distance = getDistance(context.getItemInHand());
        PlaneEntity plane = AutopilotSpawner.launchStrike(level, player, target, distance);
        if (plane == null) {
            AutopilotFeedback.warn(player, "Could not create the aircraft.");
            return InteractionResult.CONSUME;
        }
        AutopilotFeedback.success(player, "Strike launched: plane #" + plane.getId()
            + " inbound to " + target.toShortString() + " from " + distance + " blocks.");
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            int current = getDistance(stack);
            int next = DISTANCES[0];
            for (int i = 0; i < DISTANCES.length; i++) {
                if (DISTANCES[i] == current) {
                    next = DISTANCES[(i + 1) % DISTANCES.length];
                    break;
                }
            }
            stack.set(AutopilotComponents.STRIKE_DISTANCE, next);
            AutopilotFeedback.info(player, "Strike spawn distance: " + next + " blocks.");
        } else {
            AutopilotFeedback.info(player, "Spawn distance " + getDistance(stack) + " blocks. "
                + RunwayOccupancy.activeCount() + "/" + AutopilotConfig.MAX_ACTIVE_AUTOPILOTS
                + " autopilot aircraft active.");
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable(SimplePlanesMod.MODID + ".strike_tool_desc"));
        builder.accept(Component.translatable(SimplePlanesMod.MODID + ".strike_tool_distance", getDistance(stack)));
    }
}
