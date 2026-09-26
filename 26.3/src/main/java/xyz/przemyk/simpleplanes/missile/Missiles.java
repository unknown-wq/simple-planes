package xyz.przemyk.simpleplanes.missile;

import com.google.common.collect.ImmutableSet;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import xyz.przemyk.simpleplanes.SimplePlanesMod;

/**
 * Registration for the missile feature: one entity type, the silo's two blocks, its block entity and its item, the
 * four missile items, the {@code missile_explosions} game rule, the {@code /missile} command and the tracker that
 * keeps missiles' chunks loaded. {@link #init()} is the single call from the mod initialiser.
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

    public static final LaunchSiloItem LAUNCH_SILO_ITEM = Registry.register(BuiltInRegistries.ITEM, id("launch_silo"),
        new LaunchSiloItem(LAUNCH_SILO, new Item.Properties().useBlockDescriptionPrefix()
            .setId(ResourceKey.create(Registries.ITEM, id("launch_silo")))));

    public static final MissileItem MISSILE_T1 = registerMissile(MissileTier.T1, Rarity.COMMON);
    public static final MissileItem MISSILE_T2 = registerMissile(MissileTier.T2, Rarity.COMMON);
    public static final MissileItem MISSILE_T3 = registerMissile(MissileTier.T3, Rarity.UNCOMMON);
    public static final MissileItem MISSILE_T4 = registerMissile(MissileTier.T4, Rarity.RARE);

    /** {@code /gamerule simpleplanes:missile_explosions false} makes every missile harmless again (phase 1 behaviour). */
    public static final GameRule<Boolean> EXPLOSIONS = GameRuleBuilder.forBoolean(true)
        .category(GameRuleCategory.MISC)
        .buildAndRegister(id("missile_explosions"));

    public static void init() {
        MissileTracker.init();
        MissileCommand.register();
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("planes_tab")))
            .register(output -> {
                output.accept(LAUNCH_SILO_ITEM);
                for (MissileTier tier : MissileTier.values()) output.accept(missileItem(tier));
            });
    }

    public static MissileItem missileItem(MissileTier tier) {
        return switch (tier) {
            case T1 -> MISSILE_T1;
            case T2 -> MISSILE_T2;
            case T3 -> MISSILE_T3;
            case T4 -> MISSILE_T4;
        };
    }

    private static MissileItem registerMissile(MissileTier tier, Rarity rarity) {
        String name = "missile_t" + tier.tier;
        return Registry.register(BuiltInRegistries.ITEM, id(name), new MissileItem(tier, new Item.Properties()
            .stacksTo(16).rarity(rarity).setId(ResourceKey.create(Registries.ITEM, id(name)))));
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
