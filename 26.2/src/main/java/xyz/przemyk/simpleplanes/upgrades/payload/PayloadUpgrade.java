package xyz.przemyk.simpleplanes.upgrades.payload;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import xyz.przemyk.simpleplanes.datapack.PayloadEntry;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.LargeUpgrade;

public class PayloadUpgrade extends LargeUpgrade {

    private PayloadEntry payloadEntry;

    public PayloadUpgrade(PlaneEntity planeEntity, PayloadEntry payloadEntry) {
        super(SimplePlanesUpgrades.PAYLOAD.get(), planeEntity);
        this.payloadEntry = payloadEntry;
    }

    public PayloadUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.PAYLOAD.get(), planeEntity);
    }

    public PayloadEntry getPayloadEntry() {
        return payloadEntry;
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {
        buffer.writeIdentifier(BuiltInRegistries.ITEM.getKey(payloadEntry.item()));
        buffer.writeIdentifier(BuiltInRegistries.BLOCK.getKey(payloadEntry.renderBlock()));
        buffer.writeIdentifier(BuiltInRegistries.ENTITY_TYPE.getKey(payloadEntry.dropSpawnEntity()));
        buffer.writeNbt(payloadEntry.compoundTag());
    }

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {
        Item item = BuiltInRegistries.ITEM.getValue(buffer.readIdentifier());
        Block renderBlock = BuiltInRegistries.BLOCK.getValue(buffer.readIdentifier());
        EntityType<?> dropSpawnEntity = BuiltInRegistries.ENTITY_TYPE.getValue(buffer.readIdentifier());
        CompoundTag compoundTag = buffer.readNbt();
        payloadEntry = new PayloadEntry(item, renderBlock, dropSpawnEntity, compoundTag == null ? new CompoundTag() : compoundTag);
    }

    @Override
    public void save(ValueOutput output) {
        if (payloadEntry == null) {
            return;
        }
        output.putString("item", BuiltInRegistries.ITEM.getKey(payloadEntry.item()).toString());
        output.putString("block", BuiltInRegistries.BLOCK.getKey(payloadEntry.renderBlock()).toString());
        output.putString("entity", BuiltInRegistries.ENTITY_TYPE.getKey(payloadEntry.dropSpawnEntity()).toString());
        output.store("entityTag", CompoundTag.CODEC, payloadEntry.compoundTag());
    }

    @Override
    public void load(ValueInput input) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(input.getStringOr("item", "minecraft:air")));
        Block renderBlock = BuiltInRegistries.BLOCK.getValue(Identifier.parse(input.getStringOr("block", "minecraft:air")));
        EntityType<?> dropSpawnEntity = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(input.getStringOr("entity", "minecraft:pig")));
        CompoundTag entityTag = input.read("entityTag", CompoundTag.CODEC).orElseGet(CompoundTag::new);
        payloadEntry = new PayloadEntry(item, renderBlock, dropSpawnEntity, entityTag);
    }

    @Override
    public ItemStack getItemStack() {
        return payloadEntry == null ? ItemStack.EMPTY : payloadEntry.item().getDefaultInstance();
    }

    @Override
    public boolean canBeDroppedAsPayload() {
        return true;
    }

    @Override
    public void dropAsPayload() {
        if (payloadEntry != null) {
            Entity entity = payloadEntry.dropSpawnEntity().create(planeEntity.level(), EntitySpawnReason.TRIGGERED);
            if (entity != null) {
                entity.load(TagValueInput.create(ProblemReporter.DISCARDING, planeEntity.registryAccess(), payloadEntry.compoundTag()));
                entity.setPos(planeEntity.position());
                entity.setDeltaMovement(planeEntity.getDeltaMovement());
                planeEntity.level().addFreshEntity(entity);
            }
        }
        remove();
    }
}
