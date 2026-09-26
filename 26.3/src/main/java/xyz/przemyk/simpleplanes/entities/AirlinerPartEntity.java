package xyz.przemyk.simpleplanes.entities;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A hitbox along the airliner's fuselage, so that the nose, the cockpit and the tail can be clicked although
 * the airliner's own bounding box only covers the middle 3 blocks. It is invisible, is never saved, does not
 * collide and cannot be picked by the airliner's own riders; clicks and hits go to the airliner, with the hit
 * location translated into the airliner's frame. The airliner spawns and removes its parts
 * ({@link AirlinerEntity#PART_STATIONS}); both sides position them from the airliner every tick.
 */
public class AirlinerPartEntity extends Entity {

    private static final EntityDataAccessor<Integer> PARENT = SynchedEntityData.defineId(AirlinerPartEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> STATION = SynchedEntityData.defineId(AirlinerPartEntity.class, EntityDataSerializers.INT);
    private static final int ORPHAN_TICKS = 20;

    private int orphanTicks;

    public AirlinerPartEntity(EntityType<? extends AirlinerPartEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    public void attach(AirlinerEntity parent, int station) {
        entityData.set(PARENT, parent.getId());
        entityData.set(STATION, station);
        moveTo(parent);
    }

    public int station() {
        return entityData.get(STATION);
    }

    public @Nullable AirlinerEntity parent() {
        return level().getEntity(entityData.get(PARENT)) instanceof AirlinerEntity airliner && !airliner.isRemoved() ? airliner : null;
    }

    private void moveTo(AirlinerEntity parent) {
        Vec3 p = parent.partPosition(station());
        setPos(p.x, p.y, p.z);
        setOldPos();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(PARENT, -1);
        builder.define(STATION, 0);
    }

    @Override
    public void tick() {
        firstTick = false;
        AirlinerEntity parent = parent();
        if (parent != null) {
            orphanTicks = 0;
            moveTo(parent);
        } else if (!level().isClientSide() && ++orphanTicks > ORPHAN_TICKS) {
            discard();
        }
    }

    /** Pickable, except for the local player riding this airliner: a rider aims at the world, not at his own plane. */
    @Override
    public boolean isPickable() {
        if (level().isClientSide()) {
            AirlinerEntity parent = parent();
            return parent == null || !parent.hasPassenger(e -> e instanceof Player player && player.isLocalPlayer());
        }
        return true;
    }

    @Override
    public boolean canBePickedFromInside() {
        return false;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        AirlinerEntity parent = parent();
        if (parent == null) {
            return InteractionResult.PASS;
        }
        return parent.interact(player, hand, position().add(location).subtract(parent.position()));
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        AirlinerEntity parent = parent();
        if (parent == null || source.is(DamageTypeTags.IS_EXPLOSION) || source.getEntity() == null) {
            return false;
        }
        return parent.hurtServer(level, source, amount);
    }

    @Override
    public boolean ignoreExplosion(Explosion explosion) {
        return true;
    }

    @Override
    public boolean isAttackable() {
        return true;
    }

    @Override
    public @Nullable ItemStack getPickResult() {
        AirlinerEntity parent = parent();
        return parent == null ? null : parent.getPickResult();
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
    }
}
