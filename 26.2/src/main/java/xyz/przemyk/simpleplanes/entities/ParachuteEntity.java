package xyz.przemyk.simpleplanes.entities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;

public class ParachuteEntity extends Entity {

    public static final EntityDataAccessor<Boolean> HAS_STORAGE_CRATE = SynchedEntityData.defineId(ParachuteEntity.class, EntityDataSerializers.BOOLEAN);
    public static final double MOTION_DECAY = 0.9;
    public static final int CRATE_SIZE = 27;

    private SimpleContainer container;

    public ParachuteEntity(Level level) {
        super(SimplePlanesEntities.PARACHUTE.get(), level);
    }

    public ParachuteEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    public ParachuteEntity(Level level, SimpleContainer container) {
        super(SimplePlanesEntities.PARACHUTE.get(), level);
        entityData.set(HAS_STORAGE_CRATE, true);
        this.container = container;
    }

    public boolean hasStorageCrate() {
        return entityData.get(HAS_STORAGE_CRATE);
    }

    @Override
    public LivingEntity getControllingPassenger() {
        if (getFirstPassenger() instanceof LivingEntity entity) {
            return entity;
        }

        return null;
    }

    @Override
    public boolean hurtServer(ServerLevel serverLevel, DamageSource source, float amount) {
        return false;
    }

    @Override
    public void tick() {
        Entity passenger = getControllingPassenger();
        // Can't use onGround since it detects plane collisions too.
        // Mth.floor, not (int): a truncating cast rounds towards zero and samples the wrong block at
        // negative coordinates (x = -0.5 would give 0 instead of -1).
        if ((passenger == null && !hasStorageCrate()) || !level().getBlockState(new BlockPos(Mth.floor(getX()), Mth.floor(getY()) - 1, Mth.floor(getZ()))).canBeReplaced()) {
            if (level() instanceof ServerLevel serverLevel) {
                kill(serverLevel);
                spawnAtLocation(serverLevel, SimplePlanesItems.PARACHUTE_ITEM.get());
                if (hasStorageCrate() && container != null) {
                    BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos(getBlockX(), getBlockY(), getBlockZ());
                    for (int i = 0; i < 50; i++) {
                        BlockState blockState = level().getBlockState(mutableBlockPos);
                        if (blockState.canBeReplaced()) {
                            level().setBlock(mutableBlockPos, Blocks.BARREL.defaultBlockState(), 3);
                            if (level().getBlockEntity(mutableBlockPos) instanceof BarrelBlockEntity barrelBlockEntity) {
                                for (int s = 0; s < Math.min(CRATE_SIZE, container.getContainerSize()); s++) {
                                    ItemStack itemStack = container.getItem(s);
                                    if (!itemStack.isEmpty()) {
                                        barrelBlockEntity.setItem(s, itemStack);
                                    }
                                }
                            }
                            return;
                        }
                        mutableBlockPos.move(Direction.UP);
                    }

                    for (int i = 0; i < container.getContainerSize(); i++) {
                        ItemStack itemStack = container.getItem(i);
                        if (!itemStack.isEmpty()) {
                            spawnAtLocation(serverLevel, itemStack);
                        }
                    }
                }
            } else {
                discard();
            }
        } else {
            super.tick();
            fallDistance = 0;

            float moveStrafing = 0;
            float moveForward = 0;
            if (passenger instanceof LivingEntity livingEntity) {
                float angle = (float) (livingEntity.getYRot() * Math.PI / 180.0f);
                float sin = Mth.sin(angle);
                float cos = Mth.cos(angle);
                moveStrafing = (cos * livingEntity.xxa - sin * livingEntity.zza) / 50;
                moveForward = (sin * livingEntity.xxa + cos * livingEntity.zza) / 50;
            }

            Vec3 motion = getDeltaMovement();
            setDeltaMovement(motion.x * MOTION_DECAY + moveStrafing, Math.max(-0.1, motion.y - 0.005), motion.z * MOTION_DECAY + moveForward);

            move(MoverType.SELF, getDeltaMovement());
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder pBuilder) {
        pBuilder.define(HAS_STORAGE_CRATE, false);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        entityData.set(HAS_STORAGE_CRATE, input.getBooleanOr("has_storage_crate", false));
        if (hasStorageCrate()) {
            container = new SimpleContainer(CRATE_SIZE);
            container.fromItemList(input.listOrEmpty("items", ItemStack.CODEC));
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putBoolean("has_storage_crate", hasStorageCrate());
        if (hasStorageCrate() && container != null) {
            container.storeAsItemList(output.list("items", ItemStack.CODEC));
        }
    }
}
