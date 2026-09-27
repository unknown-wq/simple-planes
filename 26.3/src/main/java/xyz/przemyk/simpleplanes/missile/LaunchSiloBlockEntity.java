package xyz.przemyk.simpleplanes.missile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.airdefence.AirDefenceSilo;
import xyz.przemyk.simpleplanes.airdefence.InterceptorSpec;
import xyz.przemyk.simpleplanes.autopilot.Blast;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * State of one silo: the loaded missile, the hatch, the launch sequence, the cooldown, the target and the mode.
 *
 * <p>Sequence: {@code IDLE -> OPENING -> LAUNCHING -> CLOSING -> COOLDOWN -> IDLE}. The missile entity is
 * created only when the hatch is fully open (the open leaves clear the folded missile by about 0.1 px), rises out
 * of the shaft on its own, and the hatch closes behind it. The client runs the same hatch ramp from the synced
 * phase so the renderer can interpolate it.
 */
public class LaunchSiloBlockEntity extends BlockEntity {

    public enum Phase { IDLE, OPENING, LAUNCHING, CLOSING, COOLDOWN }

    /** {@code MANUAL} is strike (launch to coordinates, saved under its old name); {@code AIR_DEFENCE} engages hostile aircraft. */
    public enum Mode {
        MANUAL("strike"), AIR_DEFENCE("air defence");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    /**
     * The strike warhead the silo fires by default: the tier's ordinary blast, or its piercing warhead (entities only,
     * armour ignored, no block broken; {@link MissileTier#pierceWarhead}). Set with {@code /missile silo warhead} or
     * from the map; a single launch may override it ({@code /missile launch ... pierce|blast}). Air-defence
     * interceptors always carry the ordinary warhead: aircraft are not living entities, so a piercing one would not
     * touch them.
     */
    public enum Warhead {
        BLAST("blast"), PIERCING("piercing");

        public final String label;

        Warhead(String label) {
            this.label = label;
        }

        public boolean pierce() {
            return this == PIERCING;
        }

        public static Warhead of(boolean pierce) {
            return pierce ? PIERCING : BLAST;
        }
    }

    /** Ticks the hatch stays open after ignition before it starts closing. */
    private static final int LAUNCH_HOLD_TICKS = 60;
    /**
     * Game ticks after the launch command by which any sequence has ended (the longest takes about 230). A silo
     * still busy after this was not ticking, e.g. its chunk was unloaded; game time does not advance while frozen.
     */
    static final int STALE_TICKS = 600;

    private int loadedTier;
    private Phase phase = Phase.IDLE;
    private int phaseTicks;
    private int cooldown;
    private float hatch;
    private float hatchO;
    private @Nullable Vec3 target;
    private Mode mode = Mode.MANUAL;
    private Warhead warhead = Warhead.BLAST;
    /** The warhead of the current (or last) strike sequence: the setting, or the launch's own override. */
    private boolean launchPierce;
    private int launches;
    private int lastMissileId = -1;
    private long launchCommandTime;
    /** The current sequence is an air-defence launch (faster hatch, aircraft target, no chunk ticket). */
    private boolean adLaunch;
    private @Nullable UUID adTarget;
    /** What each silo block replaced, so removing the silo puts the ground back. Empty for silos built before this existed. */
    private final Map<BlockPos, BlockState> displaced = new HashMap<>();

