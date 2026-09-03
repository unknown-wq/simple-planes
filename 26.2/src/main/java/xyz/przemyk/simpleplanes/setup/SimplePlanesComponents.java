package xyz.przemyk.simpleplanes.setup;

import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.SimplePlanesMod;

import java.util.function.Supplier;

public class SimplePlanesComponents {

    /** Class-load hook — components are registered eagerly below (contract C1). */
    public static void init() {
    }

    /**
     * NeoForge's {@code ItemStack} had a {@code set(Supplier<DataComponentType<T>>, T)} extension, so
     * some call sites write {@code ENTITY_TAG.get()} and some write {@code ENTITY_TAG} directly.
     * Vanilla has no such overload, so the registered object is both the component type and a
     * {@link Supplier} of itself — every existing call site keeps compiling either way.
     */
    public static class ComponentHolder<T> implements DataComponentType<T>, Supplier<DataComponentType<T>> {

        private final DataComponentType<T> delegate;

        public ComponentHolder(DataComponentType<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public DataComponentType<T> get() {
            return this;
        }

        @Override
        public @Nullable Codec<T> codec() {
            return delegate.codec();
        }

        @Override
        public boolean ignoreSwapAnimation() {
            return delegate.ignoreSwapAnimation();
        }

        @Override
        public StreamCodec<? super RegistryFriendlyByteBuf, T> streamCodec() {
            return delegate.streamCodec();
        }
    }

    private static <T> ComponentHolder<T> register(String name, DataComponentType<T> type) {
        return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, name), new ComponentHolder<>(type));
    }

    public static final ComponentHolder<CompoundTag> ENTITY_TAG = register("entity_tag",
        DataComponentType.<CompoundTag>builder()
            .persistent(CompoundTag.CODEC)
            .networkSynchronized(ByteBufCodecs.COMPOUND_TAG)
            .build());
}
