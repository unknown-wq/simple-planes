package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.api.BlastGuards;

/**
 * The warhead: how hard an autopilot aircraft goes off when it stops flying.
 *
 * <p>Three independent things, because only the first of them is a number and the other two change
 * what the explosion <em>is</em>:
 *
 * <ul>
 *   <li>{@link #power} — vanilla explosion strength. TNT is {@value #DEFAULT_POWER}, a charged
 *       creeper is 6, an end crystal is 6. The damage radius is {@code 2 * power}, so this is the
 *       one number that has to be bounded.</li>
 *   <li>{@link #breaksBlocks} — whether the terrain is rearranged. Selects between
 *       {@link Level.ExplosionInteraction#TNT} (crater, drops, obeys the TNT drop-decay game rule)
 *       and {@link Level.ExplosionInteraction#NONE} (entity damage and knockback only, not one
 *       block moved). Worth more than a bigger number: it is the difference between a weapon you
 *       can test on a build and one you cannot.</li>
 *   <li>{@link #fire} — whether the blast leaves fires behind, which is a separate argument on the
 *       fuller {@code Level#explode} overload rather than a property of the interaction.</li>
 *   <li>{@link #pierce} — an entity-only blast that ignores armour. It does not use
 *       {@code Level#explode} at all: {@link PiercingBlast} rolls each living entity in range
 *       against a death chance that depends only on its distance from the centre and on the cover
 *       it stands behind. A piercing blast never breaks a block and never starts a fire:
 *       {@link #breaksBlocks} and {@link #fire} are forced off in the constructor, so every route
 *       in (the commands, the tool, a save file, a guard) gets the same answer, and the default
 *       {@code blocks true} of {@code /autopilot strike} does not have to be spelled out as false.
 *       See {@code design/PIERCING-BLAST.md}.</li>
 * </ul>
 *
 * <p><b>The bound.</b> {@link #MAX_POWER} is {@value #MAX_POWER}, i.e. a 32-block damage radius and
 * a crater around 30 blocks across. That is four times TNT and deliberately not more: vanilla's
 * {@code ServerExplosion} casts 1352 rays and then drops every block it removed, so the cost grows
 * with the volume of the crater, and the whole point of a bound is that a mistyped argument cannot
 * stall the server or eat a build. Values are clamped here rather than only at the command, so a
 * hand-edited save file cannot smuggle a larger one back in through the flight plan codec.
 *
 * <p>A piercing blast has its own, higher bound, {@link #MAX_PIERCE_POWER}: it casts no block rays
 * and moves no blocks, so its cost grows with the number of living entities in range rather than
 * with the volume of a crater, and a radius of 128 blocks measured well inside a tick.
 */
public record Blast(float power, boolean breaksBlocks, boolean fire, boolean pierce) {

    /** Vanilla TNT strength — what {@code PlaneEntity} has always exploded with. */
    public static final float DEFAULT_POWER = 4.0F;
    /** Lower bound: 0 is a legitimate setting and means "bang, but harmless". */
    public static final float MIN_POWER = 0.0F;
    /** Upper bound. See the class comment — this is a cost limit, not a taste limit. */
    public static final float MAX_POWER = 16.0F;

    /**
     * Upper bound for a piercing blast: a 128-block radius. See {@link PiercingBlast} for the cost,
     * which is a bounded number of short block traversals per living entity in range.
     */
    public static final float MAX_PIERCE_POWER = 64.0F;

    /** A strike drone's warhead strength: a crater about one block across. See {@code AUTOPILOT.md}. */
    public static final float DRONE_POWER = 1.0F;

    /** Exactly what an aircraft did before any of this was configurable. */
    public static final Blast DEFAULT = new Blast(DEFAULT_POWER, true, false);

