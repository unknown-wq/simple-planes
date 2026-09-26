package xyz.przemyk.simpleplanes.missile;

import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * A launched missile. Server-authoritative kinematics (no vanilla physics, no collision with entities); the
 * client only interpolates the synced position and rotation and draws the trail.
 *
 * <p>The authoritative point is the centre of the length, {@code C}; the entity position is the bottom of a
 * cube hitbox centred on it, as MISSILES-MODEL.md suggests. Impact and arrival are tested every tick on the
 * segment the nose swept, so neither depends on the hitbox.
 *
 * <p>Harmless by construction: nothing here creates an explosion, hurts an entity, sets a fire or changes a
 * block. Every way a flight can end goes through {@link #finish}, which makes a puff of particles and discards.
 */
public class MissileEntity extends Entity {

    private static final EntityDataAccessor<Integer> DATA_TIER = SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_FINS = SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_THRUST = SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_BOOSTER = SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.BOOLEAN);

    private static final Vec3 UP = new Vec3(0, 1, 0);

    public enum Phase { TUBE, DEPLOY, MIDCOURSE, TERMINAL }

    public enum Outcome { ARRIVED, TERRAIN, FUEL, TIMEOUT, OUT_OF_WORLD, ABORTED, REMOVED }

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
    private long lastTickTime = -1;
    private boolean finished;
    private float lastYaw;

    // client state
    private float finsO;
    private float fins;

    public MissileEntity(EntityType<? extends MissileEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /** Creates a missile seated in the silo's tube and adds it to the world. The silo calls this when its hatch is open. */
    public static MissileEntity launch(ServerLevel level, BlockPos silo, MissileTier tier, Vec3 target, long launchCommandTime) {
        MissileEntity m = new MissileEntity(Missiles.MISSILE, level);
        m.entityData.set(DATA_TIER, tier.tier);
        m.refreshDimensions();
        m.silo = silo.immutable();
        m.mouth = SiloStructure.mouth(silo, tier);
        m.target = target;
        m.launchCommandTime = launchCommandTime;
        m.launchRange = Math.hypot(target.x - m.mouth.x, target.z - m.mouth.z);
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
        flightTicks++;
        Vec3 prevNose = nose();

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
                if (deployed >= 1.0F) phase = Phase.MIDCOURSE;
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

        if (phase == Phase.TUBE && tail().y >= mouth.y + 0.25) {
            phase = Phase.DEPLOY;
            exitTick = flightTicks;
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
        });

        if (phase != Phase.TUBE && checkEnd(level, tier, prevNose)) return;

        if (pathLength > tier.maxRange * 1.3 + 200.0) {
            finish(level, Outcome.FUEL, nose());
        } else if (flightTicks > (int) (tier.maxRange * 1.3 / tier.cruiseSpeed) + 600) {
            finish(level, Outcome.TIMEOUT, nose());
        } else if (c.y < level.getMinY() - 16 || c.y > level.getMaxY() + 256) {
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
        if (hd < 0.5 || depression >= MissileTier.DIVE_ANGLE) {
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

    /** Ends the flight: a harmless puff at {@code at}, a report, and the entity is removed. */
    void finish(ServerLevel level, Outcome outcome, Vec3 at) {
        if (finished) return;
        finished = true;
        MissileFx.puff(level, at, tier());
        MissileTracker.report(this, outcome, at);
        discard();
    }

    public void abort() {
        if (level() instanceof ServerLevel server) finish(server, Outcome.ABORTED, nose());
    }

    void addStall() {
        stalls++;
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

    public String telemetryLine() {
        Vec3 c = centre();
        double hd = Math.hypot(target.x - c.x, target.z - c.z);
        int ground = level().getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(c.x), Mth.floor(c.z));
        return String.format(Locale.ROOT,
            "#%d T%d t=%d %s pos=%.1f,%.1f,%.1f spd=%.2f pitch=%.1f agl=%.1f to_go=%.1f dist=%.1f flown=%.1f fins=%.2f booster=%s stalls=%d",
            getId(), tier().tier, flightTicks, phase.name().toLowerCase(Locale.ROOT), c.x, c.y, c.z, speed,
            -getXRot(), c.y - ground, hd, nose().distanceTo(target), pathLength, entityData.get(DATA_FINS),
            boosterAttached() ? "on" : "off", stalls);
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
