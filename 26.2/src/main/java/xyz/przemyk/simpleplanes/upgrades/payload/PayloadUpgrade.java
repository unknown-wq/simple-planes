package xyz.przemyk.simpleplanes.upgrades.payload;

import net.minecraft.core.Registry;
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
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.datapack.PayloadEntry;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.LargeUpgrade;

public class PayloadUpgrade extends LargeUpgrade {

    /** Null until set, and again whenever the stored entry names ids this install does not have. */
    private @Nullable PayloadEntry payloadEntry;

    public PayloadUpgrade(PlaneEntity planeEntity, PayloadEntry payloadEntry) {
        super(SimplePlanesUpgrades.PAYLOAD.get(), planeEntity);
        this.payloadEntry = payloadEntry;
    }

    public PayloadUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.PAYLOAD.get(), planeEntity);
    }

    public @Nullable PayloadEntry getPayloadEntry() {
        return payloadEntry;
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {
        // The entry is optional on the wire because it is optional in memory: an upgrade loaded from
        // an entry this install cannot resolve has none, and writeSpawnData reaches every upgrade the
        // moment a player starts tracking the plane, which is before the removal below takes effect.
        buffer.writeBoolean(payloadEntry != null);
        if (payloadEntry == null) {
            return;
        }
        buffer.writeIdentifier(BuiltInRegistries.ITEM.getKey(payloadEntry.item()));
        buffer.writeIdentifier(BuiltInRegistries.BLOCK.getKey(payloadEntry.renderBlock()));
        buffer.writeIdentifier(BuiltInRegistries.ENTITY_TYPE.getKey(payloadEntry.dropSpawnEntity()));
        buffer.writeNbt(payloadEntry.compoundTag());
    }

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {
        if (!buffer.readBoolean()) {
            payloadEntry = null;
            return;
        }
        Item item = BuiltInRegistries.ITEM.getOptional(buffer.readIdentifier()).orElse(null);
        Block renderBlock = BuiltInRegistries.BLOCK.getOptional(buffer.readIdentifier()).orElse(null);
        EntityType<?> dropSpawnEntity = BuiltInRegistries.ENTITY_TYPE.getOptional(buffer.readIdentifier()).orElse(null);
        CompoundTag compoundTag = buffer.readNbt();
        payloadEntry = item == null || renderBlock == null || dropSpawnEntity == null
            ? null
            : new PayloadEntry(item, renderBlock, dropSpawnEntity, compoundTag == null ? new CompoundTag() : compoundTag);
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
        Item item = read(BuiltInRegistries.ITEM, input, "item");
        Block renderBlock = read(BuiltInRegistries.BLOCK, input, "block");
        EntityType<?> dropSpawnEntity = read(BuiltInRegistries.ENTITY_TYPE, input, "entity");
        if (item == null || renderBlock == null || dropSpawnEntity == null) {
            // A payload whose ids this install does not have is not a payload. The old defaults
            // turned it into air carried by a pig, because the defaulted registries answer with
            // those rather than with nothing; drop the upgrade instead.
            payloadEntry = null;
            remove();
            return;
        }
        CompoundTag entityTag = input.read("entityTag", CompoundTag.CODEC).orElseGet(CompoundTag::new);
        payloadEntry = new PayloadEntry(item, renderBlock, dropSpawnEntity, entityTag);
    }

    private static <T> @Nullable T read(Registry<T> registry, ValueInput input, String field) {
        Identifier id = input.getString(field).map(Identifier::tryParse).orElse(null);
        return id == null ? null : registry.getOptional(id).orElse(null);
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
