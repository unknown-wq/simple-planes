package xyz.przemyk.simpleplanes.upgrades.shooter;

import net.minecraft.util.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.StructureTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;

import java.util.function.Function;

public class ShooterUpgrade extends Upgrade {

    /** C4: NeoForge ItemStackHandler -> vanilla SimpleContainer. */
    public final SimpleContainer container = new SimpleContainer(1);

    /**
     * The shortest interval, in ticks, at which the trigger will fire.
     *
     * <p>Four ticks is vanilla's own use-item cadence ({@code Minecraft#rightClickDelay}), so it is
     * above anything a person can produce by clicking and no player will ever feel it. What it is
     * for is the client that is not clicking: {@link xyz.przemyk.simpleplanes.network.ShootPacket}
     * carries no payload and costs a modified client nothing to send, and every accepted one spawns
     * a projectile — or, for an eye of ender, runs a 100-chunk structure search. Unbounded, that is
     * a pilot with a stack of fire charges filling the sky and a server on its knees; the ammo in
     * the slot is no limit at all when the pilot is in creative and {@link #shrinkAmmo} is skipped.
     */
    private static final int SHOT_COOLDOWN_TICKS = 4;

    /**
     * Game time of the earliest tick the next shot may be taken on.
     *
     * <p>Server-side only, and deliberately neither saved nor synced: it is a rate limit on an
     * incoming packet, not aircraft state, and a cooldown that resets when the plane is reloaded
     * costs nothing. {@code getGameTime} rather than {@code tickCount} because it does not restart
     * with the entity, and unlike the time of day it never goes backwards.
     */
    private long nextShotTick = Long.MIN_VALUE;

    public ShooterUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.SHOOTER.get(), planeEntity);
    }

    public void use(Player player) {
        Level level = player.level();

        // Before any work at all: the eye-of-ender branch below is expensive whether or not it
        // finds anything, so a shot that is going to be refused must be refused first. The clock is
        // set on every accepted call, including the ones that turn out to have nothing to fire.
        long gameTime = level.getGameTime();
        if (gameTime < nextShotTick) {
            return;
        }
        nextShotTick = gameTime + SHOT_COOLDOWN_TICKS;

        Vector3f motion1 = planeEntity.transformPos(new Vector3f(0, -0.25f, (float) (1 + planeEntity.getDeltaMovement().length())));
        Vec3 motion = new Vec3(motion1);
        RandomSource random = level.getRandom();

        Vector3f pos = planeEntity.transformPos(new Vector3f(0.0f, 1.8f, 2.0f));
        // Only a dirty flag; the ammo count is read off the container when the plane's tick actually
        // serialises the upgrade, which is after the shrinkAmmo() calls below. Nothing is captured
        // here, so raising it before the shot does not sync a stale count.
        updateClient();

        double x = pos.x() + planeEntity.getX();
        double y = pos.y() + planeEntity.getY();
        double z = pos.z() + planeEntity.getZ();

        ItemStack itemStack = container.getItem(0);
        Item item = itemStack.getItem();

        if (item == Items.FIREWORK_ROCKET) {
            FireworkRocketEntity fireworkrocketentity = new FireworkRocketEntity(level, itemStack, x, y, z, true);
            fireworkrocketentity.shoot(-motion.x, -motion.y, -motion.z, -(float) Math.max(0.5F, motion.length() * 1.5), 1.0F);
            level.addFreshEntity(fireworkrocketentity);
            if (!player.isCreative()) {
                shrinkAmmo();
            }
        } else if (item == Items.FIRE_CHARGE) {
            double d3 = random.nextGaussian() * 0.05D + 2 * motion.x;
            double d4 = random.nextGaussian() * 0.05D;
            double d5 = random.nextGaussian() * 0.05D + 2 * motion.z;
            Fireball fireBallEntity = Util
                .make(new SmallFireball(level, player, new Vec3(d3, d4, d5)), (fireball) -> fireball.setItem(itemStack));
            fireBallEntity.setPos(x, y, z);
            fireBallEntity.setDeltaMovement(motion.scale(2));
            level.addFreshEntity(fireBallEntity);
            if (!player.isCreative()) {
                shrinkAmmo();
            }
        } else if (item instanceof ArrowItem arrowItem) {
            AbstractArrow arrowEntity = arrowItem.createArrow(level, itemStack, player, null);
            arrowEntity.setDeltaMovement(motion.scale(Math.max(motion.length() * 1.5, 3) / motion.length()));
            if (player.isCreative()) {
                arrowEntity.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
            } else {
                shrinkAmmo();
            }
            level.addFreshEntity(arrowEntity);
        } else if (item == Items.ENDER_EYE && level instanceof ServerLevel serverLevel) {
            // Mth.floor, not (int): a truncating cast rounds towards zero and searches from the
            // wrong block at negative coordinates (x = -0.5 would give 0 instead of -1).
            BlockPos blockpos = serverLevel.findNearestMapStructure(StructureTags.EYE_OF_ENDER_LOCATED, new BlockPos(Mth.floor(x), Mth.floor(y), Mth.floor(z)), 100, false);
            if (blockpos != null) {
                EyeOfEnder eyeOfEnder = new EyeOfEnder(level, x, y, z);
                eyeOfEnder.setItem(itemStack);
                eyeOfEnder.signalTo(Vec3.atCenterOf(blockpos));
                level.addFreshEntity(eyeOfEnder);
                level.playSound(null, x, y, z, SoundEvents.ENDER_EYE_LAUNCH, SoundSource.NEUTRAL, 0.5f, 0.4f / random.nextFloat() * 0.4f + 0.8f);
                if (!player.isCreative()) {
                    shrinkAmmo();
                }
            }
        }
    }

    private void shrinkAmmo() {
        container.removeItem(0, 1);
    }

    @Override
    public void save(ValueOutput output) {
        container.storeAsItemList(output.list("item", ItemStack.CODEC));
    }

    @Override
    public void load(ValueInput input) {
        container.fromItemList(input.listOrEmpty("item", ItemStack.CODEC));
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, container.getItem(0));
    }

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {
        container.setItem(0, ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
    }

    @Override
    public void onRemoved() {
        if (planeEntity.level() instanceof ServerLevel serverLevel) {
            planeEntity.spawnAtLocation(serverLevel, container.getItem(0));
        }
    }

    @Override
    public ItemStack getItemStack() {
        return SimplePlanesItems.SHOOTER.get().getDefaultInstance();
    }

    @Override
    public void addContainerData(Function<Slot, Slot> addSlot, Function<DataSlot, DataSlot> addDataSlot) {
        addSlot.apply(new Slot(container, 0, 134, 62));
    }
}
