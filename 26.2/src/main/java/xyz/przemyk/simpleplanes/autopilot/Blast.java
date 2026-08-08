package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

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
 * </ul>
 *
 * <p><b>The bound.</b> {@link #MAX_POWER} is {@value #MAX_POWER}, i.e. a 32-block damage radius and
 * a crater around 30 blocks across. That is four times TNT and deliberately not more: vanilla's
 * {@code ServerExplosion} casts 1352 rays and then drops every block it removed, so the cost grows
 * with the volume of the crater, and the whole point of a bound is that a mistyped argument cannot
 * stall the server or eat a build. Values are clamped here rather than only at the command, so a
 * hand-edited save file cannot smuggle a larger one back in through the flight plan codec.
 */
public record Blast(float power, boolean breaksBlocks, boolean fire) {

    /** Vanilla TNT strength — what {@code PlaneEntity} has always exploded with. */
    public static final float DEFAULT_POWER = 4.0F;
    /** Lower bound: 0 is a legitimate setting and means "bang, but harmless". */
    public static final float MIN_POWER = 0.0F;
    /** Upper bound. See the class comment — this is a cost limit, not a taste limit. */
    public static final float MAX_POWER = 16.0F;

    /** Exactly what an aircraft did before any of this was configurable. */
    public static final Blast DEFAULT = new Blast(DEFAULT_POWER, true, false);

    public static final Codec<Blast> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.FLOAT.optionalFieldOf("power", DEFAULT_POWER).forGetter(Blast::power),
        Codec.BOOL.optionalFieldOf("breaks_blocks", true).forGetter(Blast::breaksBlocks),
        Codec.BOOL.optionalFieldOf("fire", false).forGetter(Blast::fire)
    ).apply(instance, Blast::new));

    public Blast {
        // Clamped in the canonical constructor, so every route into this record is bounded: the
        // command, the item component and the codec that reads it back off disk.
        power = Float.isNaN(power) ? DEFAULT_POWER : Mth.clamp(power, MIN_POWER, MAX_POWER);
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

    public boolean isDefault() {
        return equals(DEFAULT);
    }

    /** One-line description used by the command feedback, the status line and the item tooltip. */
    public String describe() {
        return String.format("%.1f%s%s", power,
            breaksBlocks ? "" : ", no block damage",
            fire ? ", incendiary" : "");
    }
}
