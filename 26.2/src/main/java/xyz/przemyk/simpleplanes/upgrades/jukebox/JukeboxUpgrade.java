package xyz.przemyk.simpleplanes.upgrades.jukebox;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.network.JukeboxPacket;
import xyz.przemyk.simpleplanes.network.SimplePlanesNetworking;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.LargeUpgrade;

public class JukeboxUpgrade extends LargeUpgrade {

    private ItemStack record = ItemStack.EMPTY;

    public JukeboxUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.JUKEBOX.get(), planeEntity);
    }

    @Override
    public void save(ValueOutput output) {
        output.store("record", ItemStack.OPTIONAL_CODEC, record);
    }

    @Override
    public void load(ValueInput input) {
        record = input.read("record", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
    }

    @Override
    public void onItemRightClick(Player player, InteractionHand hand) {
        if (!planeEntity.level().isClientSide()) {
            ItemStack itemStack = player.getItemInHand(hand);
            // Only a playable disc goes in, and only one of it. Taking whatever was in hand meant
            // a stack of anything could be swapped in, stored whole, and handed straight back on
            // the next swap while the hand kept all but one of it.
            if (!itemStack.has(DataComponents.JUKEBOX_PLAYABLE) || itemStack.is(record.getItem())) {
                return;
            }
            ItemStack oldRecord = record;
            record = itemStack.copyWithCount(1);
            if (!player.isCreative()) {
                itemStack.shrink(1);
            }
            if (!oldRecord.isEmpty()) {
                player.addItem(oldRecord);
            }
            player.awardStat(Stats.PLAY_RECORD);
            SimplePlanesNetworking.sendToPlayersTrackingEntity(planeEntity,
                new JukeboxPacket(BuiltInRegistries.ITEM.getKey(record.getItem()), planeEntity.getId()));
        }
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {}

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {}

    @Override
    public void onRemoved() {
        if (planeEntity.level() instanceof ServerLevel serverLevel) {
            planeEntity.spawnAtLocation(serverLevel, record);
        } else if (planeEntity.level().isClientSide()) {
            // client-only class: must stay inside this branch or the dedicated server class-loads it
            xyz.przemyk.simpleplanes.client.MovingSound.remove(planeEntity);
        }
    }

    @Override
    public ItemStack getItemStack() {
        return Items.JUKEBOX.getDefaultInstance();
    }
}
