package xyz.przemyk.simpleplanes.entities;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import xyz.przemyk.simpleplanes.setup.SimplePlanesRegistries;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;
import xyz.przemyk.simpleplanes.upgrades.armor.ArmorUpgrade;

import java.util.List;

/**
 * Impact detection and impact damage for {@link PlaneEntity}.
 *
 * <h2>Why this class exists</h2>
 * The original detection in {@code PlaneEntity.tick()} was
 * <pre>
 *   speedBefore = |horizontal delta| ; move(SELF, motion) ; speedAfter = |horizontal delta|
 *   if (horizontalCollision &amp;&amp; onGroundTicks &lt;= 0 &amp;&amp; (speedBefore - speedAfter) * 10 - 5 &gt; 5) crash();
 * </pre>
 * On 26.2 that can never fire for a plane flown by a player, for two independent reasons:
 * <ol>
 *   <li>{@code Entity.move()} only applies the post-collision velocity response
 *       ({@code restituteMovementAfterCollisions}, the code that zeroes the blocked axes) when
 *       {@code canSimulateMovement()} is true. {@code canSimulateMovement() ==
 *       isLocalInstanceAuthoritative()}, and on the server that is
 *       {@code !isClientAuthoritative()} — which is <b>false</b> as soon as a {@link Player} is the
 *       controlling passenger, because {@code Player.isClientAuthoritative()} returns {@code true}
 *       unconditionally. So on the server the delta is never zeroed by a collision,
 *       {@code speedAfter == speedBefore} and the expression is a constant {@code -5.0}.</li>
 *   <li>Even with a correct {@code speedDiff}, the threshold needs {@code speedDiff &gt; 1.0}
 *       blocks/tick, while the plane's terminal level speed is ~0.76 blocks/tick.</li>
 * </ol>
 *
 * <h2>What is used instead</h2>
 * <ul>
 *   <li><b>Predicate</b> — the geometry still works: the server-side plane sits at the
 *       client-reported position, so its {@code move()} really does clip against the wall and
 *       {@code horizontalCollision} / the blocked motion vector are trustworthy.</li>
 *   <li><b>Magnitude</b> — {@link Entity#getKnownMovement()}. For a player-ridden vehicle the server
 *       gets the client's real per-tick displacement out of
 *       {@code ServerGamePacketListenerImpl.handleMoveVehicle} (it calls
 *       {@code handlePlayerKnownMovement(clientDeltaMovement)}), so this is the one
 *       <i>authoritative</i> speed reading available server-side. With no player aboard it falls
 *       back to {@code getDeltaMovement()}, which is authoritative too.</li>
 * </ul>
 * Damage is then proportional to the kinetic energy that was actually destroyed, scaled by the
 * plane type's mass, with separate tolerance bands for horizontal and vertical impacts so that
 * normal landings and taxi bumps stay free.
 */
public final class PlaneCollisions {

    private PlaneCollisions() {}

    // ------------------------------------------------------------------
    // Tuning. All speeds are blocks/tick (multiply by 20 for blocks/second).
    // See 26.2/COLLISION-DIAGNOSIS.md for how these were derived.
    // ------------------------------------------------------------------

    /** Terminal level speed at throttle 5, solved from the mod's own drag/thrust constants. */
    public static final double CRUISE_SPEED = 0.76;

    /** Horizontal impact below this is a free bump (3 blocks/s). */
    public static final double H_TOLERANCE_AIR = 0.15;
    /** Same, while the plane is rolling on the ground — taxi nudges must stay free (6 blocks/s). */
    public static final double H_TOLERANCE_GROUND = 0.30;

    /** Vertical tolerance for a knife-edge / nose-first impact (4 blocks/s). */
    public static final double V_TOLERANCE_MIN = 0.20;
    /** Extra vertical tolerance granted for being wings-level (total 0.60 = 12 blocks/s). */
    public static final double V_TOLERANCE_LEVEL_BONUS = 0.40;

