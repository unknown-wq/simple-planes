package xyz.przemyk.simpleplanes.autopilot;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.przemyk.simpleplanes.SimplePlanesMod;

import java.util.List;
import java.util.Locale;

/**
 * The entity-only, armour-ignoring blast behind {@link Blast#pierce()}.
 *
 * <p>Vanilla's explosion is not used for the damage: its damage falls off linearly and then goes
 * through armour, Protection and Blast Protection, which is exactly what this blast is meant not to
 * care about. Instead every living entity in range is rolled once against a death chance that
 * depends on nothing but its distance from the centre and the cover it stands behind. The formula
 * and a table of it are in {@code design/PIERCING-BLAST.md}; in short, with {@code R = 2 * power}
 * and {@code x = r / R}:
 *
 * <pre>
 *   x &lt;= 0.25                     death is certain (the core; cover is not looked at)
 *   s = (x - 0.25) / 0.75          position across the lethal band, 0 at the core, 1 at R
 *   f = 1 - 0.5 * (1 - exposure)   cover: fully hidden halves the band, fully exposed leaves it
 *   u = s / f
 *   P(death) = 1 - (3u^2 - 2u^3)   for u &lt; 1, else 0
 * </pre>
 *
 * <p>A <b>lethal</b> roll is dealt as {@code health + absorption} (scaled up for Resistance I-IV)
 * plus a margin, through the {@code simpleplanes:piercing_blast} damage type, which bypasses armour,
 * enchantments, shields and the hurt cooldown but <em>not</em> invulnerability. It goes through
 * {@code hurtServer} like any other damage, so graves, MineColonies' critical care and every other
 * death hook see an ordinary death from that type. A target that caps the damage one blow may deal
 * (MineColonies citizens take at most a fifth of their health per blow) is struck again, up to
 * {@link #MAX_BLOWS} times, until it reaches the point of death or stops losing health.
 *
 * <p>A <b>non-lethal</b> roll deals {@code 0.75 * maxHealth * (1 - u)} and a knockback, so a
 * survivor near the core is badly hurt and one near the edge barely touched; at full health a
 * non-lethal roll never kills on its own.
 *
 * <p>What still saves: creative and spectator players are skipped outright; a Totem of Undying
 * works as usual (the blast does not bypass it); Resistance V, which is only reachable with
 * commands, still absorbs everything; anything invulnerable stays invulnerable. What does not:
 * armour of any kind, any enchantment, a raised shield, Resistance I-IV, difficulty (the damage
 * type does not scale with difficulty, so it kills on peaceful too).
 *
 * <p>Nothing in the world is touched: no block is broken or set on fire, and only living entities
 * are considered, so items, item frames, paintings, minecarts and boats are left alone. An armour
 * stand follows the same roll as everything else and breaks only on a lethal one, since vanilla
 * breaks it on any explosion damage at all.
 */
public final class PiercingBlast {

    private static final Logger LOGGER = LoggerFactory.getLogger("simpleplanes");

