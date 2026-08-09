package xyz.przemyk.simpleplanes.combat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.autopilot.TerrainScanner;
import xyz.przemyk.simpleplanes.entities.HelicopterEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One armed helicopter, from launch to landing.
 *
 * <pre>
 *   CLIMB ──► ENGAGE ──► RECOVER ──► ENDED        magazine empty, or the sortie clock runs out
 *     │         │           │
 *     └─────────┴───────────┴──────► ENDED        shot down: stops firing on the same tick
 * </pre>
 *
 * <h2>Engagement geometry: it hovers</h2>
 * The gunship climbs over the point it was ordered to, holds that point, and shoots from it. It does
 * not orbit and it does not dive.
 *
 * <p>That was originally forced: the old {@code HelicopterEntity} was a plane with its yaw tick
 * deleted, its thrust was vertical in the body frame only, and the only way to translate was through
 * {@code moveForward}, which for an unmanned airframe arrives through the fixed-wing autopilot hook
 * this feature deliberately does not take. There was no horizontal control to use.
 *
 * <p><b>Since the rotorcraft flight model landed the choice is free, and hovering is still the right
 * one.</b> There is now a cyclic, and an orbit would be a few lines. Three measured properties of
 * the new model say not to:
 * <ul>
 *   <li>A hover at {@code HOVER_THROTTLE} drifts <b>0.000 blocks in 400 ticks</b>. A stationary
 *       platform means the firing solution has no platform-velocity term at all, so every miss is
 *       the ballistics' fault and the hit rate is a measurement of the gunnery rather than of the
 *       flight model. That is what made the numbers in the report meaningful.</li>
 *   <li>Yaw is <b>3.0 deg/tick at every airspeed and every attitude</b>, so a hovering gun platform
 *       points at its target exactly as fast as a moving one would. Orbiting buys no tracking.</li>
 *   <li>Lateral cyclic in a hover is the one thing that <em>is</em> messy: a sidestep builds drift
 *       and the airframe weathervanes about <b>61 degrees per 400 ticks</b> by design. A gunship
 *       that never touches the lateral cyclic never sees it, and the nose it aims with stays where
 *       the pedals put it.</li>
 * </ul>
 *
 * <p>What that costs is honest to state: the gunship is a <b>guard post</b>, not a hunter. It kills
 * what comes within {@link #ENGAGEMENT_RADIUS} of where it was placed and it does not chase. Adding
 * a transit or an orbit later is a change behind {@link HoverControl} plus a platform-velocity term
 * on the muzzle velocity, not a rewrite of the gunnery.
 *
 * <h2>Friendly fire</h2>
 * Three separate rules, because "does not target players" is not the same as "does not shoot
 * players":
 * <ol>
 *   <li><b>Players are never targets.</b> {@link HostileTargets} refuses them under every clause.</li>
 *   <li><b>The gunship will not fire if the round's path passes within {@link #FRIENDLY_CLEARANCE}
 *       of a player</b> — the <em>ballistic</em> path, sampled from the same closed form that aimed
 *       it, not the straight line to the target, because at these ranges the arc stands several
 *       blocks above the chord. A player standing next to a skeleton is a cease-fire, not a
 *       casualty.</li>
 *   <li><b>The rounds cannot hit the gunship itself.</b> The arrow's owner is the helicopter, which
 *       is what {@code Projectile#canHitEntity} tests against.</li>
 * </ol>
 * What is <em>not</em> covered, and is stated rather than papered over: a round that misses lands
 * somewhere, and a player who walks under a falling arrow can be hit by it. Arrows do not steer.
 */
public final class GunshipSortie {

    /** How far the gunship looks for targets, in blocks, spherical about the aircraft. */
    public static final double ENGAGEMENT_RADIUS = 40.0;
    /**
     * No round is fired whose path passes closer than this to a player. Two and a half blocks is a
     * player's own width plus a margin, and it is measured against the sampled trajectory.
     */
    public static final double FRIENDLY_CLEARANCE = 2.5;
    /** Ticks between line-of-sight samples along the trajectory. */
    private static final int PATH_SAMPLE_TICKS = 4;

    /**
     * {@code LivingEntity#hurtServer} sets {@code invulnerableTime = 20} on a hit and refuses any
     * hit of equal strength while it is above 10 — so the real window in which a mob cannot be hurt
     * again by an identical arrow is 10 ticks, and {@link #IMMUNITY_THRESHOLD} is where the test sits.
     */
    /** Ticks of target displacement averaged to get a lead velocity. */
    private static final int MOTION_WINDOW = 8;
    /** Ground speed under which a target is treated as stationary and not led at all, blocks/tick. */
    private static final double MOTION_DEADBAND = 0.02;

    private static final int IMMUNITY_WINDOW = 11;
    private static final int IMMUNITY_THRESHOLD = 10;

    /** Rounds a full magazine holds when none is asked for: two stacks. */
    public static final int DEFAULT_MAGAZINE = 128;
    /** Rounds per second when none is asked for. */
    public static final double DEFAULT_RATE = 10.0;
    /** Height above the ground the gunship holds when none is asked for. */
    public static final double DEFAULT_ALTITUDE = 18.0;

    /** Ticks allowed to reach the commanded station before the sortie engages from wherever it is. */
    private static final int CLIMB_TIMEOUT = 600;
    /**
     * Ticks a sortie may last before it recovers regardless. A gunship holds a chunk bubble and a
     * furnace full of coal; one that is never given a target must end by itself rather than hover
     * until the world is unloaded.
     */
    private static final int SORTIE_TIMEOUT = 12000;

    private enum Phase { CLIMB, ENGAGE, RECOVER, ENDED }

    private final HelicopterEntity helicopter;
    private final ServerLevel level;
    private final HoverControl control;
    private final ArrowLoadout loadout;
    private final @Nullable Player owner;

    private final int magazine;
    private final double roundsPerSecond;
    private final double altitudeAgl;
    private final double stationY;

    private Phase phase = Phase.CLIMB;
    private int remaining;
    private int fired;
    private int hits;
    private float damage;
    private int kills;
    private int ticks;
    private int firstKillTick = -1;
    private int emptyTick = -1;
    private double fireCredits;
    private @Nullable LivingEntity target;

    /** Health of every hostile currently being watched, so deaths can be attributed. */
    private final Map<LivingEntity, Float> watched = new HashMap<>();
    /** Where each target was {@link #MOTION_WINDOW} ticks ago, for the lead filter. */
    private final Map<LivingEntity, MotionSample> motionSamples = new HashMap<>();
    /** Each target's filtered ground velocity. */
    private final Map<LivingEntity, Vec3> smoothedVelocity = new HashMap<>();
    /** Per target, the tick the last round fired at it is due to land. See {@link #worthShooting}. */
    private final Map<LivingEntity, Integer> impactDue = new HashMap<>();

    public GunshipSortie(HelicopterEntity helicopter, ServerLevel level, ArrowLoadout loadout,
                         int magazine, double roundsPerSecond, double altitudeAgl,
                         @Nullable Player owner) {
        this.helicopter = helicopter;
        this.level = level;
        this.loadout = loadout;
        this.owner = owner;
        this.magazine = magazine;
        this.remaining = magazine;
        this.roundsPerSecond = roundsPerSecond;
        this.altitudeAgl = altitudeAgl;
        this.control = new CollectiveHover(helicopter);

        int surface = TerrainScanner.surfaceHeight(level, helicopter.getX(), helicopter.getZ());
        double ground = surface == TerrainScanner.UNKNOWN_HEIGHT ? helicopter.getY() : surface;
        this.stationY = ground + altitudeAgl;
    }

    public HelicopterEntity helicopter() {
        return helicopter;
    }

    public boolean isFinished() {
        return phase == Phase.ENDED;
    }

    public String launchLine() {
        return "Gunship #" + helicopter.getId() + " launched at " + CollectiveHover.position(helicopter.position())
            + " with " + magazine + " " + loadout.item().builtInRegistryHolder().getRegisteredName()
            + " at " + trim(roundsPerSecond) + " rounds/s, climbing to " + trim(altitudeAgl)
            + " above the ground.";
    }

    public String statusLine() {
        return "  #" + helicopter.getId() + " " + phase.name().toLowerCase(Locale.ROOT)
            + " pos=" + CollectiveHover.position(helicopter.position())
            + " agl=" + trim(control.heightAboveGround())
            + " vs=" + trim(control.verticalSpeed())
            + " thr=" + helicopter.getThrottle()
            + " hp=" + helicopter.getHealth()
            + " ammo=" + remaining + "/" + magazine
            + " fired=" + fired + " hits=" + hits + " kills=" + kills
            + " target=" + (target == null ? "none" : target.getType().toShortString());
    }

    // ------------------------------------------------------------------
    // The tick
    // ------------------------------------------------------------------

    /** Runs before the aircraft's own tick, so the controls set here are the ones the physics uses. */
    public void tick() {
        if (phase == Phase.ENDED) {
            return;
        }
        // Shot down. Checked first and every tick, so the sortie stops firing on the tick the
        // aircraft dies rather than on the tick something notices.
        if (helicopter.isRemoved() || !helicopter.isAlive() || helicopter.getHealth() <= 0) {
            reportLost();
            return;
        }
        ticks++;

        switch (phase) {
            case CLIMB -> tickClimb();
            case ENGAGE -> tickEngage();
            case RECOVER -> tickRecover();
            case ENDED -> { }
        }
        control.tick();
    }

    private void tickClimb() {
        control.holdAltitude(stationY);
        if (control.onStation()) {
            phase = Phase.ENGAGE;
            GunshipFeedback.report(owner, "Gunship #" + helicopter.getId() + " on station at "
                + CollectiveHover.position(helicopter.position()) + " ("
                + trim(control.heightAboveGround()) + " above the ground) on tick " + ticks + ", "
                + remaining + " rounds ready.");
        } else if (ticks > CLIMB_TIMEOUT) {
            phase = Phase.ENGAGE;
            GunshipFeedback.report(owner, "Gunship #" + helicopter.getId()
                + " did not reach its station in " + CLIMB_TIMEOUT + " ticks - engaging from "
                + CollectiveHover.position(helicopter.position()) + ", "
                + trim(control.heightAboveGround()) + " above the ground.");
        }
    }

    private void tickEngage() {
        control.holdAltitude(stationY);
        List<LivingEntity> hostiles = hostilesInRange();
        updateWatchList(hostiles);

        if (remaining <= 0 || ticks > SORTIE_TIMEOUT) {
            beginRecovery(remaining <= 0
                ? "magazine empty"
                : "sortie clock expired with " + remaining + " rounds left");
            return;
        }

        // The rate is a rate, not a tick interval, so a non-integer rate is honoured exactly rather
        // than rounded to the nearest whole number of ticks. Credits are capped at one round so a
        // quiet minute does not bank a burst.
        fireCredits = Math.min(1.0, fireCredits + roundsPerSecond / 20.0);

        target = pickTarget(hostiles);
        if (target == null || fireCredits < 1.0) {
            return;
        }

        Vec3 muzzle = muzzle();
        Shot shot = solveFor(target, muzzle);
        if (shot == null) {
            // Out of ballistic reach, blocked, or a player in the line: hold fire and keep the
            // round. Ammunition is only spent on rounds that actually leave the aircraft.
            return;
        }
        if (!worthShooting(target, shot.flightTicks())) {
            // The round would land inside the target's damage-immunity window and do nothing.
            // See #worthShooting: this is the single largest saving of ammunition in the feature.
            return;
        }

        control.faceTowards(headingTo(muzzle, shot.aim()));
        loadout.spawn(level, muzzle, shot.velocity(), helicopter);
        impactDue.put(target, ticks + (int) Math.ceil(shot.flightTicks()));
        fired++;
        remaining--;
        fireCredits -= 1.0;
        GunshipFeedback.trace("gunship #" + helicopter.getId() + " round " + fired + "/" + magazine
            + " at " + target.getType().toShortString() + " range=" + trim(muzzle.distanceTo(shot.aim()))
            + " tof=" + trim(shot.flightTicks()) + " v=" + trim(shot.velocity().length()));

        if (remaining == 0) {
            emptyTick = ticks;
            beginRecovery("magazine empty");
        }
    }

    /**
     * Whether a round fired now would actually hurt this target when it lands.
     *
     * <p><b>Vanilla caps the useful rate of fire against a single mob at about two rounds a second,
     * and nothing in the aircraft says so.</b> {@code LivingEntity#hurtServer} sets
     * {@code invulnerableTime = 20} on every hit and, while that is above 10, ignores any subsequent
     * hit that is not <em>stronger</em> than the last one. Every arrow does the same damage, so from
     * the moment one connects the next ten ticks of fire are free of charge to the mob. Measured on
     * the rig before this rule existed: 14 rounds to kill one 20 HP skeleton that four arrows'
     * worth of damage should have killed.
     *
     * <p>So the gunship counts the flight time. A round is fired only if it will arrive after the
     * window closes — both the window the target is in now ({@code invulnerableTime}) and the one
     * the rounds <em>already in flight</em> at it will open ({@link #impactDue}). Three or four
     * arrows are typically in the air at once at ten rounds a second, so tracking the earlier shot's
     * arrival matters as much as reading the mob's timer.
     *
     * <p>The consequence is a design decision, not a limitation: <b>rate of fire is a rate for the
     * engagement, not for one mob.</b> Ten rounds a second against six skeletons is ten rounds a
     * second; against one skeleton it is two, and the other eight rounds stay in the magazine.
     */
    private boolean worthShooting(LivingEntity mob, double flightTicks) {
        int arrival = ticks + (int) Math.ceil(flightTicks);
        // The mob's own timer, ticked down once per tick, has to be at or below 10 on arrival.
        if (mob.invulnerableTime - (arrival - ticks) > IMMUNITY_THRESHOLD) {
            return false;
        }
        Integer due = impactDue.get(mob);
        return due == null || arrival >= due + IMMUNITY_WINDOW;
    }

    private void tickRecover() {
        HoverControl.Landing landing = control.landing();
        if (landing == null) {
            return;
        }
        String tail = " Sortie: " + fired + " of " + magazine + " rounds fired, " + hits
            + " hits" + hitRateText() + ", " + trim(damage) + " damage, " + kills + " kills"
            + firstKillText() + timeToLandText() + ".";
        if (landing.landed()) {
            GunshipFeedback.report(owner, "Gunship #" + helicopter.getId() + " landed at "
                + CollectiveHover.position(landing.where()) + " - " + landing.reason() + "." + tail);
        } else {
            GunshipFeedback.report(owner, "Gunship #" + helicopter.getId() + " did not land: "
                + landing.reason() + ". Despawning where it stands." + tail);
        }
        despawn();
    }

    private void beginRecovery(String why) {
        phase = Phase.RECOVER;
        target = null;
        control.descendAndLand();
        GunshipFeedback.report(owner, "Gunship #" + helicopter.getId() + " " + why + " on tick "
            + ticks + " after " + fired + " rounds (" + hits + " hits, " + kills
            + " kills) - descending to land at " + CollectiveHover.position(helicopter.position()) + ".");
    }

    private void reportLost() {
        GunshipFeedback.report(owner, "Gunship #" + helicopter.getId() + " shot down at "
            + CollectiveHover.position(helicopter.position()) + " on tick " + ticks
            + " with " + remaining + " of " + magazine + " rounds unfired."
            + " Sortie lost: " + fired + " fired, " + hits + " hits, " + kills + " kills"
            + firstKillText() + ".");
        despawn();
    }

    /** Ends the sortie and takes the aircraft out of the world. */
    public void despawn() {
        phase = Phase.ENDED;
        target = null;
        watched.clear();
        if (!helicopter.isRemoved()) {
            // discard(), not kill(): kill() runs PlaneEntity#crash, which explodes. A gunship that
            // has finished its sortie leaves no crater and no wreck.
            helicopter.discard();
        }
    }

    /** Stops the sortie early, from {@code /gunship stop}. */
    public void abort() {
        if (phase == Phase.ENDED) {
            return;
        }
        GunshipFeedback.report(owner, "Gunship #" + helicopter.getId() + " recalled at "
            + CollectiveHover.position(helicopter.position()) + " with " + remaining
            + " of " + magazine + " rounds unfired (" + fired + " fired, " + hits + " hits, "
            + kills + " kills).");
        despawn();
    }

    // ------------------------------------------------------------------
    // Targeting
    // ------------------------------------------------------------------

    /**
     * Keeps the current target while it is still worth shooting at, and otherwise takes the nearest
     * hostile.
     *
     * <p><b>Nearest, sticky, and it steps over a mob it cannot currently hurt.</b>
     * <ul>
     *   <li><b>Nearest</b>, because a short flight time is the single biggest term in whether a shot
     *       connects and in how hard it lands when it does — and the closest hostile is the one about
     *       to become somebody's problem.</li>
     *   <li><b>Sticky</b>, because a magazine spread evenly over six half-dead skeletons kills none
     *       of them. The target is kept until it dies, leaves the radius or stops being reachable, so
     *       rounds finish what they started.</li>
     *   <li><b>But it steps over a mob inside its damage-immunity window</b> when another hostile is
     *       hurtable right now. That is what turns a ten-rounds-a-second weapon from a two-round
     *       weapon into a ten-round one when there is more than one thing to shoot — see
     *       {@link #worthShooting}. It is a preference and not a rule: with no hurtable target
     *       available the aircraft keeps the nearest one and simply holds fire.</li>
     * </ul>
     * Re-acquisition is immediate — the tick a target dies, the next is picked on the same tick, with
     * no cooldown.
     */
    private @Nullable LivingEntity pickTarget(List<LivingEntity> hostiles) {
        if (isEngageable(target) && worthShooting(target, estimatedFlightTicks(target))) {
            return target;
        }
        LivingEntity bestHurtable = null;
        double bestHurtableDistance = Double.MAX_VALUE;
        LivingEntity bestAny = null;
        double bestAnyDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : hostiles) {
            double distance = candidate.distanceToSqr(helicopter);
            if (distance < bestAnyDistance) {
                bestAnyDistance = distance;
                bestAny = candidate;
            }
            if (distance < bestHurtableDistance && worthShooting(candidate, estimatedFlightTicks(candidate))) {
                bestHurtableDistance = distance;
                bestHurtable = candidate;
            }
        }
        return bestHurtable != null ? bestHurtable : bestAny;
    }

    /**
     * Rough time of flight for the target-choice test only, so a full ballistic solve is not run for
     * every hostile in the radius every tick. The exact figure from {@link #solveFor} is what the
     * decision to pull the trigger uses.
     */
    private double estimatedFlightTicks(LivingEntity mob) {
        return Math.sqrt(mob.distanceToSqr(helicopter)) / (ArrowLoadout.MUZZLE_VELOCITY * 0.8);
    }

    private boolean isEngageable(@Nullable LivingEntity candidate) {
        return candidate != null
            && candidate.isAlive()
            && !candidate.isRemoved()
            && HostileTargets.isHostile(candidate, helicopter)
            && candidate.distanceToSqr(helicopter) <= ENGAGEMENT_RADIUS * ENGAGEMENT_RADIUS;
    }

    private List<LivingEntity> hostilesInRange() {
        AABB box = helicopter.getBoundingBox().inflate(ENGAGEMENT_RADIUS);
        List<LivingEntity> found = new ArrayList<>();
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box,
            entity -> HostileTargets.isHostile(entity, helicopter))) {
            if (candidate.distanceToSqr(helicopter) <= ENGAGEMENT_RADIUS * ENGAGEMENT_RADIUS) {
                found.add(candidate);
            }
        }
        return found;
    }

    /**
     * Hit, damage and kill accounting, and the bookkeeping the aiming and immunity rules need.
     *
     * <p><b>Hits are counted from the victims, not from the arrows</b>, and that is the second
     * attempt. The first counted them from the arrows — an arrow that vanishes while still moving
     * struck something alive, one that stops moving hit a block — which is exact right up until a
     * round lands inside a mob's damage-immunity window. {@code AbstractArrow#onHitEntity} then takes
     * its {@code else} branch: the arrow is <em>deflected</em>, its velocity scaled by 0.2 on each
     * contact, and discarded a few ticks later once it is slow enough. That is a removal in flight,
     * so every wasted round was counted as a hit. Measured on the rig: nine rounds fired at one
     * skeleton reported nine hits and no kills, which is not a hit rate, it is an alibi.
     *
     * <p>So: a drop in a watched mob's health is a hit, and the size of the drop is the damage. It
     * attributes <em>all</em> damage taken by a watched mob to the gunship, which is exactly true on
     * a clean rig and an overcount anywhere a mob might also be burning or drowning — the
     * measurements in the report were taken at night, on flat ground, with nothing else in the world
     * for that reason. Simultaneous arrivals would undercount, and cannot happen:
     * {@link #worthShooting} spaces rounds at one target by at least eleven ticks.
     */
    private void updateWatchList(List<LivingEntity> current) {
        for (LivingEntity mob : current) {
            Float previous = watched.get(mob);
            float health = mob.getHealth();
            if (previous != null && health < previous - 1.0E-4f) {
                hits++;
                damage += previous - health;
            }
            watched.put(mob, health);
            trackMotion(mob);
        }
        watched.entrySet().removeIf(entry -> {
            LivingEntity mob = entry.getKey();
            if (mob.isDeadOrDying()) {
                // The fatal round is never seen by the health-delta loop above: a dead mob is not
                // alive, so HostileTargets refuses it and it is not in `current`. Count it here or
                // every kill silently costs the hit rate one hit.
                Float previous = entry.getValue();
                if (previous != null && previous > 0.0f) {
                    hits++;
                    damage += previous;
                }
                kills++;
                if (firstKillTick < 0) {
                    firstKillTick = ticks;
                }
                GunshipFeedback.report(owner, "Gunship #" + helicopter.getId() + " killed "
                    + mob.getType().toShortString() + " at " + CollectiveHover.position(mob.position())
                    + " on round " + fired + " of " + magazine + " (tick " + ticks + ").");
                forget(mob);
                return true;
            }
            if (mob.isRemoved() || !current.contains(mob)) {
                forget(mob);
                return true;
            }
            return false;
        });
    }

    private void forget(LivingEntity mob) {
        impactDue.remove(mob);
        motionSamples.remove(mob);
        smoothedVelocity.remove(mob);
    }

    /**
     * The target's velocity, averaged over {@link #MOTION_WINDOW} ticks rather than read straight off
     * {@code getKnownMovement()}.
     *
     * <p><b>The instantaneous delta is the wrong number to lead on, and it is wrong in the direction
     * that costs the most.</b> A mob's per-tick movement is its walk speed plus the gravity term plus
     * — decisively — the knockback from the arrow that just hit it. Leading six ticks on a knockback
     * impulse throws the next round two blocks past a mob that has already stopped moving. Measured
     * against a stationary skeleton with the raw delta, read off a tick-stepped trace: the solved
     * launch velocity implied an aim point <b>1.95 blocks beyond</b> the target, and the rounds
     * passed over its head and buried themselves in the ground behind it.
     *
     * <p>A displacement over eight ticks divided by eight is the mob's real progress across the
     * ground, which is the only part of its motion worth leading on. Under
     * {@link #MOTION_DEADBAND} it is treated as zero, so a mob standing still is not led at all.
     */
    private void trackMotion(LivingEntity mob) {
        MotionSample sample = motionSamples.get(mob);
        if (sample == null) {
            motionSamples.put(mob, new MotionSample(mob.position(), ticks));
            return;
        }
        int elapsed = ticks - sample.tick();
        if (elapsed < MOTION_WINDOW) {
            return;
        }
        Vec3 travelled = mob.position().subtract(sample.position()).scale(1.0 / elapsed);
        smoothedVelocity.put(mob, travelled.horizontalDistance() < MOTION_DEADBAND
            ? Vec3.ZERO
            : new Vec3(travelled.x, 0.0, travelled.z));
        motionSamples.put(mob, new MotionSample(mob.position(), ticks));
    }

    private Vec3 leadVelocity(LivingEntity mob) {
        return smoothedVelocity.getOrDefault(mob, Vec3.ZERO);
    }

    private record MotionSample(Vec3 position, int tick) {}

    // ------------------------------------------------------------------
    // Gunnery
    // ------------------------------------------------------------------

    private record Shot(Vec3 velocity, Vec3 aim, double flightTicks) {}

    /** The gun sits under the hull, clear of the rotor and inside the owner-immunity bubble. */
    private Vec3 muzzle() {
        return new Vec3(helicopter.getX(), helicopter.getY() + 0.4, helicopter.getZ());
    }

    /**
     * The complete firing solution, or null for "do not shoot": out of reach, path blocked, or a
     * player in the line of fire.
     *
     * <p>The lead is solved by fixed point, because the two unknowns feed each other — where the
     * target will be depends on the flight time, and the flight time depends on how far away that
     * is. Three passes converge to well under a block at these ranges. <b>Only the horizontal
     * components of the target's velocity are used for lead.</b> A mob's vertical velocity is
     * gravity jitter, a fraction of a block per tick that reverses the moment it lands; leading on it
     * puts every round into the ground in front of a walking zombie.
     */
    private @Nullable Shot solveFor(LivingEntity mob, Vec3 muzzle) {
        Vec3 centre = mob.getBoundingBox().getCenter();
        Vec3 velocity = leadVelocity(mob);
        Vec3 aim = centre;
        Ballistics.Solution solution = null;
        for (int pass = 0; pass < 3; pass++) {
            solution = Ballistics.solve(muzzle, aim, ArrowLoadout.MUZZLE_VELOCITY);
            if (solution == null) {
                return null;
            }
            aim = centre.add(velocity.x * solution.flightTicks(), 0.0, velocity.z * solution.flightTicks());
        }
        if (!pathIsClear(muzzle, solution.velocity(), solution.flightTicks())) {
            return null;
        }
        return new Shot(solution.velocity(), aim, solution.flightTicks());
    }

    /**
     * Walks the round's own arc: terrain in the way, and players near it.
     *
     * <p>Sampled from {@link Ballistics#pointAt}, the same closed form that produced the aim, so what
     * is checked is the path the arrow will actually fly. A straight line from muzzle to target is
     * the wrong curve — at 40 blocks the arc stands about four blocks above its chord, which is the
     * difference between clearing a wall and hitting it.
     */
    private boolean pathIsClear(Vec3 muzzle, Vec3 velocity, double flightTicks) {
        List<Player> players = level.players().stream()
            .filter(player -> player.distanceToSqr(helicopter) < Math.pow(ENGAGEMENT_RADIUS + 16.0, 2))
            .map(player -> (Player) player)
            .toList();

        Vec3 previous = muzzle;
        for (double t = PATH_SAMPLE_TICKS; ; t += PATH_SAMPLE_TICKS) {
            boolean last = t >= flightTicks;
            Vec3 point = Ballistics.pointAt(muzzle, velocity, Math.min(t, flightTicks));
            for (Player player : players) {
                if (distanceToSegment(player.getBoundingBox().getCenter(), previous, point) < FRIENDLY_CLEARANCE) {
                    return false;
                }
            }
            HitResult hit = level.clip(new ClipContext(previous, point,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, helicopter));
            if (hit.getType() != HitResult.Type.MISS) {
                return false;
            }
            previous = point;
            if (last) {
                return true;
            }
        }
    }

    private static double distanceToSegment(Vec3 point, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double lengthSqr = ab.lengthSqr();
        if (lengthSqr < 1.0E-9) {
            return point.distanceTo(a);
        }
        double t = Mth.clamp(point.subtract(a).dot(ab) / lengthSqr, 0.0, 1.0);
        return point.distanceTo(a.add(ab.scale(t)));
    }

    private static double headingTo(Vec3 from, Vec3 to) {
        return Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
    }

    // ------------------------------------------------------------------

    /** Hits as a percentage of rounds that actually left the aircraft. */
    private String hitRateText() {
        return fired == 0 ? "" : " (" + Math.round(100.0 * hits / fired) + "%)";
    }

    private String firstKillText() {
        return firstKillTick < 0 ? "" : ", first kill on tick " + firstKillTick;
    }

    private String timeToLandText() {
        return emptyTick < 0 ? "" : ", " + (ticks - emptyTick) + " ticks from empty to landed";
    }

    static String trim(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
