package xyz.przemyk.simpleplanes.entities;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LinearInterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.misc.MathUtil;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;

/**
 * Quadcopter crane. Stub: server-authoritative, holds its position (no gravity, no controller) and keeps
 * a straight-down rope. The multirotor physics, controller and load handling replace the tick later.
 */
public class QuadcopterEntity extends Entity {

    public static final EntityDataAccessor<Quaternionfc> Q = SynchedEntityData.defineId(QuadcopterEntity.class, EntityDataSerializers.QUATERNION);
    public static final EntityDataAccessor<String> MATERIAL = SynchedEntityData.defineId(QuadcopterEntity.class, EntityDataSerializers.STRING);
    public static final EntityDataAccessor<Integer> HEALTH = SynchedEntityData.defineId(QuadcopterEntity.class, EntityDataSerializers.INT);
    public static final EntityDataAccessor<Integer> TIME_SINCE_HIT = SynchedEntityData.defineId(QuadcopterEntity.class, EntityDataSerializers.INT);
    public static final EntityDataAccessor<Boolean> CARRYING = SynchedEntityData.defineId(QuadcopterEntity.class, EntityDataSerializers.BOOLEAN);
    public static final EntityDataAccessor<Float> ROPE_LENGTH = SynchedEntityData.defineId(QuadcopterEntity.class, EntityDataSerializers.FLOAT);
    /** Hook position relative to the winch point, which is 0.30 above the entity origin. */
    public static final EntityDataAccessor<Vector3fc> HOOK_OFFSET = SynchedEntityData.defineId(QuadcopterEntity.class, EntityDataSerializers.VECTOR3);

    public static final int MAX_HEALTH = 10;
    public static final double WINCH_HEIGHT = 0.30;

    public Quaternionf Q_Client = new Quaternionf();
    public Quaternionf Q_Prev = new Quaternionf();
    public float propellerRotationOld;
    public float propellerRotationNew;

    private Block material = Blocks.OAK_PLANKS;
    private int damageTimeout;
    private int lerpStepsQ;
    private Vector3f hookOffsetPrev = new Vector3f(0, -1, 0);

    public QuadcopterEntity(EntityType<? extends QuadcopterEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(Q, new Quaternionf());
        builder.define(MATERIAL, BuiltInRegistries.BLOCK.getKey(Blocks.OAK_PLANKS).toString());
        builder.define(HEALTH, MAX_HEALTH);
        builder.define(TIME_SINCE_HIT, 0);
        builder.define(CARRYING, false);
        builder.define(ROPE_LENGTH, 1.0f);
        builder.define(HOOK_OFFSET, new Vector3f(0, -1.0f, 0));
    }

    @Override
    protected InterpolationHandler createInterpolationHandler() {
        return LinearInterpolationHandler.create(this, 10);
    }

    // ---- synched state ----

    public Quaternionf getQ() {
        return new Quaternionf(entityData.get(Q));
    }

    public void setQ(Quaternionf q) {
        entityData.set(Q, q);
    }

    public void setQ_Client(Quaternionf q) {
        Q_Client = q;
    }

    public void setQ_prev(Quaternionf q) {
        Q_Prev = q;
    }

    public Block getMaterial() {
        return material;
    }

    public void setMaterial(String id) {
        entityData.set(MATERIAL, id);
        Identifier key = Identifier.tryParse(id);
        material = key == null ? Blocks.OAK_PLANKS : BuiltInRegistries.BLOCK.getValue(key);
    }

    public int getHealth() {
        return entityData.get(HEALTH);
    }

    public void setHealth(int health) {
        entityData.set(HEALTH, Math.max(health, 0));
    }

    public int getTimeSinceHit() {
        return entityData.get(TIME_SINCE_HIT);
    }

    public void setTimeSinceHit(int ticks) {
        entityData.set(TIME_SINCE_HIT, ticks);
    }

    public boolean isCarrying() {
        return entityData.get(CARRYING);
    }

    public void setCarrying(boolean carrying) {
        entityData.set(CARRYING, carrying);
    }

    public float getRopeLength() {
        return entityData.get(ROPE_LENGTH);
    }

    public void setRopeLength(float length) {
        entityData.set(ROPE_LENGTH, length);
    }

    public Vector3fc getHookOffset() {
        return entityData.get(HOOK_OFFSET);
    }

    public void setHookOffset(Vector3fc offset) {
        entityData.set(HOOK_OFFSET, new Vector3f(offset));
    }

    /** World position of the hook, interpolated for rendering. */
    public Vec3 hookWorld(float partialTicks) {
        Vector3fc now = getHookOffset();
        Vec3 base = getPosition(partialTicks);
        return new Vec3(
            base.x + Mth.lerp(partialTicks, hookOffsetPrev.x, now.x()),
            base.y + WINCH_HEIGHT + Mth.lerp(partialTicks, hookOffsetPrev.y, now.y()),
            base.z + Mth.lerp(partialTicks, hookOffsetPrev.z, now.z()));
    }

