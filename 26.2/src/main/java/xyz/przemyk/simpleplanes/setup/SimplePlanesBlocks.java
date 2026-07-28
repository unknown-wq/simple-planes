package xyz.przemyk.simpleplanes.setup;

import com.google.common.collect.ImmutableSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.blocks.ChargingStationBlock;
import xyz.przemyk.simpleplanes.blocks.ChargingStationBlockEntity;
import xyz.przemyk.simpleplanes.blocks.PlaneWorkbenchBlock;
import xyz.przemyk.simpleplanes.blocks.PlaneWorkbenchBlockEntity;

import java.util.function.Function;
import java.util.function.Supplier;

@SuppressWarnings("unused")
public class SimplePlanesBlocks {

    /** Class-load hook — everything is registered eagerly in the static initializers below (contract C1). */
    public static void init() {
    }

    public static ResourceKey<Block> blockKey(String name) {
        return ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, name));
    }

    private static <T extends Block> Supplier<T> registerBlock(String name, Function<BlockBehaviour.Properties, T> factory, BlockBehaviour.Properties properties) {
        T value = Registry.register(BuiltInRegistries.BLOCK, Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, name),
            factory.apply(properties.setId(blockKey(name))));
        return () -> value;
    }

    private static <T extends BlockEntity> Supplier<BlockEntityType<T>> registerTile(String name, BlockEntityType.BlockEntitySupplier<T> factory, Supplier<? extends Block> block) {
        BlockEntityType<T> value = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, name),
            new BlockEntityType<T>(factory, ImmutableSet.<Block>of(block.get())));
        return () -> value;
    }

    public static final Supplier<PlaneWorkbenchBlock> PLANE_WORKBENCH_BLOCK =
        registerBlock("plane_workbench", PlaneWorkbenchBlock::new, BlockBehaviour.Properties.ofFullCopy(Blocks.CRAFTING_TABLE));
    public static final Supplier<ChargingStationBlock> CHARGING_STATION_BLOCK =
        registerBlock("charging_station", ChargingStationBlock::new, BlockBehaviour.Properties.ofFullCopy(Blocks.COBBLESTONE));

    public static final Supplier<BlockEntityType<PlaneWorkbenchBlockEntity>> PLANE_WORKBENCH_TILE =
        registerTile("plane_workbench", PlaneWorkbenchBlockEntity::new, PLANE_WORKBENCH_BLOCK);
    public static final Supplier<BlockEntityType<ChargingStationBlockEntity>> CHARGING_STATION_TILE =
        registerTile("charging_station", ChargingStationBlockEntity::new, CHARGING_STATION_BLOCK);
}
