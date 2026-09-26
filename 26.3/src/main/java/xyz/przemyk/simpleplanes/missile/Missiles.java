package xyz.przemyk.simpleplanes.missile;

import com.google.common.collect.ImmutableSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import xyz.przemyk.simpleplanes.SimplePlanesMod;

/**
 * Registration for the missile feature: one entity type, the silo's two blocks and its block entity, the
 * {@code /missile} command and the tracker that keeps missiles' chunks loaded. {@link #init()} is the single
 * call from the mod initialiser.
 */
public final class Missiles {

    private Missiles() {}

    public static final EntityType<MissileEntity> MISSILE = Registry.register(BuiltInRegistries.ENTITY_TYPE, id("missile"),
        EntityType.Builder.<MissileEntity>of(MissileEntity::new, MobCategory.MISC)
            .sized(1.0F, 1.0F)
            .clientTrackingRange(16)
            .updateInterval(1)
            .fireImmune()
            .noSummon()
            .noSave()
            .build(ResourceKey.create(Registries.ENTITY_TYPE, id("missile"))));

    public static final LaunchSiloBlock LAUNCH_SILO = registerBlock("launch_silo", LaunchSiloBlock::new);
    public static final LaunchSiloCasingBlock LAUNCH_SILO_CASING = registerBlock("launch_silo_casing", LaunchSiloCasingBlock::new);

    public static final BlockEntityType<LaunchSiloBlockEntity> LAUNCH_SILO_BE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
        id("launch_silo"), new BlockEntityType<>(LaunchSiloBlockEntity::new, ImmutableSet.of(LAUNCH_SILO)));

    public static void init() {
        MissileTracker.init();
        MissileCommand.register();
    }

    private static <T extends Block> T registerBlock(String name, java.util.function.Function<BlockBehaviour.Properties, T> factory) {
        // Opaque on purpose: a see-through shaft lets skylight down it and grass spreads to the dirt below.
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
            .mapColor(MapColor.METAL)
            .strength(5.0F, 1200.0F)
            .sound(SoundType.METAL)
            .pushReaction(PushReaction.IMMOVEABLE)
            .setId(ResourceKey.create(Registries.BLOCK, id(name)));
        return Registry.register(BuiltInRegistries.BLOCK, id(name), factory.apply(properties));
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, path);
    }
}
