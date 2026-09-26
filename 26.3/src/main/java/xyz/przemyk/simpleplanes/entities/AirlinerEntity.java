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
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
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
    private static final EntityDataAccessor<Integer>[] SEATS = new EntityDataAccessor[AirlinerSeats.COUNT];
    public static final TagKey<Block> METAL_SKIN_TAG = TagKey.create(Registries.BLOCK,
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "airliner_metal_skin"));

    static {
        for (int i = 0; i < SEATS.length; i++) {
            SEATS[i] = SynchedEntityData.defineId(AirlinerEntity.class, EntityDataSerializers.INT);
        }
    }

    /** Entity z (blocks) of each {@link AirlinerPartEntity}, nose to tail. */
    public static final float[] PART_STATIONS = {4.5F, 1.5F, -1.5F, -4.5F};

    private static final String THROTTLE_KEY = "throttle";
    private static final String SEATS_KEY = "Seats";
    /** The fuselage as a box in the entity frame, blocks: half width, belly, crown, tail end, nose tip. */
    private static final float HULL_X = 1.8125F, HULL_Y0 = 0.8125F, HULL_Y1 = 3.1875F, HULL_Z0 = -5.625F, HULL_Z1 = 6.0625F;
    /** A client's hit location further than this from the eye ray's hit on the hull is used as it is. */
    private static final float RAY_TRUST = 2.0F;

    public static final float MAX_SPEED_DATA = 2.0f;
    /** Keel end touches at 14.5 deg nose-up. */
    public static final float GROUND_PITCH_LIMIT = 12.0f;

    private final AirlinerPartEntity[] parts = new AirlinerPartEntity[PART_STATIONS.length];
    /** Seats read from the save, by rider, until the rider is back aboard. */
    private final Map<UUID, Integer> savedSeats = new HashMap<>();
    private @Nullable UUID boardingPlayer;
    private int boardingSeat = -1;

    public AirlinerEntity(EntityType<? extends AirlinerEntity> entityType, Level level) {
        super(entityType, level);
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
    protected int getLandingAngle() {
        return 20;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(LOGO, -1);
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
        }
    }

    private void tickParts() {
        for (int i = 0; i < parts.length; i++) {
            AirlinerPartEntity part = parts[i];
            if (part == null || part.isRemoved() || part.level() != level()) {
                part = SimplePlanesEntities.AIRLINER_PART.get().create(level(), EntitySpawnReason.EVENT);
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
        Vector3f p = new Vector3f(0, 0, PART_STATIONS[station]);
        p = level().isClientSide() ? transformPos(p) : transformPosPhysics(p);
        return position().add(p.x(), p.y(), p.z());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        setLogo(input.getIntOr("Logo", getLogo()));
        setThrottle(Math.max(0, input.getIntOr(THROTTLE_KEY, getThrottle())));
        savedSeats.clear();
        for (ValueInput seat : input.childrenListOrEmpty(SEATS_KEY)) {
            int index = seat.getIntOr("Seat", -1);
            seat.read("UUID", UUIDUtil.CODEC).ifPresent(uuid -> {
                if (index >= 0 && index < AirlinerSeats.COUNT) {
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

    /** The world save keeps the throttle (an airliner in flight comes back flying) and the seats; the item does not. */
    @Override
    public ItemStack getItemStack() {
        ItemStack itemStack = super.getItemStack();
        CompoundTag compound = itemStack.get(SimplePlanesComponents.ENTITY_TAG.get());
        if (compound != null && (compound.contains(THROTTLE_KEY) || compound.contains(SEATS_KEY))) {
            CompoundTag parked = compound.copy();
            parked.remove(THROTTLE_KEY);
            parked.remove(SEATS_KEY);
            itemStack.set(SimplePlanesComponents.ENTITY_TAG.get(), parked);
        }
        return itemStack;
    }

    // ---- seats ----

    /** Seat of a passenger, 0 (captain) to 21, or -1. */
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

    /** Players may take any seat; anyone else only the cabin. */
    private static boolean mayTake(Entity passenger, int seat) {
        return passenger instanceof Player || !AirlinerSeats.isCockpit(seat);
    }

    /**
     * The seat a click at entity-frame point ({@code x}, {@code z}) boards: the captain's seat for a player
     * clicking the cockpit or the nose while it is free, otherwise the nearest free seat the passenger may take.
     */
    public int chooseSeat(boolean player, float x, float z) {
        if (player && z >= AirlinerSeats.COCKPIT_Z && isSeatFree(AirlinerSeats.PILOT)) {
            return AirlinerSeats.PILOT;
        }
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int seat = 0; seat < AirlinerSeats.COUNT; seat++) {
            if (!(player || !AirlinerSeats.isCockpit(seat)) || !isSeatFree(seat)) {
                continue;
            }
            float dx = x - AirlinerSeats.x(seat);
            float dz = z - AirlinerSeats.z(seat);
            float distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = seat;
            }
        }
        return best;
    }

    /** Without a click: a player takes the captain's seat if it is free, anyone else the front-most free cabin seat. */
    private int defaultSeat(Entity passenger) {
        if (passenger instanceof Player && isSeatFree(AirlinerSeats.PILOT)) {
            return AirlinerSeats.PILOT;
        }
        for (int seat = AirlinerSeats.FIRST_CABIN_SEAT; seat < AirlinerSeats.COUNT; seat++) {
            if (isSeatFree(seat)) {
                return seat;
            }
        }
        return passenger instanceof Player && isSeatFree(AirlinerSeats.FIRST_OFFICER) ? AirlinerSeats.FIRST_OFFICER : -1;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        if (passenger instanceof PlaneEntity) {
            return false;
        }
        for (int seat = 0; seat < AirlinerSeats.COUNT; seat++) {
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
        return occupant(AirlinerSeats.PILOT) instanceof Player pilot ? pilot : null;
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction moveFunction) {
        positionRiderGeneric(passenger);
        int seat = seatOf(passenger);
        if (seat >= 0) {
            Vector3f pos = new Vector3f(AirlinerSeats.x(seat), AirlinerSeats.y(seat), AirlinerSeats.z(seat));
            // Server: Q_Client is stale without a pilot aboard.
            pos = level().isClientSide() ? transformPos(pos) : transformPosPhysics(pos);
            moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
        }
    }

    @Override
    public float getPassengersRidingOffset() {
        return AirlinerSeats.CABIN_Y;
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
    private static float rayToHull(Vector3f o, Vector3f d) {
        float[] min = {-HULL_X, HULL_Y0, HULL_Z0};
        float[] max = {HULL_X, HULL_Y1, HULL_Z1};
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
