package xyz.przemyk.simpleplanes.api.map;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.aviation.AviationPayloads;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Client API for a world map mod that wants to show Simple Planes' airspace and launch silos. Stable; bump
 * {@link #API_VERSION} on an incompatible change.
 *
 * <p>Client only: call it from the client thread. Nothing here is sent unless a map asks, so a client without
 * a map costs nothing. The server answers only if it runs a Simple Planes build with this feature
 * ({@link #isAvailable()}), and it decides everything: a launch request names a silo and a target column,
 * and the server checks permission, distance, chunk, silo state and range again.
 *
 * <pre>
 * AviationMap.addListener(listener);    // snapshots and launch results arrive on the client thread
 * AviationMap.requestSnapshot();        // when the map's aviation view opens, then every few seconds
 * AviationMap.requestLaunch(silo, x, AviationMap.SURFACE, z);
 * AviationMap.requestLoad(silo);        // operators, near the silo
 * AviationMap.requestLaunch(silo, x, AviationMap.SURFACE, z, true);   // this launch piercing (API 4)
 * AviationMap.requestWarhead(silo, true);                           // the silo's own setting (API 4)
 * </pre>
 */
public final class AviationMap {

    private AviationMap() {}

    /**
     * Version of this API. 1: snapshot, launch request, launch result. 2: load / unload requests
     * ({@link SiloAction}), the action on {@link LaunchResult}, and the service and air-defence fields on
     * {@link AviationSnapshot.Silo}. 3: remote launch -- a silo out of reach or in an unloaded chunk may be
     * {@link AviationSnapshot.Silo#usable() usable}, and its launch is answered first with a
     * {@link LaunchResult#pending() pending} result while the server loads the silo's chunk. 4: warheads -- the
     * {@code piercing}, {@code pierceMinRange} and {@code pierceRadius} fields of {@link AviationSnapshot.Silo},
     * {@link #requestLaunch(BlockPos, int, int, int, boolean)} with a warhead for one launch, and
     * {@link #requestWarhead} for the silo's own setting. Everything of API 3 is unchanged.
     */
    public static final int API_VERSION = 4;

    /** Target height meaning "the server picks the surface". */
    public static final int SURFACE = Integer.MIN_VALUE;

    /** Receives what the server sends. Both methods run on the client thread. */
    public interface Listener {
        default void onSnapshot(AviationSnapshot snapshot) {}

        default void onLaunchResult(LaunchResult result) {}
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile @Nullable AviationSnapshot latest;
    private static volatile @Nullable LaunchResult lastResult;
    private static boolean initialised;

    /** Whether the connected server understands the aviation payloads. */
    public static boolean isAvailable() {
        return ClientPlayNetworking.canSend(AviationPayloads.SnapshotRequest.TYPE);
    }

    /** Asks for a fresh snapshot. The server drops requests closer than half a second apart. */
    public static boolean requestSnapshot() {
        if (!isAvailable()) return false;
        ClientPlayNetworking.send(new AviationPayloads.SnapshotRequest(AviationPayloads.PROTOCOL));
        return true;
    }

    /**
     * Whether the connected server also takes load and unload requests. True whenever {@link #isAvailable()} is,
     * on a server of this version.
     */
    public static boolean canService() {
        return ClientPlayNetworking.canSend(AviationPayloads.SiloRequest.TYPE);
    }

    /**
     * Asks the server to load a missile of the silo's own tier into {@code silo}, like {@code /missile silo load}.
     * Operators only, from near the silo; works in strike and air-defence mode. The answer arrives as
     * {@link Listener#onLaunchResult} with {@link SiloAction#LOAD}.
     */
    public static boolean requestLoad(BlockPos silo) {
        return requestService(silo, SiloAction.LOAD);
    }

    /** Asks the server to take the missile out of {@code silo}, like {@code /missile silo unload}. See {@link #requestLoad}. */
    public static boolean requestUnload(BlockPos silo) {
        return requestService(silo, SiloAction.UNLOAD);
    }

    private static boolean requestService(BlockPos silo, SiloAction action) {
        if (!canService()) return false;
        ClientPlayNetworking.send(new AviationPayloads.SiloRequest(silo.immutable(), action));
        return true;
    }

    /**
     * Asks the server to launch from {@code silo} (the master position from the snapshot) at the block column
     * {@code x, z}. {@code y} is the first free block above the surface as the map knows it, or {@link #SURFACE}.
     * The answer arrives as {@link Listener#onLaunchResult}. Operators only, from any distance: for a silo whose
     * chunk is not loaded the first answer is {@link LaunchResult#pending() pending} and the final one follows once
     * the server has loaded the chunk (API 3).
     */
    public static boolean requestLaunch(BlockPos silo, int x, int y, int z) {
        if (!isAvailable()) return false;
        ClientPlayNetworking.send(new AviationPayloads.LaunchRequest(silo.immutable(), x, y, z));
        return true;
    }

    /**
     * Whether the connected server takes a warhead with a launch and warhead settings (API 4): true whenever
     * {@link #isAvailable()} is, on a server of this version. When false, the warhead methods send nothing.
     */
    public static boolean canChooseWarhead() {
        return ClientPlayNetworking.canSend(AviationPayloads.WarheadLaunchRequest.TYPE)
            && ClientPlayNetworking.canSend(AviationPayloads.WarheadRequest.TYPE);
    }

    /**
     * {@link #requestLaunch(BlockPos, int, int, int)} with the warhead of this one launch: {@code piercing} true fires
     * the tier's piercing warhead (entities only, armour ignored, no block broken), false its ordinary blast, whatever
     * the silo is set to. The silo's setting is not changed. A piercing launch needs the target at least
     * {@link AviationSnapshot.Silo#pierceMinRange()} away. The answer arrives as {@link Listener#onLaunchResult}, as
     * for any launch, pending first for a silo whose chunk is not loaded. Since API 4.
     *
     * @return false, sending nothing, when the server does not take warheads ({@link #canChooseWarhead()}).
     */
    public static boolean requestLaunch(BlockPos silo, int x, int y, int z, boolean piercing) {
        if (!canChooseWarhead()) return false;
        ClientPlayNetworking.send(new AviationPayloads.WarheadLaunchRequest(silo.immutable(), x, y, z, piercing));
        return true;
    }

    /**
     * Asks the server to set the silo's own strike warhead, like {@code /missile silo warhead <silo> pierce|blast}:
     * {@code piercing} true for the tier's piercing warhead, false for its ordinary blast. Operators only, from any
     * distance, for a silo whose chunk is loaded; either mode. There is no {@link LaunchResult} for it: the answer is
     * the action-bar line and a fresh snapshot, whose {@link AviationSnapshot.Silo#piercing()} shows the setting.
     * Since API 4.
     *
     * @return false, sending nothing, when the server does not take warheads ({@link #canChooseWarhead()}).
     */
    public static boolean requestWarhead(BlockPos silo, boolean piercing) {
        if (!canChooseWarhead()) return false;
        ClientPlayNetworking.send(new AviationPayloads.WarheadRequest(silo.immutable(), piercing));
        return true;
    }

    /** The last snapshot received on this connection, or null. */
    public static @Nullable AviationSnapshot latest() {
        return latest;
    }

    /** The last answer to a launch, load or unload request received on this connection, or null. */
    public static @Nullable LaunchResult lastResult() {
        return lastResult;
    }

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** Called once by Simple Planes' client entrypoint. Not for other mods. */
    public static void init() {
        if (initialised) return;
        initialised = true;
        ClientPlayNetworking.registerGlobalReceiver(AviationPayloads.Snapshot.TYPE, (payload, context) -> {
            latest = payload.snapshot();
            for (Listener l : LISTENERS) l.onSnapshot(payload.snapshot());
        });
        ClientPlayNetworking.registerGlobalReceiver(AviationPayloads.LaunchReply.TYPE, (payload, context) -> {
            lastResult = payload.result();
            for (Listener l : LISTENERS) l.onLaunchResult(payload.result());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            latest = null;
            lastResult = null;
        });
    }
}
