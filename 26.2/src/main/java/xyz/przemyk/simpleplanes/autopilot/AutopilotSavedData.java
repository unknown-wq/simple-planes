package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import xyz.przemyk.simpleplanes.SimplePlanesMod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-dimension persistent store of surveyed airfields. Written to
 * {@code <world>/<dimension>/data/simpleplanes/airfields.dat} by vanilla's
 * {@link net.minecraft.world.level.storage.SavedDataStorage}, so airfields survive a restart.
 *
 * <p>The {@link DataFixTypes} argument is required by {@link SavedDataType} and is only consulted
 * when the stored data version differs from the current one; since this file is always written by
 * the current version, the fixer is a no-op for us.
 */
public class AutopilotSavedData extends SavedData {

    public static final Codec<AutopilotSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Airfield.CODEC.listOf().optionalFieldOf("airfields", List.<Airfield>of()).forGetter(AutopilotSavedData::airfieldList),
        // Beside the runways rather than among them - see Helipad for why a pad is not an airfield.
        // Optional with an empty default, so every world saved before helipads existed loads
        // unchanged and a world with no pads writes no key at all.
        Helipad.CODEC.listOf().optionalFieldOf("helipads", List.<Helipad>of()).forGetter(AutopilotSavedData::helipadList)
    ).apply(instance, AutopilotSavedData::new));

    public static final SavedDataType<AutopilotSavedData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "airfields"),
        AutopilotSavedData::new,
        CODEC,
        DataFixTypes.LEVEL);

    private final Map<String, Airfield> airfields = new LinkedHashMap<>();

    /**
     * Helicopter landing sites, in their own map.
     *
     * <p>A separate namespace as well as a separate list: pads are named {@code helipad-N} and
     * runways {@code airfield-N}, so {@code /autopilot heliflight "airfield-1" …} cannot silently
     * pick up a runway and no command has to disambiguate.
     */
    private final Map<String, Helipad> helipads = new LinkedHashMap<>();

    public AutopilotSavedData() {
    }

    public AutopilotSavedData(List<Airfield> airfields) {
        this(airfields, List.of());
    }

    public AutopilotSavedData(List<Airfield> airfields, List<Helipad> helipads) {
        for (Airfield airfield : airfields) {
            this.airfields.put(airfield.name(), airfield);
        }
        for (Helipad helipad : helipads) {
            this.helipads.put(helipad.name(), helipad);
        }
    }

    public static AutopilotSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<Airfield> airfieldList() {
        return new ArrayList<>(airfields.values());
    }

    public void put(Airfield airfield) {
        airfields.put(airfield.name(), airfield);
        setDirty();
    }

    public Airfield get(String name) {
        return airfields.get(name);
    }

    public boolean remove(String name) {
        boolean removed = airfields.remove(name) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public List<String> names() {
        return new ArrayList<>(airfields.keySet());
    }

    public boolean isEmpty() {
        return airfields.isEmpty();
    }

    // ------------------------------------------------------------------ helipads

    public List<Helipad> helipadList() {
        return new ArrayList<>(helipads.values());
    }

    public void put(Helipad helipad) {
        helipads.put(helipad.name(), helipad);
        setDirty();
    }

    public Helipad helipad(String name) {
        return helipads.get(name);
    }

    public boolean removeHelipad(String name) {
        boolean removed = helipads.remove(name) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public boolean hasHelipads() {
        return !helipads.isEmpty();
    }

    /** Nearest helipad to a point, or null if none is within {@code maxDistance}. */
    public Helipad nearestHelipad(double x, double z, double maxDistance) {
        Helipad best = null;
        double bestDistance = maxDistance * maxDistance;
        for (Helipad pad : helipads.values()) {
            double dx = pad.centre().getX() + 0.5 - x;
            double dz = pad.centre().getZ() + 0.5 - z;
            double distance = dx * dx + dz * dz;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = pad;
            }
        }
        return best;
    }

    /** Nearest airfield to a point, or null if none is within {@code maxDistance}. */
    public Airfield nearest(double x, double z, double maxDistance) {
        Airfield best = null;
        double bestDistance = maxDistance * maxDistance;
        for (Airfield airfield : airfields.values()) {
            double dx = airfield.centre().x - x;
            double dz = airfield.centre().z - z;
            double distance = dx * dx + dz * dz;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = airfield;
            }
        }
        return best;
    }
}
