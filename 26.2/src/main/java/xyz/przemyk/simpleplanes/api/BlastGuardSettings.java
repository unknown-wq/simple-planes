package xyz.przemyk.simpleplanes.api;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import xyz.przemyk.simpleplanes.SimplePlanesMod;

/**
 * Whether {@link BlastGuards} is consulted at all, and the world file that remembers the answer.
 *
 * <h2>What "off" means</h2>
 * Exactly one thing: {@link BlastGuards#filter} hands back the blast it was given without running a
 * single guard. An aircraft that blows up therefore blows up with the power, the block interaction
 * and the fire flag its own flight plan asked for — which is what this mod did before guards were
 * added, unchanged to the last field. Nothing is half-suppressed, no guard gets a "reduced" say, and
 * a guard cannot detect the switch and behave differently: it simply is not called. Registrations
 * survive the switch, so turning it back on restores the previous behaviour without a restart.
 *
 * <h2>Why the default is on</h2>
 * Because with nothing registered the default cannot be felt. A guard only exists if the server owner
 * installed a mod that adds one, and {@code filter} returns immediately when the list is empty — so
 * for an installation of this mod on its own, "on" and "off" are the same server, byte for byte, and
 * no explosion anywhere behaves differently. The default therefore only ever takes effect for someone
 * who deliberately added something that protects ground from blasts, and for that person "on" is the
 * thing they were asking for when they installed it. Defaulting to off would mean their protection
 * silently did nothing until they found a second command to type.
 *
 * <h2>Why one setting for the server and not one per dimension</h2>
 * {@link SavedData} is a per-level facility, so this is stored on the overworld and read from there
 * whatever dimension the blast is in. That is deliberate rather than a limitation of the storage: a
 * person who turns blast guarding off has made a decision about their server, and a switch that left
 * it on in the Nether would be a bug report, not a feature. The file is
 * {@code <world>/data/simpleplanes/blast_guard.dat}, beside the autopilot's own store.
 *
 * <h2>Cost</h2>
 * One map lookup in the overworld's {@code SavedDataStorage} per explosion, and only on servers that
 * have a guard registered. Explosions are rare events driven by an aircraft being destroyed, so this
 * is not worth caching in a static field — a cache would have to be invalidated on world unload and
 * would be a staleness bug waiting for the second world.
 */
public final class BlastGuardSettings {

    /** The default, and the reason for it, are in the class note. */
    public static final boolean DEFAULT_ENABLED = true;

    private static final Codec<Data> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        // Optional with the default, so a world saved before this setting existed loads as "on"
        // rather than as "off", and a server that never touched the switch writes nothing surprising.
        Codec.BOOL.optionalFieldOf("enabled", DEFAULT_ENABLED).forGetter(data -> data.enabled)
    ).apply(instance, Data::new));

    private static final SavedDataType<Data> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "blast_guard"),
        () -> new Data(DEFAULT_ENABLED),
        CODEC,
        DataFixTypes.LEVEL);

    private BlastGuardSettings() {}

    /**
     * Whether guards should be consulted on this server.
     *
     * @param level any server level; the setting is read from the overworld whichever is passed.
     * @return true if {@link BlastGuards#filter} should run its chain.
     */
    public static boolean isEnabled(ServerLevel level) {
        ServerLevel store = storage(level);
        return store == null || store.getDataStorage().computeIfAbsent(TYPE).enabled;
    }

    /**
     * Turns guarding on or off for this server and writes it to the world.
     *
     * @param level   any server level.
     * @param enabled true to consult guards, false to ignore them entirely.
     * @return true if this changed anything, so a command can say "already off" rather than lying.
     */
    public static boolean setEnabled(ServerLevel level, boolean enabled) {
        ServerLevel store = storage(level);
        if (store == null) {
            return false;
        }
        Data data = store.getDataStorage().computeIfAbsent(TYPE);
        if (data.enabled == enabled) {
            return false;
        }
        data.enabled = enabled;
        data.setDirty();
        return true;
    }

    /**
     * The level the setting lives on — the overworld, so every dimension gets the same answer.
     *
     * <p>Falls back to the level it was handed if the server cannot be reached or has no overworld,
     * which should not happen on a running server but is not worth a crash inside an explosion if it
     * does.
     */
    private static ServerLevel storage(ServerLevel level) {
        if (level == null) {
            return null;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return level;
        }
        ServerLevel overworld = server.overworld();
        return overworld != null ? overworld : level;
    }

    /** The stored bit. Mutable because {@link SavedData} is written in place and then marked dirty. */
    private static final class Data extends SavedData {

        private boolean enabled;

        private Data(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