    /** damage = mass * factor * (v^2 - tolerance^2). 20 makes a cruise-speed head-on ~11 HP. */
    public static final double H_DAMAGE_FACTOR = 20.0;
    /** 14 makes a wings-level 1.0 blocks/tick belly-flop ~9 HP, a 0.7 thump ~1.8 HP. */
    public static final double V_DAMAGE_FACTOR = 14.0;

    /** {@code 1 - upY} above which touching the ground counts as a wing/nose strike (~45 deg). */
    public static final double SCRAPE_ATTITUDE = 0.30;
    public static final double SCRAPE_DAMAGE_FACTOR = 8.0;
    /** Below this ground speed a bad attitude costs nothing (tipping over while parked). */
    public static final double SCRAPE_MIN_SPEED = 0.20;

    /**
     * Single-tick horizontal deceleration that the plane's own physics can never produce
     * (worst case is ~0.10 blocks/tick: brakes on a high-friction block plus a full-rate pitch
     * change). Used as a fallback when the geometric predicate is missed.
     */
    public static final double UNAMBIGUOUS_DECELERATION = 0.35;

    /** Ticks of silence after a registered impact, so one obstacle deals damage once. */
    public static final int IMPACT_COOLDOWN = 8;

    /** Minimum speed for ramming entities (7 blocks/s). */
    public static final double ENTITY_RAM_MIN_SPEED = 0.35;
    public static final double ENTITY_RAM_DAMAGE_FACTOR = 12.0;
    public static final float ENTITY_RAM_MAX_DAMAGE = 30.0F;
    /** HP the plane itself loses per living entity it ploughs through. */
    public static final float ENTITY_RAM_SELF_DAMAGE = 1.0F;
    public static final int ENTITY_RAM_COOLDOWN = 10;

    /** Ticks a block-provided fall multiplier (hay 0.2, slime 0.0, ...) stays armed. */
    public static final int SOFT_LANDING_TTL = 4;

    // ------------------------------------------------------------------

    /**
     * Per-plane collision state. Lives here rather than as loose fields on {@link PlaneEntity} so
     * the whole collision tract stays in one file.
     */
    public static final class State {
        /** Last authoritative displacement seen by {@link #afterMove}. */
        Vec3 knownMovement = Vec3.ZERO;
        /** Same, one tick earlier — this is the "speed going in" of an impact. */
        Vec3 prevKnownMovement = Vec3.ZERO;
        boolean sampled;

        int impactCooldown;
        int entityRamCooldown;
        /** Fractional damage carried between ticks so sub-1-HP scrapes eventually add up. */
        float damageAccumulator;

        float softLandingMultiplier = 1.0F;
        int softLandingTicks;
    }

    // ------------------------------------------------------------------
    // Geometry / mass helpers
    // ------------------------------------------------------------------

    /**
     * Y component of the plane's body "up" axis: {@code 1} wings-level, {@code 0} knife-edge or
     * straight up/down, {@code -1} inverted.
     * <p>
     * Deliberately derived from {@code getXRot()} / {@code rotationRoll} rather than from
     * {@code transformPos(0,1,0)} — the latter reads {@code Q_Client}, which on the server is only
     * refreshed by {@code RotationPacket} and is stale for an unmanned plane.
     */
    public static double upY(PlaneEntity plane) {
        return Math.cos(Math.toRadians(plane.getXRot())) * Math.cos(Math.toRadians(plane.rotationRoll));
    }

    /** Relative mass of the plane type; scales every impact damage number. */
    public static double massOf(PlaneEntity plane) {
        if (plane instanceof HelicopterEntity) {
            return 1.15;
        }
        if (plane instanceof CargoPlaneEntity) {
            return 1.5;
        }
        if (plane instanceof LargePlaneEntity) {
            return 1.3;
        }
        return 1.0;
    }

    private static double horizontal(Vec3 v) {
        return Math.sqrt(v.x * v.x + v.z * v.z);
    }

    /** Downward component of a movement vector, 0 when going up. */
    private static double descent(Vec3 v) {
        return v.y < 0 ? -v.y : 0;
    }

    // ------------------------------------------------------------------
    // Main hook: called from PlaneEntity.tick() immediately after move(MoverType.SELF, motion)
    // ------------------------------------------------------------------