    /** One block the silo replaced. */
    public record Displaced(BlockPos pos, BlockState state) {
        static final Codec<Displaced> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockPos.CODEC.fieldOf("pos").forGetter(Displaced::pos),
            BlockState.CODEC.fieldOf("state").forGetter(Displaced::state)
        ).apply(i, Displaced::new));
    }

    public LaunchSiloBlockEntity(BlockPos pos, BlockState state) {
        super(Missiles.LAUNCH_SILO_BE, pos, state);
    }

    public MissileTier tier() {
        BlockState state = getBlockState();
        return MissileTier.of(state.hasProperty(LaunchSiloBlock.TIER) ? state.getValue(LaunchSiloBlock.TIER) : 1);
    }

    public boolean isLoaded() { return loadedTier > 0; }

    /** Ready to fire: a missile is loaded, or the infinite-missiles game rule is on (server side only). */
    public boolean hasMissile() {
        return isLoaded() || (level instanceof ServerLevel server && server.getGameRules().get(Missiles.INFINITE));
    }
    public Phase phase() { return phase; }
    public Mode mode() { return mode; }
    /** The silo's warhead setting for strike launches. */
    public Warhead warhead() { return warhead; }
    /** Whether the current (or last) strike launch carries the piercing warhead. */
    public boolean launchPiercing() { return launchPierce; }
    public float hatch(float partialTicks) { return Mth.lerp(partialTicks, hatchO, hatch); }
    public @Nullable Vec3 target() { return target; }
    public int launches() { return launches; }
    public int lastMissileId() { return lastMissileId; }
    public int cooldown() { return cooldown; }
    public boolean isAirDefenceLaunch() { return adLaunch; }
    public @Nullable UUID airDefenceTarget() { return adTarget; }

    /** Switches strike / air defence, or says why not. */
    public @Nullable String setMode(Mode next) {
        if (next == mode) return null;
        recoverIfStale();
        if (phase == Phase.OPENING || phase == Phase.LAUNCHING) return busy();
        mode = next;
        sync();
        return null;
    }

    /** The aircraft went away while the hatch opened and another was chosen. */
    public void retarget(UUID aircraft) {
        adTarget = aircraft;
        setChanged();
    }

    /**
     * Sets the strike warhead the silo fires by default. Allowed in any phase and in either mode: a launch already under
     * way keeps the warhead it was started with, and an air-defence silo keeps it for when it is back in strike mode.
     */
    public void setWarhead(Warhead next) {
        if (next == warhead) return;
        warhead = next;
        sync();
    }

    public @Nullable String toggleMode() {
        return setMode(mode == Mode.AIR_DEFENCE ? Mode.MANUAL : Mode.AIR_DEFENCE);
    }

    public Map<BlockPos, BlockState> displaced() {
        return Map.copyOf(displaced);
    }

    void recordDisplaced(Map<BlockPos, BlockState> states) {
        displaced.putAll(states);
        setChanged();
    }

    /** Takes over the persistent state of the silo this one replaced when an upgrade moved the master block. */
    void adopt(LaunchSiloBlockEntity old) {
        mode = old.mode;
        warhead = old.warhead;
        launches = old.launches;
        lastMissileId = old.lastMissileId;
        target = old.target;
        displaced.putAll(old.displaced);
        sync();
    }

    /** The stowed missile is drawn by the block entity renderer until the missile entity exists. */
    public boolean showsStowedMissile() {
        return isLoaded() && phase != Phase.LAUNCHING;
    }

    public @Nullable String load() {
        if (isLoaded()) return "already loaded";
        recoverIfStale();
        if (phase != Phase.IDLE && phase != Phase.COOLDOWN) return busy();
        loadedTier = tier().tier;
        sync();
        return null;
    }

    public @Nullable String unload() {
        if (!isLoaded()) return "not loaded";
        recoverIfStale();
        if (phase != Phase.IDLE && phase != Phase.COOLDOWN) return busy();
        loadedTier = 0;
        sync();
        return null;
    }

    /** Starts the launch sequence with the silo's own warhead setting, or says why not. */
    public @Nullable String launch(ServerLevel level, Vec3 aim) {
        return launch(level, aim, null);
    }

    /**
     * Starts the launch sequence, or says why not.
     *
     * @param pierce the warhead of this one launch: true piercing, false the ordinary blast, null the silo's
     *               {@link #warhead() setting}. The setting itself is not changed.
     */
    public @Nullable String launch(ServerLevel level, Vec3 aim, @Nullable Boolean pierce) {
        MissileTier tier = tier();
        if (mode == Mode.AIR_DEFENCE) return "the silo is in air-defence mode";
        String problem = readiness(level, tier);
        if (problem != null) return problem;
        boolean piercing = pierce != null ? pierce : warhead.pierce();
        String outOfRange = rangeProblem(worldPosition, tier, aim, piercing);
        if (outOfRange != null) return outOfRange;
        if (aim.y < level.getMinY() || aim.y > level.getMaxY()) return "target is outside the world's height range";
        target = aim;
        launchPierce = piercing;
        adLaunch = false;
        adTarget = null;
        phase = Phase.OPENING;
        phaseTicks = 0;
        launchCommandTime = level.getGameTime();
        MissileTracker.holdSilo(level, worldPosition);
        level.playSound(null, worldPosition, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 1.0F, 0.6F);
        sync();
        return null;
    }

    /** Why {@code aim} is outside the horizontal range of a tier {@code tier} silo at {@code master}; null when inside. */
    public static @Nullable String rangeProblem(BlockPos master, MissileTier tier, Vec3 aim) {
        return rangeProblem(master, tier, aim, false);
    }

    /**
     * Why {@code aim} is outside the horizontal range of a tier {@code tier} silo at {@code master} for this warhead;
     * null when inside. A piercing launch has a longer minimum, {@link MissileTier#pierceMinRange}: the silo must be
     * outside its own missile's radius.
     */
    public static @Nullable String rangeProblem(BlockPos master, MissileTier tier, Vec3 aim, boolean pierce) {
        Vec3 mouth = SiloStructure.mouth(master, tier);
        double range = Math.hypot(aim.x - mouth.x, aim.z - mouth.z);
        double min = tier.minRange(pierce);
        if (range >= min && range <= tier.maxRange) return null;
        // a target on or next to the silo is usually the looked-at block that Tab filled in
        String hint = range < 8.0 ? " (the target is the silo itself or right next to it; Tab fills in the block you look at, so type the target's x y z)"
            : pierce && range < min ? " (the piercing warhead's radius; the silo must be outside it)" : "";
        return String.format(Locale.ROOT, "target too %s: %.1f blocks from the silo; a tier %d missile%s needs at least %.0f and at most %.0f blocks horizontally%s",
            range < min ? "close" : "far", range, tier.tier, pierce ? " with the piercing warhead" : "", min, tier.maxRange, hint);
    }

    /** Idle, loaded, intact and a clear hatch: the checks every launch shares. */
    public @Nullable String readiness(ServerLevel level, MissileTier tier) {
        recoverIfStale();
        if (phase != Phase.IDLE) return busy();
        if (!hasMissile()) return "no missile loaded";
        if (!SiloStructure.isIntact(level, worldPosition, tier)) return "the silo structure is damaged";
        for (int k = 1; k <= tier.tier + 3; k++)
            for (int dx = 0; dx < tier.footprint; dx++)
                for (int dz = 0; dz < tier.footprint; dz++) {
                    BlockPos p = worldPosition.offset(dx, k, dz);
                    if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty())
                        return "the hatch is obstructed at " + p.toShortString();
                }
        return null;
    }

    /** True while {@code /tick freeze} stops block entities, and with them every hatch, from ticking. */
    public static boolean frozen(Level level) {
        return !level.tickRateManager().runsNormally();
    }

    private String busy() {
        String busy = "busy (" + phase.name().toLowerCase(Locale.ROOT) + ")";
        return level != null && frozen(level)
            ? busy + "; the game is frozen (/tick freeze), so the hatch won't move until /tick unfreeze" : busy;
    }

    /**
     * Ends a sequence that has lasted {@link #STALE_TICKS} of game time, which only a silo that was not ticking
     * can do. Before ignition it aborts and keeps the missile; after it, it just finishes. Returns what it did.
     */
    public @Nullable String recoverIfStale() {
        if (phase == Phase.IDLE || !(level instanceof ServerLevel server)) return null;
        long age = server.getGameTime() - launchCommandTime;
        if (age >= 0 && age <= STALE_TICKS) return null;
        return reset(server, "stale after " + age + " game ticks");
    }

    /** Puts the silo back to idle with the hatch shut; a missile not yet fired stays loaded. Returns what it did. */
    public String reset(ServerLevel level, String why) {
        String was = describe();
        boolean aborted = phase == Phase.OPENING;
        if (adLaunch) AirDefenceSilo.release(level, worldPosition);
        MissileTracker.releaseSilo(level, worldPosition);
        adTarget = null;
        hatch = hatchO = 0.0F;
        cooldown = 0;
        setPhase(Phase.IDLE);
        String did = (aborted ? "launch aborted, missile kept" : "sequence finished") + " (" + why + "); was: " + was;
        MissileTracker.LOGGER.info("[silo] {} reset: {}", worldPosition.toShortString(), did);
        return did;
    }

    /** Starts an air-defence launch at {@code aircraft}. No chunk ticket: the sequence runs only while the chunk is loaded. */
    public @Nullable String launchAirDefence(ServerLevel level, PlaneEntity aircraft) {
        MissileTier tier = tier();
        if (mode != Mode.AIR_DEFENCE) return "the silo is in strike mode";
        String problem = readiness(level, tier);
        if (problem != null) return problem;
        target = aircraft.getBoundingBox().getCenter();
        adLaunch = true;
        adTarget = aircraft.getUUID();
        phase = Phase.OPENING;
        phaseTicks = 0;
        launchCommandTime = level.getGameTime();
        AirDefenceSilo.claim(level, worldPosition, aircraft, InterceptorSpec.of(tier));
        level.playSound(null, worldPosition, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 1.0F, 0.9F);
        sync();
        return null;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, LaunchSiloBlockEntity be) {
        if (level instanceof ServerLevel server) be.tickServer(server);
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, LaunchSiloBlockEntity be) {
        be.hatchO = be.hatch;
        be.hatch = stepHatch(be.phase, be.hatch, be.openTicks(be.tier()));
    }

    private int openTicks(MissileTier tier) {
        return adLaunch ? InterceptorSpec.of(tier).hatchTicks : tier.hatchTicks;
    }

    private static float stepHatch(Phase phase, float hatch, int openTicks) {
        return switch (phase) {
            case OPENING, LAUNCHING -> Math.min(1.0F, hatch + 1.0F / openTicks);
            case CLOSING, COOLDOWN, IDLE -> Math.max(0.0F, hatch - 1.0F / MissileTier.HATCH_CLOSE_TICKS);
        };
    }

    private void tickServer(ServerLevel level) {
        if (recoverIfStale() != null) return;
        // the hold is in memory only: take it again after a restart or a reload mid-sequence
        if (phase != Phase.IDLE && !adLaunch && !MissileTracker.holdsSilo(level, worldPosition)) MissileTracker.holdSilo(level, worldPosition);
        hatchO = hatch;
        MissileTier tier = tier();
        Vec3 mouth = SiloStructure.mouth(worldPosition, tier);
        switch (phase) {
            case IDLE -> {
                if (mode == Mode.AIR_DEFENCE) AirDefenceSilo.idleTick(level, this);
                return;
            }
            case OPENING -> {
                if (adLaunch && !AirDefenceSilo.keepTarget(level, this)) {
                    adTarget = null;
                    setPhase(Phase.CLOSING);
                    return;
                }
                hatch = stepHatch(phase, hatch, openTicks(tier));
                if (hatch >= 1.0F) {
                    ignite(level, tier);
                    return;
                }
                if (phaseTicks % 4 == 0) MissileFx.vent(level, mouth, tier, 0.3);
            }
            case LAUNCHING -> {
                double strength = phaseTicks < 30 ? 1.0 : 1.0 - (phaseTicks - 30) / (double) (LAUNCH_HOLD_TICKS - 30);
                MissileFx.vent(level, mouth, tier, strength);
                if (phaseTicks >= LAUNCH_HOLD_TICKS) setPhase(Phase.CLOSING);
            }
            case CLOSING -> {
                hatch = stepHatch(phase, hatch, openTicks(tier));
                if (hatch <= 0.0F) {
                    cooldown = MissileTier.COOLDOWN_TICKS;
                    level.playSound(null, worldPosition, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 1.0F, 0.6F);
                    setPhase(Phase.COOLDOWN);
                }
            }
            case COOLDOWN -> {
                if (--cooldown <= 0) {
                    cooldown = 0;
                    MissileTracker.releaseSilo(level, worldPosition);
                    setPhase(Phase.IDLE);
                }
            }
        }
        phaseTicks++;
    }

    private void ignite(ServerLevel level, MissileTier tier) {
        hatch = 1.0F;
        Vec3 aim = target;
        // infinite_missiles: a loaded missile stays loaded
        if (!level.getGameRules().get(Missiles.INFINITE)) loadedTier = 0;
        setPhase(Phase.LAUNCHING);
        if (aim == null) return;
        MissileEntity missile;
        if (adLaunch) {
            PlaneEntity aircraft = AirDefenceSilo.resolve(level, adTarget);
            if (aircraft == null) {
                AirDefenceSilo.release(level, worldPosition);
                return;
            }
            missile = MissileEntity.launchInterceptor(level, worldPosition, tier, aircraft, launchCommandTime);
            // the missile claimed the aircraft on creation; the silo's claim ends in the same tick
            AirDefenceSilo.handOver(level, worldPosition, missile);
        } else {
            missile = MissileEntity.launch(level, worldPosition, tier, aim, launchCommandTime, launchPierce);
        }
        lastMissileId = missile.getId();
        launches++;
        level.playSound(null, worldPosition, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.BLOCKS, 4.0F, 0.5F);
        MissileFx.vent(level, SiloStructure.mouth(worldPosition, tier), tier, 1.5);
    }

    private void setPhase(Phase next) {
        phase = next;
        phaseTicks = 0;
        sync();
    }

    private void sync() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel server) {
            MissileTracker.releaseSilo(server, pos);
            SiloStructure.stashRecord(server, pos, displaced);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("loaded_tier", loadedTier);
        output.putString("phase", phase.name());
        output.putInt("phase_ticks", phaseTicks);
        output.putInt("cooldown", cooldown);
        output.putFloat("hatch", hatch);
        output.putString("mode", mode.name());
        output.putString("warhead", warhead.name());
        output.putBoolean("launch_pierce", launchPierce);
        output.putBoolean("ad_launch", adLaunch);
        if (adTarget != null) output.putString("ad_target", adTarget.toString());
        output.putInt("launches", launches);
        output.putInt("last_missile", lastMissileId);
        output.putLong("launch_time", launchCommandTime);
        if (target != null) {
            output.putBoolean("has_target", true);
            output.putDouble("tx", target.x);
            output.putDouble("ty", target.y);
            output.putDouble("tz", target.z);
        }
        List<Displaced> list = new ArrayList<>();
        displaced.forEach((pos, state) -> list.add(new Displaced(pos, state)));
        output.store("displaced", Displaced.CODEC.listOf(), list);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        loadedTier = input.getIntOr("loaded_tier", 0);
        phase = parse(Phase.class, input.getStringOr("phase", "IDLE"), Phase.IDLE);
        phaseTicks = input.getIntOr("phase_ticks", 0);
        cooldown = input.getIntOr("cooldown", 0);
        float h = input.getFloatOr("hatch", 0.0F);
        if (level == null || !level.isClientSide()) hatch = hatchO = h;
        mode = parse(Mode.class, input.getStringOr("mode", "MANUAL"), Mode.MANUAL);
        // absent in silos saved before the setting existed: the ordinary blast, as before
        warhead = parse(Warhead.class, input.getStringOr("warhead", "BLAST"), Warhead.BLAST);
        launchPierce = input.getBooleanOr("launch_pierce", false);
        adLaunch = input.getBooleanOr("ad_launch", false);
        adTarget = input.getString("ad_target").map(LaunchSiloBlockEntity::uuid).orElse(null);
        launches = input.getIntOr("launches", 0);
        lastMissileId = input.getIntOr("last_missile", -1);
        launchCommandTime = input.getLongOr("launch_time", 0L);
        target = input.getBooleanOr("has_target", false)
            ? new Vec3(input.getDoubleOr("tx", 0), input.getDoubleOr("ty", 0), input.getDoubleOr("tz", 0)) : null;
        displaced.clear();
        input.read("displaced", Displaced.CODEC.listOf()).ifPresent(list -> list.forEach(d -> displaced.put(d.pos(), d.state())));
    }

    private static @Nullable UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveCustomOnly(registries);
        tag.remove("displaced");
        return tag;
    }

    /**
     * The warhead setting for status lines: {@code blast 16.0, blocks, fire} or {@code piercing 64.0 (radius 128,
     * entities only)}, and {@code , air defence uses the ordinary blast} in air-defence mode when piercing is set.
     */
    public String warheadLine() {
        MissileTier tier = tier();
        Blast b = tier.strikeWarhead(warhead.pierce());
        String line = warhead.pierce()
            ? String.format(Locale.ROOT, "piercing %.1f (radius %.0f, entities only)", b.power(), tier.pierceRadius())
            : String.format(Locale.ROOT, "blast %.1f%s%s", b.power(), b.breaksBlocks() ? ", blocks" : "", b.fire() ? ", fire" : "");
        return line + (warhead.pierce() && mode == Mode.AIR_DEFENCE ? "; interceptors keep the ordinary blast" : "");
    }

    public String describe() {
        MissileTier tier = tier();
        return String.format(Locale.ROOT, "silo T%d at %s: %s, %s, hatch %.2f, mode %s%s, warhead %s%s, launches %d, last missile #%d%s",
            tier.tier, worldPosition.toShortString(), isLoaded() ? "loaded" : "empty",
            phase.name().toLowerCase(Locale.ROOT), hatch, mode.label + (adLaunch && adTarget != null && phase == Phase.OPENING ? " (engaging)" : "")
                + (level instanceof ServerLevel sl ? AirDefenceSilo.holding(sl, worldPosition) : ""),
            phase == Phase.COOLDOWN ? ", cooldown " + cooldown + "t" : "", warheadLine(),
            phase != Phase.IDLE && !adLaunch && launchPierce != warhead.pierce()
                ? " (this launch: " + Warhead.of(launchPierce).label + ")" : "",
            launches, lastMissileId,
            target == null ? "" : String.format(Locale.ROOT, ", target %.1f %.1f %.1f", target.x, target.y, target.z))
            + (phase != Phase.IDLE && level != null && frozen(level) ? ", GAME FROZEN (/tick freeze): nothing moves until /tick unfreeze" : "");
    }
}
