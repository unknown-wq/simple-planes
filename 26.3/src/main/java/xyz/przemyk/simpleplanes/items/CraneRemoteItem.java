package xyz.przemyk.simpleplanes.items;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
import xyz.przemyk.simpleplanes.crane.CraneFeedback;
import xyz.przemyk.simpleplanes.entities.QuadcopterEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Remote for the quadcopter crane. Use on a crane: link. Use on a mob: pick it up. Use on a block: deliver
 * the load there, or hover over it. Sneak-use in the air: recall. Sneak-use on a block: land there.
 */
public class CraneRemoteItem extends Item {

    public static final double LINK_RANGE = 6.0;
    public static final double HOVER_AGL = 6.0;

    public CraneRemoteItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public void appendHoverText(ItemStack itemStack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag tooltipFlag) {
        builder.accept(Component.translatable(SimplePlanesMod.MODID + ".crane_remote_desc"));
    }

    private static InteractionResult done(Level level) {
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        QuadcopterEntity sighted = pickQuadcopter(level, player);
        if (sighted != null) {
            if (!level.isClientSide()) {
                stack.set(SimplePlanesComponents.CRANE_LINK, sighted.getUUID());
                sighted.setOwner(player.getUUID());
                CraneFeedback.tell(player, "linked to crane #" + sighted.getId());
            }
            return done(level);
        }
        if (!player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            QuadcopterEntity crane = linked(level, player, stack);
            if (crane != null) {
                CraneFeedback.tell(player, "crane #" + crane.getId() + ": returning");
                crane.orderReturn(player.getUUID());
            }
        }
        return done(level);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        Level level = player.level();
        if (!level.isClientSide()) {
            QuadcopterEntity crane = linked(level, player, player.getItemInHand(hand));
            if (crane != null) {
                crane.setOwner(player.getUUID());
                String refused = crane.orderPickup(target);
                if (refused == null) {
                    CraneFeedback.tell(player, "crane #" + crane.getId() + ": picking up " + target.getName().getString());
                }
            }
        }
        return done(level);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        if (!level.isClientSide()) {
            QuadcopterEntity crane = linked(level, player, context.getItemInHand());
            if (crane != null) {
                crane.setOwner(player.getUUID());
                BlockPos pos = context.getClickedPos();
                Vec3 top = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                String where = String.format(Locale.ROOT, "%.1f %.1f %.1f", top.x, top.y, top.z);
                if (player.isShiftKeyDown()) {
                    CraneFeedback.tell(player, "crane #" + crane.getId() + ": landing at " + where);
                    crane.orderLand(top.x, top.z);
                } else if (crane.isCarrying()) {
                    crane.orderDeliver(top);
                } else {
                    CraneFeedback.tell(player, "crane #" + crane.getId() + ": flying to " + where);
                    crane.orderGoto(top.add(0, HOVER_AGL, 0));
                }
            }
        }
        return done(level);
    }

    /** The crane this remote is linked to, or null after telling the player why not. */
    private static @Nullable QuadcopterEntity linked(Level level, Player player, ItemStack stack) {
        UUID id = stack.get(SimplePlanesComponents.CRANE_LINK);
        if (id == null || !(level instanceof ServerLevel serverLevel)) {
            CraneFeedback.tell(player, "no crane linked");
            return null;
        }
        for (ServerLevel l : serverLevel.getServer().getAllLevels()) {
            if (l.getEntity(id) instanceof QuadcopterEntity crane && crane.isAlive()) {
                if (crane.isDying()) {
                    CraneFeedback.tell(player, "crane #" + crane.getId() + " is lost");
                    return null;
                }
                return crane;
            }
        }
        CraneFeedback.tell(player, "crane " + id.toString().substring(0, 8) + " is not loaded");
        return null;
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