    /**
     * @param plane     the plane
     * @param wanted    the motion vector handed to {@code move()}
     * @param posBefore {@code position()} sampled immediately before {@code move()}
     */
    public static void afterMove(PlaneEntity plane, Vec3 wanted, Vec3 posBefore) {
        State state = plane.collisionState;

        // Authoritative speed sample. On the server with a rider this is the client's real
        // displacement (fed by handleMoveVehicle -> handlePlayerKnownMovement); otherwise it is the
        // plane's own delta, which is authoritative in that case.
        state.prevKnownMovement = state.sampled ? state.knownMovement : plane.getKnownMovement();
        state.knownMovement = plane.getKnownMovement();
        state.sampled = true;

        // What the world actually let us do, measured from positions so it does not depend on how
        // (or whether) the engine post-processed getDeltaMovement().
        Vec3 achieved = plane.position().subtract(posBefore);
        Vec3 blocked = wanted.subtract(achieved);

        restoreCollisionResponse(plane, blocked);

        if (state.softLandingTicks > 0 && --state.softLandingTicks == 0) {
            state.softLandingMultiplier = 1.0F;
        }
        if (state.impactCooldown > 0) {
            state.impactCooldown--;
        }
        if (state.entityRamCooldown > 0) {
            state.entityRamCooldown--;
        }

        if (!(plane.level() instanceof ServerLevel serverLevel) || !plane.isAlive() || plane.isRemoved()) {
            return;
        }

        // A plane that is already at 0 HP is destroyed by any contact (unchanged behaviour).
        if (plane.getHealth() <= 0) {
            if (plane.horizontalCollision || plane.onGround() || descent(blocked) > 1.0E-5) {
                plane.crash(16);
            }
            return;
        }

        if (state.impactCooldown > 0) {
            return;
        }

        // How much motion the world destroyed this tick, as a single scalar.
        //
        // Measuring the whole vector rather than each axis separately is deliberate. An earlier
        // version split the impact into a horizontal part (gated on Entity#horizontalCollision) and
        // a vertical part with its own tolerance, and a plane diving into a hillside at 1.35
        // blocks/tick survived: its descent component was only 0.29, under the 0.60 wings-level
        // vertical tolerance, while the horizontal part never fired because the aircraft ended up
        // inside the terrain and ploughed to a halt over several ticks without the engine ever
        // setting horizontalCollision. Verified in game - the aircraft was found intact on the
        // ground. Speed lost is speed lost, whichever axis carried it and whichever flag the engine
        // decided to set.
        //
        // A landing is not caught by this because the horizontal component is not blocked: the
        // aircraft keeps rolling forward, so only the small vertical part shows up.
        double wantedSpeed = wanted.length();
        double achievedSpeed = achieved.length();
        double blockedSpeed = Math.max(0.0, wantedSpeed - achievedSpeed);
        // The plane's own speed before the move bounds the impact, so leaning on a wall with the
        // throttle open cannot invent energy.
        double reference = Math.max(state.prevKnownMovement.length(), wantedSpeed);
        double impact = Math.min(blockedSpeed, reference);

        double mass = massOf(plane);
        double up = upY(plane);

        // Tolerance depends on what kind of contact this is. A mostly-downward loss is a landing and
        // gets the wings-level allowance; a mostly-horizontal loss is a wall or a hillside and does
        // not - flying level into rock is exactly the case that must hurt.
        double descentShare = blockedSpeed > 1.0E-5 ? descent(blocked) / blockedSpeed : 0;
        double tolerance;
        if (descentShare > 0.7) {
            tolerance = V_TOLERANCE_MIN + V_TOLERANCE_LEVEL_BONUS * Mth.clamp(up, 0.0, 1.0);
        } else {
            tolerance = plane.getOnGround() ? H_TOLERANCE_GROUND : H_TOLERANCE_AIR;
        }

        double factor = descentShare > 0.7 ? V_DAMAGE_FACTOR : H_DAMAGE_FACTOR;
        double softening = descentShare > 0.7 ? state.softLandingMultiplier : 1.0;

        double damage = 0;
        if (impact > tolerance) {
            damage += mass * factor * (impact * impact - tolerance * tolerance) * softening;
        }

        // Fallback for a plane that is already inside terrain, where move() may report no collision
        // at all: a deceleration this large cannot come from the aircraft's own physics.
        double hReference = Math.max(horizontal(state.prevKnownMovement), horizontal(state.knownMovement));
        double hLost = horizontal(state.prevKnownMovement) - horizontal(state.knownMovement);
        if (damage <= 0 && hLost > UNAMBIGUOUS_DECELERATION) {
            damage += mass * H_DAMAGE_FACTOR * (hLost * hLost - H_TOLERANCE_AIR * H_TOLERANCE_AIR);
        }

        // Wing/nose strike: the hitbox is a plain box, so a cartwheel produces almost no blocked
        // motion. Charge for touching the ground at a bad attitude instead, scaled by speed —
        // this replaces the old binary "roll > 45 deg on any ground contact => explode" rule.
        boolean groundContact = plane.onGround() || descent(blocked) > 1.0E-5;
        double attitudeBad = Mth.clamp(1.0 - up, 0.0, 2.0);
        if (groundContact && attitudeBad > SCRAPE_ATTITUDE && hReference > SCRAPE_MIN_SPEED) {
            damage += mass * SCRAPE_DAMAGE_FACTOR * (attitudeBad - SCRAPE_ATTITUDE) * hReference * hReference;
        }

        applyDamage(plane, serverLevel, damage);
    }

