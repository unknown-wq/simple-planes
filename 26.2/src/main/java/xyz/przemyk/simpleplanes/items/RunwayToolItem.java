package xyz.przemyk.simpleplanes.items;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
import xyz.przemyk.simpleplanes.autopilot.Airfield;
import xyz.przemyk.simpleplanes.autopilot.AirfieldReport;
import xyz.przemyk.simpleplanes.autopilot.AutopilotComponents;
import xyz.przemyk.simpleplanes.autopilot.AutopilotFeedback;
import xyz.przemyk.simpleplanes.autopilot.AutopilotSavedData;
import xyz.przemyk.simpleplanes.autopilot.AutopilotOutput;

import java.util.function.Consumer;

/**
 * Runway survey tool. Mark the two thresholds of a strip and it measures the runway, reports its
 * characteristics and registers it as a named airfield the autopilot can land at.
 *
 * <ul>
 *   <li>right-click a block — mark the first threshold, then the second (which runs the survey)</li>
 *   <li>sneak + right-click a block — cancel a half-marked runway</li>
 *   <li>right-click the air — list the airfields registered in this dimension</li>
 * </ul>
 */
public class RunwayToolItem extends Item {

    public RunwayToolItem(Properties properties) {
        super(properties.stacksTo(1));
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
        ItemStack stack = context.getItemInHand();

        if (context.isSecondaryUseActive()) {
            stack.remove(AutopilotComponents.RUNWAY_ANCHOR);
            AutopilotFeedback.info(player, "Runway marking cancelled.");
            return InteractionResult.CONSUME;
        }

        BlockPos clicked = context.getClickedPos();
        BlockPos anchor = stack.get(AutopilotComponents.RUNWAY_ANCHOR);
        if (anchor == null) {
            stack.set(AutopilotComponents.RUNWAY_ANCHOR, clicked);
            AutopilotFeedback.info(player, "Threshold 1 at " + clicked.toShortString()
                + ". Now mark the far end of the runway.");
            return InteractionResult.CONSUME;
        }

        stack.remove(AutopilotComponents.RUNWAY_ANCHOR);
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.CONSUME;
        }

        double length = Math.sqrt(anchor.distSqr(clicked));
        if (length < 20) {
            AutopilotFeedback.warn(player, "That runway is only " + (int) length
                + " blocks long; mark at least 20 blocks apart.");
            return InteractionResult.CONSUME;
        }

        AirfieldReport.surveyAndRegister(AutopilotOutput.toPlayer(player), serverLevel, anchor, clicked);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.CONSUME;
        }
        AutopilotSavedData data = AutopilotSavedData.get(serverLevel);
        if (data.isEmpty()) {
            AutopilotFeedback.info(player, "No airfields registered in this dimension.");
            return InteractionResult.CONSUME;
        }
        AutopilotFeedback.info(player, "Airfields in this dimension:");
        for (Airfield airfield : data.airfieldList()) {
            AutopilotFeedback.info(player, "  " + airfield.name() + " " + airfield.designators()
                + " at " + airfield.thresholdA().toShortString()
                + ", " + (int) airfield.length() + "x" + airfield.width());
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable(SimplePlanesMod.MODID + ".runway_tool_desc"));
        BlockPos anchor = stack.get(AutopilotComponents.RUNWAY_ANCHOR);
        if (anchor != null) {
            builder.accept(Component.translatable(SimplePlanesMod.MODID + ".runway_tool_anchor", anchor.toShortString()));
        }
    }

}
