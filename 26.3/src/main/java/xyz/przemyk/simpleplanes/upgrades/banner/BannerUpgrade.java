package xyz.przemyk.simpleplanes.upgrades.banner;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import xyz.przemyk.simpleplanes.misc.MathUtil;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;

public class BannerUpgrade extends Upgrade {

    public ItemStack banner;
    public float rotation, prevRotation;

    public BannerUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.BANNER.get(), planeEntity);
        banner = Items.BANNER.pick(DyeColor.WHITE).getDefaultInstance();
        prevRotation = planeEntity.yRotO;
        rotation = planeEntity.yRotO;
    }

    @Override
    public void tick() {
        prevRotation = rotation;
        rotation = MathUtil.lerpAngle(0.05f, rotation, planeEntity.yRotO);
    }

    @Override
    public void save(ValueOutput output) {
        output.store("banner", ItemStack.CODEC, banner);
    }

    @Override
    public void load(ValueInput input) {
        banner = input.read("banner", ItemStack.CODEC).orElse(Items.BANNER.pick(DyeColor.WHITE).getDefaultInstance());
    }

    @Override
    public void onApply(ItemStack itemStack) {
        if (itemStack.getItem() instanceof BannerItem) {
            banner = itemStack.copy();
            banner.setCount(1);
            updateClient();
        }
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, banner);
    }

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {
        banner = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
    }

    @Override
    public ItemStack getItemStack() {
        return banner;
    }
}