    /** The damage type in {@code data/simpleplanes/damage_type/piercing_blast.json}. */
    public static final ResourceKey<DamageType> DAMAGE_TYPE = ResourceKey.create(Registries.DAMAGE_TYPE,
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "piercing_blast"));

    /** Radius per unit of power: vanilla's own entity-damage radius is {@code 2 * power}. */
    public static final double RADIUS_PER_POWER = 2.0;
    /** Fraction of the radius inside which death is certain. */
    public static final double CORE_FRACTION = 0.25;
    /** How much of the lethal band full cover takes away: 0.5 halves it. */
    public static final double COVER_WEIGHT = 0.5;
    /** Damage of a non-lethal roll at the edge of the core, as a fraction of maximum health. */
    public static final double SURVIVOR_DAMAGE = 0.75;
    /** Knockback of a non-lethal roll at the edge of the core, in blocks per tick. */
    public static final double KNOCKBACK = 1.0;
    /** Repeated blows allowed for one lethal roll, for targets that cap the damage of one blow. */
    public static final int MAX_BLOWS = 8;

    /** Rays per entity for the cover estimate: three heights by three positions across the body. */
    private static final int COVER_COLUMNS = 3;
    private static final int COVER_ROWS = 3;
    /** The last half block before the centre is the warhead's own cell and does not count as cover. */
    private static final double STOP_SHORT = 0.5;

    /** The entity whose lethal roll is being dealt right now, and whether it has reached death. */
    private static @Nullable LivingEntity dealing;
    private static boolean reachedDeath;

    static {
        // Observes, never vetoes: it only records that the entity being struck reached the point
        // of death, so the blow loop below stops there and leaves a totem, critical care or any
        // other rescue to do its work undisturbed. Registered on first use, which is the first
        // piercing blast; Fabric events accept listeners at any time.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity == dealing) {
                reachedDeath = true;
            }
            return true;
        });
    }

    private PiercingBlast() {}

    /** Radius of a piercing blast of this power. */
    public static double radius(float power) {
        return RADIUS_PER_POWER * power;
    }

    /**
     * Position across the lethal band after cover: 0 or less inside the core, 1 or more where the
     * blast no longer does anything to this entity.
     *
     * @param x        distance from the centre divided by the radius.
     * @param exposure fraction of the cover rays that reached the entity, 0 to 1.
     */
    public static double bandPosition(double x, double exposure) {
        if (x <= CORE_FRACTION) {
            return 0.0;
        }
        double s = (x - CORE_FRACTION) / (1.0 - CORE_FRACTION);
        double f = 1.0 - COVER_WEIGHT * (1.0 - Mth.clamp(exposure, 0.0, 1.0));
        return s / f;
    }

    /** Chance that an entity at {@code x = r / R} with this exposure is killed. */
    public static double deathChance(double x, double exposure) {
        if (x <= CORE_FRACTION) {
            return 1.0;
        }
        double u = bandPosition(x, exposure);
        if (u >= 1.0) {
            return 0.0;
        }
        return 1.0 - u * u * (3.0 - 2.0 * u);
    }

    /** What one blast did, for the log and for tests. */
    public record Report(float power, double radius, int inRange, int lethal, int dead, int wounded,
                         double millis) {
        public String describe() {
            return String.format(Locale.ROOT,
                "piercing blast %.1f (radius %.0f): %d in range, %d lethal rolls (%d reached death), %d wounded, %.2f ms",
                power, radius, inRange, lethal, dead, wounded, millis);
        }
    }

    /**
     * Sets a piercing blast off. Called only from {@link Blast#detonate}, after the blast guards.
     *
     * @param source the aircraft or missile, never hurt by its own blast; may be null.
     */
    public static Report detonate(ServerLevel level, @Nullable Entity source, Vec3 at, float power) {
        long t0 = System.nanoTime();
        double radius = radius(power);
        effects(level, source, at, radius);
        if (radius < 1.0E-3) {
            return new Report(power, radius, 0, 0, 0, 0, 0.0);
        }

        DamageSource damage = damageSource(level, source);
        Vec3 origin = coverOrigin(level, at);
        List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(radius),
            entity -> entity != source && entity.isAlive() && !exempt(entity));

        int inRange = 0;
        int lethal = 0;
        int dead = 0;
        int wounded = 0;
        for (LivingEntity entity : targets) {
            if (!entity.isAlive()) {
                continue; // killed as a side effect of an earlier one, a rider with its mount, say
            }
            double r = Math.sqrt(entity.distanceToSqr(at));
            if (r > radius) {
                continue;
            }
            inRange++;
            double x = r / radius;
            double exposure = x <= CORE_FRACTION ? 1.0 : exposure(level, origin, entity);
            double chance = deathChance(x, exposure);
            if (chance >= 1.0 || (chance > 0.0 && level.getRandom().nextDouble() < chance)) {
                lethal++;
                if (strikeDead(level, entity, damage)) {
                    dead++;
                }
            } else if (wound(level, entity, damage, at, bandPosition(x, exposure), exposure)) {
                wounded++;
            }
        }
        Report report = new Report(power, radius, inRange, lethal, dead, wounded, (System.nanoTime() - t0) / 1.0E6);
        LOGGER.info("{} at {}", report.describe(),
            String.format(Locale.ROOT, "%.1f %.1f %.1f", at.x, at.y, at.z));
        return report;
    }

    /** Creative and spectator players are left completely alone: no damage, no knockback. */
    private static boolean exempt(LivingEntity entity) {
        return entity instanceof Player player && (player.isCreative() || player.isSpectator());
    }

    /**
     * The blast's damage source. The aircraft is the direct entity, as in a vanilla explosion; the
     * type falls back to vanilla's explosion if the data pack that defines it is missing, which only
     * a server that disabled this mod's built-in data can arrange.
     */
    private static DamageSource damageSource(ServerLevel level, @Nullable Entity source) {
        Holder<DamageType> type = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
            .get(DAMAGE_TYPE).map(holder -> (Holder<DamageType>) holder).orElse(null);
        if (type == null) {
            LOGGER.warn("Damage type {} is missing (data pack disabled?); the piercing blast falls back to vanilla "
                + "explosion damage, which armour reduces", DAMAGE_TYPE.identifier());
            return level.damageSources().explosion(source, null);
        }
        return new DamageSource(type, source, null);
    }

    /**
     * A lethal roll: blows of the full remaining health until the entity reaches the point of death,
     * stops losing health, or {@link #MAX_BLOWS} have been dealt.
     *
     * @return true if the entity reached the point of death (it may still have been rescued by a
     *         totem or by another mod's death handling, which is theirs to decide).
     */
    private static boolean strikeDead(ServerLevel level, LivingEntity entity, DamageSource damage) {
        dealing = entity;
        reachedDeath = false;
        try {
            for (int blow = 0; blow < MAX_BLOWS; blow++) {
                float before = entity.getHealth() + entity.getAbsorptionAmount();
                entity.hurtServer(level, damage, lethalAmount(entity));
                if (reachedDeath || !entity.isAlive()) {
                    return true;
                }
                if (entity.getHealth() + entity.getAbsorptionAmount() >= before) {
                    return false; // immune, protected, or rescued without dying: stop here
                }
            }
            return reachedDeath || !entity.isAlive();
        } finally {
            dealing = null;
        }
    }

    /**
     * Enough to kill through everything the damage type does not already bypass: health plus
     * absorption, divided by what Resistance I-IV leaves through, plus a margin. Resistance V and
     * above leaves nothing through and is deliberately not compensated.
     */
    static float lethalAmount(LivingEntity entity) {
        double need = entity.getHealth() + entity.getAbsorptionAmount();
        MobEffectInstance resistance = entity.getEffect(MobEffects.RESISTANCE);
        if (resistance != null && resistance.getAmplifier() < 4) {
            need /= 1.0 - 0.2 * (resistance.getAmplifier() + 1);
        }
        return (float) (need * 1.25 + 10.0);
    }

    /**
     * A non-lethal roll: damage and a push that both fall to nothing at the edge of the band.
     *
     * @return true if the entity was hurt at all.
     */
    private static boolean wound(ServerLevel level, LivingEntity entity, DamageSource damage, Vec3 at, double u,
                                 double exposure) {
        double left = 1.0 - Mth.clamp(u, 0.0, 1.0);
        if (left <= 0.0 || entity instanceof ArmorStand) {
            return false; // an armour stand breaks on any explosion damage, so it is only hit by a lethal roll
        }
        Vec3 away = entity.getEyePosition().subtract(at);
        if (away.lengthSqr() > 1.0E-6) {
            double resist = entity.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE);
            // Pushed before the hurt, so that hurtServer's own velocity sync carries it to a player.
            entity.push(away.normalize().scale(KNOCKBACK * left * exposure * (1.0 - resist)));
        }
        float amount = (float) (SURVIVOR_DAMAGE * entity.getMaxHealth() * left);
        return amount > 0.0F && entity.hurtServer(level, damage, amount);
    }

    /** Flash and bang, sized to the radius; no block is involved. */
    private static void effects(ServerLevel level, @Nullable Entity source, Vec3 at, double radius) {
        level.gameEvent(source, GameEvent.EXPLODE, at);
        int emitters = 1 + (int) (radius / 16.0);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, true, true, at.x, at.y, at.z, emitters,
            radius / 8.0, radius / 32.0, radius / 8.0, 0.0);
        // Volume above 1 widens the audible range (16 blocks per unit) rather than the loudness.
        float volume = (float) Math.max(4.0, radius / 16.0);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, volume,
            0.6F + level.getRandom().nextFloat() * 0.1F);
    }

    /**
     * Where the cover rays converge: the centre, lifted out of the block it sits in if it is buried
     * (an aircraft that stops a hair inside the ground), so the ground under the warhead does not
     * count as cover for everybody.
     */
    private static Vec3 coverOrigin(ServerLevel level, Vec3 at) {
        Vec3 origin = at;
        for (int i = 0; i < 3; i++) {
            BlockPos pos = BlockPos.containing(origin);
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                break;
            }
            VoxelShape shape = chunk.getBlockState(pos).getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                break;
            }
            double top = pos.getY() + shape.max(Direction.Axis.Y);
            if (origin.y >= top) {
                break;
            }
            origin = new Vec3(origin.x, top + 0.05, origin.z);
        }
        return origin;
    }

    /**
     * Fraction of nine rays, from three heights by three points across the entity's width, that
     * reach the centre without passing through a block with a collision shape. Fluids do not
     * count, as in vanilla. Chunks that are not loaded count as open and are never loaded for this.
     */
    static double exposure(ServerLevel level, Vec3 origin, LivingEntity entity) {
        AABB box = entity.getBoundingBox();
        Vec3 mid = box.getCenter();
        double dx = mid.x - origin.x;
        double dz = mid.z - origin.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        double px = h < 1.0E-6 ? 1.0 : -dz / h;
        double pz = h < 1.0E-6 ? 0.0 : dx / h;
        double half = Math.min(box.getXsize(), box.getZsize()) * 0.45;
        RayWalk walk = new RayWalk(level);
        int open = 0;
        for (int column = 0; column < COVER_COLUMNS; column++) {
            double across = half * (column - (COVER_COLUMNS - 1) / 2.0);
            for (int row = 0; row < COVER_ROWS; row++) {
                double y = box.minY + box.getYsize() * (0.1 + 0.8 * row / (COVER_ROWS - 1));
                if (walk.clear(new Vec3(mid.x + px * across, y, mid.z + pz * across), origin)) {
                    open++;
                }
            }
        }
        return open / (double) (COVER_COLUMNS * COVER_ROWS);
    }

    /** A block traversal that reads loaded chunks only, caching the last one it looked in. */
    private static final class RayWalk {
        private final ServerLevel level;
        private @Nullable LevelChunk chunk;
        private int chunkX = Integer.MIN_VALUE;
        private int chunkZ = Integer.MIN_VALUE;
        private Vec3 from = Vec3.ZERO;
        private Vec3 to = Vec3.ZERO;

        RayWalk(ServerLevel level) {
            this.level = level;
        }

        boolean clear(Vec3 start, Vec3 origin) {
            Vec3 d = origin.subtract(start);
            double length = d.length();
            if (length <= STOP_SHORT) {
                return true;
            }
            from = start;
            to = start.add(d.scale((length - STOP_SHORT) / length));
            return !BlockGetter.traverseBlocks(from, to, this, RayWalk::blocks, walk -> Boolean.FALSE);
        }

        private @Nullable Boolean blocks(BlockPos pos) {
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            if (cx != chunkX || cz != chunkZ) {
                chunkX = cx;
                chunkZ = cz;
                chunk = level.getChunkSource().getChunkNow(cx, cz);
            }
            if (chunk == null) {
                return null;
            }
            BlockState state = chunk.getBlockState(pos);
            if (state.isAir()) {
                return null;
            }
            VoxelShape shape = state.getCollisionShape(level, pos);
            return shape.isEmpty() || shape.clip(from, to, pos) == null ? null : Boolean.TRUE;
        }
    }
}
