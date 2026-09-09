package xyz.przemyk.simpleplanes.entities;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.api.BlastGuards;
import xyz.przemyk.simpleplanes.autopilot.Blast; // autopilot:
import xyz.przemyk.simpleplanes.autopilot.PlaneAutopilot; // autopilot:
import xyz.przemyk.simpleplanes.container.ModifyUpgradesContainer;
import xyz.przemyk.simpleplanes.container.PlaneInventoryContainer;
import xyz.przemyk.simpleplanes.misc.MathUtil;
import xyz.przemyk.simpleplanes.network.*;
import xyz.przemyk.simpleplanes.setup.*;
import xyz.przemyk.simpleplanes.upgrades.LargeUpgrade;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;
import xyz.przemyk.simpleplanes.upgrades.armor.ArmorUpgrade;
import xyz.przemyk.simpleplanes.upgrades.booster.BoosterUpgrade;
import xyz.przemyk.simpleplanes.upgrades.engines.EngineUpgrade;
import xyz.przemyk.simpleplanes.upgrades.shooter.ShooterUpgrade;

import org.jspecify.annotations.Nullable;
import java.util.*;

import static net.minecraft.util.Mth.wrapDegrees;
import static xyz.przemyk.simpleplanes.misc.MathUtil.*;

@SuppressWarnings({"deprecation"})
public class PlaneEntity extends Entity {
    public static final EntityDataAccessor<Integer> MAX_HEALTH = SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.INT);
    public static final EntityDataAccessor<Integer> HEALTH = SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.INT);
    public static final EntityDataAccessor<Float> MAX_SPEED = SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<String> MATERIAL = SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.STRING);
    public static final EntityDataAccessor<Integer> TIME_SINCE_HIT = SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.INT);
    public static final EntityDataAccessor<Float> DAMAGE_TAKEN = SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Quaternionfc> Q = SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.QUATERNION);
    public static final EntityDataAccessor<Integer> THROTTLE = SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.INT);
    public static final EntityDataAccessor<Byte> PITCH_UP = SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.BYTE);
    public static final EntityDataAccessor<Byte> YAW_RIGHT = SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.BYTE);
    // autopilot: true while the flight director is flying this aircraft. This has to be synched: the
    // PlaneAutopilot object lives on the server only, so without it a riding player's client still
    // believes it is the pilot and keeps steering the aircraft out from under the flight director.
    public static final EntityDataAccessor<Boolean> AUTOPILOT_FLYING = SynchedEntityData.defineId(PlaneEntity.class, EntityDataSerializers.BOOLEAN);
    public static final int MAX_THROTTLE = 5;
    public Quaternionf Q_Client = new Quaternionf();
    public Quaternionf Q_Prev = new Quaternionf();

    private int onGroundTicks;
    /** Impact-detection state; the whole collision tract lives in {@link PlaneCollisions}. */
    public final PlaneCollisions.State collisionState = new PlaneCollisions.State();
    public final HashMap<Identifier, Upgrade> upgrades = new HashMap<>();
    public EngineUpgrade engineUpgrade = null;

    public float rotationRoll;
    public float prevRotationRoll;
    private float deltaRotation;
    private float deltaRotationLeft;
    private int deltaRotationTicks;

    private Block planksMaterial;
    private int damageTimeout;
    public int notMovingTime;
    public int goldenHeartsTimeout = 0;

    private final int networkUpdateInterval;

    public float propellerRotationOld;
    public float propellerRotationNew;

    // 26.2: Entity#lerpTo is gone, position interpolation is handled by InterpolationHandler.
    private final InterpolationHandler interpolation = new InterpolationHandler(this, 10);

    /**
     * Per-entity motion scratch. This used to be a single {@code static} instance shared by every
     * plane on both logical sides, which corrupted the motion of concurrently ticking planes.
     */
    private final TempMotionVars motionVars = new TempMotionVars();

    /**
     * Scratch quaternion reused by {@link #tick()}. Only ever read/written on the logical side that
     * owns this entity, and never stored into a field that outlives the call (see
     * {@link #setQ_Client(Quaternionf)} / {@link #setQ_prev(Quaternionf)}, which alias the argument).
     */
    private final Quaternionf tickQScratch = new Quaternionf();

    /** Scratch quaternion reused by {@link #transformPos(Vector3f)}; must not be {@link #tickQScratch}, transformPos runs inside tick(). */
    private final Quaternionf transformQScratch = new Quaternionf();

    /**
     * Euler/quaternion scratch buffers for the hot path. Each one belongs to exactly one method, so
     * they never overlap: {@link #transformPos(Vector3f)} runs inside {@link #tick()} (via
     * {@code getTickPush} and {@code positionRider}) while {@code tickAngles} is still live.
     */
    private final EulerAngles tickAngles = new EulerAngles();
    private final EulerAngles deltaRotationAngles = new EulerAngles();
    private final EulerAngles transformAngles = new EulerAngles();
    private final Quaternionf transformRotScratch = new Quaternionf();
    /** Shared with {@code HelicopterEntity#getTickPush}, which builds a vertical thrust vector instead. */
    protected final Vector3f pushScratch = new Vector3f();

    /** Last rotation actually pushed to the server, so an idle plane stops spamming RotationPacket. */
    private final Quaternionf lastSentQ = new Quaternionf();

    // autopilot: server-side flight director. When present it supplies the same four control inputs
    // a player would (throttle / pitch / yaw / roll) instead of the plane being flown by a passenger.
    // All of the logic lives in xyz.przemyk.simpleplanes.autopilot.PlaneAutopilot.
    private @Nullable PlaneAutopilot autopilot;

    // autopilot: accessors for the flight director.
    public @Nullable PlaneAutopilot getAutopilot() {
        return autopilot;
    }

    // autopilot:
    public void setAutopilot(@Nullable PlaneAutopilot autopilot) {
        this.autopilot = autopilot;
        if (!level().isClientSide()) {
            entityData.set(AUTOPILOT_FLYING, isAutopilotEngaged());
        }
    }

    // autopilot: true when the flight director is flying this plane, i.e. control inputs come from
    // the autopilot rather than from a controlling passenger.
    public boolean isAutopilotEngaged() {
        return autopilot != null && autopilot.isActive();
    }

    // autopilot: the same question as isAutopilotEngaged(), but answerable on both sides. The server
    // owns the truth in the field above; the client reads the synched mirror of it.
    public boolean isAutopilotFlying() {
        return level().isClientSide() ? entityData.get(AUTOPILOT_FLYING) : isAutopilotEngaged();
    }

    // autopilot: exposes the protected rotation-rate multiplier so the controllers can size their
    // braking correctly on the larger airframes, which turn more slowly.
    public float autopilotRotationSpeedMultiplier() {
        return getRotationSpeedMultiplier();
    }

    public PlaneEntity(EntityType<? extends PlaneEntity> entityTypeIn, Level worldIn) {
        this(entityTypeIn, worldIn, Blocks.OAK_PLANKS);
    }

    public PlaneEntity(EntityType<? extends PlaneEntity> entityTypeIn, Level worldIn, Block material) {
        super(entityTypeIn, worldIn);
        networkUpdateInterval = entityTypeIn.updateInterval();
        setMaterial(material);
        setMaxSpeed(1f);
    }

    public PlaneEntity(EntityType<? extends PlaneEntity> entityTypeIn, Level worldIn, Block material, double x, double y, double z) {
        this(entityTypeIn, worldIn, material);
        setPos(x, y, z);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder pBuilder) {
        pBuilder.define(MAX_HEALTH, 10);
        pBuilder.define(HEALTH, 10);
        pBuilder.define(Q, new Quaternionf());
        pBuilder.define(MAX_SPEED, 0.25f);
        pBuilder.define(MATERIAL, BuiltInRegistries.BLOCK.getKey(Blocks.OAK_PLANKS).toString());
        pBuilder.define(TIME_SINCE_HIT, 0);
        pBuilder.define(DAMAGE_TAKEN, 0f);
        pBuilder.define(THROTTLE, 0);
        pBuilder.define(PITCH_UP, (byte) 0);
        pBuilder.define(YAW_RIGHT, (byte) 0);
        pBuilder.define(AUTOPILOT_FLYING, false);
    }

    @Override
    public InterpolationHandler getInterpolation() {
        return interpolation;
    }

    public float getMaxSpeed() {
        return entityData.get(MAX_SPEED);
    }

    public void setMaxSpeed(float maxSpeed) {
        entityData.set(MAX_SPEED, maxSpeed);
    }

    public Quaternionf getQ() {
        return new Quaternionf(entityData.get(Q));
    }

    /**
     * Allocation-free variant of {@link #getQ()}: copies the value into {@code dest} and returns it.
     * Never pass a buffer that is going to be handed to {@link #setQ(Quaternionf)},
     * {@link #setQ_Client(Quaternionf)} or {@link #setQ_prev(Quaternionf)} — those store the
     * reference itself, so a reused scratch object would alias the stored rotation.
     */
    public Quaternionf getQ(Quaternionf dest) {
        return dest.set(entityData.get(Q));
    }

    public void setQ(Quaternionf q) {
        entityData.set(Q, q);
    }

    public Quaternionf getQ_Client() {
        return new Quaternionf(Q_Client);
    }

    /** Allocation-free variant of {@link #getQ_Client()}; see {@link #getQ(Quaternionf)} for the aliasing rules. */
    public Quaternionf getQ_Client(Quaternionf dest) {
        return dest.set(Q_Client);
    }

    public void setQ_Client(Quaternionf q) {
        Q_Client = q;
    }

    public Quaternionf getQ_Prev() {
        return new Quaternionf(Q_Prev);
    }

    /** Allocation-free variant of {@link #getQ_Prev()}; see {@link #getQ(Quaternionf)} for the aliasing rules. */
    public Quaternionf getQ_Prev(Quaternionf dest) {
        return dest.set(Q_Prev);
    }

    public void setQ_prev(Quaternionf q) {
        Q_Prev = q;
    }

    public Block getMaterial() {
        return planksMaterial;
    }

    public void setHealth(int health) {
        entityData.set(HEALTH, Math.max(health, 0));
    }

    public int getHealth() {
        return entityData.get(HEALTH);
    }

    public int getMaxHealth() {
        return entityData.get(MAX_HEALTH);
    }

    @Override
    public ItemStack getPickResult() {
        return getItemStack();
    }

    public void setMaterial(String material) {
        entityData.set(MATERIAL, material);
        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(material));
        planksMaterial = block == null ? Blocks.OAK_PLANKS : block;
    }

    public void setMaterial(Block material) {
        entityData.set(MATERIAL, BuiltInRegistries.BLOCK.getKey(material).toString());
        planksMaterial = material;
    }

    public static final TagKey<DimensionType> BLACKLISTED_DIMENSIONS_TAG = TagKey.create(Registries.DIMENSION_TYPE, Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "blacklisted_dimensions"));

    public boolean isPowered() {
        // autopilot: aircraft conjured by the autopilot tools run on autopilot fuel; a plane the
        // player built still needs a working engine (PlaneAutopilot#providesPower).
        // The client has no PlaneAutopilot to ask and, while the flight director is flying, no
        // controlling passenger to make isCreative() true either, so it uses the synched flag. This
        // only drives the propeller animation — the physics that reads isPowered() is server-side.
        return isAlive() && !level().dimensionTypeRegistration().is(BLACKLISTED_DIMENSIONS_TAG) && (isCreative() || (autopilot != null && autopilot.providesPower()) || (level().isClientSide() && isAutopilotFlying()) || (engineUpgrade != null && engineUpgrade.isPowered()));
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        List<Entity> passengers = getPassengers();
        if (!upgrades.containsKey(SimplePlanesRegistries.UPGRADE_TYPE.getKey(SimplePlanesUpgrades.SEATS.get()))) {
            return passengers.isEmpty();
        } else {
            return passengers.size() < 3;
        }
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        ItemStack itemStack = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && itemStack.isEmpty()) {
            boolean hasPlayer = false;
            for (Entity passenger : getPassengers()) {
                if ((passenger instanceof Player)) {
                    hasPlayer = true;
                    break;
                }
            }
            if ((!hasPlayer) || SimplePlanesConfig.THIEF.get()) {
                ejectPassengers();
            }
            return InteractionResult.SUCCESS;
        }

        if (itemStack.getItem() == SimplePlanesItems.WRENCH.get()) {
            if (!level().isClientSide()) {
                player.openMenu(modifyUpgradesProvider(this));
                return InteractionResult.CONSUME;
            }
            return InteractionResult.SUCCESS;
        }

        if (tryToAddUpgrade(player, itemStack)) {
            return InteractionResult.SUCCESS;
        }

        if (!level().isClientSide()) {
            return player.startRiding(this) ? InteractionResult.CONSUME : InteractionResult.FAIL;
        } else {
            return player.getRootVehicle() == getRootVehicle() ? InteractionResult.FAIL : InteractionResult.SUCCESS;
        }
    }

    /**
     * NeoForge's {@code openMenu(provider, buf -> buf.writeVarInt(getId()))} is gone; on Fabric the
     * extra screen-opening data comes from an {@link ExtendedMenuProvider} whose codec is declared
     * on the {@code ExtendedMenuType} in {@code SimplePlanesContainers} (VAR_INT = entity id).
     */
    public static ExtendedMenuProvider<Integer> planeInventoryProvider(PlaneEntity planeEntity) {
        return new ExtendedMenuProvider<>() {
            @Override
            public Integer getScreenOpeningData(ServerPlayer serverPlayer) {
                return planeEntity.getId();
            }

            @Override
            public Component getDisplayName() {
                return planeEntity.getName();
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
                return new PlaneInventoryContainer(containerId, inventory, planeEntity);
            }
        };
    }

    public static ExtendedMenuProvider<Integer> modifyUpgradesProvider(PlaneEntity planeEntity) {
        return new ExtendedMenuProvider<>() {
            @Override
            public Integer getScreenOpeningData(ServerPlayer serverPlayer) {
                return planeEntity.getId();
            }

            @Override
            public Component getDisplayName() {
                return Component.empty();
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
                return new ModifyUpgradesContainer(containerId, inventory, planeEntity.getId());
            }
        };
    }

    protected boolean tryToAddUpgrade(Player playerEntity, ItemStack itemStack) {
        Optional<UpgradeType> upgradeTypeOptional = SimplePlanesUpgrades.getUpgradeFromItem(itemStack.getItem());
        return upgradeTypeOptional.map(upgradeType -> {
            if (canAddUpgrade(upgradeType)) {
                Upgrade upgrade = upgradeType.instanceSupplier.apply(this);
                addUpgrade(playerEntity, itemStack, upgrade);
                return true;
            }
            return false;
        }).orElse(false);
    }

    protected void addUpgrade(Player playerEntity, ItemStack itemStack, Upgrade upgrade) {
        upgrade.onApply(itemStack);
        if (!playerEntity.isCreative()) {
            itemStack.shrink(1);
        }
        UpgradeType upgradeType = upgrade.getType();
        upgrades.put(SimplePlanesRegistries.UPGRADE_TYPE.getKey(upgradeType), upgrade);
        if (upgradeType.isEngine) {
            engineUpgrade = (EngineUpgrade) upgrade;
        }
        if (!level().isClientSide()) {
            SimplePlanesNetworking.sendToPlayersTrackingEntity(this,
                UpdateUpgradePacket.create(true, SimplePlanesRegistries.UPGRADE_TYPE.getKey(upgradeType), this));
        }
    }

    public void addUpgradeUsingWrench(ItemStack itemStack, Upgrade upgrade) {
        upgrade.onApply(itemStack);
        UpgradeType upgradeType = upgrade.getType();
        upgrades.put(SimplePlanesRegistries.UPGRADE_TYPE.getKey(upgradeType), upgrade);
        if (upgradeType.isEngine) {
            engineUpgrade = (EngineUpgrade) upgrade;
        }
        if (!level().isClientSide()) {
            SimplePlanesNetworking.sendToPlayersTrackingEntity(this,
                UpdateUpgradePacket.create(true, SimplePlanesRegistries.UPGRADE_TYPE.getKey(upgradeType), this));
        }
    }

    @Override
    public boolean hurtServer(ServerLevel serverLevel, DamageSource source, float amount) {
        Entity entity = source.getDirectEntity();
        if (entity == getControllingPassenger() && entity instanceof Player player) {
            if (upgrades.get(SimplePlanesRegistries.UPGRADE_TYPE.getKey(SimplePlanesUpgrades.SHOOTER.get())) instanceof ShooterUpgrade shooterUpgrade) {
                shooterUpgrade.use(player);
            }
            return false;
        }

        if (getOnGround() && entity instanceof Player) {
            amount *= 3;
        } else {
            Upgrade upgrade = upgrades.get(SimplePlanesRegistries.UPGRADE_TYPE.getKey(SimplePlanesUpgrades.ARMOR.get()));
            if (upgrade instanceof ArmorUpgrade armorUpgrade) {
                amount = armorUpgrade.getReducedDamage(amount);
            }
        }
        setTimeSinceHit(20);
        setDamageTaken(getDamageTaken() + 10 * amount);

        if (isInvulnerableTo(source) || damageTimeout > 0) {
            return false;
        }
        if (isRemoved()) {
            return false;
        }
        int health = getHealth();
        if (health < 0) {
            return false;
        }

        setHealth((int) (health - amount));
        damageTimeout = 10;
        boolean isPlayer = source.getDirectEntity() instanceof Player;
        boolean creativePlayer = isPlayer && source.getEntity() instanceof Player player && player.getAbilities().instabuild;
        if (creativePlayer) {
            kill(serverLevel);
        } else if (getOnGround() && getHealth() <= 0) {
            kill(serverLevel);
            if (serverLevel.getGameRules().get(GameRules.ENTITY_DROPS)) {
                dropItem(serverLevel);
            }
        }
        return true;
    }

    private void explode() {
        if (level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.SMOKE,
                getX(),
                getY(),
                getZ(),
                5, 1, 1, 1, 2);
            serverLevel.sendParticles(ParticleTypes.POOF,
                getX(),
                getY(),
                getZ(),
                10, 1, 1, 1, 1);
        }
        // autopilot: the warhead is a property of the flight, not a constant. An aircraft with no
        // flight plan — every plane a player ever built or flew — gets Blast.DEFAULT, which is
        // 4.0F with TNT block interaction and no fire, i.e. bit-for-bit what this line used to do.
        Blast blast = Blast.DEFAULT;
        PlaneAutopilot engaged = getAutopilot();
        if (engaged != null && engaged.getPlan() != null) {
            blast = engaged.getPlan().blast();
        }
        // Extension point. Every blast this mod produces passes through this one line, so this is
        // the only place a land-claim mod, a protection plugin or a server's own glue has to be
        // consulted to cover all of them. Guards may weaken the warhead or refuse it outright; with
        // none registered -- the default, and every installation that has not gone looking for it --
        // this is one isEmpty() test and the blast is the one that was ordered. Nothing about the
        // mods that register here is known to this one: see BlastGuard.
        if (level() instanceof ServerLevel guardLevel) {
            blast = BlastGuards.filter(guardLevel, this, position(), blast);
            if (blast == null) {
                // Suppressed. The aircraft is still destroyed and has already left its smoke; only
                // the detonation is skipped.
                return;
            }
        }
        // The fuller overload, because "does it break blocks" and "does it start fires" are separate
        // arguments there: the interaction selects the block behaviour (TNT craters and drops, NONE
        // leaves the world alone and only damages entities) and fire is its own flag.
        level().explode(this, getX(), getY(), getZ(), blast.power(), blast.fire(), blast.interaction());
    }

    protected void dropItem(ServerLevel serverLevel) {
        ItemStack itemStack = getItemStack();
        Entity itemEntity = spawnAtLocation(serverLevel, itemStack);
        if (itemEntity != null) {
            itemEntity.setInvulnerable(true);
        }
    }

    public boolean isPickable() {
        return true;
    }

    @Override
    public void tick() {
        super.tick();

        if (Double.isNaN(getDeltaMovement().length())) {
            setDeltaMovement(Vec3.ZERO);
        }
        yRotO = getYRot();
        xRotO = getXRot();
        prevRotationRoll = rotationRoll;
        if (level().isClientSide()) {
            propellerRotationOld = propellerRotationNew;
            if (isPowered()) {
                int throttle = getThrottle();
                propellerRotationNew += (float) (throttle * 0.1);
            }
            // The engine loop belongs with the propeller animation: both are cosmetics every client
            // that can see the aircraft should get, and both read only synched state. It used to sit
            // further down the tick, past the authority check below, so an aircraft the local client
            // does not own was silent — which since the flight director took the controlling
            // passenger away meant a plane flown by the autopilot made no sound at all, not even for
            // the player riding in it. Nothing about hearing an engine needs local authority.
            if (isPowered() && getThrottle() > 0) {
                playEngineSound();
            }
        }

        if (level().isClientSide() && getHealth() <= 0) {
            level().addAlwaysVisibleParticle(ParticleTypes.LARGE_SMOKE, true, getX(), getY(), getZ(), 0.0, 0.005, 0.0);
        }

        if (level().isClientSide() && getTimeSinceHit() > 0) {
            setTimeSinceHit(getTimeSinceHit() - 1);
        }

        if (level().isClientSide() && !isLocalInstanceAuthoritative()) {
            tickLerp();
            setDeltaMovement(Vec3.ZERO);
            // tickDeltaRotation only reads the quaternion, so the scratch buffer is safe here.
            tickDeltaRotation(getQ_Client(tickQScratch));
            tickUpgrades();
            return;
        }
        markHurt(); //TODO: this might be the cause of high network usage

        // autopilot: the flight director runs before the control inputs are read below, so the
        // throttle/pitch/yaw it sets this tick are the ones the physics acts on. Server only.
        if (!level().isClientSide()) {
            if (autopilot != null) {
                autopilot.tick(this);
            }
            // autopilot: publish who is flying so the client agrees about authority. isActive() can
            // flip inside the tick above (a flight reaching its end), hence setting it afterwards.
            // SynchedEntityData#set only marks dirty on a real change, so this costs nothing.
            entityData.set(AUTOPILOT_FLYING, isAutopilotEngaged());
        }

        TempMotionVars tempMotionVars = getMotionVars();
        if (isNoGravity()) {
            tempMotionVars.gravity = 0;
            tempMotionVars.maxLift = 0;
            tempMotionVars.push = 0.00f;
            tempMotionVars.passiveEnginePush = 0;
        }
        Entity controllingPassenger = getControllingPassenger();
        if (controllingPassenger instanceof Player playerEntity) {
            tempMotionVars.moveForward = getMoveForward(playerEntity);
            tempMotionVars.moveStrafing = playerEntity.xxa;
        } else if (isAutopilotEngaged()) {
            // autopilot: with nobody aboard, the roll/strafe input comes from the flight director.
            tempMotionVars.moveForward = autopilot.getMoveForward();
            tempMotionVars.moveStrafing = autopilot.getMoveStrafing();
            setSprinting(false);
        } else {
            tempMotionVars.moveForward = 0;
            tempMotionVars.moveStrafing = 0;
            setSprinting(false);
        }
        tempMotionVars.turnThreshold = SimplePlanesConfig.TURN_THRESHOLD.get() / 100d;

        if (Math.abs(tempMotionVars.moveStrafing) < tempMotionVars.turnThreshold) {
            tempMotionVars.moveStrafing = 0;
        }

        // Scratch buffer: q is only mutated locally below and is replaced by a fresh instance from
        // normalizeQuaternionf() before it ever reaches setQ()/setQ_Client(), which store the reference.
        Quaternionf q;
        if (level().isClientSide()) {
            q = getQ_Client(tickQScratch);
        } else {
            q = getQ(tickQScratch);
        }

        // toEulerAngles(q).copy() allocated twice per tick; tickAngles belongs to this method alone
        // and stays live until the "back to q" block at the end of the tick.
        EulerAngles anglesOld = toEulerAngles(q, tickAngles);

        Vec3 oldMotion = getDeltaMovement();

        tempMotionVars.push = 0.00625f * getThrottle();

        //motion and rotation interpolation + lift.
        if (getDeltaMovement().length() > 0.05) {
            q = tickRotateMotion(tempMotionVars, q, getDeltaMovement());
        }
        boolean doPitch = true;
        //pitch + movement speed
        if (getOnGround() || isOnWater()) {
            doPitch = tickOnGround(tempMotionVars);
        } else {
            onGroundTicks--;
        }
        if (doPitch) {
            tickPitch(tempMotionVars);
        }

        tickYaw();

        tickMotion(tempMotionVars);

        tickRoll(tempMotionVars);

        // Impact detection: the velocity the aerodynamics produced, before the upgrades get to
        // rewrite it. FloatingUpgrade runs inside tickUpgrades() and arrests a descent over water
        // before move() ever happens, so this is the only place the real water-entry speed exists.
        collisionState.preUpgradeMotion = getDeltaMovement();

        tickUpgrades();

        //made so plane fully stops when moves slow, removing the slipperiness effect
        if (onGroundTicks > -50 && oldMotion.length() < 0.002 && getDeltaMovement().length() < 0.002) {
            setDeltaMovement(Vec3.ZERO);
        }
        reapplyPosition();

        if (!onGround() || getDeltaMovement().horizontalDistanceSqr() > (double) 1.0E-5F || (tickCount + getId()) % 4 == 0) {
            boolean onGroundOld = onGround();
            Vec3 motion = getDeltaMovement();
            if (motion.lengthSqr() > 0.25 || getPitchUp() != 0) {
                setOnGround(true);
            }
            Vec3 posBeforeMove = position();
            move(MoverType.SELF, motion);
            setOnGround(((motion.y()) == 0.0) ? onGroundOld : onGround());
            // Impact detection: the old speedBefore/speedAfter test is a constant -5.0 on 26.2
            // (Entity.move() skips the collision velocity response for client-authoritative
            // vehicles), so the measurement is done from positions + getKnownMovement() instead.
            PlaneCollisions.afterMove(this, motion, posBeforeMove);
        }
        PlaneCollisions.tickEntityCollisions(this);

        if (getHealth() <= 0 && onGround() && !isRemoved()) {
            crash(16);
        }

        //back to q
        // Axis.ZP/XN/YP.rotationDegrees() each allocate a Quaternionf (see com.mojang.math.Axis:
        // ZP == new Quaternionf().rotationZ(a), XN == rotationX(-a)) and q.mul() post-multiplies —
        // which is exactly what JOML's in-place rotateZ/rotateX/rotateY do, allocation-free.
        q.rotateZ((float) Math.toRadians(rotationRoll - anglesOld.roll));
        q.rotateX((float) Math.toRadians(anglesOld.pitch - getXRot()));
        q.rotateY((float) Math.toRadians(getYRot() - anglesOld.yaw));

        q = normalizeQuaternionf(q);

        setQ_prev(getQ_Client());
        setQ(q);
        tickDeltaRotation(q);

        if (level().isClientSide() && isLocalInstanceAuthoritative()) {
            setQ_Client(q);

            // This packet used to go out every single tick from every locally controlled plane, even
            // one parked on a runway with the engine off. The server keeps whatever rotation it last
            // received, so skipping bit-identical resends is free; the epsilon is small enough
            // (1e-5 per component, ~0.001 degrees) that no perceptible motion is ever dropped.
            if (!q.equals(lastSentQ, 1.0E-5f)) {
                lastSentQ.set(q);
                SimplePlanesClientNetworking.sendRotation(getQ());
            }
        } else {
            ServerPlayer player = getPlayer() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
            if (player != null) {
                player.connection.resetFlyingTicks();
            }
        }
        if (damageTimeout > 0) {
            --damageTimeout;
        }
        if (getDamageTaken() > 0.0F) {
            setDamageTaken(getDamageTaken() - 1.0F);
        }
        if (!level().isClientSide() && getHealth() > getMaxHealth() & goldenHeartsTimeout > (getOnGround() ? 300 : 100)) {
            setHealth(getHealth() - 1);
            goldenHeartsTimeout = 0;
        }
        if (goldenHeartsTimeout < 1000 && isPowered()) {
            goldenHeartsTimeout++;
        }

        tickLerp();
    }

    /**
     * Client-only hook, overridden nowhere on the server side. Kept as a separate method so the
     * client sound class ({@code xyz.przemyk.simpleplanes.client.PlaneSound}) is never resolved on
     * a dedicated server.
     */
    private void playEngineSound() {
        xyz.przemyk.simpleplanes.client.PlaneSound.tryToPlay(this);
    }

    protected float getMoveForward(Player player) {
        return player.zza;
    }

    /** Lazily allocated: upgrades are removed rarely, but tickUpgrades() runs every tick for every plane. */
    private List<Identifier> upgradesToRemove;

    public void tickUpgrades() {
        upgrades.forEach((rl, upgrade) -> {
            upgrade.tick();
            if (upgrade.removed) {
                if (upgradesToRemove == null) {
                    upgradesToRemove = new ArrayList<>();
                }
                upgradesToRemove.add(rl);
            }
        });

        if (upgradesToRemove != null) {
            for (Identifier name : upgradesToRemove) {
                upgrades.remove(name);
            }
            upgradesToRemove = null;
        }

        if (!level().isClientSide()) {
            if (tickCount % networkUpdateInterval == 0) {
                upgrades.forEach((rl, upgrade) -> {
                    if (upgrade.updateClient) {
                        SimplePlanesNetworking.sendToPlayersTrackingEntity(this,
                            UpdateUpgradePacket.create(false, rl, this));
                        upgrade.updateClient = false;
                    }
                });
            }
        }
    }

    public int getFuelCost() {
        return SimplePlanesConfig.PLANE_FUEL_COST.get();
    }

    protected TempMotionVars getMotionVars() {
        motionVars.reset();
        motionVars.maxPushSpeed = getMaxSpeed() * 10;
        return motionVars;
    }

    protected void tickDeltaRotation(Quaternionf q) {
        EulerAngles angles = toEulerAngles(q, deltaRotationAngles);
        setXRot((float) angles.pitch);
        setYRot((float) angles.yaw);
        rotationRoll = (float) angles.roll;

        float d = (float) wrapSubtractDegrees(yRotO, getYRot());
        if (rotationRoll >= 90 && prevRotationRoll <= 90) {
            d = 0;
        }
        int diff = 3;

        deltaRotationTicks = Math.min(10, Math.max((int) Math.abs(deltaRotationLeft) * 5, deltaRotationTicks));
        deltaRotationLeft *= 0.7F;
        deltaRotationLeft += d;
        deltaRotationLeft = wrapDegrees(deltaRotationLeft);
        deltaRotation = Math.min(Math.abs(deltaRotationLeft), diff) * Math.signum(deltaRotationLeft);
        deltaRotationLeft -= deltaRotation;
        if (!(deltaRotation > 0)) {
            deltaRotationTicks--;
        }
    }

    protected float getRotationSpeedMultiplier() {
        return 1.0f;
    }

    protected float pitchSpeed = 0;

    protected void tickPitch(TempMotionVars tempMotionVars) {
        float pitch;
        if (getHealth() <= 0) {
            pitch = 10.0f;
        } else {
            if (getPitchUp() > 0) {
                pitchSpeed += 0.5f * getRotationSpeedMultiplier();
            } else if (getPitchUp() < 0) {
                pitchSpeed -= 0.5f * getRotationSpeedMultiplier();
            } else {
                if (pitchSpeed < 0) {
                    pitchSpeed += 0.5f * getRotationSpeedMultiplier();
                } else if (pitchSpeed > 0) {
                    pitchSpeed -= 0.5f * getRotationSpeedMultiplier();
                }
            }
            // Airspeed-limited elevator authority: see getPitchAuthority(). Above the take-off speed
            // this factor is 1 and the clamp is identical to the original.
            float maxPitchSpeed = 5.0f * getRotationSpeedMultiplier() * getPitchAuthority(tempMotionVars);
            pitchSpeed = Mth.clamp(pitchSpeed, -maxPitchSpeed, maxPitchSpeed);
            pitch = pitchSpeed;
        }
        setXRot(getXRot() + pitch);
    }

    protected float yawSpeed = 0;

    protected void tickYaw() {
        float yaw;
        if (getHealth() <= 0) {
            yaw = 10.0f;
        } else {
            if (getYawRight() > 0) {
                yawSpeed += 0.5f * getRotationSpeedMultiplier();
            } else if (getYawRight() < 0) {
                yawSpeed -= 0.5f * getRotationSpeedMultiplier();
            } else {
                if (yawSpeed < 0) {
                    yawSpeed += 0.5f * getRotationSpeedMultiplier();
                } else if (yawSpeed > 0) {
                    yawSpeed -= 0.5f * getRotationSpeedMultiplier();
                }
            }
            yawSpeed = Mth.clamp(yawSpeed, -2.5f * getRotationSpeedMultiplier(), 2.5f * getRotationSpeedMultiplier());
            yaw = yawSpeed;
        }
        setYRot(getYRot() + yaw);
    }

    protected float rollSpeed = 0;

    // Tick roll if in the air, yaw if on ground
    protected void tickRoll(TempMotionVars tempMotionVars) {
        if (getHealth() <= 0) {
            rotationRoll += getId() % 2 == 0 ? 10.0f : -10.0f;
            return;
        }

        double turn = 0;

        if (getOnGround() || isOnWater()) {
            turn = tempMotionVars.moveStrafing > 0 ? 3 : tempMotionVars.moveStrafing == 0 ? 0 : -3;
            // Nose-wheel / rudder authority scales with ground speed. A parked plane used to be able
            // to pirouette on the spot at 3 deg/tick (60 deg/s) with no airflow and no wheel motion,
            // which also made the take-off run wander. Full authority is restored at take-off speed,
            // so nothing changes for a plane that is actually rolling fast.
            if (turn != 0 && tempMotionVars.takeOffSpeed > 0) {
                turn *= Mth.clamp(getDeltaMovement().length() / tempMotionVars.takeOffSpeed,
                    tempMotionVars.minGroundSteering, 1.0);
            }
            rotationRoll = lerpAngle(0.1f, rotationRoll, 0);

        } else {
            if (tempMotionVars.moveStrafing > 0.0f) {
                rollSpeed += 0.5f;
            } else if (tempMotionVars.moveStrafing < 0.0f) {
                rollSpeed -= 0.5f;
            } else {
                if (rollSpeed < 0) {
                    rollSpeed += 0.5f;
                } else if (rollSpeed > 0) {
                    rollSpeed -= 0.5f;
                }
            }

            rollSpeed = Mth.clamp(rollSpeed, -5.0f, 5.0f);
            rotationRoll += rollSpeed;
        }

        setYRot((float) (getYRot() - turn));
    }

    protected void tickMotion(TempMotionVars tempMotionVars) {
        Vec3 motion;
        if (!isPowered()) {
            tempMotionVars.push = 0;
        }
        motion = getDeltaMovement();
        double speed = motion.length();
        double brakesMul = getThrottle() == 0 ? 5.0 : 1.0;
        speed -= (speed * speed * tempMotionVars.dragQuad + speed * tempMotionVars.dragMul + tempMotionVars.drag) * brakesMul;
        speed = Math.max(speed, 0);
        if (speed > tempMotionVars.maxSpeed) {
            speed = Mth.lerp(0.2, speed, tempMotionVars.maxSpeed);
        }

        if (speed == 0) {
            motion = Vec3.ZERO;
        }
        if (motion.length() > 0) {
            motion = motion.scale(speed / motion.length());
        }

        Vec3 pushVec = new Vec3(getTickPush(tempMotionVars));
        if (pushVec.length() != 0 && motion.length() > 0.1) {
            double dot = normalizedDotProduct(pushVec, motion);
            pushVec = pushVec.scale(Mth.clamp(1 - dot * speed / (tempMotionVars.maxPushSpeed * (tempMotionVars.push + 0.05)), 0, 2));
        }

        motion = motion.add(pushVec);

        motion = motion.add(0, tempMotionVars.gravity, 0);

        setDeltaMovement(motion);
    }

    protected Vector3f getTickPush(TempMotionVars tempMotionVars) {
        // transformPosPhysics(), not transformPos(): the thrust must point where the nose actually
        // is, and transformPos() reads a quaternion that is never refreshed for a plane with nobody
        // aboard. See transformPosPhysics for the measurements.
        //
        // transformPosPhysics() mutates and returns its argument, so the scratch can be reused; the
        // result is immediately copied into a Vec3 by tickMotion() and never stored.
        return transformPosPhysics(pushScratch.set(0, 0, tempMotionVars.push));
    }

    protected boolean tickOnGround(TempMotionVars tempMotionVars) {
        if (getDeltaMovement().lengthSqr() < 0.01 && getOnGround()) {
            notMovingTime += 1;
        } else {
            notMovingTime = 0;
        }
        if (notMovingTime > 200 && getHealth() < getMaxHealth() && getPlayer() != null) {
            setHealth(getHealth() + 1);
            notMovingTime = 100;
        }

        boolean speedingUp = true;
        refreshGroundContact();
        float pitch = getGroundPitch();
        if ((isPowered() && getPitchUp() > 0) || isOnWater()) {
            pitch = 0;
        } else if (getDeltaMovement().length() > tempMotionVars.takeOffSpeed) {
            pitch /= 2;
        }
        setXRot(lerpAngle(0.1f, getXRot(), pitch));

        // Static rolling resistance while breaking away from a stop. Deliberately left exactly as
        // upstream wrote it: an earlier revision of this audit claimed the flat /5 made the small
        // plane unable to ever reach take-off speed, but that was an arithmetic error (the push was
        // divided by 5 twice by hand). Simulating the real tick shows the ground roll is fine —
        // at throttle 5 the plane reaches 0.3 b/t in 38 ticks (1.9 s), throttle 4 in 56, throttle 3
        // in 126. Only throttle 1 and 2 stall out below the 0.1 b/t threshold, which is defensible
        // as "idle taxi". See PHYSICS-AUDIT.md, issue B2, for the corrected numbers.
        if (degreesDifferenceAbs(getXRot(), 0) > 1 && getDeltaMovement().length() < 0.1) {
            tempMotionVars.push /= 5; //runs while the plane is taking off
        }
        if (getDeltaMovement().length() < tempMotionVars.takeOffSpeed) {
            //                rotationPitch = lerpAngle(0.2f, rotationPitch, pitch);
            speedingUp = false;
            //                push = 0;
        }
        if (getPitchUp() < 0) {
            tempMotionVars.push = -tempMotionVars.groundPush;
        } else if (getPitchUp() > 0 && tempMotionVars.push < tempMotionVars.groundPush) {
            tempMotionVars.push = tempMotionVars.groundPush;
        }
        if (!isPowered()) {
            tempMotionVars.push = 0;
        }
        // Rolling resistance only applies while something is actually being rolled/floated on.
        // getOnGround() stays true for up to four ticks after the wheels leave the runway
        // (the onGroundTicks coyote timer), and applying the full 48x ground drag during that window
        // put a deceleration spike right at the moment of lift-off — the jolt on the ground -> air
        // transition. It also sampled the friction of whatever air block happened to be below.
        if (onGround() || isOnWater()) {
            // Mth.floor, not (int): a truncating cast rounds towards zero and samples the wrong block at
            // negative coordinates (x = -0.5 would give 0 instead of -1).
            BlockPos pos = new BlockPos(Mth.floor(getX()), Mth.floor(getY() - 1.0D), Mth.floor(getZ()));
            // Block.getFriction() is what vanilla vehicles use too (AbstractBoat#getGroundFriction).
            // NeoForge's per-BlockState BlockState#getFriction(level, pos, entity) has no equivalent
            // in 26.2, so modded per-state friction is lost; vanilla blocks are unaffected.
            float f = level().getBlockState(pos).getBlock().getFriction();
            tempMotionVars.dragMul *= 20 * (3 - f);
        }
        return speedingUp;
    }

    protected float getGroundPitch() {
        return 5;
    }

    /**
     * The ground/air hysteresis counter behind {@link #getOnGround()}. Extracted verbatim from
     * {@code tickOnGround} so a subclass with its own ground handling — {@link HelicopterEntity} —
     * can keep the counter running without inheriting the fixed-wing ground roll along with it.
     * Behaviour is unchanged; see PHYSICS-AUDIT.md issue N4 for the wart it carries.
     */
    protected void refreshGroundContact() {
        if (onGroundTicks < 0) {
            onGroundTicks = 5;
        } else {
            onGroundTicks--;
        }
    }

    /**
     * Normalised wing lift, in [0, 1], as a function of airspeed.
     *
     * <p>Real lift is {@code 0.5 * rho * v^2 * S * Cl}, i.e. quadratic in airspeed with a hard floor
     * at the stall speed. The original model used {@code min(speed * 10, maxLift)}, which saturated
     * at {@code speed = 0.2} — a third below the 0.3 b/t take-off speed — so the wings were already
     * at full authority long before the plane was supposed to be able to fly. That is what let a
     * plane "take off at zero speed": pull the nose up at 0.2 b/t and the velocity vector was
     * dragged along with full lift behind it.
     *
     * <p>Now: zero below {@code takeOffSpeed * stallSpeedFactor} (0.165 b/t), rising with v^2, and
     * saturating at {@code takeOffSpeed * liftSaturationFactor} (0.39 b/t). Cruise flight, which
     * happens well above 0.4 b/t, is numerically unchanged; only the region around and below the
     * take-off speed behaves differently, which is exactly the region this is meant to fix.
     */
    protected double getLiftRatio(TempMotionVars tempMotionVars, double speed) {
        double stall = tempMotionVars.takeOffSpeed * tempMotionVars.stallSpeedFactor;
        if (speed <= stall) {
            return 0;
        }
        double saturation = tempMotionVars.takeOffSpeed * tempMotionVars.liftSaturationFactor;
        double span = saturation * saturation - stall * stall;
        if (span <= 1.0E-9) {
            return 1;
        }
        return Math.min((speed * speed - stall * stall) / span, 1.0);
    }

    /**
     * Elevator effectiveness, in {@code [minPitchAuthority, 1]}. Control surfaces work on airflow,
     * so a plane that has not reached its take-off speed cannot rotate at the full 5 deg/tick — the
     * old model could, which is why the nose used to snap skyward the instant the ground roll
     * reached 0.3 b/t. At and above the take-off speed this returns 1 and nothing changes.
     */
    protected float getPitchAuthority(TempMotionVars tempMotionVars) {
        if (tempMotionVars.takeOffSpeed <= 0) {
            return 1.0f;
        }
        return Mth.clamp((float) (getDeltaMovement().length() / tempMotionVars.takeOffSpeed),
            tempMotionVars.minPitchAuthority, 1.0f);
    }

    /**
     * Vanilla {@code Entity.maxUpStep()} is 0 for everything that is not a {@code LivingEntity}, and
     * {@code Entity.collide()} only considers its step-up branch when {@code maxUpStep() > 0}. A
     * plane rolling for take-off was therefore stopped dead by a slab, a dirt path edge or a
     * farmland block — and {@code horizontalCollision} then fires the crash check. Buying half a
     * block of step height is what the {@code setOnGround(true)} call in front of {@code move()}
     * has always been trying to do; without this override that call does nothing at all.
     *
     * <p>Deliberately restricted to taxi speeds (horizontal speed below 0.5 b/t, i.e. below the
     * whole ground-roll range) so that flying into terrain still collides — and still crashes —
     * exactly as before.
     */
    @Override
    public float maxUpStep() {
        return getDeltaMovement().horizontalDistanceSqr() < 0.25 ? 0.6F : 0.0F;
    }

    protected Quaternionf tickRotateMotion(TempMotionVars tempMotionVars, Quaternionf q, Vec3 motion) {
        float yaw = MathUtil.getYaw(motion);
        float pitch = MathUtil.getPitch(motion);
        if (degreesDifferenceAbs(yaw, getYRot()) > 5 && (getOnGround() || isOnWater())) {
            setDeltaMovement(motion.scale(0.98));
        }

        float d = (float) degreesDifferenceAbs(pitch, getXRot());
        if (d > 180) {
            d = d - 180;
        }
        //            d/=3600;
        d /= 60;
        d = Math.min(1, d);
        d *= d;
        d = 1 - d;
        //            speed = getMotion().length()*(d);
        double speed = getDeltaMovement().length();
        double lift = tempMotionVars.maxLift * getLiftRatio(tempMotionVars, speed) * d;
        if (getHealth() <= 0) {
            lift = 0;
        }

        setDeltaMovement(rotationToVector(lerpAngle180(0.1f, yaw, getYRot()),
                lerpAngle180(tempMotionVars.pitchToMotion * d, pitch, getXRot()) + lift,
                speed));
        if (!getOnGround() && !isOnWater() && motion.length() > 0.1) {

            if (degreesDifferenceAbs(pitch, getXRot()) > 90) {
                pitch = wrapDegrees(pitch + 180);
            }
            if (Math.abs(getXRot()) < 85) {

                yaw = MathUtil.getYaw(getDeltaMovement());
                if (degreesDifferenceAbs(yaw, getYRot()) > 90) {
                    yaw = yaw - 180;
                }
                Quaternionf q1 = toQuaternionf(yaw, pitch, rotationRoll);
                q = lerpQ(tempMotionVars.motionToRotation, q, q1);
            }

        }
        return q;
    }

    public Vector3f transformPos(Vector3f relPos) {
        // toEulerAngles() only reads its argument, so the scratch buffer never escapes. Neither the
        // angles nor the quaternion outlive this call, so both come from per-entity scratch.
        EulerAngles angles = toEulerAngles(getQ_Client(transformQScratch), transformAngles);
        relPos.rotate(toQuaternionf(-angles.yaw, angles.pitch, -angles.roll, transformRotScratch));
        return relPos;
    }

    /**
     * Body-frame to world-frame for the <em>physics</em>, as opposed to for rendering and rider
     * placement.
     *
     * <p>{@link #transformPos(Vector3f)} rotates by {@code Q_Client}, and {@code Q_Client} is a
     * client-side quantity: on the server the only thing that ever writes it is
     * {@link xyz.przemyk.simpleplanes.network.RotationPacket}, sent by the player flying the plane.
     * A plane with nobody aboard therefore keeps whatever {@code Q_Client} it was created with, for
     * its entire life, while {@code Q} — set from the freshly integrated attitude at the end of
     * every {@link #tick()} — tracks reality.
     *
     * <p>That mattered in exactly one place and mattered enormously there: {@link #getTickPush}
     * builds the engine thrust vector by rotating {@code (0, 0, push)} out of the body frame. An
     * unmanned aircraft was therefore thrusting in the direction it was <em>spawned</em> facing, for
     * ever, no matter where the nose was actually pointing. Straight-line flight looked perfect — a
     * strike run launched at the target accelerated 2.00 to 3.14 blocks/tick without a wobble — and
     * anything involving a turn quietly fell apart: after a 180 the engine was pushing backwards.
     * Measured on a 200-block out-and-back, the aircraft came out of the turnback at 0.36
     * blocks/tick and stayed pinned there at full throttle, descending, until it reached the ground.
     * That single frozen quaternion is the "spawned aircraft gradually loses speed" report, the
     * turnback stall and the failed landing descent, all three.
     *
     * <p>A plane with a rider is untouched: its {@code Q_Client} is refreshed every tick from the
     * client that is authoritative for it, so the two quaternions agree and the branch below picks
     * the same one it always did.
     *
     * <p><b>The test is {@code getPlayer()}, not {@code getControllingPassenger()}.</b> The question
     * this branch is really asking is "is there somebody whose client is authoritative and sending
     * {@code RotationPacket}s", and only a player is ever that. {@link #getControllingPassenger()}
     * returns the first passenger if it is any {@link LivingEntity}, so a <em>mob</em> aboard used to
     * select {@code Q_Client} — and a mob is not a pilot and sends nothing, so the quaternion stayed
     * frozen at the spawn orientation and the frozen-thrust bug came straight back. That is not a
     * hypothetical: {@link LargePlaneEntity} and {@link CargoPlaneEntity} deliberately mount any
     * nearby non-player {@code LivingEntity} in their {@code tick()}, so a plane parked near a cow
     * acquires a passenger by itself. Measured on a 200-block out-and-back with a pig aboard, before
     * this fix: the flight director commanded heading 236 to bring the aircraft home, the nose
     * obediently read 236, and the aircraft flew east-north-east instead — the range to the target
     * grew monotonically 380, 575, 826, 1060, 1287 blocks and it never came back. With the fix the
     * same flight turns and closes.
     */
    public Vector3f transformPosPhysics(Vector3f relPos) {
        Quaternionf rotation = getPlayer() == null
            ? getQ(transformQScratch)
            : getQ_Client(transformQScratch);
        EulerAngles angles = toEulerAngles(rotation, transformAngles);
        relPos.rotate(toQuaternionf(-angles.yaw, angles.pitch, -angles.roll, transformRotScratch));
        return relPos;
    }

    /**
     * autopilot: while the flight director is flying, nobody aboard is the pilot.
     *
     * <p>This one seam is what stops a riding player from fighting the autopilot, because vanilla
     * hangs everything that matters off the controlling passenger:
     * {@code Entity#isClientAuthoritative} and {@code #isLocalClientAuthoritative} both derive from
     * it, so the server stays authoritative and the rider's client stops sending
     * {@code ServerboundMoveVehiclePacket}; {@code ServerGamePacketListenerImpl#handleMoveVehicle}
     * applies a client's vehicle movement only for the vehicle's controlling passenger, so a stray
     * packet is ignored rather than overwriting the server's position; and every control packet in
     * this mod ({@code RotationPacket}, {@code PitchPacket}, {@code YawPacket},
     * {@code ChangeThrottlePacket}) is gated on the same test, so the rider's stick inputs stop
     * arriving. The tick's control-input branch then falls through to the autopilot arm, and
     * {@link #transformPosPhysics} takes the server's quaternion instead of the client's, so thrust
     * points where the flight director aimed it.
     *
     * <p>Why it matters: with a rider aboard the aircraft was client-authoritative, so the altitude
     * loop read {@code position.y} from the client while its only damping term — the flight path
     * angle, taken from {@code getDeltaMovement().y} — came from the server's own discarded
     * integration. Decorrelated, the damping vanished and the loop became a pure proportional law on
     * altitude, saturating its climb/sink demand and oscillating without ever settling.
     *
     * <p>Vanilla's floating kick is guarded by the same test, so an autopiloted aircraft can no
     * longer get its rider disconnected for "flying" either.
     */
    @Nullable
    public LivingEntity getControllingPassenger() {
        if (isAutopilotFlying()) {
            return null;
        }
        if (getFirstPassenger() instanceof LivingEntity livingEntity) {
            return livingEntity;
        }

        return null;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        entityData.set(MAX_SPEED, input.getFloatOr("max_speed", getMaxSpeed()));

        int maxHealth = input.getIntOr("max_health", getMaxHealth());
        if (maxHealth <= 0) {
            maxHealth = 20;
        }
        entityData.set(MAX_HEALTH, maxHealth);

        entityData.set(HEALTH, input.getIntOr("health", getHealth()));

        input.getString("material").ifPresent(this::setMaterial);

        deserializeUpgrades(input);

        // autopilot: restore an in-progress route so a flight survives a restart.
        PlaneAutopilot.load(this, input);

        setQ(MathUtil.toQuaternionf(getYRot(), getXRot(), 0));
    }

    /**
     * Public bridge used by {@code items/PlaneItem}: {@code readAdditionalSaveData} is protected in
     * 26.2 and takes a {@link ValueInput}, but the item component still stores a raw CompoundTag.
     */
    public void loadFromItemTag(CompoundTag entityTag) {
        readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, registryAccess(), entityTag));
    }

    /**
     * The "upgrades" tag is a compound keyed by upgrade id. {@link ValueInput} cannot enumerate
     * keys, so the raw CompoundTag is read back through {@code CompoundTag.CODEC} — this also keeps
     * the on-disk/item-component format byte-identical with the NeoForge build.
     */
    private void deserializeUpgrades(ValueInput input) {
        CompoundTag upgradesTag = input.read("upgrades", CompoundTag.CODEC).orElse(null);
        if (upgradesTag == null) {
            return;
        }
        for (String key : upgradesTag.keySet()) {
            Identifier identifier = Identifier.tryParse(key);
            if (identifier == null) {
                continue;
            }
            UpgradeType upgradeType = SimplePlanesRegistries.UPGRADE_TYPE.getValue(identifier);
            if (upgradeType != null) {
                Upgrade upgrade = upgradeType.instanceSupplier.apply(this);
                upgrade.load(TagValueInput.create(ProblemReporter.DISCARDING, registryAccess(), upgradesTag.getCompoundOrEmpty(key)));
                upgrades.put(identifier, upgrade);
                if (upgradeType.isEngine) {
                    engineUpgrade = (EngineUpgrade) upgrade;
                }
            }
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putInt("health", entityData.get(HEALTH));
        output.putInt("max_health", entityData.get(MAX_HEALTH));
        output.putFloat("max_speed", entityData.get(MAX_SPEED));
        output.putString("material", entityData.get(MATERIAL));
        writeUpgrades(output);

        // autopilot: persist an in-progress route (strike flights deliberately write nothing).
        if (autopilot != null) {
            autopilot.save(output);
        }
    }

    // autopilot: releasing the runway reservation and the traffic slot when the aircraft goes away,
    // so a destroyed plane never keeps a runway blocked or an autopilot slot allocated.
    //
    // UNLOADED_TO_CHUNK is not the aircraft going away: it is the only removal reason that saves
    // the entity, and the flight plan is written to that save (addAdditionalSaveData) before the
    // removal runs, so the same flight comes back when the chunk does. Reporting an outcome there
    // told the owner the plane was lost while it was still en route, and released a runway
    // reservation the flight would want again a moment later.
    @Override
    public void remove(RemovalReason reason) {
        if (reason != RemovalReason.UNLOADED_TO_CHUNK && autopilot != null && autopilot.isActive()) {
            autopilot.reportOutcome(this);
            autopilot.stop(this);
        }
        super.remove(reason);
    }

    private void writeUpgrades(ValueOutput output) {
        ValueOutput upgradesOutput = output.child("upgrades");
        for (Upgrade upgrade : upgrades.values()) {
            upgrade.save(upgradesOutput.child(SimplePlanesRegistries.UPGRADE_TYPE.getKey(upgrade.getType()).toString()));
        }
    }

    @Override
    protected boolean canRide(Entity entityIn) {
        return true;
    }

    @Override
    public boolean dismountsUnderwater() {
        return !upgrades.containsKey(SimplePlanesRegistries.UPGRADE_TYPE.getKey(SimplePlanesUpgrades.FLOATY_BEDDING.get()));
    }

    @Override
    public boolean canBeCollidedWith(@Nullable Entity other) {
        return true;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (MATERIAL.equals(key) && level().isClientSide()) {
            Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(entityData.get(MATERIAL)));
            planksMaterial = block == null ? Blocks.OAK_PLANKS : block;
        } else if (Q.equals(key) && level().isClientSide() && !isLocalInstanceAuthoritative()) {
            if (firstTick) {
                lerpStepsQ = 0;
                setQ_Client(getQ());
                setQ_prev(getQ());
            } else {
                lerpStepsQ = 10;
            }
        }
    }

