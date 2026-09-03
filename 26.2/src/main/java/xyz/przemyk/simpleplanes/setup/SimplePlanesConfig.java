package xyz.przemyk.simpleplanes.setup;

import java.util.function.Supplier;

/**
 * TODO(port-26.2): DISABLED — NeoForge's {@code ModConfigSpec} has no Fabric equivalent and pulling in
 * a config library is out of scope for the port (rule §9). The values below are the exact defaults of
 * the old {@code simpleplanes-common.toml} / {@code simpleplanes-client.toml}, exposed through the same
 * {@code XXX.get()} accessors so no call site changes. Making them editable again means wiring up a
 * config library (e.g. Fabric's own or midnightlib) in this class only.
 *
 * <pre>
 * Original spec:
 *   general.turnThreshold                        = 20    [0, 90]
 *   general.plane_heist                          = true
 *   general.plane_fuel_cost                      = 3     [0, MAX]
 *   general.large_plane_fuel_cost                = 6     [0, MAX]
 *   general.cargo_plane_fuel_cost                = 10    [0, MAX]
 *   general.helicopter_fuel_cost                 = 6     [0, MAX]
 *   general.liquid_engine_capacity               = 4000  [1, MAX]
 *   general_client.plane_camera_distance_multiplier       = 1.0 [1.0, 2.0]
 *   general_client.large_plane_camera_distance_multiplier = 1.3 [1.0, 2.0]
 *   general_client.cargo_plane_camera_distance_multiplier = 1.8 [1.0, 2.0]
 *   general_client.heli_camera_distance_multiplier        = 1.2 [1.0, 2.0]
 * </pre>
 */
public class SimplePlanesConfig {

    public static final Supplier<Boolean> THIEF = () -> true;
    public static final Supplier<Integer> TURN_THRESHOLD = () -> 20;
    public static final Supplier<Integer> PLANE_FUEL_COST = () -> 3;
    public static final Supplier<Integer> LARGE_PLANE_FUEL_COST = () -> 6;
    public static final Supplier<Integer> CARGO_PLANE_FUEL_COST = () -> 10;
    public static final Supplier<Integer> HELICOPTER_FUEL_COST = () -> 6;
    public static final Supplier<Integer> LIQUID_ENGINE_CAPACITY = () -> 4000;
    public static final Supplier<Double> PLANE_CAMERA_DISTANCE_MULTIPLIER = () -> 1.0;
    public static final Supplier<Double> LARGE_PLANE_CAMERA_DISTANCE_MULTIPLIER = () -> 1.3;
    public static final Supplier<Double> CARGO_PLANE_CAMERA_DISTANCE_MULTIPLIER = () -> 1.8;
    public static final Supplier<Double> HELI_CAMERA_DISTANCE_MULTIPLIER = () -> 1.2;

    public static void init() {
    }
}