    public static final Codec<Blast> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.FLOAT.optionalFieldOf("power", DEFAULT_POWER).forGetter(Blast::power),
        Codec.BOOL.optionalFieldOf("breaks_blocks", true).forGetter(Blast::breaksBlocks),
        Codec.BOOL.optionalFieldOf("fire", false).forGetter(Blast::fire),
        // Absent in every flight plan saved before the field existed, and read back as false.
        Codec.BOOL.optionalFieldOf("pierce", false).forGetter(Blast::pierce)
    ).apply(instance, Blast::new));

    public Blast {
        // Clamped in the canonical constructor, so every route into this record is bounded: the
        // command, the item component and the codec that reads it back off disk.
        if (pierce) {
            // Entity-only by definition. Forced rather than refused, so that a guard, a save file
            // or the default "blocks true" of a command cannot produce a contradictory record.
            breaksBlocks = false;
            fire = false;
        }
        power = Float.isNaN(power) ? DEFAULT_POWER : Mth.clamp(power, MIN_POWER, pierce ? MAX_PIERCE_POWER : MAX_POWER);
    }

    /**
     * An ordinary, non-piercing blast. Kept as a real constructor rather than only as a factory:
     * {@link xyz.przemyk.simpleplanes.api.BlastGuard} implementations in other mods build their
     * downgraded blasts with exactly this signature (some of them reflectively), and removing it
     * would unlink them.
     */
    public Blast(float power, boolean breaksBlocks, boolean fire) {
        this(power, breaksBlocks, fire, false);
    }

    /** The highest power this blast's kind accepts: {@link #MAX_PIERCE_POWER} if piercing, else {@link #MAX_POWER}. */
    public static float maxPower(boolean pierce) {
        return pierce ? MAX_PIERCE_POWER : MAX_POWER;
    }

    /**
     * Whether the blast rearranges the world. {@code NONE} maps to
     * {@code Explosion.BlockInteraction.KEEP} in {@code ServerLevel#explode}, which skips the block
     * removal entirely — the rays are still cast for entity damage, so a no-blocks blast is cheaper
     * as well as tidier.
     */
    public Level.ExplosionInteraction interaction() {
        return breaksBlocks ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE;
    }

    /**
     * Sets this blast off at {@code at}: the one path every explosion of this mod takes. The registered
     * {@link BlastGuards} are consulted first and may weaken or suppress it.
     *
     * <p>A piercing blast is asked about in exactly the same way and at the same point. It arrives
     * at the guards with {@code breaksBlocks} and {@code fire} already off, so a guard that only
     * strips those two abstains by handing it back unchanged; a guard may still suppress it
     * ({@code null}) or replace it.
     *
     * @return the blast actually applied, or {@code null} if a guard suppressed it.
     */
    public @Nullable Blast detonate(ServerLevel level, @Nullable Entity source, Vec3 at) {
        Blast applied = BlastGuards.filter(level, source, at, this);
        if (applied != null) {
            if (applied.pierce()) {
                PiercingBlast.detonate(level, source, at, applied.power());
            } else {
                level.explode(source, at.x, at.y, at.z, applied.power(), applied.fire(), applied.interaction());
            }
        }
        return applied;
    }

    /**
     * The fixed charge of a strike drone: {@link #DRONE_POWER}, never incendiary. Whether it breaks
     * blocks is still the caller's choice, so a no-block-damage tool stays one with a drone.
     *
     * <p>A piercing tool gives a piercing drone, but the charge does <em>not</em> scale: it stays
     * {@link #DRONE_POWER}, i.e. a 2-block radius with a half-block certain-death core. That makes
     * the drone the precise armour-piercing weapon and the aircraft the area one, which is the
     * split the two already had.
     */
    public Blast forDrone() {
        return new Blast(DRONE_POWER, breaksBlocks, false, pierce);
    }

    public boolean isDefault() {
        return equals(DEFAULT);
    }

    /** One-line description used by the command feedback, the status line and the item tooltip. */
    public String describe() {
        if (pierce) {
            return String.format("%.1f, piercing (entities only, armour ignored)", power);
        }
        return String.format("%.1f%s%s", power,
            breaksBlocks ? "" : ", no block damage",
            fire ? ", incendiary" : "");
    }
}
