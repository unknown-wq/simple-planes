package xyz.przemyk.simpleplanes.items;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.entities.QuadcopterEntity;
import xyz.przemyk.simpleplanes.misc.MathUtil;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;

import java.util.List;
import java.util.function.Predicate;

/** Places a quadcopter crane; the quadcopter is not a PlaneEntity, so PlaneItem cannot. */
public class QuadcopterItem extends Item {

    private static final Predicate<Entity> ENTITY_PREDICATE = EntitySelector.NO_SPECTATORS.and(Entity::isPickable);

    public QuadcopterItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack itemstack = player.getItemInHand(hand);
        HitResult hitResult = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            return InteractionResult.PASS;
        }
        Vec3 viewVector = player.getViewVector(1.0F);
        List<Entity> list = level.getEntities(player, player.getBoundingBox().expandTowards(viewVector.scale(5.0D)).inflate(1.0D), ENTITY_PREDICATE);
        Vec3 eyePosition = player.getEyePosition(1.0F);
        for (Entity entity : list) {
            AABB aabb = entity.getBoundingBox().inflate(entity.getPickRadius());
            if (aabb.contains(eyePosition)) {
                return InteractionResult.PASS;
            }
        }

        QuadcopterEntity quadcopter = SimplePlanesEntities.QUADCOPTER.get().create(level, EntitySpawnReason.SPAWN_ITEM_USE);
        if (quadcopter == null) {
            return InteractionResult.PASS;
        }
        Vec3 at = hitResult.getLocation();
        quadcopter.setPos(at.x(), at.y(), at.z());
        quadcopter.setYRot(player.getYRot());
        quadcopter.yRotO = player.yRotO;
        Component name = itemstack.get(DataComponents.CUSTOM_NAME);
        if (name != null) {
            quadcopter.setCustomName(name);
        }
        CompoundTag entityTag = itemstack.get(SimplePlanesComponents.ENTITY_TAG);
        if (entityTag != null) {
            quadcopter.loadFromItemTag(entityTag);
        } else {
            quadcopter.setQ(MathUtil.toQuaternionf(player.getYRot(), 0, 0));
        }
        quadcopter.initAt(at, false);
        quadcopter.setOwner(player.getUUID());
        if (!level.noCollision(quadcopter, quadcopter.getBoundingBox().inflate(-0.1D))) {
            return InteractionResult.FAIL;
        }
        if (!level.isClientSide()) {
            level.addFreshEntity(quadcopter);
            if (!player.getAbilities().instabuild) {
                itemstack.shrink(1);
            }
        }
        player.awardStat(Stats.ITEM_USED.get(this));
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
    }
}
