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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    /** Only manual launches exist so far; the field is persisted so other modes can be added without a format change. */
    public enum Mode { MANUAL }

    /** Ticks the hatch stays open after ignition before it starts closing. */
    private static final int LAUNCH_HOLD_TICKS = 60;

    private int loadedTier;
    private Phase phase = Phase.IDLE;
    private int phaseTicks;
    private int cooldown;
    private float hatch;
    private float hatchO;
    private @Nullable Vec3 target;
    private Mode mode = Mode.MANUAL;
    private int launches;
    private int lastMissileId = -1;
    private long launchCommandTime;
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
    public Phase phase() { return phase; }
    public Mode mode() { return mode; }
    public float hatch(float partialTicks) { return Mth.lerp(partialTicks, hatchO, hatch); }
    public @Nullable Vec3 target() { return target; }
    public int launches() { return launches; }
    public int lastMissileId() { return lastMissileId; }
    public int cooldown() { return cooldown; }

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
        if (phase != Phase.IDLE && phase != Phase.COOLDOWN) return "busy (" + phase.name().toLowerCase(Locale.ROOT) + ")";
        loadedTier = tier().tier;
        sync();
        return null;
    }

    public @Nullable String unload() {
        if (!isLoaded()) return "not loaded";
        if (phase != Phase.IDLE && phase != Phase.COOLDOWN) return "busy (" + phase.name().toLowerCase(Locale.ROOT) + ")";
        loadedTier = 0;
        sync();
        return null;
    }

    /** Starts the launch sequence, or says why not. */
    public @Nullable String launch(ServerLevel level, Vec3 aim) {
        MissileTier tier = tier();
        if (phase != Phase.IDLE) return "busy (" + phase.name().toLowerCase(Locale.ROOT) + ")";
        if (!isLoaded()) return "no missile loaded";
        if (!SiloStructure.isIntact(level, worldPosition, tier)) return "the silo structure is damaged";
        Vec3 mouth = SiloStructure.mouth(worldPosition, tier);
        double range = Math.hypot(aim.x - mouth.x, aim.z - mouth.z);
        if (range < tier.minRange)
            return String.format(Locale.ROOT, "target is %.1f blocks away, inside the tier %d minimum range of %.0f", range, tier.tier, tier.minRange);
        if (range > tier.maxRange)
            return String.format(Locale.ROOT, "target is %.1f blocks away, beyond the tier %d range of %.0f", range, tier.tier, tier.maxRange);
        if (aim.y < level.getMinY() || aim.y > level.getMaxY()) return "target is outside the world's height range";
        for (int k = 1; k <= tier.tier + 3; k++)
            for (int dx = 0; dx < tier.footprint; dx++)
                for (int dz = 0; dz < tier.footprint; dz++) {
                    BlockPos p = worldPosition.offset(dx, k, dz);
                    if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty())
                        return "the hatch is obstructed at " + p.toShortString();
                }
        target = aim;
        phase = Phase.OPENING;
        phaseTicks = 0;
        launchCommandTime = level.getGameTime();
        MissileTracker.holdSilo(level, worldPosition);
        level.playSound(null, worldPosition, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 1.0F, 0.6F);
        sync();
        return null;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, LaunchSiloBlockEntity be) {
        if (level instanceof ServerLevel server) be.tickServer(server);
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, LaunchSiloBlockEntity be) {
        be.hatchO = be.hatch;
        be.hatch = stepHatch(be.phase, be.hatch, be.tier());
    }

    private static float stepHatch(Phase phase, float hatch, MissileTier tier) {
        return switch (phase) {
            case OPENING, LAUNCHING -> Math.min(1.0F, hatch + 1.0F / tier.hatchTicks);
            case CLOSING, COOLDOWN, IDLE -> Math.max(0.0F, hatch - 1.0F / MissileTier.HATCH_CLOSE_TICKS);
        };
    }

    private void tickServer(ServerLevel level) {
        hatchO = hatch;
        MissileTier tier = tier();
        Vec3 mouth = SiloStructure.mouth(worldPosition, tier);
        switch (phase) {
            case IDLE -> {
                return;
            }
            case OPENING -> {
                hatch = stepHatch(phase, hatch, tier);
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
                hatch = stepHatch(phase, hatch, tier);
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
        loadedTier = 0;
        setPhase(Phase.LAUNCHING);
        if (aim == null) return;
        MissileEntity missile = MissileEntity.launch(level, worldPosition, tier, aim, launchCommandTime);
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
        launches = input.getIntOr("launches", 0);
        lastMissileId = input.getIntOr("last_missile", -1);
        launchCommandTime = input.getLongOr("launch_time", 0L);
        target = input.getBooleanOr("has_target", false)
            ? new Vec3(input.getDoubleOr("tx", 0), input.getDoubleOr("ty", 0), input.getDoubleOr("tz", 0)) : null;
        displaced.clear();
        input.read("displaced", Displaced.CODEC.listOf()).ifPresent(list -> list.forEach(d -> displaced.put(d.pos(), d.state())));
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

    public String describe() {
        MissileTier tier = tier();
        return String.format(Locale.ROOT, "silo T%d at %s: %s, %s, hatch %.2f, mode %s%s, launches %d, last missile #%d%s",
            tier.tier, worldPosition.toShortString(), isLoaded() ? "loaded" : "empty",
            phase.name().toLowerCase(Locale.ROOT), hatch, mode.name().toLowerCase(Locale.ROOT),
            phase == Phase.COOLDOWN ? ", cooldown " + cooldown + "t" : "", launches, lastMissileId,
            target == null ? "" : String.format(Locale.ROOT, ", target %.1f %.1f %.1f", target.x, target.y, target.z));
    }
}
