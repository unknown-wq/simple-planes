package xyz.przemyk.simpleplanes.aviation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.missile.LaunchSiloBlockEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-dimension index of launch silos, so silos in unloaded chunks can be shown on the map. Written to
 * {@code <world>/<dimension>/data/simpleplanes/silos.dat}.
 *
 * <p>The index is a cache of the block entities, never the authority: a launch always goes through the live
 * {@link LaunchSiloBlockEntity}. It is kept up to date by {@link AviationService} from Fabric events (block
 * entity load, chunk load/unload) and a once-a-second sweep over the entries whose chunk is loaded, which
 * also drops entries whose silo is gone (self-healing). No hook in the silo classes is needed.
 */
public final class SiloIndex extends SavedData {

    /** One silo as last seen. {@code pos} is the master block. */
    public record Entry(BlockPos pos, int tier, boolean strike, boolean loaded, long seen) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
            Codec.INT.fieldOf("tier").forGetter(Entry::tier),
            Codec.BOOL.optionalFieldOf("strike", true).forGetter(Entry::strike),
            Codec.BOOL.optionalFieldOf("loaded", false).forGetter(Entry::loaded),
            Codec.LONG.optionalFieldOf("seen", 0L).forGetter(Entry::seen)
        ).apply(i, Entry::new));

        boolean sameState(Entry other) {
            return tier == other.tier && strike == other.strike && loaded == other.loaded;
        }
    }

    public static final Codec<SiloIndex> CODEC = RecordCodecBuilder.create(i -> i.group(
        Entry.CODEC.listOf().optionalFieldOf("silos", List.of()).forGetter(SiloIndex::entries)
    ).apply(i, SiloIndex::new));

    public static final SavedDataType<SiloIndex> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "silos"), SiloIndex::new, CODEC, DataFixTypes.LEVEL);

    private final Map<BlockPos, Entry> silos = new LinkedHashMap<>();

    public SiloIndex() {}

    private SiloIndex(List<Entry> entries) {
        for (Entry e : entries) silos.put(e.pos().immutable(), e);
    }

    /** The index for a level, created on first use. */
    public static SiloIndex get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /** The index for a level, or null if nothing was ever indexed there (does not create the file). */
    public static @Nullable SiloIndex peek(ServerLevel level) {
        return level.getDataStorage().get(TYPE);
    }

    public List<Entry> entries() {
        return new ArrayList<>(silos.values());
    }

    public Collection<Entry> view() {
        return java.util.Collections.unmodifiableCollection(silos.values());
    }

    public @Nullable Entry get(BlockPos pos) {
        return silos.get(pos);
    }

    public int size() {
        return silos.size();
    }

    /** Records the silo's current state. Marks the file dirty only when something persistent changed. */
    public void update(LaunchSiloBlockEntity be, long now) {
        BlockPos pos = be.getBlockPos().immutable();
        Entry next = new Entry(pos, be.tier().tier, be.mode() == LaunchSiloBlockEntity.Mode.MANUAL, be.isLoaded(), now);
        Entry previous = silos.put(pos, next);
        if (previous == null || !previous.sameState(next)) setDirty();
    }

    public boolean remove(BlockPos pos) {
        boolean removed = silos.remove(pos) != null;
        if (removed) setDirty();
        return removed;
    }

    /** Entries in one chunk, copied. */
    public List<Entry> inChunk(ChunkPos chunk) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : silos.values()) {
            if ((e.pos().getX() >> 4) == chunk.x() && (e.pos().getZ() >> 4) == chunk.z()) out.add(e);
        }
        return out;
    }
}
