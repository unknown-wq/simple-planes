package xyz.przemyk.simpleplanes.items;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class PlaneItem extends Item {

    private static final Predicate<Entity> ENTITY_PREDICATE = EntitySelector.NO_SPECTATORS.and(Entity::isPickable);
    public final Supplier<? extends EntityType<? extends PlaneEntity>> planeEntityType;

    public PlaneItem(Properties properties, Supplier<? extends EntityType<? extends PlaneEntity>> planeEntityType) {
        super(properties.stacksTo(1));
        this.planeEntityType = planeEntityType;
    }

    @Override
    public void appendHoverText(ItemStack itemStack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag tooltipFlag) {
        CompoundTag entityTag = itemStack.get(SimplePlanesComponents.ENTITY_TAG);

        if (entityTag != null) {
            entityTag.getString("material").ifPresent(material -> {
                Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(material));
                if (block != null) {
                    builder.accept(Component.translatable(SimplePlanesMod.MODID + ".material").append(block.getName()));
                }
            });

            CompoundTag upgradesNBT = entityTag.getCompoundOrEmpty("upgrades");
            for (String key : upgradesNBT.keySet()) {
                CompoundTag upgradeNbt = upgradesNBT.getCompoundOrEmpty(key);
                Identifier identifier = Identifier.parse(key);
                upgradeNbt.getString("desc").ifPresentOrElse(
                    desc -> builder.accept(Component.literal(desc)),
                    () -> builder.accept(Component.translatable("name." + identifier.toString().replace(":", "."))));
            }
        }
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack itemstack = player.getItemInHand(hand);
        HitResult hitResult = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);
        if (hitResult.getType() == HitResult.Type.MISS) {
            return InteractionResult.PASS;
        } else {
            Vec3 viewVector = player.getViewVector(1.0F);
            List<Entity> list = level.getEntities(player, player.getBoundingBox().expandTowards(viewVector.scale(5.0D)).inflate(1.0D), ENTITY_PREDICATE);
            if (!list.isEmpty()) {
                Vec3 eyePosition = player.getEyePosition(1.0F);

                for (Entity entity : list) {
                    AABB aabb = entity.getBoundingBox().inflate(entity.getPickRadius());
                    if (aabb.contains(eyePosition)) {
                        return InteractionResult.PASS;
                    }
                }
            }

            if (hitResult.getType() == HitResult.Type.BLOCK) {
                PlaneEntity planeEntity = planeEntityType.get().create(level, EntitySpawnReason.SPAWN_ITEM_USE);
                if (planeEntity == null) {
                    return InteractionResult.PASS;
                }

                planeEntity.setPos(hitResult.getLocation().x(), hitResult.getLocation().y(), hitResult.getLocation().z());
                planeEntity.setYRot(player.getYRot());
                planeEntity.yRotO = player.yRotO;
                Component name = itemstack.get(DataComponents.CUSTOM_NAME);
                if (name != null) {
                    planeEntity.setCustomName(name);
                }
                CompoundTag entityTag = itemstack.get(SimplePlanesComponents.ENTITY_TAG);
                if (entityTag != null) {
                    // `Entity#readAdditionalSaveData` is protected in 26.2 (it was public in 1.21.1),
                    // so PlaneEntity exposes this public bridge instead.
                    planeEntity.loadFromItemTag(entityTag);
                }
                if (!level.noCollision(planeEntity, planeEntity.getBoundingBox().inflate(-0.1D))) {
                    return InteractionResult.FAIL;
                } else {
                    if (!level.isClientSide()) {
                        level.addFreshEntity(planeEntity);
                        if (!player.getAbilities().instabuild) {
                            itemstack.shrink(1);
                        }
                    }
                    player.awardStat(Stats.ITEM_USED.get(this));
                    return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
                }
            } else {
                return InteractionResult.PASS;
            }
        }
    }
}
