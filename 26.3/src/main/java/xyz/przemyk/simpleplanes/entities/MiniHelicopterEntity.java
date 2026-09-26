package xyz.przemyk.simpleplanes.entities;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.joml.Vector3f;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.datapack.PlanePayloadReloadListener;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-seat mini helicopter: the helicopter's flight model with a lighter, twitchier, slower tune and a
 * service ceiling (DESIGN.md section 5b). See MINI-HELI-MODEL.md for the seat and the livery.
 */
public class MiniHelicopterEntity extends HelicopterEntity {

    public static final TagKey<Block> MEDICAL_TAG = TagKey.create(Registries.BLOCK,
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "mini_heli_medical"));

    public static final double COLLECTIVE_PER_NOTCH = 0.015;
    public static final double ROTOR_INFLOW_LIMIT = 1.6;
    public static final double MAX_CYCLIC = 30.0;
    public static final double MAX_CYCLIC_RATE = 3.5;
    public static final float MAX_YAW_RATE = 4.5f;
    public static final float YAW_RAMP = 1.0f;
    public static final double TURN_FROM_BANK = 3.0;
    public static final double H_DRAG_QUAD = 0.025;
    public static final double H_DRAG_LIN = 0.004;
    public static final double H_DRAG_CONST = 0.0003;
    public static final double MAX_SPEED = 1.5;
    public static final double GROUND_FRICTION = 0.30;
    /** Rotor thrust fades linearly from full at {@code CEILING_FADE_START} to {@code CEILING_MIN_FACTOR}. */
    public static final double CEILING_FADE_START = 100.0;
    public static final double CEILING_FADE_SPAN = 100.0;
    public static final double CEILING_MIN_FACTOR = 0.4;

    public MiniHelicopterEntity(EntityType<? extends MiniHelicopterEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected double collectivePerNotch() {
        return COLLECTIVE_PER_NOTCH;
    }

    @Override
    protected double rotorInflowLimit() {
        return ROTOR_INFLOW_LIMIT;
    }

    @Override
    protected double maxCyclic() {
        return MAX_CYCLIC;
    }

    @Override
    protected double maxCyclicRate() {
        return MAX_CYCLIC_RATE;
    }

    @Override
    protected float maxYawRate() {
        return MAX_YAW_RATE;
    }

    @Override
    protected float yawRamp() {
        return YAW_RAMP;
    }

    @Override
    protected double turnFromBank() {
        return TURN_FROM_BANK;
    }

    @Override
    protected double hDragQuad() {
        return H_DRAG_QUAD;
    }

    @Override
    protected double hDragLin() {
        return H_DRAG_LIN;
    }

    @Override
    protected double hDragConst() {
        return H_DRAG_CONST;
    }

    @Override
    protected double maxSpeedBackstop() {
        return MAX_SPEED;
    }

    @Override
    protected double groundFriction() {
        return GROUND_FRICTION;
    }

    /** World height, not agl: at notch 5 the factor 0.4 is exactly hover, so the ceiling is y 160. */
    @Override
    protected double ceilingThrustFactor(double y) {
        return Mth.clamp(1.0 - (y - CEILING_FADE_START) / CEILING_FADE_SPAN, CEILING_MIN_FACTOR, 1.0);
    }

    /** 2.5 * 1.8 = 4.5, this airframe's pedal rate, for code using the fixed-wing idiom. */
    @Override
    protected float getRotationSpeedMultiplier() {
        return 1.8f;
    }

    public boolean hasMedicalLivery() {
        return getMaterial().builtInRegistryHolder().is(MEDICAL_TAG);
    }

    // Players board an empty machine only. A non-player rides only when loaded through loadRider
    // (dispatch API / autopilot); this also keeps LargeAirframeEntity's livestock pickup out.
    @Override
    protected boolean canAddPassenger(Entity passenger) {
        if (passenger instanceof Player) {
            // Not onto a dispatch flight in progress: the autopilot owns it until it lands.
            boolean dispatchFlying = dispatchManaged && getAutopilot() != null && getAutopilot().isActive();
            return getPassengers().isEmpty() && !dispatchFlying;
        }
        return riders.containsKey(passenger.getUUID()) && getPassengers().size() < riderCapacity();
    }

    // A forced mount (/ride, Passengers NBT) skips canAddPassenger; such a rider is put off on the next tick
    // unless it was loaded through loadRider.
    @Override
    public void tick() {
        if (!level().isClientSide()) {
            for (Entity passenger : List.copyOf(getPassengers())) {
                if (!(passenger instanceof Player) && !riders.containsKey(passenger.getUUID())) {
                    passenger.stopRiding();
                }
            }
            // A rider that got off by any other route loses its permit.
            if (!riders.isEmpty() && riders.size() != getPassengers().size()) {
                riders.keySet().removeIf(uuid -> getPassengers().stream().noneMatch(p -> p.getUUID().equals(uuid)));
            }
        }
        super.tick();
    }

    // ------------------------------------------------------------------ dispatch riders

    /** Front seat: the pilot's, or a crew member's on a dispatch flight. */
    public static final int SEAT_FRONT = 0;
    /** External litter on the right skid, medical livery only. */
    public static final int SEAT_LITTER = 1;
    /** Any free seat, front first. */
    public static final int SEAT_ANY = -1;

    private static final String RIDERS_KEY = "dispatch_riders";
    private static final String MANAGED_KEY = "dispatch_managed";

    /** Non-player riders loaded through {@link #loadRider}, by UUID, with their seat. Server side. */
    private final Map<UUID, Integer> riders = new LinkedHashMap<>();
    /** Set while the dispatch API owns this aircraft; air defence never engages it then. */
    private boolean dispatchManaged;

    /** Two riders in the medical livery (seat and litter), one otherwise. */
    public int riderCapacity() {
        return hasMedicalLivery() ? 2 : 1;
    }

    public boolean isDispatchManaged() {
        return dispatchManaged;
    }

    public void setDispatchManaged(boolean managed) {
        dispatchManaged = managed;
    }

    /**
     * Puts a non-player on board. The rider never steers: {@link #getControllingPassenger} only
     * answers a player.
     *
     * @param seat {@link #SEAT_FRONT}, {@link #SEAT_LITTER} or {@link #SEAT_ANY}
     * @return false when the rider is a player, the seat is taken or does not exist, or it is full
     */
    public boolean loadRider(Entity rider, int seat) {
        if (level().isClientSide() || rider instanceof Player || rider instanceof PlaneEntity || rider == this
            || !rider.isAlive() || rider.level() != level() || rider.getVehicle() == this) {
            return false;
        }
        if (getPassengers().size() >= riderCapacity()) {
            return false;
        }
        int chosen = seat;
        if (seat == SEAT_ANY) {
            chosen = seatFree(SEAT_FRONT) ? SEAT_FRONT : SEAT_LITTER;
        }
        if (chosen < 0 || chosen >= riderCapacity() || !seatFree(chosen)) {
            return false;
        }
        riders.put(rider.getUUID(), chosen);
        // Forced: our own checks above replace canAddPassenger, and a rider that got off a moment
        // ago still has vanilla's 60-tick boarding cooldown.
        if (!rider.startRiding(this, true, true)) {
            riders.remove(rider.getUUID());
            return false;
        }
        return true;
    }

    /** Takes a rider loaded through {@link #loadRider} off; it is put down beside the aircraft. */
    public boolean unloadRider(Entity rider) {
        if (!riders.containsKey(rider.getUUID())) {
            return false;
        }
        riders.remove(rider.getUUID());
        if (rider.getVehicle() == this) {
            rider.stopRiding();
        }
        return true;
    }

    /** UUIDs of the riders loaded through {@link #loadRider}, in loading order. */
    public List<UUID> riderIds() {
        return List.copyOf(riders.keySet());
    }

    /** The seat a passenger occupies. */
    public int seatOf(Entity passenger) {
        if (passenger instanceof Player) {
            return SEAT_FRONT;
        }
        Integer seat = riders.get(passenger.getUUID());
        return seat == null ? getPassengers().indexOf(passenger) : seat;
    }

    private boolean seatFree(int seat) {
        for (Entity passenger : getPassengers()) {
            if (seatOf(passenger) == seat) {
                return false;
            }
        }
        return true;
    }

    // Only a player steers. A crew member or patient aboard a dispatch flight is cargo.
    @Override
    public @Nullable LivingEntity getControllingPassenger() {
        LivingEntity controller = super.getControllingPassenger();
        return controller instanceof Player ? controller : null;
    }

    // A dispatch (medical) flight is never a target for air defence.
    @Override
    public boolean isHostile() {
        return !dispatchManaged && super.isHostile();
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (dispatchManaged) {
            output.putBoolean(MANAGED_KEY, true);
        }
        if (!riders.isEmpty()) {
            ValueOutput.TypedOutputList<Rider> list = output.list(RIDERS_KEY, Rider.CODEC);
            riders.forEach((uuid, seat) -> list.add(new Rider(uuid, seat)));
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        dispatchManaged = input.getBooleanOr(MANAGED_KEY, false);
        riders.clear();
        input.listOrEmpty(RIDERS_KEY, Rider.CODEC).forEach(rider -> riders.put(rider.uuid(), rider.seat()));
    }

    // The dispatch keys belong to the world save, not to the item a player picks up.
    @Override
    public ItemStack getItemStack() {
        ItemStack itemStack = super.getItemStack();
        CompoundTag compound = itemStack.get(SimplePlanesComponents.ENTITY_TAG.get());
        if (compound != null && (compound.contains(RIDERS_KEY) || compound.contains(MANAGED_KEY))) {
            CompoundTag clean = compound.copy();
            clean.remove(RIDERS_KEY);
            clean.remove(MANAGED_KEY);
            itemStack.set(SimplePlanesComponents.ENTITY_TAG.get(), clean);
        }
        return itemStack;
    }

    private record Rider(UUID uuid, int seat) {
        static final Codec<Rider> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.STRING_CODEC.fieldOf("uuid").forGetter(Rider::uuid),
            Codec.INT.optionalFieldOf("seat", SEAT_FRONT).forGetter(Rider::seat)
        ).apply(instance, Rider::new));
    }

    @Override
    public boolean tryToAddUpgrade(Player player, ItemStack itemStack) {
        Item item = itemStack.getItem();
        if (SimplePlanesUpgrades.getLargeUpgradeFromItem(item).isPresent()
            || PlanePayloadReloadListener.payloadEntries.containsKey(item)) {
            return false;
        }
        return super.tryToAddUpgrade(player, itemStack);
    }

    // Also closes the wrench screen's route for large upgrades and payloads, which only asks canAddUpgrade.
    @Override
    protected boolean acceptsUpgrade(UpgradeType type) {
        return type != SimplePlanesUpgrades.SEATS.get()
            && type != SimplePlanesUpgrades.SHOOTER.get()
            && type != SimplePlanesUpgrades.FLOATY_BEDDING.get()
            && type != SimplePlanesUpgrades.PAYLOAD.get()
            && !SimplePlanesUpgrades.LARGE_ITEM_UPGRADE_MAP.containsValue(type);
    }

    @Override
    public void positionRider(Entity passenger, MoveFunction moveFunction) {
        positionRiderGeneric(passenger);
        int seat = seatOf(passenger);
        float seatY = getPassengersRidingOffset() + getEntityYOffset(passenger);
        Vector3f local;
        if (seat == SEAT_FRONT) {
            local = new Vector3f(0, seatY - 0.375f, 0.625f);
        } else if (seat == SEAT_LITTER) {
            // On the litter over the right skid (model x -12 px), level with the cabin floor.
            local = new Vector3f(LITTER_X, seatY + LITTER_RISE - 0.375f, LITTER_Z);
        } else {
            return;
        }
        // Rotate about the render pivot (0, 0.375, 0) so the rider stays put on the airframe. The
        // server's Q_Client only follows a player pilot, so an unpiloted server copy uses Q.
        Vector3f pos = (level().isClientSide() ? transformPos(local) : transformPosPhysics(local))
            .add(0, 0.375f, 0);
        moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
    }

    /** Litter seat, entity space: right of the cabin, a little aft of the pilot. */
    public static final float LITTER_X = -0.80f;
    public static final float LITTER_Z = 0.45f;
    public static final float LITTER_RISE = 0.05f;

    @Override
    public float getPassengersRidingOffset() {
        return 0.4f;
    }

    @Override
    public double getCameraDistanceMultiplayer() {
        return 1.0;
    }

    @Override
    public int getFuelCost() {
        return 4;
    }

    @Override
    protected Item getItem() {
        return SimplePlanesItems.MINI_HELICOPTER_ITEM.get();
    }
}
