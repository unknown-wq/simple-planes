package xyz.przemyk.simpleplanes.entities;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.autopilot.AutopilotMode;
import xyz.przemyk.simpleplanes.autopilot.PlaneAutopilot;
import xyz.przemyk.simpleplanes.misc.MathUtil;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 22-seat mini airliner: heavy, slow to turn, long take-off run, nose clamped on the ground below the
 * tail-strike angle. Numbers: design/DESIGN.md section 4; seats, skin and logos: AIRLINER-MODEL.md.
 * {@link RegionalAirlinerEntity} is the narrow size: everything here is shared, driven by the {@link AirlinerLayout}.
 *
 * <p>Seats are assigned, not implied by the passenger order: each seat holds the id of its rider in synched
 * data, so server and client place every rider in the same seat. A player boards the seat nearest to where
 * he clicked, the captain's seat when he clicks the cockpit or the nose; the captain is the pilot. The nose
 * and tail are clickable through {@link AirlinerPartEntity} hitboxes.
 */
public class AirlinerEntity extends PlaneEntity {

    public static final int LOGO_COUNT = 6;
    public static final EntityDataAccessor<Integer> LOGO = SynchedEntityData.defineId(AirlinerEntity.class, EntityDataSerializers.INT);
    @SuppressWarnings("unchecked")
    private static final EntityDataAccessor<Integer>[] SEATS = new EntityDataAccessor[AirlinerLayout.MAX_SEATS];
    /**
     * Landing gear commanded down; the server decides ({@link #tickGear()}), the client animates towards it
     * ({@link #gearDown(float)}). Visual only. DESIGN.md section 4.7.
     */
    public static final EntityDataAccessor<Boolean> GEAR_DOWN = SynchedEntityData.defineId(AirlinerEntity.class, EntityDataSerializers.BOOLEAN);
    public static final TagKey<Block> METAL_SKIN_TAG = TagKey.create(Registries.BLOCK,
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "airliner_metal_skin"));

    static {
        for (int i = 0; i < SEATS.length; i++) {
            SEATS[i] = SynchedEntityData.defineId(AirlinerEntity.class, EntityDataSerializers.INT);
        }
    }

    private static final String THROTTLE_KEY = "throttle";
    private static final String GEAR_KEY = "GearDown";
    /** Gear: retracts once this high (blocks above the ground) and climbing, or above {@link #GEAR_EXTEND_AGL}. */
    public static final double GEAR_RETRACT_AGL = 4.0;
    /** Gear: extends below this height when descending, or level and slower than {@link #GEAR_SLOW_SPEED}. */
    public static final double GEAR_EXTEND_AGL = 25.0;
    /** Gear: extends below this height when level or descending. */
    public static final double GEAR_LOW_AGL = 8.0;
    /** Vertical speed, b/t, that counts as climbing or descending for the gear. */
    public static final double GEAR_CLIMB = 0.02;
    /** Ground speed, b/t, below which a level airliner under {@link #GEAR_EXTEND_AGL} counts as slow. */
    public static final double GEAR_SLOW_SPEED = 0.85;
    /** Ticks for the gear to travel from up to down or back. */
    public static final int GEAR_TRAVEL_TICKS = 30;
    /** Ticks after a gear change before the automatic rule may reverse it in the air (no flicker at a level-off). */
    public static final int GEAR_HOLD_TICKS = 60;
    private static final String SEATS_KEY = "Seats";
    /** A client's hit location further than this from the eye ray's hit on the hull is used as it is. */
    private static final float RAY_TRUST = 2.0F;

    public static final float MAX_SPEED_DATA = 2.0f;
    /** Keel end touches at 14.5 deg nose-up. */
    public static final float GROUND_PITCH_LIMIT = 12.0f;
    /**
     * Drag multiplier with the throttle closed on the ground (5 elsewhere): about 0.2 g from touchdown, a 30.5 b
     * roll-out from 0.55 b/t and 4 b from taxi speed 0.20, instead of 3.4 b and 0.4 b. DESIGN.md section 4.6.
     */
    public static final double GROUND_BRAKES = 0.6;
    /** Above this ground speed, b/t, nose-down braking is scaled like {@link #GROUND_BRAKES}; see {@link #tickOnGround}. */
    public static final double WHEEL_BRAKE_TAXI_SPEED = 0.2;

    private final AirlinerLayout layout;
    private final AirlinerPartEntity[] parts;
    /** Seats read from the save, by rider, until the rider is back aboard. */
    private final Map<UUID, Integer> savedSeats = new HashMap<>();
    private @Nullable UUID boardingPlayer;
    /** Client: gear position, 1 down, 0 up, this tick and the last. */
    private float gear = 1.0F, gearO = 1.0F;
    private boolean gearSynced;
    /** Server, test aid ({@code /airliner gear}): gear held down or up, null for the automatic rule. Not saved. */
    private @Nullable Boolean gearOverride;
    /** Server: ticks before the automatic rule may move the gear again. */
    private int gearHold;
    private int boardingSeat = -1;

    public AirlinerEntity(EntityType<? extends AirlinerEntity> entityType, Level level) {
        this(entityType, level, AirlinerLayout.WIDE);
    }

    protected AirlinerEntity(EntityType<? extends AirlinerEntity> entityType, Level level, AirlinerLayout layout) {
        super(entityType, level);
        this.layout = layout;
        this.parts = new AirlinerPartEntity[layout.partCount()];
        setMaxSpeed(MAX_SPEED_DATA);
        // Rolled before the spawn packet; a saved or item Logo overrides it in readAdditionalSaveData.
        if (!level.isClientSide()) {
            setLogo(level.getRandom().nextInt(LOGO_COUNT));
        }
    }

    @Override
    protected TempMotionVars getMotionVars() {
        TempMotionVars vars = super.getMotionVars();
        vars.maxSpeed = 2.0;
        vars.takeOffSpeed = 0.60;
        vars.stallSpeedFactor = 0.6;
        vars.liftSaturationFactor = 1.25;
        vars.maxLift = 2.0f;
        vars.dragQuad = 0.0006;
        vars.dragMul = 0.0003;
        vars.drag = 0.0005;
        vars.pitchToMotion = 0.16f;
        vars.yawToMotion = 0.06f;
        vars.motionToRotation = 0.05f;
        return vars;
    }

    @Override
    protected float pushPerNotch() {
        return 0.004f;
    }

    @Override
    protected float maxRollRate() {
        return 2.5f;
    }

    @Override
    protected float groundPitchLimit() {
        return GROUND_PITCH_LIMIT;
    }

    @Override
    protected double groundRollingResistance() {
        return 0.007;
    }

    @Override
    protected double groundLinearDragFactor(float friction) {
        return 5.0;
    }

    @Override
    protected double brakeMultiplier(boolean onGround) {
        return onGround ? GROUND_BRAKES : super.brakeMultiplier(false);
    }

    /**
     * Nose-down on the ground is a reverse push ({@code -groundPush}); rolling faster than
     * {@link #WHEEL_BRAKE_TAXI_SPEED} it is scaled like the idle brakes ({@code brakeMultiplier(true) / 5}), so the
     * autopilot's derotation after touchdown does not stop the roll-out in a few blocks. Taxi and reversing keep
     * the full push.
     */
    @Override
    protected boolean tickOnGround(TempMotionVars vars) {
        boolean speedingUp = super.tickOnGround(vars);
        if (vars.push < 0 && getPitchUp() < 0 && getDeltaMovement().horizontalDistance() > WHEEL_BRAKE_TAXI_SPEED) {
            vars.push *= (float) (brakeMultiplier(true) / 5.0);
        }
        return speedingUp;
    }

    @Override
    protected int getLandingAngle() {
        return 20;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(LOGO, -1);
        builder.define(GEAR_DOWN, true);
        for (EntityDataAccessor<Integer> seat : SEATS) {
            builder.define(seat, -1);
        }
    }

    public int getLogo() {
        return entityData.get(LOGO);
    }

    public void setLogo(int logo) {
        entityData.set(LOGO, logo);
    }

    public AirlinerLayout layout() {
        return layout;
    }

    /** The hitbox entity type, sized to the fuselage. */
    protected EntityType<AirlinerPartEntity> partType() {
        return SimplePlanesEntities.AIRLINER_PART.get();
    }

    public boolean hasMetalSkin() {
        return getMaterial().builtInRegistryHolder().is(METAL_SKIN_TAG);
    }

    @Override
    public void tick() {
        // -1: saved without a logo.
        if (!level().isClientSide() && getLogo() < 0) {
            setLogo(level().getRandom().nextInt(LOGO_COUNT));
        }
        super.tick();
        if (!level().isClientSide() && !isRemoved()) {
            tickParts();
            tickGear();
        } else if (level().isClientSide()) {
            float target = entityData.get(GEAR_DOWN) ? 1.0F : 0.0F;
            gearO = gear;
            if (!gearSynced || tickCount < 5) {
                // spawned or loaded (the server settles the gear on its first tick): no travel
                gear = gearO = target;
                gearSynced = true;
            } else {
                gear = Mth.approach(gear, target, 1.0F / GEAR_TRAVEL_TICKS);
            }
        }
    }

    /**
     * Server: gear down on the ground and in the autopilot's arrival; up once clearly airborne. Visual only, physics
     * and hitboxes do not change.
     */
    private void tickGear() {
        boolean down = entityData.get(GEAR_DOWN);
        if (gearHold > 0) {
            gearHold--;
        }
        if (gearOverride != null) {
            down = gearOverride;
        } else if (getOnGround() || isOnWater()) {
            down = true;
        } else if (gearHold == 0) {
            double agl = getY() - level().getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(getX()), Mth.floor(getZ()));
            double vy = getY() - yo;
            // from the position, not the delta movement: a player-flown airliner moves by packets on the server
            double speed = position().subtract(xo, yo, zo).length();
            boolean arriving = isArriving();
            if (down) {
                if (!arriving && (agl > GEAR_EXTEND_AGL || (agl > GEAR_RETRACT_AGL && vy > GEAR_CLIMB))) {
                    down = false;
                }
            } else if (arriving || (agl < GEAR_EXTEND_AGL && vy < -GEAR_CLIMB)
                    || (agl < GEAR_EXTEND_AGL && vy <= 0.0 && (agl < GEAR_LOW_AGL || speed < GEAR_SLOW_SPEED))) {
                down = true;
            }
        }
        if (down != entityData.get(GEAR_DOWN)) {
            entityData.set(GEAR_DOWN, down);
            gearHold = GEAR_HOLD_TICKS;
        }
    }

    /** The autopilot is on final (from 150 b before the threshold), in the flare, the roll-out or taxiing in. */
    private boolean isArriving() {
        PlaneAutopilot autopilot = getAutopilot();
        if (autopilot == null || !isAutopilotEngaged()) {
            return false;
        }
        AutopilotMode mode = autopilot.getMode();
        return mode == AutopilotMode.FINAL || mode == AutopilotMode.FLARE
            || mode == AutopilotMode.ROLLOUT || mode == AutopilotMode.TAXI_IN;
    }

    /** Test aid: holds the gear down ({@code true}) or up ({@code false}), or back to the automatic rule ({@code null}). */
    public void setGearOverride(@Nullable Boolean override) {
        this.gearOverride = override;
    }

    public @Nullable Boolean getGearOverride() {
        return gearOverride;
    }

    public boolean isGearDown() {
        return entityData.get(GEAR_DOWN);
    }

    /** Client: gear position for rendering, 1 down, 0 up. */
    public float gearDown(float partialTicks) {
        if (!gearSynced) {
            // not ticked on this client yet: show the synced state, not the field's initial value
            return entityData.get(GEAR_DOWN) ? 1.0F : 0.0F;
        }
        return Mth.lerp(partialTicks, gearO, gear);
    }

    private void tickParts() {
        for (int i = 0; i < parts.length; i++) {
            AirlinerPartEntity part = parts[i];
            if (part == null || part.isRemoved() || part.level() != level()) {
                part = partType().create(level(), EntitySpawnReason.EVENT);
                if (part == null) {
                    return;
                }
                part.attach(this, i);
                level().addFreshEntity(part);
                parts[i] = part;
            }
        }
    }

    @Override
    public void onRemoval(RemovalReason reason) {
        super.onRemoval(reason);
        if (!level().isClientSide()) {
            for (AirlinerPartEntity part : parts) {
                if (part != null) {
                    part.discard();
                }
            }
        }
    }

    /** Where hitbox {@code station} stands: on the fuselage axis, rotated with the airliner. */
    public Vec3 partPosition(int station) {
        Vector3f p = new Vector3f(0, 0, layout.partStation(station));
        p = level().isClientSide() ? transformPos(p) : transformPosPhysics(p);
        return position().add(p.x(), p.y(), p.z());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        setLogo(input.getIntOr("Logo", getLogo()));
        setThrottle(Math.max(0, input.getIntOr(THROTTLE_KEY, getThrottle())));
        entityData.set(GEAR_DOWN, input.getBooleanOr(GEAR_KEY, true));
        savedSeats.clear();
        for (ValueInput seat : input.childrenListOrEmpty(SEATS_KEY)) {
            int index = seat.getIntOr("Seat", -1);
            seat.read("UUID", UUIDUtil.CODEC).ifPresent(uuid -> {
                if (index >= 0 && index < layout.count()) {
                    savedSeats.put(uuid, index);
                }
            });
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("Logo", getLogo());
        output.putInt(THROTTLE_KEY, getThrottle());
        output.putBoolean(GEAR_KEY, isGearDown());
        ValueOutput.ValueOutputList seats = output.childrenList(SEATS_KEY);
        for (Entity passenger : getPassengers()) {
            int seat = seatOf(passenger);
            if (seat >= 0) {
                ValueOutput child = seats.addChild();
                child.store("UUID", UUIDUtil.CODEC, passenger.getUUID());
                child.putInt("Seat", seat);
            }
        }
    }

    /** The world save keeps the throttle (an airliner in flight comes back flying), the gear and the seats; the item does not. */
    @Override
    public ItemStack getItemStack() {
        ItemStack itemStack = super.getItemStack();
        CompoundTag compound = itemStack.get(SimplePlanesComponents.ENTITY_TAG.get());
        if (compound != null && (compound.contains(THROTTLE_KEY) || compound.contains(SEATS_KEY) || compound.contains(GEAR_KEY))) {
            CompoundTag parked = compound.copy();
            parked.remove(THROTTLE_KEY);
            parked.remove(SEATS_KEY);
            parked.remove(GEAR_KEY);
            itemStack.set(SimplePlanesComponents.ENTITY_TAG.get(), parked);
        }
        return itemStack;
    }

    // ---- seats ----

    /** Seat of a passenger, 0 (captain) to {@code layout().count() - 1}, or -1. */
    public int seatOf(Entity passenger) {
        int id = passenger.getId();
        for (int i = 0; i < SEATS.length; i++) {
            if (entityData.get(SEATS[i]) == id) {
                return i;
            }
        }
        return -1;
    }

    /** The rider in {@code seat}, or null. */
    public @Nullable Entity occupant(int seat) {
        int id = entityData.get(SEATS[seat]);
        if (id < 0) {
            return null;
        }
        for (Entity passenger : getPassengers()) {
            if (passenger.getId() == id) {
                return passenger;
            }
        }
        return null;
    }

    public boolean isSeatFree(int seat) {
        return occupant(seat) == null;
    }

    /** Players may take any seat; anyone else any seat but the captain's. */
    private static boolean mayTake(Entity passenger, int seat) {
        return mayTake(passenger instanceof Player, seat);
    }

    private static boolean mayTake(boolean player, int seat) {
        return player || seat != AirlinerLayout.PILOT;
    }

    /**
     * The seat a click at entity-frame point ({@code x}, {@code z}) boards: the captain's seat for a player
     * clicking the cockpit or the nose while it is free, otherwise the nearest free seat the passenger may take.
     */
    public int chooseSeat(boolean player, float x, float z) {
        if (player && z >= layout.cockpitZ() && isSeatFree(AirlinerLayout.PILOT)) {
            return AirlinerLayout.PILOT;
        }
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int seat = 0; seat < layout.count(); seat++) {
            if (!mayTake(player, seat) || !isSeatFree(seat)) {
                continue;
            }
            float dx = x - layout.x(seat);
            float dz = z - layout.z(seat);
            float distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = seat;
            }
        }
        return best;
    }

    /**
     * Without a click: a player takes the captain's seat if it is free; then everyone the front-most free cabin
     * seat, and the first officer's last, so it stays free for a player as long as the cabin has room.
     */
    private int defaultSeat(Entity passenger) {
        if (passenger instanceof Player && isSeatFree(AirlinerLayout.PILOT)) {
            return AirlinerLayout.PILOT;
        }
        for (int seat = AirlinerLayout.FIRST_CABIN_SEAT; seat < layout.count(); seat++) {
            if (isSeatFree(seat)) {
                return seat;
            }
        }
        return isSeatFree(AirlinerLayout.FIRST_OFFICER) ? AirlinerLayout.FIRST_OFFICER : -1;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        if (passenger instanceof PlaneEntity) {
            return false;
        }
        for (int seat = 0; seat < layout.count(); seat++) {
            if (mayTake(passenger, seat) && isSeatFree(seat)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        if (level().isClientSide()) {
            return;
        }
        int seat = -1;
        UUID uuid = passenger.getUUID();
        if (uuid.equals(boardingPlayer) && boardingSeat >= 0 && isSeatFree(boardingSeat)) {
            seat = boardingSeat;
        } else {
            Integer saved = savedSeats.remove(uuid);
            if (saved != null && mayTake(passenger, saved) && isSeatFree(saved)) {
                seat = saved;
            }
        }
        if (seat < 0) {
            seat = defaultSeat(passenger);
        }
        if (seat >= 0) {
            entityData.set(SEATS[seat], passenger.getId());
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!level().isClientSide()) {
            int id = passenger.getId();
            for (EntityDataAccessor<Integer> seat : SEATS) {
                if (entityData.get(seat) == id) {
                    entityData.set(seat, -1);
                }
            }
        }
    }

    /** The captain flies; nobody else aboard does, and nobody at all while the autopilot is flying. */
    @Override
    public @Nullable LivingEntity getControllingPassenger() {
        if (isAutopilotFlying()) {
            return null;
        }
        return occupant(AirlinerLayout.PILOT) instanceof Player pilot ? pilot : null;
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction moveFunction) {
        positionRiderGeneric(passenger);
        int seat = seatOf(passenger);
        if (seat >= 0) {
            Vector3f pos = new Vector3f(layout.x(seat), layout.y(seat), layout.z(seat));
            // Server: Q_Client is stale without a pilot aboard.
            pos = level().isClientSide() ? transformPos(pos) : transformPosPhysics(pos);
            moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
        }
    }

    @Override
    public float getPassengersRidingOffset() {
        return layout.cabinY();
    }

    // ---- boarding by click ----

    /** Boards the seat nearest to the click; a new rider is turned to face the nose. */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (level().isClientSide() || player.getVehicle() == this) {
            return super.interact(player, hand, location);
        }
        boardingPlayer = player.getUUID();
        boardingSeat = seatForClick(player, location);
        try {
            InteractionResult result = super.interact(player, hand, location);
            if (player.getVehicle() == this && player instanceof ServerPlayer serverPlayer) {
                serverPlayer.forceSetRotation(getYRot(), false, serverPlayer.getXRot(), false);
            }
            return result;
        } finally {
            boardingPlayer = null;
            boardingSeat = -1;
        }
    }

    /** The seat a click at {@code location} (relative to this entity, as vanilla passes it) boards, or -1. */
    public int seatForClick(Player player, Vec3 location) {
        Vector3f point = clickPoint(player, location);
        return chooseSeat(true, point.x(), point.z());
    }

    /**
     * The clicked point in the entity frame. The client's location is where its pick ray entered a bounding box,
     * up to a block off the skin when the airliner is not axis-aligned, so the player's own eye ray is traced to
     * the hull instead; the location is kept when the two disagree (a stale look, or a scripted click).
     */
    public Vector3f clickPoint(Player player, Vec3 location) {
        Quaternionf toLocal = frameRotation().conjugate();
        Vector3f hit = new Vector3f((float) location.x, (float) location.y, (float) location.z).rotate(toLocal);
        Vec3 eye = player.getEyePosition().subtract(position());
        Vec3 look = player.getViewVector(1.0F);
        Vector3f o = new Vector3f((float) eye.x, (float) eye.y, (float) eye.z).rotate(toLocal);
        Vector3f d = new Vector3f((float) look.x, (float) look.y, (float) look.z).rotate(toLocal);
        float t = rayToHull(o, d);
        if (t >= 0) {
            Vector3f onHull = new Vector3f(d).mul(t).add(o);
            if (onHull.distance(hit) <= RAY_TRUST) {
                return onHull;
            }
        }
        return hit;
    }

    /** Body-to-world rotation of the frame riders and parts are placed in (see {@link #transformPosPhysics}). */
    public Quaternionf frameRotation() {
        Quaternionf q = level().isClientSide() || getPlayer() != null ? getQ_Client() : getQ();
        MathUtil.EulerAngles a = MathUtil.toEulerAngles(q);
        return MathUtil.toQuaternionf(-a.yaw, a.pitch, -a.roll);
    }

    /** Distance along the ray to the hull box, or -1. */
    private float rayToHull(Vector3f o, Vector3f d) {
        float[] min = {-layout.hullHalfWidth(), AirlinerLayout.HULL_Y0, -layout.hullTail()};
        float[] max = {layout.hullHalfWidth(), layout.hullTop(), AirlinerLayout.HULL_NOSE};
        float[] origin = {o.x(), o.y(), o.z()};
        float[] dir = {d.x(), d.y(), d.z()};
        float near = 0, far = Float.MAX_VALUE;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(dir[axis]) < 1.0E-6F) {
                if (origin[axis] < min[axis] || origin[axis] > max[axis]) {
                    return -1;
                }
                continue;
            }
            float t1 = (min[axis] - origin[axis]) / dir[axis];
            float t2 = (max[axis] - origin[axis]) / dir[axis];
            near = Math.max(near, Math.min(t1, t2));
            far = Math.min(far, Math.max(t1, t2));
        }
        return near <= far ? near : -1;
    }

    @Override
    protected float getRotationSpeedMultiplier() {
        return 0.35f;
    }

    @Override
    protected float getGroundPitch() {
        return 0;
    }

    @Override
    protected boolean acceptsUpgrade(UpgradeType type) {
        return type != SimplePlanesUpgrades.SEATS.get()
            && type != SimplePlanesUpgrades.SHOOTER.get();
    }

    @Override
    public double getCameraDistanceMultiplayer() {
        return 1.6;
    }

    @Override
    public int getFuelCost() {
        return 10;
    }

    @Override
    protected Item getItem() {
        return SimplePlanesItems.AIRLINER_ITEM.get();
    }
}
