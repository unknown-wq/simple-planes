package xyz.przemyk.simpleplanes.api.map;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * What the server lets one player see of the airspace in their dimension: airfields, helipads, shuttle
 * routes, autopilot flights and launch silos. Plain data, built server-side for the requesting player and
 * sent on request only (see {@link AviationMap#requestSnapshot()}). Part of the stable map API.
 *
 * <p>Nothing in here is trusted by the server afterwards: a launch request names a silo position and a
 * target, and the server checks both again from its own state.
 *
 * @param dimension       dimension id, e.g. {@code minecraft:overworld}
 * @param gameTime        server game time the snapshot was built at
 * @param nearRadius      how close (blocks, 3D, to the silo mouth) a player must be to launch from a silo
 * @param launchPermitted whether the server would accept a launch from this player at all (operator
 *                        permission); a hint for the UI, the server checks it again on every request
 * @param snapshotRadius  horizontal radius around the player the lists were cut to
 */
public record AviationSnapshot(String dimension, long gameTime, int nearRadius, boolean launchPermitted,
                               int snapshotRadius, List<Airfield> airfields, List<Helipad> helipads,
                               List<Route> routes, List<Flight> flights, List<Silo> silos) {

    /** A surveyed runway from threshold A to threshold B. Designators are the runway numbers, e.g. "09" / "27". */
    public record Airfield(String name, BlockPos thresholdA, BlockPos thresholdB, int width,
                           String designatorA, String designatorB, boolean usable) {}

    /** A surveyed helipad: a square of side {@code 2 * radius + 1} about {@code centre}. */
    public record Helipad(String name, BlockPos centre, int radius) {}

    /**
     * A scheduled shuttle between two airfields ({@code /autopilot shuttle}).
     *
     * @param state        {@code waiting}, {@code flying} or {@code paused}
     * @param aircraftType the airframe ordered, e.g. {@code plane}, {@code cargo}
     * @param note         the last problem the dispatcher recorded, or empty
     */
    public record Route(int id, String fieldA, String fieldB, BlockPos endA, BlockPos endB, String state,
                        String aircraftType, boolean hostile, int legs, String note) {}

    /**
     * An aircraft under autopilot in this dimension.
     *
     * @param kind           {@code route}, {@code strike} or {@code heli}
     * @param mode           the autopilot phase, e.g. {@code cruise}, {@code approach}
     * @param hasDestination whether {@code destX}/{@code destZ} mean anything
     * @param destination    destination airfield or helipad name, or empty
     */
    public record Flight(int entityId, String aircraftType, boolean hostile, double x, double y, double z,
                         String kind, String mode, boolean hasDestination, double destX, double destZ,
                         String destination) {}

    /**
     * A launch silo from the server's silo index.
     *
     * @param pos         the master block (top layer, minimum X/Z corner); this is what a launch request names
     * @param strike      true for strike mode, false for air defence
     * @param loaded      a missile is loaded
     * @param phase       {@code idle}, {@code opening}, {@code launching}, {@code closing}, {@code cooldown},
     *                    or {@code unknown} when the silo's chunk is not loaded
     * @param chunkLoaded the silo's chunk is loaded right now; the rest is live when true and from the
     *                    index (last known) when false
     * @param mouthX      horizontal centre of the silo mouth; range is measured from here
     * @param distance    3D distance from the player to the silo mouth when the snapshot was built
     * @param minRange    strike minimum horizontal range of the tier
     * @param maxRange    strike maximum horizontal range of the tier
     * @param usable      whether this player could launch from it right now (target and permission aside). Since
     *                    API 3 this is true at any distance and for a silo in an unloaded chunk (remote launch,
     *                    judged from the index's last known state; the server loads the chunk and checks again)
     * @param status      why not, or "ready" (a remote-launch variant of "ready" when out of reach or unloaded)
     * @param serviceable whether this player could load or unload it right now (permission and the silo's own
     *                    state aside): near enough and the chunk loaded. True for air-defence silos too
     * @param serviceStatus why not, or "ready"
     * @param detectionRadius air-defence detection radius of the tier (3D, from the mouth), from
     *                    {@code InterceptorSpec#detectionRadius}; meaningful in either mode
     * @param engagementRange air-defence interceptor motor path of the tier ({@code InterceptorSpec#range}): how far
     *                    an interceptor can fly, so an upper bound on how far from the silo it can engage
     */
    public record Silo(BlockPos pos, int tier, boolean strike, boolean loaded, String phase, boolean chunkLoaded,
                       double mouthX, double mouthZ, int minRange, int maxRange, double distance,
                       boolean usable, Component status, boolean serviceable, Component serviceStatus,
                       double detectionRadius, double engagementRange) {}
}
