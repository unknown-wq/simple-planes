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
import xyz.przemyk.simpleplanes.autopilot.Blast;
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
 *   <li>right-click the air — status report, including the blast setting</li>
 *   <li>sneak + right-click the air — cycle the spawn distance, and the blast each time it wraps</li>
 * </ul>
 *
 * <p>The tool carries a blast <em>strength</em> only. Whether the blast breaks blocks and whether it
 * sets fire are settable on {@code /autopilot strike} but not here: a held item offers exactly one
 * spare gesture, and spending it on a three-way cycle of independent settings would be harder to use
 * than not having them. The tool's blast always breaks blocks and never sets fire, which is what a
 * strike has always done.
 */
public class PlaneStrikeToolItem extends Item {

    private static final int[] DISTANCES = {100, 200, 400, 800};
    /** Blast strengths the tool cycles through. The first is vanilla TNT, i.e. the historic default. */
    private static final float[] BLASTS = {Blast.DEFAULT_POWER, 8.0F, Blast.MAX_POWER, 1.0F};

    public PlaneStrikeToolItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    private static int getDistance(ItemStack stack) {
        Integer distance = stack.get(AutopilotComponents.STRIKE_DISTANCE);
        return distance == null ? AutopilotConfig.STRIKE_SPAWN_DISTANCE : distance;
    }

    /** Blast the tool is set to. Always block-breaking and never incendiary — see the component. */
    private static Blast getBlast(ItemStack stack) {
        Float power = stack.get(AutopilotComponents.STRIKE_BLAST);
        return power == null ? Blast.DEFAULT : new Blast(power, true, false);
    }

    /** Next entry in a cycle, wrapping; returns index 0 for a value that is not in the list. */
    private static int nextIndex(float[] values, float current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                return (i + 1) % values.length;
            }
        }
        return 0;
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
        // Run in from the player's side, so the aircraft passes them on the way to the target.
        double bearing = AutopilotSpawner.approachBearingFrom(player.position(), target);
        PlaneEntity plane = AutopilotSpawner.launchStrike(level, target, distance, bearing, player,
            getBlast(context.getItemInHand()));
        if (plane == null) {
            AutopilotFeedback.warn(player, "Could not create the aircraft.");
            return InteractionResult.CONSUME;
        }
        AutopilotFeedback.success(player, AutopilotSpawner.describeLaunch(plane, target, distance, bearing));
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            // One gesture, two settings. The distance advances on every use, and the blast advances
            // one step each time the distance wraps back to the start — so the pair walks through
            // every combination without inventing a second gesture the game does not offer for a
            // held item. Both values are printed on every press, which is what makes it legible.
            int current = getDistance(stack);
            int index = DISTANCES.length - 1;
            for (int i = 0; i < DISTANCES.length; i++) {
                if (DISTANCES[i] == current) {
                    index = i;
                    break;
                }
            }
            int next = DISTANCES[(index + 1) % DISTANCES.length];
            stack.set(AutopilotComponents.STRIKE_DISTANCE, next);

            Blast blast = getBlast(stack);
            if ((index + 1) % DISTANCES.length == 0) {
                blast = new Blast(BLASTS[nextIndex(BLASTS, blast.power())], true, false);
                stack.set(AutopilotComponents.STRIKE_BLAST, blast.power());
            }
            AutopilotFeedback.info(player, "Strike spawn distance: " + next
                + " blocks, blast " + blast.describe() + ".");
        } else {
            AutopilotFeedback.info(player, "Spawn distance " + getDistance(stack) + " blocks, blast "
                + getBlast(stack).describe() + ". "
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
        builder.accept(Component.translatable(SimplePlanesMod.MODID + ".strike_tool_blast",
            String.format("%.1f", getBlast(stack).power())));
    }
}