    // ---- behaviour ----

    @Override
    public boolean isNoGravity() {
        return true;
    }

    @Override
    public @Nullable LivingEntity getControllingPassenger() {
        return null;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean canBeCollidedWith(@Nullable Entity other) {
        return true;
    }

    @Override
    public ItemStack getPickResult() {
        return getItemStack();
    }

    @Override
    public void tick() {
        super.tick();
        hookOffsetPrev.set(getHookOffset());
        if (level().isClientSide()) {
            propellerRotationOld = propellerRotationNew;
            propellerRotationNew += 0.6f;
            if (getTimeSinceHit() > 0) {
                setTimeSinceHit(getTimeSinceHit() - 1);
            }
            tickLerp();
            return;
        }

        setDeltaMovement(getDeltaMovement().scale(0.9));
        move(MoverType.SELF, getDeltaMovement());

        float rope = getRopeLength();
        Vector3fc hook = getHookOffset();
        if (hook.x() != 0 || hook.z() != 0 || hook.y() != -rope) {
            setHookOffset(new Vector3f(0, -rope, 0));
        }
        if (damageTimeout > 0) {
            damageTimeout--;
        }
        if (getTimeSinceHit() > 0) {
            setTimeSinceHit(getTimeSinceHit() - 1);
        }
    }

    private void tickLerp() {
        getInterpolation().interpolate();
        if (lerpStepsQ > 0) {
            Q_Prev = new Quaternionf(Q_Client);
            Q_Client = MathUtil.lerpQ(1f / lerpStepsQ, new Quaternionf(Q_Client), getQ());
            --lerpStepsQ;
        } else if (lerpStepsQ == 0) {
            Q_Prev = new Quaternionf(Q_Client);
            Q_Client = getQ();
            --lerpStepsQ;
        } else {
            Q_Prev = new Quaternionf(Q_Client);
        }
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (MATERIAL.equals(key) && level().isClientSide()) {
            setMaterial(entityData.get(MATERIAL));
        } else if (Q.equals(key) && level().isClientSide()) {
            if (firstTick) {
                lerpStepsQ = 0;
                Q_Client = getQ();
                Q_Prev = getQ();
            } else {
                lerpStepsQ = 10;
            }
        } else if (HOOK_OFFSET.equals(key) && firstTick) {
            hookOffsetPrev.set(getHookOffset());
        }
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (isRemoved() || isInvulnerableToBase(source) || damageTimeout > 0) {
            return false;
        }
        Entity direct = source.getDirectEntity();
        if (direct != null && direct.isPassengerOfSameVehicle(this)) {
            return false;
        }
        if (onGround() && source.getDirectEntity() instanceof Player) {
            amount *= 3;
        }
        setTimeSinceHit(20);
        setHealth((int) (getHealth() - amount));
        damageTimeout = 10;
        boolean creative = source.getEntity() instanceof Player player && player.getAbilities().instabuild;
        if (creative) {
            kill(level);
        } else if (getHealth() <= 0) {
            kill(level);
            if (level.getGameRules().get(GameRules.ENTITY_DROPS)) {
                dropItem(level);
            }
        }
        return true;
    }

    protected void dropItem(ServerLevel level) {
        Entity item = spawnAtLocation(level, getItemStack());
        if (item != null) {
            item.setPermanentlyInvulnerable(true);
        }
    }

    public ItemStack getItemStack() {
        ItemStack stack = SimplePlanesItems.QUADCOPTER_ITEM.get().getDefaultInstance();
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registryAccess());
        addAdditionalSaveData(output);
        CompoundTag tag = output.buildResult();
        tag.putInt("health", MAX_HEALTH);
        tag.putBoolean("carrying", false);
        tag.putBoolean("Used", true);
        stack.set(SimplePlanesComponents.ENTITY_TAG.get(), tag);
        return stack;
    }

    /** Public bridge for the item, which stores the entity data as a raw tag. */
    public void loadFromItemTag(CompoundTag tag) {
        readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, registryAccess(), tag));
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        input.getString("material").ifPresent(this::setMaterial);
        setHealth(input.getIntOr("health", getHealth()));
        setRopeLength(Mth.clamp(input.getFloatOr("rope_length", getRopeLength()), 1.0f, 12.0f));
        setCarrying(input.getBooleanOr("carrying", isCarrying()));
        setHookOffset(new Vector3f(0, -getRopeLength(), 0));
        Quaternionf q = MathUtil.toQuaternionf(getYRot(), 0, 0);
        setQ(q);
        Q_Client = new Quaternionf(q);
        Q_Prev = new Quaternionf(q);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putString("material", entityData.get(MATERIAL));
        output.putInt("health", getHealth());
        output.putFloat("rope_length", getRopeLength());
        output.putBoolean("carrying", isCarrying());
    }
}