    /**
     * Re-applies the part of {@code Entity.move()} that 26.2 skips for client-authoritative
     * vehicles: zero the delta on the axes the world blocked. Without this the server-side
     * simulation keeps flying full speed into a wall forever, which both breaks any
     * before/after speed measurement and makes the server fight the client's position.
     * <p>
     * Guarded on {@code canSimulateMovement()} so it is a no-op whenever the engine already did it.
     */
    private static void restoreCollisionResponse(PlaneEntity plane, Vec3 blocked) {
        if (plane.canSimulateMovement()) {
            return;
        }
        Vec3 delta = plane.getDeltaMovement();
        double x = Math.abs(blocked.x) > 1.0E-5 ? 0 : delta.x;
        double y = Math.abs(blocked.y) > 1.0E-5 ? 0 : delta.y;
        double z = Math.abs(blocked.z) > 1.0E-5 ? 0 : delta.z;
        if (x != delta.x || y != delta.y || z != delta.z) {
            plane.setDeltaMovement(x, y, z);
        }
    }

    // ------------------------------------------------------------------
    // Entity collisions
    // ------------------------------------------------------------------

    /** Called once per tick from {@code PlaneEntity.tick()}; server-side only. */
    public static void tickEntityCollisions(PlaneEntity plane) {
        if (!(plane.level() instanceof ServerLevel serverLevel) || !plane.isAlive() || plane.isRemoved()) {
            return;
        }
        State state = plane.collisionState;
        if (state.entityRamCooldown > 0 || plane.getHealth() <= 0) {
            return;
        }

        Vec3 movement = state.knownMovement;
        double speed = movement.length();
        if (speed < ENTITY_RAM_MIN_SPEED) {
            return;
        }

        List<Entity> hits = plane.level().getEntities(plane, plane.getBoundingBox().inflate(0.1),
            other -> other.isAlive()
                && !other.isRemoved()
                && !other.isPassengerOfSameVehicle(plane)
                && !plane.hasPassenger(other)
                && (other instanceof LivingEntity || other instanceof PlaneEntity)
                && !(other instanceof Player player && (player.isSpectator() || player.isCreative())));

        if (hits.isEmpty()) {
            return;
        }

        double mass = massOf(plane);
        // FLY_INTO_WALL ("experienced kinetic energy") attributed to the plane and its pilot.
        // Built through the public DamageSource constructor rather than DamageSources#source(key,
        // entity, entity), which is only public via fabric's transitive access wideners.
        DamageSource source = new DamageSource(plane.damageSources().flyIntoWall().typeHolder(),
            plane, plane.getControllingPassenger());

        float selfDamage = 0;
        for (Entity victim : hits) {
            if (victim instanceof PlaneEntity otherPlane) {
                // Plane vs plane: charge both for the closing speed.
                double closing = movement.subtract(otherPlane.getKnownMovement()).length();
                double energy = closing * closing - H_TOLERANCE_AIR * H_TOLERANCE_AIR;
                if (energy > 0) {
                    double otherMass = massOf(otherPlane);
                    applyDamage(otherPlane, serverLevel, mass * H_DAMAGE_FACTOR * energy);
                    selfDamage += (float) (otherMass * H_DAMAGE_FACTOR * energy);
                }
                continue;
            }

            float damage = (float) Math.min(mass * ENTITY_RAM_DAMAGE_FACTOR * speed, ENTITY_RAM_MAX_DAMAGE);
            if (victim.hurtServer(serverLevel, source, damage)) {
                victim.push(movement.x * 0.6, 0.25, movement.z * 0.6);
                selfDamage += ENTITY_RAM_SELF_DAMAGE;
            }
        }

        if (selfDamage > 0) {
            state.entityRamCooldown = ENTITY_RAM_COOLDOWN;
            applyDamage(plane, serverLevel, selfDamage);
        }
    }

