package xyz.przemyk.simpleplanes.entities;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LinearInterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.autopilot.Blast;
import xyz.przemyk.simpleplanes.crane.CraneFeedback;
import xyz.przemyk.simpleplanes.crane.CraneRegistry;
import xyz.przemyk.simpleplanes.entities.crane.CraneController;
import xyz.przemyk.simpleplanes.entities.crane.MultirotorPhysics;
import xyz.przemyk.simpleplanes.entities.crane.SlungLoad;
import xyz.przemyk.simpleplanes.misc.MathUtil;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;

import java.util.Locale;
import java.util.UUID;

/**
 * Quadcopter crane: an unmanned, server-flown multirotor that carries one mob or player on a rope.
 * Physics in {@code entities/crane}; this class owns the state machine, the load as a passenger,
 * collisions, damage and persistence.
 */
public class QuadcopterEntity extends Entity {

    public static final EntityDataAccessor<Quaternionfc> Q = SynchedEntityData.defineId(QuadcopterEntity.class, EntityDataSerializers.QUATERNION);
    public static final EntityDataAccessor<String> MATERIAL = SynchedEntityData.defineId(QuadcopterEntity.class, EntityDataSerializers.STRING);
    public static final EntityDataAccessor<Integer> HEALTH = SynchedEntityData.defineId(QuadcopterEntity.class, EntityDataSerializers.INT);
    public static final EntityDataAccessor<Integer> TIME_SINCE_HIT = SynchedEntityData.defineId(QuadcopterEntity.class, EntityDataSerializers.INT);
    public static final EntityDataAccessor<Boolean> CARRYING = SynchedEntityData.defineId(QuadcopterEntity.class, EntityDataSerializers.BOOLEAN);
    public static final EntityDataAccessor<Float> ROPE_LENGTH = SynchedEntityData.defineId(QuadcopterEntity.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> ROPE_THETA_X = SynchedEntityData.defineId(QuadcopterEntity.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> ROPE_THETA_Z = SynchedEntityData.defineId(QuadcopterEntity.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> THRUST = SynchedEntityData.defineId(QuadcopterEntity.class, EntityDataSerializers.FLOAT);

    public static final int MAX_HEALTH = 10;
    public static final double WINCH_HEIGHT = SlungLoad.WINCH_Y;

    /** Q3, the power model: true = electric and always powered. */
    public static final boolean ALWAYS_POWERED = true;

    public static final double PICK_CLEARANCE = 1.5;
    public static final double ARRIVE_H = 0.6;
    public static final double ARRIVE_V = 0.8;
    public static final int ARRIVE_TICKS = 10;
    public static final double HOOK_REACH = 0.6;
    public static final double FOLLOW_RANGE = 8.0;
    public static final int LOWER_TIMEOUT = 160;
    public static final int PICKUP_TIMEOUT = 2400;
    public static final double CRUISE_AGL = 10.0;
    public static final double CLIMB_FIRST = 3.0;
    public static final double CLIMB_FIRST_SPEED = 0.15;
    public static final int LOOKAHEAD = 24;
    public static final double RELEASE_GAP = 0.2;
    public static final double RELEASE_BAND = 0.3;
    public static final int RELEASE_TICKS = 5;
    public static final double FINAL_DESCENT_SPEED = 0.10;
    public static final int LOWER_LOAD_TIMEOUT = 600;
    public static final double STOW_AGL = 6.0;
    public static final double RETURN_DISTANCE = 4.0;
    public static final double RETURN_AGL = 3.0;
    public static final double LAND_RATE = 0.15;
    public static final double LAND_ALIGN = 0.3;
    public static final double LAND_HOLD_AGL = 1.5;
    /** Q7: beyond this distance a recall is refused and the crane hovers where it is. */
    public static final double RECALL_RANGE = 64.0;
    public static final int OVERLOAD_TICKS = 20;
    public static final double OVERLOAD_VY = -0.02;
    public static final double OVERLOAD_RELEASE_AGL = 1.5;
    public static final double IMPACT_SPEED = 0.3;
    public static final double IMPACT_DAMAGE = 4.0;
    public static final int IMPACT_COOLDOWN = 8;
    public static final float DEATH_BLAST = 1.5F;
    public static final int FALL_TIMEOUT = 1200;
    /** CARRY gives up and sets the load down when it has not closed 0.5 b on the delivery point for this long. */
    public static final int CARRY_STALL_TICKS = 200;
    public static final String LOAD_TAG = "crane-load";

    // Load rules (Q8): these constants and refusal() are the only place that decides what may be lifted.
    public static final TagKey<EntityType<?>> CRANE_LIFTABLE = TagKey.create(Registries.ENTITY_TYPE,
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "crane_liftable"));
    public static final TagKey<EntityType<?>> CRANE_NEVER = TagKey.create(Registries.ENTITY_TYPE,
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "crane_never"));
    /** Datapack mass multipliers (x0.5, x2, x4; several multiply). Shipped: hoglin and zoglin in x4. */
    public static final TagKey<EntityType<?>> CRANE_MASS_HALF = TagKey.create(Registries.ENTITY_TYPE,
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "crane_mass_x0_5"));
    public static final TagKey<EntityType<?>> CRANE_MASS_DOUBLE = TagKey.create(Registries.ENTITY_TYPE,
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "crane_mass_x2"));
    public static final TagKey<EntityType<?>> CRANE_MASS_QUADRUPLE = TagKey.create(Registries.ENTITY_TYPE,
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "crane_mass_x4"));
    /** Fabric's conventional boss tag (ender dragon, wither); read by id, no compile dependency. */
    public static final TagKey<EntityType<?>> C_BOSSES = TagKey.create(Registries.ENTITY_TYPE,
        Identifier.fromNamespaceAndPath("c", "bosses"));
    /** Hostiles ({@code Enemy}) may be lifted; false restores the old rule, with {@link #CRANE_LIFTABLE} as the allow-list. */
    public static final boolean ALLOW_HOSTILES = true;

    /** Transient (never saved) follow-range modifier on a slung mob: range 0, so target goals find and keep nothing. */
    public static final AttributeModifier SLUNG_BLIND = new AttributeModifier(
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "crane_slung"), -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

    public enum State { IDLE, TO_PICKUP, LOWER, ATTACH, WINCH_IN, CARRY, LOWER_LOAD, RELEASE, STOW, RETURN, LAND, OVERLOAD }

    private static final boolean TRACE_ALL = Boolean.getBoolean("simpleplanes.crane.trace");

    public Quaternionf Q_Client = new Quaternionf();
    public Quaternionf Q_Prev = new Quaternionf();
    public float propellerRotationOld;
    public float propellerRotationNew;

    private Block material = Blocks.OAK_PLANKS;
    private int damageTimeout;
    private int lerpStepsQ;
    private final double[] hookPrev = {0, WINCH_HEIGHT - 1, 0};
    private final double[] hookNow = {0, WINCH_HEIGHT - 1, 0};

    public final MultirotorPhysics phys = new MultirotorPhysics();
    public final CraneController controller = new CraneController();
    public final SlungLoad rope = new SlungLoad();
    private final double[] extra = new double[3];
    private State state = State.IDLE;
    private State afterStow = State.IDLE;
    private @Nullable Vec3 target;
    private @Nullable Vec3 delivery;
    private @Nullable Vec3 home;
    private @Nullable Vec3 lowerStart;
    private @Nullable UUID owner;
    private @Nullable UUID pickupId;
    private @Nullable Entity pendingLoad;
    private @Nullable UUID releasedLoad;
    /** The entity on the hook, kept only to name why it was lost; not saved. */
    private @Nullable Entity carried;
    private boolean rotorsOff;
    private boolean physInit;
    private boolean checkLoadAfterLoad;
    private boolean dying;
    private boolean trace = TRACE_ALL;
    private int stateTicks;
    private int arriveTicks;
    private int satTicks;
    private int impactCooldown;
    private int traceTick;
    private int carryStall;
    private double carryBest = Double.MAX_VALUE;
    private String loadName = "none";

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
        builder.define(ROPE_LENGTH, (float) SlungLoad.L_MIN);
        builder.define(ROPE_THETA_X, 0.0F);
        builder.define(ROPE_THETA_Z, 0.0F);
        builder.define(THRUST, 0.0F);
    }

    @Override
    protected InterpolationHandler createInterpolationHandler() {
        return LinearInterpolationHandler.create(this, 3);
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
        rope.length = Mth.clamp(length, SlungLoad.L_MIN, SlungLoad.L_MAX);
        rope.targetLength = rope.length;
        entityData.set(ROPE_LENGTH, (float) rope.length);
    }

    public float getThrust() {
        return entityData.get(THRUST);
    }

    /** Hook (the load's box top) relative to the entity origin, from the synched rope. */
    public double[] hookOffset(double[] out) {
        return SlungLoad.hookOffset(getRopeLength(), entityData.get(ROPE_THETA_X), entityData.get(ROPE_THETA_Z), out);
    }

    /** World position of the hook, interpolated for rendering. */
    public Vec3 hookWorld(float partialTicks) {
        Vec3 base = getPosition(partialTicks);
        return new Vec3(
            base.x + Mth.lerp(partialTicks, hookPrev[0], hookNow[0]),
            base.y + Mth.lerp(partialTicks, hookPrev[1], hookNow[1]),
            base.z + Mth.lerp(partialTicks, hookPrev[2], hookNow[2]));
    }

    public Vec3 hookPosition() {
        double[] o = hookOffset(new double[3]);
        return position().add(o[0], o[1], o[2]);
    }

    public State getState() {
        return state;
    }

    public @Nullable UUID getOwner() {
        return owner;
    }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
    }

    public @Nullable Vec3 getTarget() {
        return target;
    }

    public @Nullable Vec3 getDelivery() {
        return delivery;
    }

    public @Nullable Vec3 getHome() {
        return home;
    }

    public boolean isSaturated() {
        return controller.saturated;
    }

    public String getLoadName() {
        return loadName;
    }

    public boolean isDying() {
        return dying;
    }

    public void setTrace(boolean on) {
        trace = on;
    }

    // ---- entity behaviour ----

    @Override
    public @Nullable LivingEntity getControllingPassenger() {
        return null;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        if (!getPassengers().isEmpty()) {
            return false;
        }
        // a load converting on the hook (piglin zombifying, zombie drowning) hands over to its successor,
        // which vanilla's ConversionType mounts at the same spot right after the old one dismounts
        return passenger == pendingLoad || isCarrying() && carried != null && passenger != carried
            && !carried.isPassenger() && passenger instanceof LivingEntity && passenger.distanceToSqr(carried) < 1.0;
    }

    @Override
    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        if (!level().isClientSide() && passenger instanceof Mob mob) {
            setSlung(mob, true);
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!level().isClientSide() && passenger instanceof Mob mob) {
            setSlung(mob, false);
        }
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction moveFunction) {
        double[] o = hookOffset(new double[3]);
        moveFunction.accept(passenger, getX() + o[0], getY() + o[1] - passenger.getBbHeight(), getZ() + o[2]);
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        double[] o = hookOffset(new double[3]);
        return new Vec3(getX() + o[0], getY() + o[1] - passenger.getBbHeight(), getZ() + o[2]);
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
        if (level().isClientSide()) {
            clientTick();
            return;
        }
        ServerLevel level = (ServerLevel) level();
        if (!physInit) {
            initPhysics();
        }
        CraneRegistry.track(this);
        if (damageTimeout > 0) {
            damageTimeout--;
        }
        if (getTimeSinceHit() > 0) {
            setTimeSinceHit(getTimeSinceHit() - 1);
        }
        if (impactCooldown > 0) {
            impactCooldown--;
        }
        phys.p[0] = getX();
        phys.p[1] = getY();
        phys.p[2] = getZ();

        if (dying || getHealth() <= 0) {
            tickDying(level);
            return;
        }
        checkLoad();
        if (isCarrying() && getFirstPassenger() instanceof Mob mob) {
            pacify(mob);
        }
        tickStateMachine(level);

        if (rotorsOff || !ALWAYS_POWERED) {
            phys.thrust = 0;
            phys.attitude(0, 0, phys.yaw);
            controller.saturated = false;
            if (onGround()) {
                phys.v[0] = phys.v[2] = 0;
            }
        } else {
            Vec3 t = target == null ? position() : target;
            Entity load = getFirstPassenger();
            boolean hanging = isCarrying() && load != null;
            // near the ground a heavy load gets the ground assist; above its ceiling it cannot be held
            phys.tMax = phys.tMaxBase * (hanging ? SlungLoad.assist(loadAgl()) : 1.0);
            controller.control(phys, rope, t.x, hanging ? Math.min(t.y, ceilingY()) : t.y, t.z);
        }
        satTicks = controller.saturated ? satTicks + 1 : 0;
        physicsStep();
        rope.winch();
        syncOut();
        if (trace) {
            CraneFeedback.trace(this, traceTick++);
        }
    }

    private void initPhysics() {
        physInit = true;
        phys.yaw = getYRot();
        Vec3 v = getDeltaMovement();
        phys.v[0] = v.x;
        phys.v[1] = v.y;
        phys.v[2] = v.z;
        rope.mass = 0;
        phys.mass = 1;
        if (target == null && !rotorsOff) {
            target = position();
        }
        if (home == null) {
            home = position();
        }
        if (checkLoadAfterLoad) {
            checkLoadAfterLoad = false;
            Entity load = getFirstPassenger();
            if (isCarrying() && load != null) {
                attachMass(load);
            } else if (isCarrying()) {
                setCarrying(false);
                CraneFeedback.report(this, "load lost: " + loadName + " did not come back with the save");
                loadName = "none";
                carried = null;
                if (isCarryingState(state)) {
                    enter(State.STOW);
                }
            }
        }
    }

    private void physicsStep() {
        double vx0 = phys.v[0], vy0 = phys.v[1], vz0 = phys.v[2];
        double thX = rope.thetaX, thZ = rope.thetaZ;
        rope.reaction(phys, extra);
        phys.step(extra);
        Vec3 wanted = new Vec3(phys.v[0], phys.v[1], phys.v[2]);
        Vec3 before = position();
        setDeltaMovement(wanted);
        move(MoverType.SELF, wanted);

        Entity load = getFirstPassenger();
        if (load != null && isCarrying()) {
            // the load collides too: the drone may not move where the load's box would enter a block
            if (!collides(load, loadBox(load, before)) && collides(load, loadBox(load, position()))) {
                setPos(before.x, getY(), before.z);
                if (collides(load, loadBox(load, position()))) {
                    setPos(before);
                }
                rope.thetaX = thX;
                rope.thetaZ = thZ;
                rope.omegaX = rope.omegaZ = 0;
            }
        }

        Vec3 achieved = position().subtract(before);
        double[] w = {wanted.x, wanted.y, wanted.z};
        double[] got = {achieved.x, achieved.y, achieved.z};
        double worst = 0;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(w[i] - got[i]) > 1.0E-4) {
                worst = Math.max(worst, Math.abs(w[i]));
                phys.v[i] = got[i];
            }
        }
        phys.a[0] = phys.v[0] - vx0;
        phys.a[1] = phys.v[1] - vy0;
        phys.a[2] = phys.v[2] - vz0;
        phys.p[0] = getX();
        phys.p[1] = getY();
        phys.p[2] = getZ();
        setDeltaMovement(phys.v[0], phys.v[1], phys.v[2]);
        thX = rope.thetaX;
        thZ = rope.thetaZ;
        rope.swing(phys);
        if (load != null && isCarrying()) {
            stopSwingAtBlocks(load, thX, thZ);
        }
        if (worst > IMPACT_SPEED && impactCooldown == 0 && level() instanceof ServerLevel level) {
            impactCooldown = IMPACT_COOLDOWN;
            float damage = (float) (IMPACT_DAMAGE * (worst - IMPACT_SPEED));
            CraneFeedback.log(this, String.format(Locale.ROOT, "impact at %.2f b/t, %.1f damage", worst, damage));
            applyDamage(level, damage, null);
        }
    }

    /** A swing that would put the load into a block stops on that axis (the rope hits and goes slack). */
    private void stopSwingAtBlocks(Entity load, double oldX, double oldZ) {
        double newX = rope.thetaX, newZ = rope.thetaZ;
        rope.thetaX = oldX;
        rope.thetaZ = oldZ;
        if (collides(load, loadBox(load, position()))) {
            rope.thetaX = newX;
            rope.thetaZ = newZ;
            return;
        }
        rope.thetaX = newX;
        rope.thetaZ = newZ;
        if (!collides(load, loadBox(load, position()))) {
            return;
        }
        rope.thetaZ = oldZ;
        if (!collides(load, loadBox(load, position()))) {
            rope.omegaZ = 0;
            return;
        }
        rope.thetaX = oldX;
        rope.thetaZ = newZ;
        if (!collides(load, loadBox(load, position()))) {
            rope.omegaX = 0;
            return;
        }
        rope.thetaZ = oldZ;
        rope.omegaX = rope.omegaZ = 0;
    }

    private AABB loadBox(Entity load, Vec3 dronePos) {
        double[] o = SlungLoad.hookOffset(rope.length, rope.thetaX, rope.thetaZ, new double[3]);
        double top = dronePos.y + o[1];
        return load.getDimensions(load.getPose())
            .makeBoundingBox(dronePos.x + o[0], top - load.getBbHeight(), dronePos.z + o[2]).deflate(0.01);
    }

    private boolean collides(Entity load, AABB box) {
        return !level().noCollision(load, box);
    }

    private void syncOut() {
        setYRot((float) phys.yaw);
        setQ(MathUtil.toQuaternionf(phys.yaw, -phys.pitch, -phys.roll));
        entityData.set(THRUST, (float) phys.thrust);
        entityData.set(ROPE_LENGTH, (float) rope.length);
        entityData.set(ROPE_THETA_X, (float) rope.thetaX);
        entityData.set(ROPE_THETA_Z, (float) rope.thetaZ);
    }

    private void clientTick() {
        propellerRotationOld = propellerRotationNew;
        // animation keeps its old scale (hover of an empty drone = 1/3 of the reference)
        propellerRotationNew += (float) (0.6 + 4.0 * getThrust() / (3.0 * MultirotorPhysics.G));
        if (getTimeSinceHit() > 0) {
            setTimeSinceHit(getTimeSinceHit() - 1);
        }
        tickLerp();
        System.arraycopy(hookNow, 0, hookPrev, 0, 3);
        hookOffset(hookNow);
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
                lerpStepsQ = 3;
            }
        } else if (firstTick && (ROPE_LENGTH.equals(key) || ROPE_THETA_X.equals(key) || ROPE_THETA_Z.equals(key))) {
            hookOffset(hookNow);
            System.arraycopy(hookNow, 0, hookPrev, 0, 3);
        }
    }

    // ---- load rules ----

    /** Why this entity cannot be lifted, or null if it can. Every text is "cannot lift <name>: <reason>" or "too heavy: ...". */
    public static @Nullable String refusal(Entity entity) {
        String name = entity.getName().getString();
        if (!(entity instanceof LivingEntity living)) {
            return "cannot lift " + name + ": not a mob";
        }
        if (!living.isAlive()) {
            return "cannot lift " + name + ": dead";
        }
        if (entity instanceof QuadcopterEntity || entity instanceof PlaneEntity) {
            return "cannot lift " + name + ": aircraft";
        }
        if (entity.isSpectator()) {
            return "cannot lift " + name + ": spectator";
        }
        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            return "cannot lift " + name + ": riding " + vehicle.getName().getString();
        }
        if (entity.isVehicle()) {
            return "cannot lift " + name + ": has a rider";
        }
        if (isBoss(entity)) {
            return "cannot lift " + name + ": boss";
        }
        var type = entity.getType().builtInRegistryHolder();
        if (type.is(CRANE_NEVER)) {
            return "cannot lift " + name + ": in tag simpleplanes:crane_never";
        }
        if (entity instanceof Enemy && !ALLOW_HOSTILES && !type.is(CRANE_LIFTABLE)) {
            return "cannot lift " + name + ": hostile";
        }
        double m = massOf(entity);
        double limit = SlungLoad.liftLimit(MultirotorPhysics.T_MAX);
        if (m > limit) {
            return String.format(Locale.ROOT, "too heavy: %s \u2248 %.2f, max %.2f (%.0f%% of capacity)", name, m, limit,
                100 * m / SlungLoad.capacity(MultirotorPhysics.T_MAX));
        }
        return null;
    }

    /** The four vanilla bosses always; {@code c:bosses} unless the type is also in {@link #CRANE_LIFTABLE}. */
    public static boolean isBoss(Entity entity) {
        EntityType<?> type = entity.getType();
        var holder = type.builtInRegistryHolder();
        return type == EntityTypes.ENDER_DRAGON || type == EntityTypes.WITHER || type == EntityTypes.WARDEN
            || type == EntityTypes.ELDER_GUARDIAN || holder.is(C_BOSSES) && !holder.is(CRANE_LIFTABLE);
    }

    /**
     * A slung mob does not fight. Two parts: {@link #SLUNG_BLIND} while it is a passenger (set and cleared in
     * {@link #addPassenger}/{@link #removePassenger}, so every way off the hook clears it), and this, every tick
     * from the crane's tick, which runs before the passenger's: the target is dropped and a creeper's fuse runs
     * back down. A creeper lit by flint and steel ({@code isIgnited}) still explodes.
     */
    private static void pacify(Mob mob) {
        mob.setTarget(null);
        mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        mob.getNavigation().stop();
        if (mob instanceof Creeper creeper && !creeper.isIgnited()) {
            creeper.setSwellDir(-1);
        }
    }

    private static void setSlung(Mob mob, boolean slung) {
        AttributeInstance range = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (range == null) {
            return;
        }
        if (slung && !range.hasModifier(SLUNG_BLIND.id())) {
            range.addTransientModifier(SLUNG_BLIND);
        } else if (!slung) {
            range.removeModifier(SLUNG_BLIND.id());
        }
    }

    /**
     * Estimated mass in drone masses: bounding-box volume w^2 * h at the entity's current size (babies, slime
     * size and the scale attribute follow), times (1 + knockback resistance) as a density proxy (iron golem and
     * warden 1.0, ravager 0.75, hoglin 0.6, armour adds to players), times the datapack multiplier tags.
     */
    public static double massOf(Entity entity) {
        double density = 1.0;
        if (entity instanceof LivingEntity living) {
            AttributeInstance kbr = living.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            density += kbr == null ? 0.0 : Mth.clamp(kbr.getValue(), 0.0, 1.0);
        }
        var type = entity.getType().builtInRegistryHolder();
        if (type.is(CRANE_MASS_HALF)) {
            density *= 0.5;
        }
        if (type.is(CRANE_MASS_DOUBLE)) {
            density *= 2.0;
        }
        if (type.is(CRANE_MASS_QUADRUPLE)) {
            density *= 4.0;
        }
        return SlungLoad.massOf(entity.getBbWidth(), entity.getBbHeight(), density);
    }

    /** "mass 10.58, 171% of capacity, max lift 0.48 b" for a load of mass m on this crane's rated thrust. */
    public String describeLoad(double m) {
        double h = SlungLoad.ceiling(m, phys.tMaxBase);
        return String.format(Locale.ROOT, "mass %.2f, %.0f%% of capacity%s", m, 100 * m / SlungLoad.capacity(phys.tMaxBase),
            Double.isInfinite(h) ? "" : String.format(Locale.ROOT, ", max lift %.1f b", Math.max(0.0, h)));
    }

    /** Max load-bottom height above ground for the current load; infinite when not limited or not carrying. */
    public double liftCeiling() {
        return isCarrying() ? SlungLoad.ceiling(rope.mass, phys.tMaxBase) : Double.POSITIVE_INFINITY;
    }

    /** Highest drone y that keeps a lift-limited load at its ceiling; infinite when not limited. */
    public double ceilingY() {
        double h = liftCeiling();
        if (Double.isInfinite(h) || getFirstPassenger() == null) {
            return Double.POSITIVE_INFINITY;
        }
        return getY() - loadAgl() + Math.max(0.0, h);
    }

    // ---- orders (remote item and /crane) ----

    /** Orders a pickup; returns the refusal text, or null if accepted. */
    public @Nullable String orderPickup(Entity mob) {
        String refused = refusal(mob);
        if (refused != null) {
            CraneFeedback.report(this, "refused: " + refused);
            if (!isCarrying()) {
                holdHere();
            }
            return refused;
        }
        if (isCarrying()) {
            String busy = "busy: already carrying " + loadName;
            CraneFeedback.report(this, busy);
            return busy;
        }
        pickupId = mob.getUUID();
        rotorsOff = false;
        enter(State.TO_PICKUP);
        CraneFeedback.report(this, "picking up " + mob.getName().getString() + " (" + describeLoad(massOf(mob)) + ")");
        return null;
    }

    /** Sets the delivery point; flies there now if carrying. */
    public void orderDeliver(Vec3 point) {
        delivery = point;
        if (isCarrying()) {
            rotorsOff = false;
            afterStow = State.IDLE;
            enter(State.CARRY);
            CraneFeedback.report(this, String.format(Locale.ROOT, "delivering %s to %.1f %.1f %.1f", loadName, point.x, point.y, point.z));
        } else {
            CraneFeedback.report(this, String.format(Locale.ROOT, "delivery point set to %.1f %.1f %.1f", point.x, point.y, point.z));
        }
    }

    /** Flies straight to a point and hovers there (carrying: with the load, no terrain following). */
    public void orderGoto(Vec3 point) {
        rotorsOff = false;
        pickupId = null;
        if (isCarrying()) {
            delivery = null;
            enter(State.CARRY);
        } else {
            enter(State.IDLE);
        }
        target = point;
        CraneFeedback.log(this, String.format(Locale.ROOT, "going to %.1f %.1f %.1f", point.x, point.y, point.z));
    }

    /** Lands at (x, z); a load is set down there first. */
    public void orderLand(double x, double z) {
        rotorsOff = false;
        pickupId = null;
        if (isCarrying()) {
            delivery = new Vec3(x, getY(), z);
            afterStow = State.LAND;
            enter(State.CARRY);
            return;
        }
        enter(State.LAND);
        target = new Vec3(x, getY(), z);
    }

    /** Recall to the owner; a load is set down next to them first. */
    public void orderReturn(@Nullable UUID player) {
        if (player != null) {
            owner = player;
        }
        Player p = owner == null ? null : level().getPlayerByUUID(owner);
        if (p == null || p.distanceTo(this) > RECALL_RANGE) {
            CraneFeedback.report(this, "recall: owner out of range, hovering");
            if (!isCarrying()) {
                holdHere();
            }
            return;
        }
        rotorsOff = false;
        pickupId = null;
        if (isCarrying()) {
            delivery = returnPoint(p);
            afterStow = State.RETURN;
            enter(State.CARRY);
        } else {
            enter(State.RETURN);
        }
    }

    /** Stops everything; a carried load is set down (at once if it is low, else lowered first). */
    public void orderStop() {
        pickupId = null;
        afterStow = State.IDLE;
        if (isCarrying()) {
            if (loadAgl() < OVERLOAD_RELEASE_AGL) {
                enter(State.RELEASE);
            } else {
                delivery = position();
                enter(State.LOWER_LOAD);
            }
        } else {
            rope.targetLength = SlungLoad.L_MIN;
            holdHere();
        }
    }

    public void orderWinch(double length) {
        rope.targetLength = Mth.clamp(length, SlungLoad.L_MIN, SlungLoad.L_MAX);
    }

    /** Test aid: sets the rope angle on the x axis. */
    public void debugKick(double degrees) {
        rope.thetaX = Math.toRadians(degrees);
        rope.omegaX = 0;
    }

    /** Hover at pos, or park (rotors off) when hover is false. */
    public void initAt(Vec3 pos, boolean hover) {
        target = hover ? pos : null;
        rotorsOff = !hover;
        home = pos;
        state = State.IDLE;
    }

    private void holdHere() {
        pickupId = null;
        enter(State.IDLE);
        if (!rotorsOff) {
            target = position();
        }
    }

    private void enter(State next) {
        if (next != state) {
            CraneFeedback.log(this, "state " + state + " -> " + next);
        }
        state = next;
        stateTicks = 0;
        arriveTicks = 0;
        controller.vMax = CraneController.V_MAX;
        controller.vzMax = CraneController.VZ_MAX;
        switch (next) {
            case CARRY -> {
                carryBest = Double.MAX_VALUE;
                carryStall = 0;
            }
            case LOWER -> lowerStart = null;
            case STOW -> {
                rope.targetLength = SlungLoad.L_MIN;
                target = new Vec3(getX(), surface(getX(), getZ()) + STOW_AGL, getZ());
            }
            case WINCH_IN -> target = position();
            case IDLE -> {
                if (target == null && !rotorsOff) {
                    target = position();
                }
            }
            default -> { }
        }
    }

    // ---- state machine ----

    private static boolean isCarryingState(State s) {
        return s == State.WINCH_IN || s == State.CARRY || s == State.LOWER_LOAD || s == State.OVERLOAD;
    }

    private void checkLoad() {
        if (!isCarrying()) {
            return;
        }
        Entity load = getFirstPassenger();
        if (load == null || !load.isAlive()) {
            String why;
            if (load != null) {
                why = " died";
            } else if (carried != null && carried.isRemoved()) {
                // a creeper that exploded, a mob discarded on peaceful, /kill
                Entity.RemovalReason reason = carried.getRemovalReason();
                why = " was removed (" + (reason == null ? "unknown" : reason.name().toLowerCase(Locale.ROOT)) + ")";
            } else {
                why = " let go";
            }
            carried = null;
            setCarrying(false);
            rope.mass = 0;
            phys.mass = 1;
            CraneFeedback.report(this, "load lost: " + loadName + why);
            loadName = "none";
            delivery = null;
            if (isCarryingState(state)) {
                enter(State.STOW);
            }
        } else if (load != carried) {
            String was = loadName;
            load.addTag(LOAD_TAG);
            attachMass(load);
            CraneFeedback.report(this, "load changed: " + was + " is now " + loadName + " (" + describeLoad(rope.mass) + ")");
        }
    }

    private void tickStateMachine(ServerLevel level) {
        stateTicks++;
        // a lift-limited load (finite ceiling) sinking while saturated is settling onto its ceiling, not overloaded
        if (isCarrying() && isCarryingState(state) && state != State.OVERLOAD && Double.isInfinite(liftCeiling())
            && satTicks >= OVERLOAD_TICKS && phys.v[1] < OVERLOAD_VY) {
            CraneFeedback.report(this, "overloaded: thrust saturated and sinking, lowering " + loadName);
            enter(State.OVERLOAD);
        } else if (isCarrying() && isCarryingState(state) && state != State.OVERLOAD && liftCeiling() < 0) {
            CraneFeedback.report(this, "overloaded: " + loadName + " is over the lift limit, lowering it");
            enter(State.OVERLOAD);
        }
        switch (state) {
            case IDLE, ATTACH -> { }
            case TO_PICKUP -> tickToPickup(level);
            case LOWER -> tickLower(level);
            case WINCH_IN -> {
                rope.targetLength = SlungLoad.L_CARRY;
                if (rope.length == SlungLoad.L_CARRY) {
                    enter(State.CARRY);
                    target = new Vec3(getX(), surface(getX(), getZ()) + CRUISE_AGL + rope.length, getZ());
                }
            }
            case CARRY -> tickCarry();
            case LOWER_LOAD -> tickLowerLoad();
            case RELEASE -> release(null);
            case STOW -> {
                if (rope.length == SlungLoad.L_MIN) {
                    State next = afterStow;
                    afterStow = State.IDLE;
                    if (next == State.RETURN) {
                        orderReturn(null);
                    } else {
                        enter(next);
                    }
                }
            }
            case RETURN -> tickReturn();
            case LAND -> tickLand();
            case OVERLOAD -> tickOverload();
        }
    }

    private @Nullable Entity pickupEntity(ServerLevel level) {
        if (pickupId == null) {
            return null;
        }
        Entity e = level.getEntity(pickupId);
        return e != null && e.isAlive() ? e : null;
    }

    private void abortPickup(String why) {
        CraneFeedback.report(this, "pickup aborted: " + why);
        rope.targetLength = SlungLoad.L_MIN;
        holdHere();
    }

    private void tickToPickup(ServerLevel level) {
        Entity mob = pickupEntity(level);
        String refused = mob == null ? "target lost" : refusal(mob);
        if (refused != null) {
            abortPickup(refused);
            return;
        }
        rope.targetLength = SlungLoad.L_MIN;
        target = new Vec3(mob.getX(), mob.getBoundingBox().maxY + PICK_CLEARANCE + SlungLoad.L_PICK, mob.getZ());
        if (arrived(target)) {
            enter(State.LOWER);
        } else if (stateTicks > PICKUP_TIMEOUT) {
            abortPickup("could not reach " + mob.getName().getString());
        }
    }

    private void tickLower(ServerLevel level) {
        Entity mob = pickupEntity(level);
        String refused = mob == null ? "target lost" : refusal(mob);
        if (refused != null) {
            abortPickup(refused);
            return;
        }
        if (lowerStart == null) {
            lowerStart = mob.position();
        }
        if (Math.hypot(mob.getX() - lowerStart.x, mob.getZ() - lowerStart.z) > FOLLOW_RANGE) {
            abortPickup("target moved away");
            return;
        }
        double holdY = target == null ? getY() : target.y;
        target = new Vec3(mob.getX(), holdY, mob.getZ());
        Vec3 hook = hookPosition();
        double top = mob.getBoundingBox().maxY;
        double dh = Math.hypot(hook.x - mob.getX(), hook.z - mob.getZ());
        double gap = hook.y - top;
        if (dh < HOOK_REACH && gap < HOOK_REACH && gap < SlungLoad.WINCH_RATE) {
            pendingLoad = mob;
            enter(State.ATTACH);
            attach(level);
            return;
        }
        rope.targetLength = Mth.clamp(rope.length + gap, SlungLoad.L_MIN, SlungLoad.L_MAX);
        if (stateTicks > LOWER_TIMEOUT) {
            abortPickup("hook did not reach " + mob.getName().getString());
        }
    }

    private void attach(ServerLevel level) {
        Entity mob = pendingLoad != null ? pendingLoad : pickupEntity(level);
        String refused = mob == null ? "target lost" : refusal(mob);
        if (refused != null) {
            pendingLoad = null;
            abortPickup(refused);
            return;
        }
        pendingLoad = mob;
        boolean ok = mob.startRiding(this, true, true);
        pendingLoad = null;
        if (!ok || mob.getVehicle() != this) {
            abortPickup("cannot lift " + mob.getName().getString() + ": it would not attach to the hook");
            return;
        }
        mob.addTag(LOAD_TAG);
        attachMass(mob);
        rope.stopSwing();
        setCarrying(true);
        pickupId = null;
        CraneFeedback.report(this, "picked up " + loadName + " (" + describeLoad(rope.mass) + ")");
        enter(State.WINCH_IN);
    }

    private void attachMass(Entity load) {
        carried = load;
        rope.mass = massOf(load);
        phys.mass = 1.0 + rope.mass;
        loadName = load.getName().getString();
    }

    private void tickCarry() {
        if (delivery == null) {
            if (target == null) {
                target = position();
            }
            return;
        }
        double dx = delivery.x - getX();
        double dz = delivery.z - getZ();
        double dist = Math.hypot(dx, dz);
        double cruise = cruiseSurface(dx, dz, dist) + CRUISE_AGL + rope.length;
        // a lift-limited load flies as high as it can; climbing-first waits for that height, not the cruise
        double reachable = Math.min(cruise, ceilingY());
        controller.vMax = getY() < reachable - CLIMB_FIRST ? CLIMB_FIRST_SPEED : CraneController.V_MAX;
        target = new Vec3(delivery.x, cruise, delivery.z);
        if (dist < ARRIVE_H) {
            enter(State.LOWER_LOAD);
            return;
        }
        if (dist < carryBest - 0.5) {
            carryBest = dist;
            carryStall = 0;
        } else if (++carryStall > CARRY_STALL_TICKS) {
            double h = liftCeiling();
            CraneFeedback.report(this, String.format(Locale.ROOT, "stuck: no progress with %s for %d ticks%s, setting it down here",
                loadName, CARRY_STALL_TICKS, Double.isInfinite(h) ? "" : String.format(Locale.ROOT, " (max lift %.1f b)", Math.max(0.0, h))));
            delivery = position();
            enter(State.LOWER_LOAD);
        }
    }

    /** Highest surface under the drone and along the track ahead, in loaded chunks only. */
    private double cruiseSurface(double dx, double dz, double dist) {
        double h = surface(getX(), getZ());
        if (dist < 1.0E-3) {
            return h;
        }
        double ux = dx / dist, uz = dz / dist;
        double reach = Math.min(LOOKAHEAD, dist);
        for (double s = 2; s <= reach; s += 2) {
            for (int side = -1; side <= 1; side++) {
                int bx = Mth.floor(getX() + ux * s - uz * side);
                int bz = Mth.floor(getZ() + uz * s + ux * side);
                if (level().hasChunk(bx >> 4, bz >> 4)) {
                    h = Math.max(h, level().getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz));
                }
            }
        }
        if (dist <= LOOKAHEAD && delivery != null) {
            h = Math.max(h, surface(delivery.x, delivery.z));
        }
        return h;
    }

    public double surface(double x, double z) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        if (!level().hasChunk(bx >> 4, bz >> 4)) {
            return getY() - CRUISE_AGL;
        }
        return level().getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
    }

    /** Height of the load's box bottom above the surface under it; the hook's height when empty. */
    public double loadAgl() {
        Entity load = getFirstPassenger();
        Vec3 hook = hookPosition();
        if (load == null) {
            return hook.y - surface(hook.x, hook.z);
        }
        AABB box = load.getBoundingBox();
        double ground = Math.max(Math.max(surface(box.minX, box.minZ), surface(box.maxX - 1.0E-3, box.minZ)),
            Math.max(surface(box.minX, box.maxZ - 1.0E-3), surface(box.maxX - 1.0E-3, box.maxZ - 1.0E-3)));
        return hook.y - load.getBbHeight() - ground;
    }

    public double agl() {
        return getY() - surface(getX(), getZ());
    }

    private void tickLowerLoad() {
        if (getFirstPassenger() == null) {
            enter(State.STOW);
            return;
        }
        Vec3 at = delivery == null ? position() : delivery;
        double agl = loadAgl();
        controller.vzMax = agl > CLIMB_FIRST ? CraneController.VZ_MAX : FINAL_DESCENT_SPEED;
        target = new Vec3(at.x, getY() - agl + RELEASE_GAP, at.z);
        if (agl < RELEASE_BAND) {
            if (++arriveTicks >= RELEASE_TICKS) {
                enter(State.RELEASE);
            }
        } else {
            arriveTicks = 0;
        }
        if (stateTicks > LOWER_LOAD_TIMEOUT && agl < OVERLOAD_RELEASE_AGL) {
            enter(State.RELEASE);
        }
    }

    private void release(@Nullable String message) {
        Entity load = getFirstPassenger();
        String name = loadName;
        if (load != null) {
            load.stopRiding();
        }
        setCarrying(false);
        rope.mass = 0;
        phys.mass = 1;
        loadName = "none";
        carried = null;
        delivery = null;
        if (load != null) {
            CraneFeedback.report(this, message != null ? message + " (" + name + ")"
                : String.format(Locale.ROOT, "set down %s at %.1f %.1f %.1f", name, load.getX(), load.getY(), load.getZ()));
        }
        enter(State.STOW);
    }

    private Vec3 returnPoint(Player p) {
        double dx = getX() - p.getX(), dz = getZ() - p.getZ();
        double d = Math.hypot(dx, dz);
        if (d < 1.0E-3) {
            dx = 1;
            dz = 0;
            d = 1;
        }
        double x = p.getX() + dx / d * RETURN_DISTANCE;
        double z = p.getZ() + dz / d * RETURN_DISTANCE;
        return new Vec3(x, surface(x, z), z);
    }

    private void tickReturn() {
        Player p = owner == null ? null : level().getPlayerByUUID(owner);
        if (p == null || p.distanceTo(this) > RECALL_RANGE) {
            CraneFeedback.report(this, "recall: owner out of range, hovering");
            holdHere();
            return;
        }
        Vec3 spot = returnPoint(p);
        target = new Vec3(spot.x, spot.y + RETURN_AGL, spot.z);
        if (arrived(target)) {
            enter(State.LAND);
            target = new Vec3(getX(), getY(), getZ());
        }
    }

    private void tickLand() {
        Vec3 t = target == null ? position() : target;
        controller.vzMax = LAND_RATE;
        double ground = surface(t.x, t.z);
        boolean over = Math.hypot(t.x - getX(), t.z - getZ()) < LAND_ALIGN;
        target = new Vec3(t.x, over ? ground - 0.5 : Math.max(getY(), ground + LAND_HOLD_AGL), t.z);
        if (onGround()) {
            phys.v[0] = phys.v[1] = phys.v[2] = 0;
            setDeltaMovement(Vec3.ZERO);
            rotorsOff = true;
            target = null;
            CraneFeedback.report(this, String.format(Locale.ROOT, "landed at %.1f %.1f %.1f", getX(), getY(), getZ()));
            enter(State.IDLE);
        }
    }

    private void tickOverload() {
        if (!isCarrying()) {
            enter(State.STOW);
            return;
        }
        double agl = loadAgl();
        target = new Vec3(getX(), getY(), getZ());
        if (agl < OVERLOAD_RELEASE_AGL) {
            release("load released: overweight");
        } else {
            rope.targetLength = Mth.clamp(rope.length + agl, SlungLoad.L_MIN, SlungLoad.L_MAX);
        }
    }

    private boolean arrived(Vec3 t) {
        boolean near = Math.hypot(t.x - getX(), t.z - getZ()) < ARRIVE_H && Math.abs(t.y - getY()) < ARRIVE_V;
        arriveTicks = near ? arriveTicks + 1 : 0;
        return arriveTicks >= ARRIVE_TICKS;
    }

    /** Whether the crane needs its chunks kept loaded: anything but parked on the ground. */
    public boolean needsChunks() {
        return !isRemoved() && (dying || !rotorsOff || state != State.IDLE);
    }

    // ---- damage and death ----

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (isRemoved() || isInvulnerableToBase(source) || damageTimeout > 0) {
            return false;
        }
        Entity direct = source.getDirectEntity();
        // the load cannot hit its own crane; an explosion (a lit creeper on the hook) still does
        if (direct != null && direct.isPassengerOfSameVehicle(this) && !source.is(DamageTypeTags.IS_EXPLOSION)) {
            return false;
        }
        if (onGround() && source.getDirectEntity() instanceof Player) {
            amount *= 3;
        }
        setTimeSinceHit(20);
        damageTimeout = 10;
        applyDamage(level, amount, source);
        return true;
    }

    private void applyDamage(ServerLevel level, float amount, @Nullable DamageSource source) {
        int before = getHealth();
        setHealth((int) (before - amount));
        boolean creative = source != null && source.getEntity() instanceof Player player && player.getAbilities().instabuild;
        if (creative) {
            CraneFeedback.report(this, "lost: removed by a creative player");
            kill(level);
        } else if (getHealth() <= 0 && !dying) {
            // Only a player's own hit gives the item back (see PlaneEntity#isPlayerBreak); anything else crashes it.
            if (before > 0 && source != null && PlaneEntity.isPlayerBreak(source)) {
                CraneFeedback.report(this, "lost: broken by a player");
                kill(level);
                if (level.getGameRules().get(GameRules.ENTITY_DROPS)) {
                    dropItem(level);
                }
            } else {
                dying = true;
                CraneFeedback.report(this, String.format(Locale.ROOT, "destroyed at %.1f %.1f %.1f (agl %.1f), falling",
                    getX(), getY(), getZ(), agl()));
            }
        }
    }

    private void tickDying(ServerLevel level) {
        dying = true;
        stateTicks++;
        Entity load = getFirstPassenger();
        if (load != null) {
            releasedLoad = load.getUUID();
            CraneFeedback.report(this, String.format(Locale.ROOT, "load released: crane destroyed (%s, feet at agl %.1f)", loadName, loadAgl()));
            load.stopRiding();
            setCarrying(false);
            rope.mass = 0;
            phys.mass = 1;
            loadName = "none";
            carried = null;
        }
        phys.thrust = 0;
        controller.saturated = false;
        physicsStep();
        syncOut();
        if (trace) {
            CraneFeedback.trace(this, traceTick++);
        }
        if (onGround() || horizontalCollision || verticalCollision || stateTicks > FALL_TIMEOUT) {
            crash(level);
        }
    }

    private void crash(ServerLevel level) {
        Blast blast = new Blast(DEATH_BLAST, false, false);
        UUID spared = releasedLoad;
        // the load it let go of on the way down is not hurt by its own crash
        ExplosionDamageCalculator calculator = new ExplosionDamageCalculator() {
            @Override
            public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
                return !entity.getUUID().equals(spared) && super.shouldDamageEntity(explosion, entity);
            }
        };
        CraneFeedback.report(this, String.format(Locale.ROOT, "lost: crashed at %.1f %.1f %.1f", getX(), getY(), getZ()));
        level.explode(this, null, calculator, getX(), getY(), getZ(), blast.power(), blast.fire(), blast.interaction());
        discard();
    }

    protected void dropItem(ServerLevel level) {
        Entity item = spawnAtLocation(level, getItemStack());
        if (item != null) {
            item.setPermanentlyInvulnerable(true);
        }
    }

    public ItemStack getItemStack() {
        ItemStack stack = SimplePlanesItems.QUADCOPTER_ITEM.get().getDefaultInstance();
        CompoundTag tag = new CompoundTag();
        tag.putString("material", entityData.get(MATERIAL));
        tag.putInt("health", MAX_HEALTH);
        tag.putBoolean("Used", true);
        stack.set(SimplePlanesComponents.ENTITY_TAG.get(), tag);
        return stack;
    }

    /** Public bridge for the item, which stores the entity data as a raw tag. */
    public void loadFromItemTag(CompoundTag tag) {
        readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, registryAccess(), tag));
        state = State.IDLE;
        setCarrying(false);
        checkLoadAfterLoad = false;
    }

    // ---- persistence ----

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        input.getString("material").ifPresent(this::setMaterial);
        setHealth(input.getIntOr("health", getHealth()));
        setRopeLength(input.getFloatOr("rope_length", getRopeLength()));
        rope.thetaX = input.getDoubleOr("theta_x", 0);
        rope.thetaZ = input.getDoubleOr("theta_z", 0);
        rope.omegaX = rope.omegaZ = 0;
        setCarrying(input.getBooleanOr("carrying", false));
        checkLoadAfterLoad = true;
        state = input.getString("state").map(QuadcopterEntity::parseState).orElse(State.IDLE);
        afterStow = input.getString("after_stow").map(QuadcopterEntity::parseState).orElse(State.IDLE);
        target = input.read("target", Vec3.CODEC).orElse(null);
        delivery = input.read("delivery", Vec3.CODEC).orElse(null);
        home = input.read("home", Vec3.CODEC).orElse(null);
        owner = input.read("owner", UUIDUtil.CODEC).orElse(null);
        pickupId = input.read("pickup", UUIDUtil.CODEC).orElse(null);
        rotorsOff = input.getBooleanOr("rotors_off", false);
        loadName = input.getStringOr("load_name", "none");
        physInit = false;
        Quaternionf q = MathUtil.toQuaternionf(getYRot(), 0, 0);
        setQ(q);
        Q_Client = new Quaternionf(q);
        Q_Prev = new Quaternionf(q);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putString("material", entityData.get(MATERIAL));
        output.putInt("health", getHealth());
        output.putString("state", state.name());
        output.putString("after_stow", afterStow.name());
        output.storeNullable("target", Vec3.CODEC, target);
        output.storeNullable("delivery", Vec3.CODEC, delivery);
        output.storeNullable("home", Vec3.CODEC, home);
        output.storeNullable("owner", UUIDUtil.CODEC, owner);
        output.storeNullable("pickup", UUIDUtil.CODEC, pickupId);
        output.putFloat("rope_length", (float) rope.length);
        output.putDouble("theta_x", rope.thetaX);
        output.putDouble("theta_z", rope.thetaZ);
        output.putBoolean("carrying", isCarrying());
        output.putBoolean("rotors_off", rotorsOff);
        output.putString("load_name", loadName);
    }

    private static State parseState(String name) {
        try {
            return State.valueOf(name);
        } catch (IllegalArgumentException e) {
            return State.IDLE;
        }
    }
}
