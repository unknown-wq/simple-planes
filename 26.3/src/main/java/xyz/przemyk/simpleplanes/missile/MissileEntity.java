package xyz.przemyk.simpleplanes.missile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.airdefence.Interceptor;
import xyz.przemyk.simpleplanes.airdefence.InterceptorSpec;
import xyz.przemyk.simpleplanes.autopilot.Blast;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * A launched missile. Server-authoritative kinematics (no vanilla physics, no collision with entities); the
 * client only interpolates the synced position and rotation and draws the trail.
 *
 * <p>The authoritative point is the centre of the length, {@code C}; the entity position is the bottom of a
 * cube hitbox centred on it, as MISSILES-MODEL.md suggests. Impact and arrival are tested every tick on the
 * segment the nose swept, so neither depends on the hitbox.
 *
 * <p><b>Fuel.</b> Every missile carries a fuel budget in blocks of powered flight past the tube: the tier's
 * strike motor path ({@link MissileTier#fuel}) or, for an interceptor, {@link InterceptorSpec#range}. It burns by
 * the distance actually flown. When it is gone (or the powered time limit is reached) the motor stops: no thrust,
 * no flame, no guidance, and an interceptor gives up its engagement claim. The missile then falls under gravity
 * with drag, keeping its momentum ({@link Phase#UNPOWERED}), until it hits terrain or an entity ({@link
 * Outcome#FELL}). A hard lifetime cap ({@link #lifetimeLimit}) ends anything still airborne after that.
 *
 * <p>Every way a flight can end goes through {@link #finish}: a puff of particles, then, for an arrival, a
 * terrain impact or a fall, the tier's warhead through {@link Blast#detonate} (blast guards apply), unless the
 * {@code simpleplanes:missile_explosions} game rule is off. An interceptor detonates its warhead only on its
 * fuse; spent, it falls with {@link InterceptorSpec#SPENT_WARHEAD}. Nothing else here hurts an entity or changes
 * a block.
 */
public class MissileEntity extends Entity {

    private static final EntityDataAccessor<Integer> DATA_TIER = SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_FINS = SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_THRUST = SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_BOOSTER = SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.BOOLEAN);

    private static final Vec3 UP = new Vec3(0, 1, 0);

    public enum Phase { TUBE, DEPLOY, MIDCOURSE, TERMINAL, PURSUIT, UNPOWERED }

    /** {@code FELL}: the motor had stopped and the missile fell onto terrain or an entity. */
    public enum Outcome { ARRIVED, TERRAIN, FELL, TIMEOUT, OUT_OF_WORLD, STALLED, ABORTED, REMOVED, INTERCEPTED, LOST }

    /** Motor state for reports: accelerating (tube, fins, booster), holding cruise speed, or burnt out. */
    public enum Motor { BOOST, CRUISE, UNPOWERED }

    /** Unpowered flight: gravity (b/t², as a mob) and drag per tick (as an arrow). */
    public static final double UNPOWERED_GRAVITY = 0.08;
    public static final double UNPOWERED_DRAG = 0.99;
    /** Backstop: a burnt-out missile still airborne after this many ticks is ended where it is. */
    public static final int UNPOWERED_MAX_TICKS = 600;

    private static final Predicate<Entity> IMPACTABLE = e -> e.isPickable() && !e.isSpectator() && !(e instanceof MissileEntity);

    // server state
    private Vec3 target = Vec3.ZERO;
    private BlockPos silo = BlockPos.ZERO;
    private Vec3 mouth = Vec3.ZERO;
    private Phase phase = Phase.TUBE;
    private double speed;
    private Vec3 dir = UP;
    private int flightTicks;
    private int exitTick = -1;
    private double pathLength;
    private double cruiseAlt;
    private long launchCommandTime;
    private double maxAltitude = Double.NEGATIVE_INFINITY;
    private double closest = Double.POSITIVE_INFINITY;
    private double launchRange;
    private int stalls;
    private int stallRun;
    private long lastTickTime = -1;
    private boolean finished;
    private float lastYaw;
    /** Set for an air-defence missile: pursuit instead of the climb-cruise-dive profile. */
    private @Nullable Interceptor interceptor;
    /** A strike missile with the tier's piercing warhead ({@link MissileTier#pierceWarhead}). Never set on an interceptor. */
    private boolean pierce;
    private double exitPath;
    /** Fuel left and the budget, in blocks of powered flight past the tube. */
    private double fuel;
    private double fuelBudget;
    private int burnoutTick = -1;
    private @Nullable Vec3 burnoutAt;
    private String burnoutWhy = "";
    private String impact = "";

    // client state
    private float finsO;
    private float fins;

    public MissileEntity(EntityType<? extends MissileEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /** Creates a missile with the tier's ordinary warhead seated in the silo's tube and adds it to the world. */
    public static MissileEntity launch(ServerLevel level, BlockPos silo, MissileTier tier, Vec3 target, long launchCommandTime) {
        return launch(level, silo, tier, target, launchCommandTime, false);
    }

    /**
     * Creates a missile seated in the silo's tube and adds it to the world. The silo calls this when its hatch is open.
     *
     * @param pierce the tier's piercing warhead instead of the ordinary one ({@link MissileTier#strikeWarhead}).
     */
    public static MissileEntity launch(ServerLevel level, BlockPos silo, MissileTier tier, Vec3 target, long launchCommandTime,
                                       boolean pierce) {
        MissileEntity m = new MissileEntity(Missiles.MISSILE, level);
        m.pierce = pierce;
        m.entityData.set(DATA_TIER, tier.tier);
        m.refreshDimensions();
        m.silo = silo.immutable();
        m.mouth = SiloStructure.mouth(silo, tier);
        m.target = target;
        m.launchCommandTime = launchCommandTime;
        m.launchRange = Math.hypot(target.x - m.mouth.x, target.z - m.mouth.z);
        m.fuel = m.fuelBudget = tier.fuel();
        m.cruiseAlt = Math.min(Math.max(m.mouth.y, target.y) + tier.cruiseAgl, level.getMaxY() - 8.0);
        m.lastYaw = (float) Math.toDegrees(Math.atan2(-(target.x - m.mouth.x), target.z - m.mouth.z));
        Vec3 base = m.mouth.subtract(0, tier.seatDepth, 0);
        m.setCentre(base.add(0, tier.length / 2.0, 0));
        m.applyRotation();
        m.entityData.set(DATA_THRUST, 1.0F);
        level.addFreshEntity(m);
        MissileTracker.track(m);
        return m;
    }

    /** An air-defence missile: rises out of the tube like any other, deploys its fins, then pursues {@code aircraft}. */
    public static MissileEntity launchInterceptor(ServerLevel level, BlockPos silo, MissileTier tier, PlaneEntity aircraft, long launchCommandTime) {
        MissileEntity m = new MissileEntity(Missiles.MISSILE, level);
        m.entityData.set(DATA_TIER, tier.tier);
        m.refreshDimensions();
        m.silo = silo.immutable();
        m.mouth = SiloStructure.mouth(silo, tier);
        m.interceptor = new Interceptor(InterceptorSpec.of(tier), aircraft, m.getId(), silo);
        m.interceptor.track(level, m.mouth);
        m.fuel = m.fuelBudget = m.interceptor.spec.range;
        m.target = m.interceptor.aim();
        m.launchCommandTime = launchCommandTime;
        m.launchRange = m.target.distanceTo(m.mouth);
        m.lastYaw = (float) Math.toDegrees(Math.atan2(-(m.target.x - m.mouth.x), m.target.z - m.mouth.z));
        Vec3 base = m.mouth.subtract(0, tier.seatDepth, 0);
        m.setCentre(base.add(0, tier.length / 2.0, 0));
        m.applyRotation();
        m.entityData.set(DATA_THRUST, 1.0F);
        level.addFreshEntity(m);
        MissileTracker.track(m);
        return m;
    }

    public @Nullable Interceptor interceptor() {
        return interceptor;
    }

    /** True for a strike missile carrying the tier's piercing warhead. Server side only (not synced). */
    public boolean isPiercing() {
        return pierce;
    }

    public MissileTier tier() {
        return MissileTier.of(entityData.get(DATA_TIER));
    }

    public Vec3 centre() {
        return position().add(0, getBbHeight() / 2.0, 0);
    }

    private void setCentre(Vec3 c) {
        setPos(c.x, c.y - getBbHeight() / 2.0, c.z);
    }

    public Vec3 nose() {
        return centre().add(dir.scale(tier().length / 2.0));
    }

    public Vec3 tail() {
        return centre().subtract(dir.scale(tier().length / 2.0));
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel server) {
            if (!finished) tickServer(server);
        } else {
            tickClient();
        }
    }

    private void tickServer(ServerLevel level) {
        MissileTier tier = tier();
        lastTickTime = level.getGameTime();
        stallRun = 0;
        flightTicks++;
        Vec3 prevNose = nose();

        if (flightTicks > lifetimeLimit()) {
            finish(level, Outcome.TIMEOUT, nose());
            return;
        }
        if (phase == Phase.UNPOWERED) {
            fall(level, tier, prevNose);
            return;
        }

        if (interceptor != null) {
            if (!interceptor.track(level, prevNose)) {
                finish(level, Outcome.LOST, prevNose);
                return;
            }
            target = interceptor.aim();
        }

        switch (phase) {
            case TUBE -> {
                speed = Math.min(speed + MissileTier.TUBE_ACCEL, MissileTier.TUBE_MAX_SPEED);
                dir = UP;
            }
            case DEPLOY -> {
                speed = Math.min(speed + tier.accel, tier.cruiseSpeed);
                dir = UP;
                float deployed = Math.min(1.0F, (flightTicks - exitTick) / (float) MissileTier.FIN_DEPLOY_TICKS);
                entityData.set(DATA_FINS, deployed);
                if (deployed >= 1.0F) phase = interceptor != null ? Phase.PURSUIT : Phase.MIDCOURSE;
            }
            case PURSUIT -> {
                speed = Math.min(speed + tier.accel, tier.cruiseSpeed);
                dir = interceptor.steer(level, prevNose, dir, speed);
                target = interceptor.aim();
            }
            case MIDCOURSE -> {
                speed = Math.min(speed + tier.accel, tier.cruiseSpeed);
                midcourse(level, tier);
            }
            case TERMINAL -> {
                speed = Math.min(speed + tier.accel, tier.cruiseSpeed);
                terminal(tier);
            }
        }

        Vec3 c = centre().add(dir.scale(speed));
        setCentre(c);
        setDeltaMovement(dir.scale(speed));
        applyRotation();
        pathLength += speed;
        maxAltitude = Math.max(maxAltitude, c.y);
        // fuel burns by the distance flown under power once out of the tube
        if (phase != Phase.TUBE) fuel = Math.max(0.0, fuel - speed);

        if (phase == Phase.TUBE && tail().y >= mouth.y + 0.25) {
            phase = Phase.DEPLOY;
            exitTick = flightTicks;
            exitPath = pathLength;
        }

        if (tier == MissileTier.T4 && exitTick >= 0 && entityData.get(DATA_BOOSTER)
                && flightTicks - exitTick >= MissileTier.BOOSTER_BURN_TICKS) {
            entityData.set(DATA_BOOSTER, false);
            MissileFx.staging(level, tail());
        }

        entityData.set(DATA_THRUST, switch (phase) {
            case TUBE, DEPLOY -> 1.0F;
            case MIDCOURSE -> speed < tier.cruiseSpeed ? 1.0F : 0.55F;
            case TERMINAL -> 0.8F;
            case PURSUIT -> 1.0F;
            case UNPOWERED -> 0.0F;
        });

        if (phase != Phase.TUBE && checkEnd(level, tier, prevNose)) return;

        if (interceptor != null && phase != Phase.TUBE
                && interceptor.check(level, prevNose, nose(), pathLength - exitPath) == Interceptor.Verdict.DETONATE) {
            finish(level, Outcome.INTERCEPTED, interceptor.detonation());
            return;
        }
        if (c.y < level.getMinY() - 16 || c.y > level.getMaxY() + 256) {
            finish(level, Outcome.OUT_OF_WORLD, nose());
            return;
        }
        if (fuel <= 0.0) {
            burnout(level, "fuel out");
        } else if (flightTicks > poweredTicksLimit()) {
            burnout(level, "motor time limit");
        }
        MissileTracker.telemetry(this);
    }

    /** Powered flight time limit; the motor stops after it even with fuel left. */
    private int poweredTicksLimit() {
        return interceptor != null ? interceptor.spec.maxFlightTicks() : tier().poweredTicks();
    }

    /** Hard lifetime cap: powered flight plus the longest fall allowed. Nothing outlives it. */
    public int lifetimeLimit() {
        return poweredTicksLimit() + UNPOWERED_MAX_TICKS;
    }

    /** Stops the motor: no thrust or flame, no guidance, an interceptor's claim released; the missile falls. */
    private void burnout(ServerLevel level, String why) {
        phase = Phase.UNPOWERED;
        burnoutTick = flightTicks;
        burnoutAt = centre();
        burnoutWhy = why;
        fuel = 0.0;
        entityData.set(DATA_THRUST, 0.0F);
        if (interceptor != null) interceptor.burnout(level);
        MissileTracker.LOGGER.info("[missile] #{} T{} {} at {} t={} flown={} spd={}: motor off, falling unguided", getId(), tier().tier,
            why, MissileTracker.fmt(burnoutAt), flightTicks, String.format(Locale.ROOT, "%.1f", pathLength),
            String.format(Locale.ROOT, "%.2f", speed));
    }

    /** One tick of unpowered flight: gravity and drag, the nose follows the velocity, impact ends it. */
    private void fall(ServerLevel level, MissileTier tier, Vec3 prevNose) {
        Vec3 v = dir.scale(speed).scale(UNPOWERED_DRAG).add(0, -UNPOWERED_GRAVITY, 0);
        speed = v.length();
        if (speed > 1.0E-6) dir = v.scale(1.0 / speed);
        Vec3 c = centre().add(v);
        setCentre(c);
        setDeltaMovement(v);
        applyRotation();
        pathLength += speed;
        maxAltitude = Math.max(maxAltitude, c.y);

        Vec3 to = nose();
        BlockHitResult hit = level.clip(new ClipContext(prevNose, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this));
        boolean blocked = hit.getType() != HitResult.Type.MISS;
        Vec3 end = blocked ? hit.getLocation() : to;
        EntityHitResult struck = ProjectileUtil.getEntityHitResult(level, this, prevNose, end,
            new AABB(prevNose, end).inflate(tier.hitbox + 1.0), IMPACTABLE, tier.hitbox / 2.0F);
        if (struck != null) {
            impact = "on " + EntityType.getKey(struck.getEntity().getType()).getPath() + " #" + struck.getEntity().getId();
            finish(level, Outcome.FELL, struck.getLocation());
        } else if (blocked) {
            impact = "on " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit.getBlockPos()).getBlock()).getPath();
            finish(level, Outcome.FELL, end);
        } else if (c.y < level.getMinY() - 16) {
            finish(level, Outcome.OUT_OF_WORLD, nose());
        } else {
            MissileTracker.telemetry(this);
        }
    }

    /** Arrival or terrain impact along the segment the nose swept this tick. True if the flight ended. */
    private boolean checkEnd(ServerLevel level, MissileTier tier, Vec3 from) {
        Vec3 to = nose();
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this));
        boolean blocked = hit.getType() != HitResult.Type.MISS;
        Vec3 end = blocked ? hit.getLocation() : to;
        if (interceptor != null) {
            if (blocked) finish(level, Outcome.TERRAIN, end);
            return blocked;
        }
        Vec3 nearest = closestPoint(from, end, target);
        double d = nearest.distanceTo(target);
        closest = Math.min(closest, d);
        // arrive at the true closest approach: only once the target is no longer ahead of the nose, or the path is blocked
        boolean passed = target.subtract(end).dot(end.subtract(from)) <= 0.0;
        if (d <= MissileTier.ARRIVAL_RADIUS && (passed || blocked)) {
            finish(level, Outcome.ARRIVED, nearest);
            return true;
        }
        if (blocked) {
            finish(level, Outcome.TERRAIN, end);
            return true;
        }
        return false;
    }

    private void midcourse(ServerLevel level, MissileTier tier) {
        Vec3 c = centre();
        double hx = target.x - c.x;
        double hz = target.z - c.z;
        double hd = Math.sqrt(hx * hx + hz * hz);
        double depression = Math.toDegrees(Math.atan2(c.y - target.y, hd));
        if (hd < 2.0 || depression >= MissileTier.DIVE_ANGLE && lineOfSight(level)) {
            phase = Phase.TERMINAL;
            terminal(tier);
            return;
        }
        double ux = hx / hd;
        double uz = hz / hd;
        double desiredAlt = Math.max(cruiseAlt, terrainAhead(level, c, ux, uz, tier) + MissileTier.TERRAIN_CLEARANCE);
        double lookahead = Math.max(10.0, speed * 15.0);
        double pitch = Math.toDegrees(Math.atan2(desiredAlt - c.y, lookahead));
        pitch = Mth.clamp(pitch, -MissileTier.MAX_DESCENT, MissileTier.MAX_CLIMB);
        double pr = Math.toRadians(pitch);
        Vec3 desired = new Vec3(ux * Math.cos(pr), Math.sin(pr), uz * Math.cos(pr));
        dir = rotateToward(dir, desired, Math.toRadians(tier.midcourseTurn));
    }

    /** True if nothing solid lies between the nose and the target (a hit right at the target does not count). */
    private boolean lineOfSight(ServerLevel level) {
        BlockHitResult hit = level.clip(new ClipContext(nose(), target, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(target) <= MissileTier.ARRIVAL_RADIUS;
    }

    /** Pure pursuit of the target from the nose. The turn limit rises as the range closes, so the missile cannot orbit. */
    private void terminal(MissileTier tier) {
        Vec3 toTarget = target.subtract(nose());
        double d = toTarget.length();
        if (d < 1.0E-6) return;
        Vec3 want = toTarget.scale(1.0 / d);
        if (d <= speed * 1.5) {
            dir = want;
            return;
        }
        double omega = Math.max(Math.toRadians(tier.terminalTurn), 2.2 * speed / Math.max(d, 0.5));
        dir = rotateToward(dir, want, omega);
    }

    /** Highest surface ahead within the look-ahead distance, read only from chunks that are already loaded. */
    private static double terrainAhead(ServerLevel level, Vec3 c, double ux, double uz, MissileTier tier) {
        double top = level.getMinY();
        double reach = tier.cruiseSpeed * 30.0;
        for (double s = 0; s <= reach; s += 8.0) {
            int x = Mth.floor(c.x + ux * s);
            int z = Mth.floor(c.z + uz * s);
            if (!level.hasChunkAt(x, z)) break;
            top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
        }
        return top;
    }

    static Vec3 rotateToward(Vec3 from, Vec3 to, double maxAngle) {
        double cos = Mth.clamp(from.dot(to), -1.0, 1.0);
        double angle = Math.acos(cos);
        if (angle <= maxAngle) return to;
        Vec3 axis = from.cross(to);
        if (axis.lengthSqr() < 1.0E-12) {
            axis = Math.abs(from.y) < 0.9 ? from.cross(UP) : from.cross(new Vec3(1, 0, 0));
        }
        axis = axis.normalize();
        return from.scale(Math.cos(maxAngle)).add(axis.cross(from).scale(Math.sin(maxAngle))).normalize();
    }

    static Vec3 closestPoint(Vec3 a, Vec3 b, Vec3 p) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        if (len2 < 1.0E-12) return a;
        double t = Mth.clamp(p.subtract(a).dot(ab) / len2, 0.0, 1.0);
        return a.add(ab.scale(t));
    }

    private void applyRotation() {
        double h = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (h > 1.0E-3) lastYaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        setYRot(lastYaw);
        setXRot((float) Math.toDegrees(-Math.asin(Mth.clamp(dir.y, -1.0, 1.0))));
    }

    /** Ends the flight: a puff at {@code at}, the warhead if the flight hit something, a report, and the entity is removed. */
    void finish(ServerLevel level, Outcome outcome, Vec3 at) {
        if (finished) return;
        finished = true;
        MissileFx.puff(level, at, tier());
        String blast = detonate(level, outcome, at);
        // a piercing missile says so in the report even when nothing went off (inert, suppressed, none)
        if (pierce && !blast.contains(",pierce")) blast += "(pierce)";
        if (interceptor != null) interceptor.end(level, outcome.name());
        MissileTracker.report(this, outcome, at, blast);
        discard();
    }

    /** Sets the warhead off for an arrival, an impact or a fuse burst; returns the report's description of what happened. */
    private String detonate(ServerLevel level, Outcome outcome, Vec3 at) {
        Blast ordered;
        if (interceptor != null) {
            // an interceptor's warhead goes off only on its fuse; spent, it falls with the spent warhead (none by default)
            ordered = outcome == Outcome.INTERCEPTED ? tier().warhead : outcome == Outcome.FELL ? InterceptorSpec.SPENT_WARHEAD : null;
        } else {
            ordered = outcome == Outcome.ARRIVED || outcome == Outcome.TERRAIN || outcome == Outcome.FELL ? tier().strikeWarhead(pierce) : null;
        }
        if (ordered == null) return "none";
        if (!level.getGameRules().get(Missiles.EXPLOSIONS)) return "inert";
        // a terrain hit lies on the block face; start the blast in the air cell in front of it
        Vec3 centre = outcome == Outcome.TERRAIN || outcome == Outcome.FELL ? at.subtract(dir.scale(0.05)) : at;
        long t0 = System.nanoTime();
        Blast applied = ordered.detonate(level, this, centre);
        double ms = (System.nanoTime() - t0) / 1.0E6;
        if (applied == null) return "suppressed";
        return String.format(Locale.ROOT, "%s%.1f%s%s%s,%.1fms", applied.equals(ordered) ? "" : "guarded:", applied.power(),
            applied.breaksBlocks() ? ",blocks" : "", applied.fire() ? ",fire" : "", applied.pierce() ? ",pierce" : "", ms);
    }

    public void abort() {
        if (level() instanceof ServerLevel server) finish(server, Outcome.ABORTED, nose());
    }

    /** Removed without {@link #finish} ({@code /kill}, shutdown): an interceptor gives its claim up at once. */
    @Override
    public void onRemoval(RemovalReason reason) {
        super.onRemoval(reason);
        if (!finished && interceptor != null && level() instanceof ServerLevel server) {
            interceptor.end(server, "REMOVED:" + reason.name().toLowerCase(Locale.ROOT));
        }
    }

    /** Test: sets the fuel left, in blocks; 0 stops the motor on the next powered tick. */
    public void setFuel(double blocks) {
        if (phase != Phase.UNPOWERED) fuel = Math.max(0.0, blocks);
    }

    /** Counts a tick the missile missed; returns how many it has missed in a row. */
    int addStall() {
        stalls++;
        return ++stallRun;
    }

    private void tickClient() {
        finsO = fins;
        fins = entityData.get(DATA_FINS);
        float thrust = entityData.get(DATA_THRUST);
        if (thrust > 0.05F && fins > 0.0F) {
            Vec3 d = Vec3.directionFromRotation(getXRot(), getYRot());
            Vec3 t = centre().subtract(d.scale(tier().length / 2.0));
            level().addParticle(ParticleTypes.FLAME, t.x, t.y, t.z, -d.x * 0.1, -d.y * 0.1, -d.z * 0.1);
            level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, t.x - d.x, t.y - d.y, t.z - d.z, 0.0, 0.01, 0.0);
        }
    }

    // ---- render accessors ----

    public float finsDeployed(float partialTicks) {
        return Mth.lerp(partialTicks, finsO, fins);
    }

    public float thrust() {
        return entityData.get(DATA_THRUST);
    }

    public boolean boosterAttached() {
        return entityData.get(DATA_BOOSTER);
    }

    // ---- reporting ----

    public Phase phase() { return phase; }
    public Vec3 target() { return target; }
    public BlockPos silo() { return silo; }
    public int flightTicks() { return flightTicks; }
    public long launchCommandTime() { return launchCommandTime; }
    public double pathLength() { return pathLength; }
    public double speed() { return speed; }
    public double maxAltitude() { return maxAltitude; }
    public double closest() { return closest; }
    public double launchRange() { return launchRange; }
    public int stalls() { return stalls; }
    public long lastTickTime() { return lastTickTime; }
    public boolean isFinished() { return finished; }
    public Vec3 direction() { return dir; }
    public double fuel() { return fuel; }
    public double fuelBudget() { return fuelBudget; }

    public Motor motor() {
        if (phase == Phase.UNPOWERED) return Motor.UNPOWERED;
        if (phase == Phase.TUBE || phase == Phase.DEPLOY || speed < tier().cruiseSpeed - 1.0E-6
            || tier() == MissileTier.T4 && boosterAttached()) return Motor.BOOST;
        return Motor.CRUISE;
    }

    /** Fuel and motor for reports and listings: {@code fuel=<left>/<budget> motor=<state>}, and the burnout if any. */
    public String fuelLine() {
        String s = String.format(Locale.ROOT, "fuel=%.1f/%.0f motor=%s", fuel, fuelBudget, motor().name().toLowerCase(Locale.ROOT));
        if (burnoutAt == null) return s;
        return s + String.format(Locale.ROOT, " burnout=%s@t%d,%.1f,%.1f,%.1f fell=%dt%s", burnoutWhy.replace(' ', '_'), burnoutTick,
            burnoutAt.x, burnoutAt.y, burnoutAt.z, flightTicks - burnoutTick, impact.isEmpty() ? "" : " " + impact.replace(' ', '_'));
    }

    public String telemetryLine() {
        Vec3 c = centre();
        double hd = Math.hypot(target.x - c.x, target.z - c.z);
        int ground = level().getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(c.x), Mth.floor(c.z));
        return String.format(Locale.ROOT,
            "#%d T%d%s t=%d %s pos=%.1f,%.1f,%.1f spd=%.2f pitch=%.1f agl=%.1f to_go=%.1f dist=%.1f flown=%.1f fins=%.2f booster=%s stalls=%d %s",
            getId(), tier().tier, pierce ? " pierce" : "", flightTicks, phase.name().toLowerCase(Locale.ROOT), c.x, c.y, c.z, speed,
            -getXRot(), c.y - ground, hd, nose().distanceTo(target), pathLength, entityData.get(DATA_FINS),
            tier() != MissileTier.T4 ? "-" : boosterAttached() ? "on" : "off", stalls, fuelLine());
    }

    // ---- vanilla behaviour switched off ----

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_TIER, 1);
        builder.define(DATA_FINS, 0.0F);
        builder.define(DATA_THRUST, 0.0F);
        builder.define(DATA_BOOSTER, true);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (DATA_TIER.equals(accessor)) refreshDimensions();
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        float w = tier().hitbox;
        return EntityDimensions.fixed(w, w);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean ignoreExplosion(Explosion explosion) {
        return true;
    }

    @Override
    public boolean canUsePortal(boolean ignorePassenger) {
        return false;
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE_ENTITY;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 512.0 * 512.0;
    }

    /** Missiles are never saved (the type is {@code noSave}); a flight does not survive a restart. */
    @Override
    protected void readAdditionalSaveData(ValueInput input) {
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
    }
}
