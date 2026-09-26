package xyz.przemyk.simpleplanes.entities;

import net.minecraft.core.registries.Registries;
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

import java.util.List;

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

    // Players only: this also keeps LargeAirframeEntity's livestock pickup out, which mounts via startRiding.
    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty() && passenger instanceof Player;
    }

    // A forced mount (/ride, Passengers NBT) skips canAddPassenger; such a rider is put off on the next tick.
    @Override
    public void tick() {
        if (!level().isClientSide()) {
            for (Entity passenger : List.copyOf(getPassengers())) {
                if (!(passenger instanceof Player)) {
                    passenger.stopRiding();
                }
            }
        }
        super.tick();
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
        if (getPassengers().indexOf(passenger) == 0) {
            float seatY = getPassengersRidingOffset() + getEntityYOffset(passenger);
            // Rotate about the render pivot (0, 0.375, 0) so the pilot stays put in the cabin.
            Vector3f pos = transformPos(new Vector3f(0, seatY - 0.375f, 0.625f)).add(0, 0.375f, 0);
            moveFunction.accept(passenger, getX() + pos.x(), getY() + pos.y(), getZ() + pos.z());
        }
    }

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
