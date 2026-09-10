package xyz.przemyk.simpleplanes.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.network.AirfieldMarkersPacket;
import xyz.przemyk.simpleplanes.network.AirfieldMarkersPacket.Pad;
import xyz.przemyk.simpleplanes.network.AirfieldMarkersPacket.Runway;

import java.util.List;

/**
 * The registered fields around this client, as last sent by {@code AirfieldMarkerSync}.
 *
 * <p>A plain replace-the-whole-thing cache, because that is exactly what arrives: the server sends
 * the complete set of fields near the player whenever it differs from the set the player already
 * has, so there is no merging to do and no way for the cache to drift. It is cleared on disconnect —
 * markers from the last world drawn in the next one would be worse than none.
 *
 * <p>Read from both the client thread (the tool preview) and the render thread (the overlay), so the
 * two lists are swapped as immutable snapshots and never mutated in place.
 *
 * <p>Alongside them is a {@link #generation} counter, which is how the overlay knows its geometry is
 * stale without comparing lists it would have to walk anyway. What arrives at most once a second
 * used to be turned into vertices sixty or two hundred times a second; the counter changes exactly
 * when the content does, so the renderer can build once per payload instead.
 */
@Environment(EnvType.CLIENT)
public final class AirfieldMarkers {

    private AirfieldMarkers() {}

    private static volatile List<Runway> runways = List.of();
    private static volatile List<Pad> pads = List.of();

    /**
     * Bumped whenever the two lists are replaced, so a reader that has built something out of them
     * can tell in one comparison whether it still holds.
     *
     * <p>Incremented without an atomic, which is safe for exactly one reason: both writers are the
     * client thread — the packet receiver and the disconnect handler — so there is only ever one.
     * It is volatile for the readers, which are not.
     *
     * <p>Written last, after the lists it describes, so a reader that sees a new number is certain
     * to see the payload that goes with it. Seeing an old number with new lists is possible and
     * costs one more frame drawn from the previous payload.
     */
    private static volatile int generation;

    public static void accept(AirfieldMarkersPacket payload) {
        runways = List.copyOf(payload.runways());
        pads = List.copyOf(payload.pads());
        generation++;
    }

    public static void clear() {
        runways = List.of();
        pads = List.of();
        generation++;
    }

    /** Which version of the fields {@link #runways()} and {@link #pads()} are presently holding. */
    public static int generation() {
        return generation;
    }

    public static List<Runway> runways() {
        return runways;
    }

    public static List<Pad> pads() {
        return pads;
    }

    /**
     * The nearest known runway to a point, or null if none is within {@code maxDistance}.
     *
     * <p>Measured to the runway centre and matching {@code AutopilotSavedData#nearest}, which is how
     * the runway tool decides which airfield a parking click was meant for: the preview has to pick
     * the same one the click will, or it shades the ground against the wrong runway's rules.
     */
    public static @Nullable Runway nearest(double x, double z, double maxDistance) {
        Runway best = null;
        double bestDistance = maxDistance * maxDistance;
        for (Runway runway : runways) {
            double dx = (runway.thresholdA().getX() + runway.thresholdB().getX()) / 2.0 + 0.5 - x;
            double dz = (runway.thresholdA().getZ() + runway.thresholdB().getZ()) / 2.0 + 0.5 - z;
            double distance = dx * dx + dz * dz;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = runway;
            }
        }
        return best;
    }
}
