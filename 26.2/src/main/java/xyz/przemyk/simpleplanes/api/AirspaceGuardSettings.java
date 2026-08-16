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
 * Whether {@link AirspaceGuards} is consulted at all, and the world file that remembers the answer.
 *
 * <h2>What "off" means</h2>
 * Exactly one thing: {@link AirspaceGuards#isActive} answers false, so
 * {@link xyz.przemyk.simpleplanes.autopilot.RoutePlanner} runs the terrain-only search it ran before
 * this feature existed and no guard is called. Nothing is half-applied, no guard gets a "reduced"
 * say, and a guard cannot detect the switch and behave differently: it simply is not asked.
 * Registrations survive the switch, so turning it back on restores the previous behaviour without a
 * restart — and without disturbing an aircraft that is already in the air, because the planner
 * re-decides from scratch on its next interval.
 *
 * <h2>Why the default is on</h2>
 * The same argument as {@link BlastGuardSettings}, and it holds a little harder here. With nothing
 * registered the default cannot be felt at all: the autopilot's gate is
 * {@link AirspaceGuards#isActive}, which short-circuits on the empty list before it ever reads this,
 * so for an installation of this mod on its own "on" and "off" are the same server and no aircraft
 * anywhere flies a different track. The default therefore only ever takes effect for someone who
 * deliberately installed a mod that claims airspace, and for that person "on" is the thing they were
 * asking for. Defaulting to off would mean their claim silently did nothing until they found a
 * second command to type.
 *
 * <h2>Why one setting for the server and not one per dimension</h2>
 * {@link SavedData} is a per-level facility, so this is stored on the overworld and read from there
 * whatever dimension the flight is in. A person who turns airspace avoidance off has made a decision
 * about their server, and a switch that left it on in the Nether would be a bug report rather than a
 * feature. The file is {@code <world>/data/simpleplanes/airspace_guard.dat}, beside the blast
 * guard's own store and the autopilot's.
 *
 * <h2>Cost</h2>
 * One map lookup in the overworld's {@code SavedDataStorage} per read, and it is read <b>once per
 * autopilot tick per aircraft</b> — not once per route search. That is deliberate and it is the
 * price of "off means off": the switch is half of the gate in
 * {@code PlaneAutopilot#applyTerrainFollowing} that decides whether the route planner is entered at
 * all over ordinary ground, so an aircraft has to know the answer on every tick, not only on the
 * ticks it happens to re-plan. Testing it once a second instead would mean a server with the switch
 * off still paying for a search a second per aircraft that it did not pay for before the feature
 * existed, which is not off.
 * <p>
 * It is only ever read on a server that has a guard registered, because {@link AirspaceGuards#isActive}
 * short-circuits on the empty list first. At the 24-aircraft autopilot cap that is 24 hash lookups a
 * tick, which measured as noise against the tick — see the flight measurements in the branch that
 * added this. Not worth caching in a static field either way: a cache would have to be invalidated
 * on world unload and would be a staleness bug waiting for the second world.
 */
public final class AirspaceGuardSettings {

    /** The default, and the reason for it, are in the class note. */
    public static final boolean DEFAULT_ENABLED = true;

    private static final Codec<Data> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        // Optional with the default, so a world saved before this setting existed loads as "on"
        // rather than as "off", and a server that never touched the switch writes nothing surprising.
        Codec.BOOL.optionalFieldOf("enabled", DEFAULT_ENABLED).forGetter(data -> data.enabled)
    ).apply(instance, Data::new));

    private static final SavedDataType<Data> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "airspace_guard"),
        () -> new Data(DEFAULT_ENABLED),
        CODEC,
        DataFixTypes.LEVEL);

    private AirspaceGuardSettings() {}

    /**
     * Whether guards should be consulted on this server.
     *
     * @param level any server level; the setting is read from the overworld whichever is passed.
     * @return true if the route planner should ask about airspace.
     */
    public static boolean isEnabled(ServerLevel level) {
        ServerLevel store = storage(level);
        return store == null || store.getDataStorage().computeIfAbsent(TYPE).enabled;
    }

    /**
     * Turns airspace avoidance on or off for this server and writes it to the world.
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
     * which should not happen on a running server but is not worth a crash inside a flight if it
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
