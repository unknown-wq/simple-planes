package xyz.przemyk.simpleplanes.setup;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.entities.*;

import java.util.function.Supplier;

@SuppressWarnings("unused")
public class SimplePlanesEntities {

    /** Class-load hook — entity types are registered eagerly below (contract C1). */
    public static void init() {
    }

    public static ResourceKey<EntityType<?>> entityKey(String name) {
        return ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, name));
    }

    private static <T extends Entity> Supplier<EntityType<T>> register(String name, EntityType.EntityFactory<T> factory, float width, float height) {
        EntityType<T> value = Registry.register(BuiltInRegistries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, name),
            EntityType.Builder.of(factory, MobCategory.MISC)
                .sized(width, height)
                .clientTrackingRange(5)
                .updateInterval(3)
                .build(entityKey(name)));
        return () -> value;
    }

    public static final Supplier<EntityType<PlaneEntity>> PLANE = register("plane", PlaneEntity::new, 2.5F, 1.8F);
    public static final Supplier<EntityType<LargePlaneEntity>> LARGE_PLANE = register("large_plane", LargePlaneEntity::new, 3F, 2.3F);
    public static final Supplier<EntityType<CargoPlaneEntity>> CARGO_PLANE = register("cargo_plane", CargoPlaneEntity::new, 3F, 2.3F);
    public static final Supplier<EntityType<HelicopterEntity>> HELICOPTER = register("helicopter", HelicopterEntity::new, 2.5F, 2.2F);

    public static final Supplier<EntityType<ParachuteEntity>> PARACHUTE = register("parachute", ParachuteEntity::new, 1.0F, 1.0F);
}
