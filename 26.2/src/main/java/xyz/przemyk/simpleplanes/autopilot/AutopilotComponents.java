package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import xyz.przemyk.simpleplanes.SimplePlanesMod;

import java.util.List;

/**
 * Data components used by the autopilot tools. Registered here rather than in
 * {@code SimplePlanesComponents} so the feature stays self-contained.
 *
 * <p>Component values live on the item stack, so a half-drawn route or a half-marked runway
 * survives the player logging out, dropping the tool or putting it in a chest.
 */
public class AutopilotComponents {

    /** Class-load hook — components are registered eagerly below (contract C1). */
    public static void init() {
    }

    private static <T> DataComponentType<T> register(String name, DataComponentType<T> type) {
        return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, name), type);
    }

    /** Waypoints marked so far with the route wand. */
    public static final DataComponentType<List<BlockPos>> ROUTE = register("autopilot_route",
        DataComponentType.<List<BlockPos>>builder()
            .persistent(BlockPos.CODEC.listOf())
            .networkSynchronized(BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()))
            .build());

    /** Cruise altitude the route wand will hand to the aircraft. */
    public static final DataComponentType<Integer> CRUISE_ALTITUDE = register("autopilot_cruise_altitude",
        DataComponentType.<Integer>builder()
            .persistent(Codec.INT)
            .networkSynchronized(ByteBufCodecs.VAR_INT)
            .build());

    /** First runway threshold marked with the survey tool, waiting for its pair. */
    public static final DataComponentType<BlockPos> RUNWAY_ANCHOR = register("autopilot_runway_anchor",
        DataComponentType.<BlockPos>builder()
            .persistent(BlockPos.CODEC)
            .networkSynchronized(BlockPos.STREAM_CODEC)
            .build());

    /** Spawn distance configured on the strike tool. */
    public static final DataComponentType<Integer> STRIKE_DISTANCE = register("autopilot_strike_distance",
        DataComponentType.<Integer>builder()
            .persistent(Codec.INT)
            .networkSynchronized(ByteBufCodecs.VAR_INT)
            .build());
}
