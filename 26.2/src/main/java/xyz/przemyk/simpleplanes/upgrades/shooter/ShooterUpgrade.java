package xyz.przemyk.simpleplanes.upgrades.shooter;

import net.minecraft.util.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.StructureTags;
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

    public ShooterUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.SHOOTER.get(), planeEntity);
    }

    public void use(Player player) {
        Vector3f motion1 = planeEntity.transformPos(new Vector3f(0, -0.25f, (float) (1 + planeEntity.getDeltaMovement().length())));
        Vec3 motion = new Vec3(motion1);
        Level level = player.level();
        RandomSource random = level.getRandom();

        Vector3f pos = planeEntity.transformPos(new Vector3f(0.0f, 1.8f, 2.0f));
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
            BlockPos blockpos = serverLevel.findNearestMapStructure(StructureTags.EYE_OF_ENDER_LOCATED, new BlockPos((int) x, (int) y, (int) z), 100, false);
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