    // ------------------------------------------------------------------
    // Fall / landing hooks
    // ------------------------------------------------------------------

    /**
     * Replacement for the old {@code causeFallDamage} body, which called {@code crash()} — an
     * unconditional explosion — whenever the plane touched the ground with more than 45 degrees of
     * roll, at <i>any</i> speed. Vertical impacts are now measured in {@link #afterMove}; the only
     * thing kept from this path is the block's own softness multiplier (hay 0.2, slime 0.0, honey
     * 0.2, stalagmite 2.0), which is armed here and consumed by the next vertical impact.
     */
    public static boolean causeFallDamage(PlaneEntity plane, double fallDistance, float damageMultiplier) {
        State state = plane.collisionState;
        state.softLandingMultiplier = damageMultiplier;
        state.softLandingTicks = SOFT_LANDING_TTL;
        return false;
    }

    // ------------------------------------------------------------------
    // Damage application
    // ------------------------------------------------------------------

    private static void applyDamage(PlaneEntity plane, ServerLevel serverLevel, double rawDamage) {
        if (rawDamage <= 0 || !plane.isAlive() || plane.isRemoved()) {
            return;
        }

        float damage = (float) rawDamage;
        Upgrade upgrade = plane.upgrades.get(SimplePlanesRegistries.UPGRADE_TYPE.getKey(SimplePlanesUpgrades.ARMOR.get()));
        if (upgrade instanceof ArmorUpgrade armorUpgrade) {
            damage = armorUpgrade.getReducedDamage(damage);
        }

        State state = plane.collisionState;
        state.damageAccumulator += damage;
        int whole = Mth.floor(state.damageAccumulator);
        if (whole <= 0) {
            // A scrape worth less than a full heart: feedback only, no health lost.
            return;
        }
        state.damageAccumulator -= whole;
        state.impactCooldown = IMPACT_COOLDOWN;

        plane.setTimeSinceHit(20);
        plane.setDamageTaken(plane.getDamageTaken() + 10 * damage);

        int health = plane.getHealth() - whole;
        plane.setHealth(health);

        serverLevel.sendParticles(ParticleTypes.SMOKE, plane.getX(), plane.getY() + 0.5, plane.getZ(),
            Math.min(2 + whole, 12), 0.5, 0.5, 0.5, 0.02);
        serverLevel.playSound(null, plane.getX(), plane.getY(), plane.getZ(),
            SoundEvents.WOOD_BREAK, SoundSource.NEUTRAL,
            Math.min(1.0F, 0.35F + whole * 0.12F),
            0.7F + plane.getRandom().nextFloat() * 0.2F);

        if (health <= 0) {
            plane.crash(damage);
        }
    }
}
