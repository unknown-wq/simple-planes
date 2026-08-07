package xyz.przemyk.simpleplanes.items;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.autopilot.Airfield;
import xyz.przemyk.simpleplanes.autopilot.AutopilotComponents;
import xyz.przemyk.simpleplanes.autopilot.AutopilotConfig;
import xyz.przemyk.simpleplanes.autopilot.AutopilotFeedback;
import xyz.przemyk.simpleplanes.autopilot.AutopilotMath;
import xyz.przemyk.simpleplanes.autopilot.AutopilotSavedData;
import xyz.przemyk.simpleplanes.autopilot.RunwayEnd;

import java.util.List;
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

        AutopilotSavedData data = AutopilotSavedData.get(serverLevel);
        String name = uniqueName(data);
        Airfield airfield = Airfield.survey(level, name, anchor, clicked);
        data.put(airfield);

        report(player, level, airfield);
        highlight(serverLevel, airfield);
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

    private static String uniqueName(AutopilotSavedData data) {
        int index = 1;
        while (data.get("airfield-" + index) != null) {
            index++;
        }
        return "airfield-" + index;
    }

    /** Prints everything the survey measured, which is the point of the tool. */
    private static void report(Player player, Level level, Airfield airfield) {
        RunwayEnd endA = airfield.endA();
        RunwayEnd endB = airfield.endB();
        int obstaclesA = Airfield.countApproachObstacles(level, endA);
        int obstaclesB = Airfield.countApproachObstacles(level, endB);
        RunwayEnd best = airfield.bestEnd(level);

        AutopilotFeedback.success(player, "Airfield " + airfield.name() + " registered ("
            + airfield.designators() + ")");
        AutopilotFeedback.info(player, String.format(
            "  length %.0f, width %d, slope %.1f deg", airfield.length(), airfield.width(), airfield.slopeDegrees()));
        AutopilotFeedback.info(player, String.format(
            "  threshold %s elevation %.0f, heading %03.0f deg",
            endA.designator(), endA.elevation(), AutopilotMath.compassHeading(endA.landingHeading())));
        AutopilotFeedback.info(player, String.format(
            "  threshold %s elevation %.0f, heading %03.0f deg",
            endB.designator(), endB.elevation(), AutopilotMath.compassHeading(endB.landingHeading())));
        AutopilotFeedback.info(player, String.format(
            "  surface roughness %.2f blocks (0 is perfectly flat)", airfield.roughness(level)));
        AutopilotFeedback.info(player, "  approach obstacles: " + endA.designator() + " -> " + obstaclesA
            + ", " + endB.designator() + " -> " + obstaclesB
            + " (of " + (AutopilotConfig.SURVEY_APPROACH_LENGTH / AutopilotConfig.SURVEY_APPROACH_STEP) + " samples)");
        AutopilotFeedback.info(player, "  preferred landing direction: " + best.designator());
        if (airfield.length() < 60) {
            AutopilotFeedback.warn(player, "  warning: short runway, the roll-out may overrun.");
        }
        if (Math.abs(airfield.slopeDegrees()) > 5) {
            AutopilotFeedback.warn(player, "  warning: steep slope.");
        }
    }

    /** Marks the centreline and both thresholds so the player can see what was measured. */
    private static void highlight(ServerLevel level, Airfield airfield) {
        Vec3 a = airfield.pointA();
        Vec3 b = airfield.pointB();
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, a.x, a.y + 1, a.z, 20, 0.4, 1.0, 0.4, 0.0);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, b.x, b.y + 1, b.z, 20, 0.4, 1.0, 0.4, 0.0);
        int steps = (int) Math.min(96, Math.max(1, airfield.length() / 2));
        for (int step = 0; step <= steps; step++) {
            double t = (double) step / steps;
            level.sendParticles(ParticleTypes.END_ROD,
                a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t + 0.5, a.z + (b.z - a.z) * t,
                1, 0.0, 0.0, 0.0, 0.0);
        }
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

    /** Exposed for the debug command so surveys can be run without the item. */
    public static void survey(Player player, ServerLevel level, BlockPos first, BlockPos second) {
        AutopilotSavedData data = AutopilotSavedData.get(level);
        Airfield airfield = Airfield.survey(level, uniqueName(data), first, second);
        data.put(airfield);
        report(player, level, airfield);
        highlight(level, airfield);
    }

    /** Exposed for the debug command. */
    public static List<Airfield> airfields(ServerLevel level) {
        return AutopilotSavedData.get(level).airfieldList();
    }
}
