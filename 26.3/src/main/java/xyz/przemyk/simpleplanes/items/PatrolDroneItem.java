package xyz.przemyk.simpleplanes.items;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.drone.PatrolDroneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;

import java.util.function.Consumer;

/**
 * Puts a patrol drone down, parked, owned by the player. It stays on the ground until it is given a route
 * ({@code /drone}) or picked up again by hitting it.
 */
public class PatrolDroneItem extends Item {

    public PatrolDroneItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        HitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return InteractionResult.PASS;
        }
        Vec3 at = hit.getLocation();
        AABB box = SimplePlanesEntities.PATROL_DRONE.get().getDimensions().makeBoundingBox(at).inflate(-0.05);
        if (!level.noCollision(box)) {
            return InteractionResult.FAIL;
        }
        if (level instanceof ServerLevel server) {
            PatrolDroneEntity drone = PatrolDroneEntity.deploy(server, stack, at, player.getUUID(), "", "");
            if (drone == null) {
                return InteractionResult.FAIL;
            }
            drone.setYRot(player.getYRot());
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            player.sendSystemMessage(Component.translatable("simpleplanes.patrol_drone.placed", drone.getId(), drone.getId()));
        }
        player.awardStat(Stats.ITEM_USED.get(this));
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("simpleplanes.patrol_drone.tooltip"));
    }
}