//    @Override
    public float getPassengersRidingOffset() {
        return 0.5f;
    }

    public static final TagKey<Block> FIREPROOF_MATERIALS_TAG = TagKey.create(Registries.BLOCK,
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "fireproof_materials"));

    // 26.2: Entity no longer declares isInvulnerableTo(DamageSource); the shared part is
    // isInvulnerableToBase(DamageSource) (final). This stays a plain helper used by hurtServer.
    public boolean isInvulnerableTo(DamageSource source) {
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            return false;
        }
        if (source.is(DamageTypeTags.IS_FIRE) && planksMaterial.builtInRegistryHolder().is(FIREPROOF_MATERIALS_TAG)) {
            return true;
        }
        if (source.getDirectEntity() != null && source.getDirectEntity().isPassengerOfSameVehicle(this)) {
            return true;
        }
        return isInvulnerableToBase(source);
    }

    @Override
    public boolean fireImmune() {
        return planksMaterial.builtInRegistryHolder().is(FIREPROOF_MATERIALS_TAG);
    }

    @Override
    protected void checkFallDamage(double y, boolean onGroundIn, BlockState state, BlockPos pos) {
        if ((onGroundIn || isOnWater())) {
            // PlaneCollisions.upY(), not transformPos(): transformPos reads Q_Client, which on the
            // server is only refreshed by RotationPacket and is stale for an unmanned plane.
            if (PlaneCollisions.upY(this) < Math.cos(Math.toRadians(getLandingAngle()))) {
                state.getBlock().fallOn(level(), state, pos, this, getDeltaMovement().length() * 5);
            }
            fallDistance = 0.0F;
        }
    }

    protected int getLandingAngle() {
        return 30;
    }

    @Override
    public boolean causeFallDamage(double fallDistance, float damageMultiplier, DamageSource p_146830_) {
        return PlaneCollisions.causeFallDamage(this, fallDistance, damageMultiplier);
    }

    public void crash(float damage) {
        if (level() instanceof ServerLevel serverLevel && isAlive()) {
            explode();
            kill(serverLevel);

            if (serverLevel.getGameRules().get(GameRules.ENTITY_DROPS)) {
                dropItem(serverLevel);
            }
        }
    }

    public boolean isCreative() {
        return getControllingPassenger() instanceof Player player && player.isCreative();
    }

    public boolean getOnGround() {
        return onGround() || onGroundTicks > 1;
    }

    private int waterCacheTick = -1;
    private int waterCacheX;
    private int waterCacheY;
    private int waterCacheZ;
    private boolean waterCacheValue;

    /**
     * A single {@code tick()} used to call this up to five times (the ground/air test, tickRoll,
     * twice in tickRotateMotion, plus checkFallDamage during move()), each one allocating a BlockPos
     * and doing a full chunk lookup for the same block. Memoised per tick <em>and</em> per block
     * position, so a moving plane still re-samples the moment it crosses a block boundary and a
     * stationary one still notices water being placed under it within a tick.
     */
    public boolean isOnWater() {
        int x = Mth.floor(getX());
        int y = Mth.floor(getY() + 0.4);
        int z = Mth.floor(getZ());
        if (waterCacheTick != tickCount || waterCacheX != x || waterCacheY != y || waterCacheZ != z) {
            waterCacheTick = tickCount;
            waterCacheX = x;
            waterCacheY = y;
            waterCacheZ = z;
            waterCacheValue = level().getBlockState(new BlockPos(x, y, z)).getFluidState().is(FluidTags.WATER);
        }
        return waterCacheValue;
    }

    public boolean canAddUpgrade(UpgradeType upgradeType) {
        if (upgradeType.isEngine && engineUpgrade != null) {
            return false;
        }
        return !upgrades.containsKey(SimplePlanesRegistries.UPGRADE_TYPE.getKey(upgradeType));
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction moveFunction) {
        positionRiderGeneric(passenger);

        int index = getPassengers().indexOf(passenger);
        if (index == 0) {
            Vector3f pos = transformPos(new Vector3f(0, (float) (getPassengersRidingOffset()), 0));
            moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
        } else if (index == 1) {
            Vector3f pos = transformPos(new Vector3f(-1, (float) (getPassengersRidingOffset()), -1.3f));
            moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
        } else if (index == 2) {
            Vector3f pos = transformPos(new Vector3f(1, (float) (getPassengersRidingOffset()), -1.3f));
            moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
        }
    }

    protected void positionRiderGeneric(Entity passenger) {
        boolean local = (passenger instanceof Player player) && player.isLocalPlayer();

        if (hasPassenger(passenger) && !local) {
            applyYawToEntity(passenger);
        }
    }

    public void applyYawToEntity(Entity entityToUpdate) {
        entityToUpdate.setYHeadRot(entityToUpdate.getYHeadRot() + deltaRotation);

        entityToUpdate.yRotO += deltaRotation;

        entityToUpdate.setYBodyRot(getYRot());

        entityToUpdate.setYHeadRot(entityToUpdate.getYRot());
    }

    //on dismount
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity livingEntity) {
        if (upgrades.containsKey(SimplePlanesRegistries.UPGRADE_TYPE.getKey(SimplePlanesUpgrades.FOLDING.get()))) {
            if (livingEntity instanceof Player player) {

                if (!player.isCreative() && getPassengers().isEmpty() && isAlive() && level() instanceof ServerLevel serverLevel) {
                    ItemStack itemStack = getItemStack();

                    if (!player.addItem(itemStack)) {
                        player.drop(itemStack, false);
                    }
                    kill(serverLevel);
                    return player.position();
                }
            }
        }

        if (getPassengers().isEmpty()) {
            setThrottle(0);
            setPitchUp((byte) 0);
            setYawRight((byte) 0);
        }

        return super.getDismountLocationForPassenger(livingEntity);
    }

    public ItemStack getItemStack() {
        ItemStack itemStack = getItem().getDefaultInstance();
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registryAccess());
        addAdditionalSaveData(output);
        CompoundTag compound = output.buildResult();
        compound.putInt("health", entityData.get(MAX_HEALTH));
        compound.putBoolean("Used", true);
        itemStack.set(SimplePlanesComponents.ENTITY_TAG.get(), compound);
        return itemStack;
    }

    protected Item getItem() {
        return SimplePlanesItems.PLANE_ITEM.get();
    }

    private int lerpStepsQ;

    private void tickLerp() {
        if (isLocalInstanceAuthoritative()) {
            interpolation.cancel();
            lerpStepsQ = 0;
            syncPacketPositionCodec(getX(), getY(), getZ());
            return;
        }

        interpolation.interpolate();

        if (lerpStepsQ > 0) {
            setQ_prev(getQ_Client());
            setQ_Client(lerpQ(1f / lerpStepsQ, getQ_Client(), getQ()));
            --lerpStepsQ;
        } else if (lerpStepsQ == 0) {
            setQ_prev(getQ_Client());
            setQ_Client(getQ());
            --lerpStepsQ;
        }
    }

    @Override
    public void absSnapTo(double x, double y, double z, float yaw, float pitch) {
        double d0 = Mth.clamp(x, -3.0E7D, 3.0E7D);
        double d1 = Mth.clamp(z, -3.0E7D, 3.0E7D);
        xOld = d0;
        yOld = y;
        zOld = d1;
        setPos(d0, y, d1);
        setYRot(yaw % 360.0F);
        setXRot(pitch % 360.0F);

        yRotO = getYRot();
        xRotO = getXRot();
    }

    public Player getPlayer() {
        if (getControllingPassenger() instanceof Player player) {
            return player;
        }
        return null;
    }

    /**
     * Sets the time to count down from since the last time entity was hit.
     */
    public void setTimeSinceHit(int timeSinceHit) {
        entityData.set(TIME_SINCE_HIT, timeSinceHit);
    }

    /**
     * Gets the time since the last hit.
     */
    public int getTimeSinceHit() {
        return entityData.get(TIME_SINCE_HIT);
    }

    /**
     * Sets the damage taken from the last hit.
     */
    public void setDamageTaken(float damageTaken) {
        entityData.set(DAMAGE_TAKEN, damageTaken);
    }

    /**
     * Gets the damage taken from the last hit.
     */
    public float getDamageTaken() {
        return entityData.get(DAMAGE_TAKEN);
    }

    public double getCameraDistanceMultiplayer() {
        return SimplePlanesConfig.PLANE_CAMERA_DISTANCE_MULTIPLIER.get();
    }

    public void writeUpdateUpgradePacket(Identifier upgradeID, RegistryFriendlyByteBuf buffer) {
        upgrades.get(upgradeID).writePacket(buffer);
    }

    public void readUpdateUpgradePacket(Identifier upgradeID, RegistryFriendlyByteBuf buffer, boolean newUpgrade) {
        if (newUpgrade) {
            UpgradeType upgradeType = SimplePlanesRegistries.UPGRADE_TYPE.getValue(upgradeID);
            if (upgradeType == null) {
                return;
            }
            Upgrade upgrade = upgradeType.instanceSupplier.apply(this);
            upgrades.put(upgradeID, upgrade);
            if (upgradeType.isEngine) {
                engineUpgrade = (EngineUpgrade) upgrade;
            }
        }

        Upgrade upgrade = upgrades.get(upgradeID);
        if (upgrade != null) {
            upgrade.readPacket(buffer);
        }
    }

    /**
     * Extra spawn data, sent by {@link PlaneSpawnDataPacket} (NeoForge's IEntityWithComplexSpawn is gone).
     */
    public void writeSpawnData(RegistryFriendlyByteBuf buffer) {
        Collection<Upgrade> upgrades = this.upgrades.values();
        buffer.writeVarInt(upgrades.size());
        for (Upgrade upgrade : upgrades) {
            Identifier upgradeID = SimplePlanesRegistries.UPGRADE_TYPE.getKey(upgrade.getType());
            buffer.writeIdentifier(upgradeID);
            upgrade.writePacket(buffer);
        }
    }

    public void readSpawnData(RegistryFriendlyByteBuf additionalData) {
        int upgradesSize = additionalData.readVarInt();
        for (int i = 0; i < upgradesSize; i++) {
            Identifier upgradeID = additionalData.readIdentifier();
            UpgradeType upgradeType = SimplePlanesRegistries.UPGRADE_TYPE.getValue(upgradeID);
            if (upgradeType == null) {
                return;
            }
            Upgrade upgrade = upgradeType.instanceSupplier.apply(this);
            upgrades.put(upgradeID, upgrade);
            if (upgradeType.isEngine) {
                engineUpgrade = (EngineUpgrade) upgrade;
            }
            upgrade.readPacket(additionalData);
        }
    }

    public void removeUpgrade(Identifier upgradeID) {
        Upgrade upgrade = upgrades.remove(upgradeID);
        if (upgrade != null) {
            upgrade.onRemoved();
            upgrade.remove();

            if (!level().isClientSide()) {
                SimplePlanesNetworking.sendToPlayersTrackingEntity(this, new SUpgradeRemovedPacket(upgradeID, getId()));
            }
        }
    }

    public void changeThrottle(ChangeThrottlePacket.Direction type) {
        int throttle = getThrottle();
        if (type == ChangeThrottlePacket.Direction.UP) {
            if (throttle < MAX_THROTTLE
                ||(upgrades.containsKey(SimplePlanesRegistries.UPGRADE_TYPE.getKey(SimplePlanesUpgrades.BOOSTER.get()))
                && throttle < BoosterUpgrade.MAX_THROTTLE)) {
                setThrottle(throttle + 1);
            }
        } else if (throttle > 0) {
            setThrottle(throttle - 1);
        }
    }

    public int getThrottle() {
        return entityData.get(THROTTLE);
    }

    public void setThrottle(int value) {
        entityData.set(THROTTLE, value);
    }

    public byte getPitchUp() {
        return entityData.get(PITCH_UP);
    }

    public void setPitchUp(byte pitchUp) {
        entityData.set(PITCH_UP, pitchUp);
    }

    public byte getYawRight() {
        return entityData.get(YAW_RIGHT);
    }

    public void setYawRight(byte yawRight) {
        entityData.set(YAW_RIGHT, yawRight);
    }

    public void openContainer(Player player, int containerID) {
        if (containerID == 0) {
            player.openMenu(planeInventoryProvider(this));
        } else {
            int id = 0;
            for (Upgrade upgrade : upgrades.values()) {
                if (upgrade instanceof LargeUpgrade largeUpgrade && largeUpgrade.hasStorage()) {
                    id++;
                    if (containerID == id) {
                        largeUpgrade.openStorageGui(player, id);
                    }
                }
            }
        }
    }

    public void dropPayload() {}

    protected static class TempMotionVars {
        public float moveForward; //TODO: move to HelicopterEntity?
        public double turnThreshold;
        public float moveStrafing;
        double maxSpeed;
        double maxPushSpeed;
        double takeOffSpeed;
        float maxLift;
        /**
         * @deprecated superseded by {@link #stallSpeedFactor} / {@link #liftSaturationFactor}.
         * The old lift law was {@code min(speed * liftFactor, maxLift)}, which saturated at
         * {@code speed = 0.2} — below the take-off speed — so a plane crawling at 0.2 b/t got
         * exactly the same lift as one at cruise. Kept so third-party subclasses still compile.
         */
        @Deprecated
        double liftFactor;
        /**
         * Airspeed at which the wings stop working, as a fraction of {@link #takeOffSpeed}.
         * Below it lift is zero and the plane mushes down instead of flying.
         */
        double stallSpeedFactor;
        /** Airspeed at which lift reaches {@link #maxLift}, as a fraction of {@link #takeOffSpeed}. */
        double liftSaturationFactor;
        /** Floor on elevator authority when the plane is far below its take-off speed. */
        float minPitchAuthority;
        /** Floor on nose-wheel / rudder authority when the plane is standing still. */
        double minGroundSteering;
        double gravity;
        double drag;
        double dragMul;
        double dragQuad;
        float push;
        float groundPush;
        float passiveEnginePush;
        float motionToRotation;
        float pitchToMotion;
        float yawMultiplayer;

        public TempMotionVars() {
            reset();
        }

        public void reset() {
            moveForward = 0;
            turnThreshold = 0;
            moveStrafing = 0;
            maxSpeed = 3;
            takeOffSpeed = 0.3;
            maxLift = 2;
            liftFactor = 10;
            stallSpeedFactor = 0.55;
            liftSaturationFactor = 1.3;
            minPitchAuthority = 0.35f;
            minGroundSteering = 0.2;
            gravity = -0.03;
            drag = 0.001;
            dragMul = 0.0005;
            dragQuad = 0.001;
            push = 0.0f;
            groundPush = 0.01f;
            passiveEnginePush = 0.025f;
            motionToRotation = 0.05f;
            pitchToMotion = 0.2f;
            yawMultiplayer = 0.5f;
        }
    }
}
