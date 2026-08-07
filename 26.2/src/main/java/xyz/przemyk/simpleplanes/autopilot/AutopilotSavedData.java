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
        Airfield.CODEC.listOf().optionalFieldOf("airfields", List.<Airfield>of()).forGetter(AutopilotSavedData::airfieldList)
    ).apply(instance, AutopilotSavedData::new));

    public static final SavedDataType<AutopilotSavedData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "airfields"),
        AutopilotSavedData::new,
        CODEC,
        DataFixTypes.LEVEL);

    private final Map<String, Airfield> airfields = new LinkedHashMap<>();

    public AutopilotSavedData() {
    }

    public AutopilotSavedData(List<Airfield> airfields) {
        for (Airfield airfield : airfields) {
            this.airfields.put(airfield.name(), airfield);
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
