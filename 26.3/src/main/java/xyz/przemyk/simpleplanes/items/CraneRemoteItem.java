package xyz.przemyk.simpleplanes.items;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.entities.QuadcopterEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;

import java.util.function.Consumer;

/** Remote for the quadcopter crane. Stub: linking works, every other action reports "not implemented". */
public class CraneRemoteItem extends Item {

    public static final double LINK_RANGE = 6.0;

    public CraneRemoteItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public void appendHoverText(ItemStack itemStack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag tooltipFlag) {
        builder.accept(Component.translatable(SimplePlanesMod.MODID + ".crane_remote_desc"));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        QuadcopterEntity target = pickQuadcopter(level, player);
        if (target != null) {
            if (!level.isClientSide()) {
                stack.set(SimplePlanesComponents.CRANE_LINK, target.getUUID());
                player.sendSystemMessage(Component.literal("Crane remote linked to crane #" + target.getId()));
            }
            return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
        }
        return notImplemented(level, player);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        return notImplemented(player.level(), player);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        return notImplemented(context.getLevel(), player);
    }

    private static InteractionResult notImplemented(Level level, Player player) {
        if (!level.isClientSide()) {
            player.sendSystemMessage(Component.literal("Crane remote: not implemented"));
        }
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
    }

    /** The nearest quadcopter whose box the player's view ray crosses within {@link #LINK_RANGE}. */
    private static @Nullable QuadcopterEntity pickQuadcopter(Level level, Player player) {
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(LINK_RANGE));
        AABB search = new AABB(eye, end).inflate(1.0);
        QuadcopterEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (QuadcopterEntity candidate : level.getEntitiesOfClass(QuadcopterEntity.class, search, Entity::isAlive)) {
            var hit = candidate.getBoundingBox().inflate(candidate.getPickRadius()).clip(eye, end);
            if (hit.isPresent()) {
                double d = eye.distanceToSqr(hit.get());
                if (d < bestDist) {
                    bestDist = d;
                    best = candidate;
                }
            }
        }
        return best;
    }
}
