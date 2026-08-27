package xyz.przemyk.simpleplanes.items;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
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
import xyz.przemyk.simpleplanes.autopilot.AutopilotMath;
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
 * <p><b>Settings.</b> The gesture cycles the two settings anyone changes in flight — spawn distance
 * and blast strength — because a held item offers exactly one spare gesture and cycling five
 * independent settings through it would be worse than not having them. The full set, including
 * whether the blast breaks blocks, whether it sets fire, and a pinned run-in bearing, is written
 * onto the held tool by {@code /autopilot tool}, which takes the same arguments in the same order as
 * {@code /autopilot strike}. Every setting is stored on the stack, so it survives logging out.
 *
 * <p>Unset means what a strike has always done: {@value Blast#DEFAULT_POWER} strength, blocks
 * broken, no fire, and a run-in worked out from where the player is standing.
 */
public class PlaneStrikeToolItem extends Item {

    private static final int[] DISTANCES = {100, 200, 400, 800};
    /** Blast strengths the tool cycles through. The first is vanilla TNT, i.e. the historic default. */
    private static final float[] BLASTS = {Blast.DEFAULT_POWER, 8.0F, Blast.MAX_POWER, 1.0F};

    public PlaneStrikeToolItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    public static int getDistance(ItemStack stack) {
        Integer distance = stack.get(AutopilotComponents.STRIKE_DISTANCE);
        return distance == null ? AutopilotConfig.STRIKE_SPAWN_DISTANCE : distance;
    }

    /** Blast the tool is set to; each field falls back to the historic default when unset. */
    public static Blast getBlast(ItemStack stack) {
        Float power = stack.get(AutopilotComponents.STRIKE_BLAST);
        Boolean blocks = stack.get(AutopilotComponents.STRIKE_BLOCKS);
        Boolean fire = stack.get(AutopilotComponents.STRIKE_FIRE);
        if (power == null && blocks == null && fire == null) {
            return Blast.DEFAULT;
        }
        return new Blast(power == null ? Blast.DEFAULT_POWER : power,
            blocks == null || blocks, fire != null && fire);
    }

    /** Pinned run-in bearing in compass degrees, or null to work one out from the player. */
    public static @Nullable Integer getBearing(ItemStack stack) {
        return stack.get(AutopilotComponents.STRIKE_BEARING);
    }

    /** ", bearing 050" for a pinned run-in, or nothing at all when it is worked out per launch. */
    private static String describeBearing(ItemStack stack) {
        Integer bearing = getBearing(stack);
        return bearing == null ? "" : String.format(", bearing %03d", bearing);
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

        ItemStack stack = context.getItemInHand();
        BlockPos target = context.getClickedPos();
        int distance = getDistance(stack);
        Integer pinned = getBearing(stack);
        // Pinned bearing if the tool carries one, otherwise run in from the player's side so the
        // aircraft passes them on the way to the target.
        double bearing = pinned != null
            ? AutopilotMath.yawFromCompass(pinned)
            : AutopilotSpawner.approachBearingFrom(player.position(), target);
        Blast blast = getBlast(stack);
        PlaneEntity plane = AutopilotSpawner.launchStrike(level, target, distance, bearing, player, blast);
        if (plane == null) {
            AutopilotFeedback.warn(player, "Could not create the aircraft.");
            return InteractionResult.CONSUME;
        }
        // Compass degrees, not the internal yaw: describeLaunch prints the number as a bearing, and
        // the two conventions are 180 degrees apart.
        AutopilotFeedback.success(player, AutopilotSpawner.describeLaunch(plane, target, distance,
            AutopilotMath.compassHeading(bearing)) + " Warhead: " + blast.describe() + ".");
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
                // Strength only. The block-breaking and incendiary flags are left exactly as
                // /autopilot tool set them — a gesture meant for the two numbers must not quietly
                // undo the two settings it does not show.
                blast = new Blast(BLASTS[nextIndex(BLASTS, blast.power())], blast.breaksBlocks(), blast.fire());
                stack.set(AutopilotComponents.STRIKE_BLAST, blast.power());
            }
            AutopilotFeedback.info(player, "Strike spawn distance: " + next
                + " blocks, blast " + blast.describe() + describeBearing(stack) + ".");
        } else {
            AutopilotFeedback.info(player, "Spawn distance " + getDistance(stack) + " blocks, blast "
                + getBlast(stack).describe() + describeBearing(stack) + ". "
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
            getBlast(stack).describe()));
        Integer bearing = getBearing(stack);
        builder.accept(bearing == null
            ? Component.translatable(SimplePlanesMod.MODID + ".strike_tool_bearing_auto")
            : Component.translatable(SimplePlanesMod.MODID + ".strike_tool_bearing",
                String.format("%03d", bearing)));
    }
}
