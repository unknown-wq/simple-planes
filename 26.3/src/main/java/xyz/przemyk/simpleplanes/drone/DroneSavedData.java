package xyz.przemyk.simpleplanes.drone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.SimplePlanesMod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-dimension roster of patrol drones that exist: where each was last seen and whether it was airborne.
 * Written to {@code data/simpleplanes/patrol_drones.dat}. It is what lets an airborne drone be brought back
 * after a restart (its chunk is ticketed from here, since ender-pearl tickets are not saved), and what lets a
 * controller tell "unloaded" from "gone".
 */
public class DroneSavedData extends SavedData {

    /** One drone. */
    public record Entry(UUID id, Vec3 pos, boolean airborne, String controller) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Entry::id),
            Vec3.CODEC.fieldOf("pos").forGetter(Entry::pos),
            Codec.BOOL.optionalFieldOf("airborne", false).forGetter(Entry::airborne),
            Codec.STRING.optionalFieldOf("controller", "").forGetter(Entry::controller)
        ).apply(i, Entry::new));
    }

    public static final Codec<DroneSavedData> CODEC = RecordCodecBuilder.create(i -> i.group(
        Entry.CODEC.listOf().optionalFieldOf("drones", List.of()).forGetter(DroneSavedData::list)
    ).apply(i, DroneSavedData::new));

    public static final SavedDataType<DroneSavedData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "patrol_drones"),
        DroneSavedData::new,
        CODEC,
        DataFixTypes.LEVEL);

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public DroneSavedData() {
    }

    public DroneSavedData(List<Entry> list) {
        for (Entry e : list) {
            entries.put(e.id(), e);
        }
    }

    public static DroneSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    private List<Entry> list() {
        return new ArrayList<>(entries.values());
    }

    public Map<UUID, Entry> entries() {
        return entries;
    }

    public Entry get(UUID id) {
        return entries.get(id);
    }

    public void put(Entry entry) {
        Entry old = entries.put(entry.id(), entry);
        if (!entry.equals(old)) {
            setDirty();
        }
    }

    public void remove(UUID id) {
        if (entries.remove(id) != null) {
            setDirty();
        }
    }
}
