package xyz.przemyk.simpleplanes.entities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
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
 *       client-reported position, so its {@code move()} really does clip against the wall. An axis
 *       counts as blocked when the position change {@code move()} produced differs from the motion
 *       that was asked for on that axis. Every charge below requires this positive geometric
 *       evidence of contact; nothing is inferred from speed history alone, because the only
 *       authoritative speed history on the server ({@link Entity#getKnownMovement()}) is fed by
 *       client packets and legitimately jumps to zero or doubles whenever a movement packet misses
 *       a tick ({@code ServerGamePacketListenerImpl.handleClientTickEnd} zeroes it, and a late
 *       packet carries two ticks of displacement).</li>
 *   <li><b>Magnitude</b> — the full component of this tick's motion on the blocked axes. On the
 *       first contact tick that is the plane's real approach velocity: where the obstacle sits
 *       inside the tick's travel does not matter. The engine (or
 *       {@link #restoreCollisionResponse}) zeroes those axes immediately afterwards, so follow-up
 *       ticks only carry the small delta the physics rebuilds (thrust ~0.03, gravity ~0.05 per
 *       tick) and an aircraft parked against a wall or resting on the ground stays below every
 *       tolerance. Measuring only the clipped remainder of the tick — what an earlier version did —
 *       makes the reading a random fraction in {@code [0, v]} of the impact speed, depending on
 *       where the wall happens to fall within the tick's travel; most impacts then land under the
 *       tolerance and deal nothing.</li>
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
     * Per-axis threshold above which the difference between the motion handed to {@code move()} and
     * the position change it produced counts as contact. Double rounding across a position
     * round-trip is ~1e-13 at survival coordinates, so 1e-5 is pure signal.
     */
    public static final double CONTACT_EPSILON = 1.0E-5;

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

    // ---- water entry -------------------------------------------------
    //
    // Water has no collision shape: Entity.move() is never blocked by it, so none of the machinery
    // above ever fires for it and hitting the sea at 3 blocks/tick used to be free — with or without
    // the Floaty Bedding upgrade. The upgrade made it worse rather than causing it, by deleting the
    // descent as well.
    //
    // The evidence used here is still positive and geometric, and specifically is NOT inferred from
    // speed history: it is a fluid boundary the aircraft demonstrably crossed this tick, read from
    // two real block samples (dry before the move, water after it), charged at the velocity the
    // aerodynamics actually produced. That is the same standard of proof as a blocked axis.

    /** Entry speed below which touching down on water is free at any attitude (7 blocks/s). */
    public static final double WATER_TOLERANCE_MIN = 0.35;
    /**
     * Extra tolerance granted for arriving wings-level, so a deliberate water landing with Floaty
     * Bedding — the whole point of the upgrade — costs nothing up to 0.70 blocks/tick (14 blocks/s).
     */
    public static final double WATER_TOLERANCE_LEVEL_BONUS = 0.35;
    /**
     * damage = mass * factor * (v^2 - tolerance^2). Softer than the ground factors because water is
     * softer: a wings-level entry at 1.0 blocks/tick costs ~5 HP, at 2.0 ~35 HP, and at the 3.14
     * blocks/tick a boosted aircraft reaches, the plane does not survive.
     */
    public static final double WATER_DAMAGE_FACTOR = 10.0;

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

        /**
         * The plane's velocity as the aerodynamics left it this tick, before {@code tickUpgrades()}
         * had a chance to rewrite it. {@code FloatingUpgrade} runs before {@code move()} and arrests
         * a descent over water, so without this the water-entry check would measure the velocity
         * after the arrest — which is exactly the velocity the upgrade was hiding.
         */
        Vec3 preUpgradeMotion = Vec3.ZERO;
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
        // (or whether) the engine post-processed getDeltaMovement(). An axis with a mismatch is an
        // axis the world pushed back on: positive geometric evidence of contact.
        Vec3 achieved = plane.position().subtract(posBefore);
        Vec3 blocked = wanted.subtract(achieved);
        boolean blockedX = Math.abs(blocked.x) > CONTACT_EPSILON;
        boolean blockedY = Math.abs(blocked.y) > CONTACT_EPSILON;
        boolean blockedZ = Math.abs(blocked.z) > CONTACT_EPSILON;

        armSoftLanding(plane, achieved);

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

        // The velocity the world destroyed this tick: the FULL component of the requested motion on
        // every axis the world refused, not just the clipped remainder of the tick. On the first
        // contact tick this is the plane's real approach speed regardless of where the obstacle sat
        // within the tick's travel — measuring only the remainder (an earlier version did) turned
        // every impact into a lottery: a 3.0 blocks/tick head-on registered anywhere between 0 and
        // 3.0 depending on the sub-tick phase, and most rolls came up under the tolerance.
        //
        // The follow-up ticks are naturally cheap: the engine's restituteMovementAfterCollisions
        // (or restoreCollisionResponse above, for a client-authoritative vehicle) zeroes the
        // blocked axes right after the hit, so a plane resting on the ground or parked against a
        // wall only re-accumulates ~0.03-0.08 blocks/tick of thrust/gravity between moves and stays
        // below every tolerance.
        //
        // A landing is not caught by this because the horizontal axes are not blocked: the
        // aircraft keeps rolling forward, so only the vertical component is charged, against the
        // (generous, wings-level) vertical tolerance.
        Vec3 vHit = new Vec3(blockedX ? wanted.x : 0, blockedY ? wanted.y : 0, blockedZ ? wanted.z : 0);
        double impact = vHit.length();

        double mass = massOf(plane);
        double up = upY(plane);

        // Tolerance depends on what kind of contact this is. A mostly-downward loss is a landing and
        // gets the wings-level allowance; a mostly-horizontal loss is a wall or a hillside and does
        // not - flying level into rock is exactly the case that must hurt.
        double descentShare = impact > 1.0E-5 ? descent(vHit) / impact : 0;
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

        // Wing/nose strike: the hitbox is a plain box, so a cartwheel produces almost no blocked
        // motion. Charge for touching the ground at a bad attitude instead, scaled by speed —
        // this replaces the old binary "roll > 45 deg on any ground contact => explode" rule.
        //
        // The speed here prefers the plane's own pre-move velocity and only trusts
        // getKnownMovement() through the min of two consecutive samples: for a ridden plane that
        // reading is fed by client packets, and a single missed packet reads as 0 while the
        // catch-up packet after it carries two ticks of displacement. min() of two adjacent samples
        // is immune to any single such spike.
        damage += waterEntryDamage(plane, posBefore, mass, up);

        boolean groundContact = plane.onGround() || (blockedY && wanted.y < 0);
        double hSteady = Math.min(horizontal(state.knownMovement), horizontal(state.prevKnownMovement));
        double hSpeed = Math.max(horizontal(wanted), hSteady);
        double attitudeBad = Mth.clamp(1.0 - up, 0.0, 2.0);
        if (groundContact && attitudeBad > SCRAPE_ATTITUDE && hSpeed > SCRAPE_MIN_SPEED) {
            damage += mass * SCRAPE_DAMAGE_FACTOR * (attitudeBad - SCRAPE_ATTITUDE) * hSpeed * hSpeed;
        }

        applyDamage(plane, serverLevel, damage);
    }

    /**
     * Damage for going into the water at speed.
     *
     * <h2>Why this needs its own rule</h2>
     * Everything else in this class keys off an axis {@code Entity.move()} refused to move along.
     * Water has no collision shape, so {@code move()} is never blocked by it, {@code horizontal-
     * Collision} stays false and not one of those charges ever fires. A plane could go into the sea
     * at any speed for free. The Floaty Bedding upgrade was blamed for this and did make it worse —
     * it deleted the descent as well, so there was nothing left to see — but the hole was there
     * without it too.
     *
     * <h2>Why this is still positive evidence</h2>
     * The trigger is a fluid boundary the aircraft is measured to have crossed during this tick: the
     * block at the sample point was not water before {@code move()} and is water after it. Two real
     * block reads, one tick apart, of a boundary that does not move. Nothing here guesses an impact
     * from a change in speed, which is the inference that made the earlier versions of this file
     * fire on packet jitter — {@code getKnownMovement()} is not consulted at all.
     *
     * <p>The magnitude is {@link State#preUpgradeMotion}, the velocity the aerodynamics produced
     * this tick, sampled before {@code tickUpgrades()} could arrest it. The sample point matches
     * {@code PlaneEntity#isOnWater} so that "the upgrade thinks it is floating" and "the collision
     * code thinks it has entered the water" can never disagree.
     */
    private static double waterEntryDamage(PlaneEntity plane, Vec3 posBefore, double mass, double up) {
        boolean wasInWater = isWaterAt(plane, posBefore);
        boolean nowInWater = isWaterAt(plane, plane.position());
        if (wasInWater || !nowInWater) {
            return 0;
        }

        double impact = plane.collisionState.preUpgradeMotion.length();
        // Wings-level gets the full allowance; arriving on a wingtip or nose-first does not, exactly
        // as for a ground landing.
        double tolerance = WATER_TOLERANCE_MIN + WATER_TOLERANCE_LEVEL_BONUS * Mth.clamp(up, 0.0, 1.0);
        if (impact <= tolerance) {
            return 0;
        }
        return mass * WATER_DAMAGE_FACTOR * (impact * impact - tolerance * tolerance);
    }

    /**
     * Water test at the same offset {@code PlaneEntity#isOnWater} uses, so the flotation code and
     * the impact code always agree about where the waterline is.
     */
    private static boolean isWaterAt(PlaneEntity plane, Vec3 position) {
        BlockPos pos = new BlockPos(Mth.floor(position.x), Mth.floor(position.y + 0.4), Mth.floor(position.z));
        return plane.level().getBlockState(pos).getFluidState().is(FluidTags.WATER);
    }

    /**
     * Runs the part of {@code Entity.move()} that 26.2 skips for client-authoritative vehicles:
     * the fall-damage hook, which is where a block gets to say how soft it is to land on.
     *
     * <p>{@code Entity.move()} calls {@code checkFallDamage} only under
     * {@code isLocalInstanceAuthoritative()}, and on the server that is
     * {@code !isClientAuthoritative()} — false as soon as a {@link Player} is the controlling
     * passenger, because {@code Player.isClientAuthoritative()} returns {@code true}
     * unconditionally. So for the ordinary case, a plane somebody is flying, the server never
     * reached {@code PlaneEntity#checkFallDamage} at all, and {@link State#softLandingMultiplier}
     * — the whole point of that path, hay 0.2 / slime 0.0 / honey 0.2 / stalagmite 2.0 — was only
     * ever armed on the pilot's client, which is not the side that applies the damage. The
     * multiplier worked exclusively for aircraft with nobody aboard.
     *
     * <p>Guarded on the same test the engine uses, so this fires exactly when the engine skipped
     * it and the hook still runs once per {@code move()}, never twice.
     */
    private static void armSoftLanding(PlaneEntity plane, Vec3 achieved) {
        if (plane.isLocalInstanceAuthoritative()) {
            return;
        }
        BlockPos onPos = plane.getOnPosLegacy();
        plane.checkFallDamage(achieved.y, plane.onGround(), plane.level().getBlockState(onPos), onPos);
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

        // min() of two consecutive samples, so a single packet hiccup (a zero sample or a doubled
        // catch-up sample) can neither arm the ram below real speed nor inflate its damage.
        Vec3 movement = state.knownMovement;
        double speed = Math.min(movement.length(), state.prevKnownMovement.length());
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
                    // One collision, one bill. The other aircraft runs this same loop later in the
                    // same tick and sees the same pair, and its own cooldown is the only thing that
                    // stops it charging both of them a second time: applyDamage sets the victim's
                    // impactCooldown, which guards afterMove and not this.
                    otherPlane.collisionState.entityRamCooldown = ENTITY_RAM_COOLDOWN;
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
