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
import xyz.przemyk.simpleplanes.autopilot.AutopilotSavedData;
import xyz.przemyk.simpleplanes.autopilot.AutopilotSpawner;
import xyz.przemyk.simpleplanes.autopilot.RunwayOccupancy;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Route editor. Marks waypoints onto the wand, then launches an aircraft that flies them and lands.
 *
 * <ul>
 *   <li>right-click a block — add a waypoint</li>
 *   <li>sneak + right-click a block — finish the route and launch the aircraft</li>
 *   <li>right-click the air — preview the route with particles and print it</li>
 *   <li>sneak + right-click the air — clear the route</li>
 * </ul>
 *
 * <p>The waypoint list lives on the item stack, so a half-drawn route survives logging out.
 */
public class RouteWandItem extends Item {

    /** Legs flown before the aircraft goes off to land: two means out and back. */
    private static final int DEFAULT_LEGS = 2;

    public RouteWandItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    private static List<BlockPos> getRoute(ItemStack stack) {
        List<BlockPos> route = stack.get(AutopilotComponents.ROUTE);
        return route == null ? List.of() : route;
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
        List<BlockPos> route = new ArrayList<>(getRoute(stack));

        if (context.isSecondaryUseActive()) {
            route.add(context.getClickedPos());
            return launch(level, player, stack, route);
        }

        route.add(context.getClickedPos());
        stack.set(AutopilotComponents.ROUTE, List.copyOf(route));
        AutopilotFeedback.info(player, "Waypoint " + route.size() + " at "
            + context.getClickedPos().toShortString() + ". Sneak + right-click to finish.");
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        List<BlockPos> route = getRoute(stack);

        if (player.isShiftKeyDown()) {
            stack.remove(AutopilotComponents.ROUTE);
            AutopilotFeedback.info(player, "Route cleared.");
            return InteractionResult.CONSUME;
        }

        if (route.isEmpty()) {
            AutopilotFeedback.info(player, "No route yet. Right-click blocks to add waypoints.");
            return InteractionResult.CONSUME;
        }

        StringBuilder description = new StringBuilder("Route (" + route.size() + " waypoints): ");
        for (int i = 0; i < route.size(); i++) {
            if (i > 0) {
                description.append(" -> ");
            }
            description.append(route.get(i).toShortString());
        }
        AutopilotFeedback.info(player, description.toString());
        if (level instanceof ServerLevel serverLevel) {
            preview(serverLevel, route);
        }
        return InteractionResult.CONSUME;
    }

    /** Draws the route in the world so the player can see what they marked. */
    private static void preview(ServerLevel level, List<BlockPos> route) {
        for (BlockPos waypoint : route) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                waypoint.getX() + 0.5, waypoint.getY() + 1.5, waypoint.getZ() + 0.5,
                12, 0.3, 1.5, 0.3, 0.0);
        }
        for (int i = 1; i < route.size(); i++) {
            Vec3 from = new Vec3(route.get(i - 1).getX() + 0.5, route.get(i - 1).getY() + 1.5, route.get(i - 1).getZ() + 0.5);
            Vec3 to = new Vec3(route.get(i).getX() + 0.5, route.get(i).getY() + 1.5, route.get(i).getZ() + 0.5);
            double distance = from.distanceTo(to);
            int steps = (int) Math.min(64, Math.max(1, distance / 4));
            for (int step = 0; step <= steps; step++) {
                double t = (double) step / steps;
                level.sendParticles(ParticleTypes.END_ROD,
                    from.x + (to.x - from.x) * t,
                    from.y + (to.y - from.y) * t,
                    from.z + (to.z - from.z) * t,
                    1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    private InteractionResult launch(Level level, Player player, ItemStack stack, List<BlockPos> route) {
        if (route.size() < 2) {
            AutopilotFeedback.warn(player, "A route needs at least two waypoints.");
            return InteractionResult.CONSUME;
        }
        if (!RunwayOccupancy.canActivateAnother()) {
            AutopilotFeedback.warn(player, "Too many autopilot aircraft already flying ("
                + RunwayOccupancy.activeCount() + "/" + AutopilotConfig.MAX_ACTIVE_AUTOPILOTS + ").");
            return InteractionResult.CONSUME;
        }

        int cruiseAltitude = AutopilotSpawner.cruiseAltitudeFor(level, route);

        // Land at the nearest surveyed airfield to the first waypoint, if there is one.
        String airfieldName = null;
        if (level instanceof ServerLevel serverLevel) {
            BlockPos first = route.get(0);
            Airfield nearest = AutopilotSavedData.get(serverLevel).nearest(first.getX(), first.getZ(), 512);
            if (nearest != null) {
                airfieldName = nearest.name();
            }
        }

        PlaneEntity plane = AutopilotSpawner.launchRoute(level, route, cruiseAltitude, DEFAULT_LEGS, airfieldName, player);
        if (plane == null) {
            AutopilotFeedback.warn(player, "Could not create the aircraft.");
            return InteractionResult.CONSUME;
        }

        stack.remove(AutopilotComponents.ROUTE);
        AutopilotFeedback.success(player, "Plane #" + plane.getId() + " flying " + route.size()
            + " waypoints at altitude " + cruiseAltitude + ", "
            + (airfieldName == null ? "landing on terrain at the first waypoint." : "landing at " + airfieldName + "."));
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable(SimplePlanesMod.MODID + ".route_wand_desc"));
        int size = getRoute(stack).size();
        if (size > 0) {
            builder.accept(Component.translatable(SimplePlanesMod.MODID + ".route_wand_waypoints", size));
        }
    }

}
