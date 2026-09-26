package xyz.przemyk.simpleplanes.drone;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LinearInterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.api.drone.DroneController;
import xyz.przemyk.simpleplanes.api.drone.DroneState;
import xyz.przemyk.simpleplanes.api.drone.DroneStatus;
import xyz.przemyk.simpleplanes.api.drone.PatrolDrone;
import xyz.przemyk.simpleplanes.api.drone.PatrolDrones;
import xyz.przemyk.simpleplanes.entities.crane.CraneController;
import xyz.przemyk.simpleplanes.entities.crane.MultirotorPhysics;
import xyz.przemyk.simpleplanes.entities.crane.SlungLoad;
import xyz.przemyk.simpleplanes.misc.MathUtil;
import xyz.przemyk.simpleplanes.setup.SimplePlanesComponents;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Recon/patrol drone: a small unmanned multirotor that flies a waypoint route at a fixed height above the
 * terrain, scans for living entities below it, can orbit one, and comes home. Flies on the crane's
 * {@link MultirotorPhysics} and {@link CraneController} with no load, so it moves by thrust and tilt, never by
 * setting its position. Not a {@code PlaneEntity}: air defence (which lists PlaneEntity only) ignores it.
 */
public class PatrolDroneEntity extends Entity implements PatrolDrone {

    public static final EntityDataAccessor<Quaternionfc> Q = SynchedEntityData.defineId(PatrolDroneEntity.class, EntityDataSerializers.QUATERNION);
    public static final EntityDataAccessor<Integer> HEALTH = SynchedEntityData.defineId(PatrolDroneEntity.class, EntityDataSerializers.INT);
    public static final EntityDataAccessor<Integer> TIME_SINCE_HIT = SynchedEntityData.defineId(PatrolDroneEntity.class, EntityDataSerializers.INT);
    public static final EntityDataAccessor<Float> THRUST = SynchedEntityData.defineId(PatrolDroneEntity.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Boolean> TRACKING = SynchedEntityData.defineId(PatrolDroneEntity.class, EntityDataSerializers.BOOLEAN);

    public static final int MAX_HEALTH = 12;
    public static final double DEFAULT_CRUISE_AGL = 20.0;
    public static final double MIN_CRUISE_AGL = 8.0;
    public static final double MAX_CRUISE_AGL = 48.0;
    public static final int DEFAULT_SCAN_INTERVAL = 10;
    public static final double PATROL_SPEED = 0.6;
    public static final double TRACK_SPEED = 0.8;
    public static final double CLIMB_FIRST = 3.0;
    public static final double CLIMB_FIRST_SPEED = 0.15;
    public static final double ARRIVE_RADIUS = 4.0;
    public static final double HOME_RADIUS = 1.5;
    public static final int LOOKAHEAD = 32;
    public static final double ORBIT_RADIUS = 7.0;
    public static final double ORBIT_HEIGHT = 10.0;
    public static final double ORBIT_MIN_AGL = 6.0;
    public static final double ORBIT_RATE = 2.0;
    /** A tracked target out of view this long is lost. */
    public static final int LOST_TICKS = 100;
    /** Longest the drone may fly towards a target it was told to track before it has to see it. */
    public static final int APPROACH_TICKS = 600;
    /** While tracking, the target counts as in view out to this multiple of the detection radius. */
    public static final double TRACK_VIEW = 1.5;
    public static final int SCAN_BELOW = 64;
    /** A takeoff that has not reached cruise height by now is under something and leaves sideways. */
    static final int TAKEOFF_STUCK_TICKS = 100;
    /** An airborne drone that has not moved a block in this long is boxed in. */
    static final int STUCK_TICKS = 200;
    /** A landing not finished by now parks where it is. */
    static final int LAND_TIMEOUT = 600;
    public static final int SCAN_ABOVE = 16;
    public static final double LAND_RATE = 0.15;
    public static final double LAND_ALIGN = 0.6;
    public static final double LAND_HOLD_AGL = 1.5;
    public static final double IMPACT_SPEED = 0.35;
    public static final double IMPACT_DAMAGE = 4.0;
    public static final int IMPACT_COOLDOWN = 8;
    public static final int FALL_TIMEOUT = 1200;

    public Quaternionf Q_Client = new Quaternionf();
    public Quaternionf Q_Prev = new Quaternionf();
    public float propellerRotationOld;
    public float propellerRotationNew;
    private int lerpStepsQ;

    public final MultirotorPhysics phys = new MultirotorPhysics();
    public final CraneController pilot = new CraneController();
    private final SlungLoad noLoad = new SlungLoad();

    private DroneState state = DroneState.PARKED;
    private Vec3 home = Vec3.ZERO;
    private boolean homeSet;
    private final List<Vec3> route = new ArrayList<>();
    private boolean loop = true;
    private int routeIndex;
    private double cruiseAgl = DEFAULT_CRUISE_AGL;
    private double maxRange = PatrolDrones.MAX_RANGE;
    private int scanInterval = DEFAULT_SCAN_INTERVAL;
    private boolean autoTrack;
    private @Nullable UUID trackId;
    private @Nullable Vec3 trackSeen;
    private int trackUnseen;
    private double orbitAngle;
    private boolean stowPending;
    private @Nullable UUID owner;
    private String controllerKey = "";
    private String controllerData = "";
    private @Nullable Vec3 target;

    private final Set<UUID> seenLast = new HashSet<>();
    private boolean physInit;
    private int damageTimeout;
    private int impactCooldown;
    private int stateTicks;
    private int stuckTicks;
    private @Nullable Vec3 stuckAnchor;

    public PatrolDroneEntity(EntityType<? extends PatrolDroneEntity> type, Level level) {
        super(type, level);
    }

    /** Creates a parked drone at {@code home} from an item and adds it to the level. */
    public static @Nullable PatrolDroneEntity deploy(ServerLevel level, ItemStack item, Vec3 home, @Nullable UUID owner,
                                                     String controllerKey, String controllerData) {
        PatrolDroneEntity drone = SimplePlanesEntities.PATROL_DRONE.get().create(level, EntitySpawnReason.SPAWN_ITEM_USE);
        if (drone == null) {
            return null;
        }
        drone.setPos(home.x, home.y, home.z);
        drone.applyItem(item);
        drone.home = home;
        drone.homeSet = true;
        drone.owner = owner;
        drone.controllerKey = controllerKey == null ? "" : controllerKey;
        drone.controllerData = controllerData == null ? "" : controllerData;
        if (!level.addFreshEntity(drone)) {
            return null;
        }
        DroneRegistry.track(drone, level);
        DroneRegistry.save(drone, level);
        DroneFeedback.log(drone, "deployed at " + DroneFeedback.fmt(home) + " controller=" + drone.controllerKey);
        return drone;
    }

    /** Reads health and name from a drone item. */
    public void applyItem(ItemStack item) {
        CompoundTag tag = item.get(SimplePlanesComponents.ENTITY_TAG.get());
        if (tag != null) {
            setHealth(Mth.clamp(tag.getIntOr("health", MAX_HEALTH), 1, MAX_HEALTH));
        }
        Component name = item.get(DataComponents.CUSTOM_NAME);
        if (name != null) {
            setCustomName(name);
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(Q, new Quaternionf());
        builder.define(HEALTH, MAX_HEALTH);
        builder.define(TIME_SINCE_HIT, 0);
        builder.define(THRUST, 0.0F);
        builder.define(TRACKING, false);
    }

    @Override
    protected InterpolationHandler createInterpolationHandler() {
        return LinearInterpolationHandler.create(this, 3);
    }

    // ---- synched state ----

    public Quaternionf getQ() {
        return new Quaternionf(entityData.get(Q));
    }

    public void setQ(Quaternionf q) {
        entityData.set(Q, q);
    }

    public int getHealth() {
        return entityData.get(HEALTH);
    }

    public void setHealth(int health) {
        entityData.set(HEALTH, Math.max(health, 0));
    }

    public int getTimeSinceHit() {
        return entityData.get(TIME_SINCE_HIT);
    }

    public void setTimeSinceHit(int ticks) {
        entityData.set(TIME_SINCE_HIT, ticks);
    }

    public float getThrust() {
        return entityData.get(THRUST);
    }

    public boolean isTracking() {
        return entityData.get(TRACKING);
    }

    // ---- entity behaviour ----

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean canBeCollidedWith(@Nullable Entity other) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(SimplePlanesItems.PATROL_DRONE_ITEM.get());
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            clientTick();
            return;
        }
        ServerLevel level = (ServerLevel) level();
        if (!physInit) {
            initPhysics();
        }
        if (state != DroneState.FALLING) {
            // a falling drone is already written off; tracking it again would put it back on the roster
            DroneRegistry.track(this, level);
        }
        if (damageTimeout > 0) {
            damageTimeout--;
        }
        if (getTimeSinceHit() > 0) {
            setTimeSinceHit(getTimeSinceHit() - 1);
        }
        if (impactCooldown > 0) {
            impactCooldown--;
        }
        stateTicks++;
        phys.p[0] = getX();
        phys.p[1] = getY();
        phys.p[2] = getZ();

        if (state == DroneState.FALLING) {
            tickFalling(level);
            return;
        }
        tickState(level);
        if (isRemoved()) {
            return;
        }
        if (state == DroneState.PARKED || target == null) {
            phys.thrust = 0;
            phys.attitude(0, 0, phys.yaw);
            pilot.saturated = false;
            if (onGround()) {
                phys.v[0] = phys.v[2] = 0;
            }
        } else {
            pilot.control(phys, noLoad, target.x, target.y, target.z);
        }
        physicsStep(level);
        syncOut();
        watchStuck();
        if (state.airborne() && state != DroneState.FALLING && (tickCount + getId()) % scanInterval == 0) {
            scan(level);
        }
    }

    private void initPhysics() {
        physInit = true;
        phys.yaw = getYRot();
        Vec3 v = getDeltaMovement();
        phys.v[0] = v.x;
        phys.v[1] = v.y;
        phys.v[2] = v.z;
        phys.mass = 1;
        if (!homeSet) {
            home = position();
            homeSet = true;
        }
    }

    private void physicsStep(ServerLevel level) {
        double vx0 = phys.v[0], vy0 = phys.v[1], vz0 = phys.v[2];
        phys.step(null);
        Vec3 wanted = new Vec3(phys.v[0], phys.v[1], phys.v[2]);
        Vec3 before = position();
        setDeltaMovement(wanted);
        move(MoverType.SELF, wanted);
        Vec3 achieved = position().subtract(before);
        double[] w = {wanted.x, wanted.y, wanted.z};
        double[] got = {achieved.x, achieved.y, achieved.z};
        double worst = 0;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(w[i] - got[i]) > 1.0E-4) {
                worst = Math.max(worst, Math.abs(w[i]));
                phys.v[i] = got[i];
            }
        }
        phys.a[0] = phys.v[0] - vx0;
        phys.a[1] = phys.v[1] - vy0;
        phys.a[2] = phys.v[2] - vz0;
        phys.p[0] = getX();
        phys.p[1] = getY();
        phys.p[2] = getZ();
        setDeltaMovement(phys.v[0], phys.v[1], phys.v[2]);
        if (worst > IMPACT_SPEED && impactCooldown == 0 && state != DroneState.FALLING && state != DroneState.PARKED) {
            impactCooldown = IMPACT_COOLDOWN;
            float damage = (float) (IMPACT_DAMAGE * (worst - IMPACT_SPEED) + 1.0);
            DroneFeedback.log(this, String.format(Locale.ROOT, "impact at %.2f b/t, %.1f damage", worst, damage));
            applyDamage(level, damage, null);
        }
    }

    private void syncOut() {
        setYRot((float) phys.yaw);
        setQ(MathUtil.toQuaternionf(phys.yaw, -phys.pitch, -phys.roll));
        entityData.set(THRUST, (float) phys.thrust);
    }

    private void clientTick() {
        propellerRotationOld = propellerRotationNew;
        propellerRotationNew += (float) (getThrust() > 0.001F ? 0.9 + 5.0 * getThrust() / MultirotorPhysics.T_MAX : 0.0);
        if (getTimeSinceHit() > 0) {
            setTimeSinceHit(getTimeSinceHit() - 1);
        }
        getInterpolation().interpolate();
        if (lerpStepsQ > 0) {
            Q_Prev = new Quaternionf(Q_Client);
            Q_Client = MathUtil.lerpQ(1f / lerpStepsQ, new Quaternionf(Q_Client), getQ());
            --lerpStepsQ;
        } else if (lerpStepsQ == 0) {
            Q_Prev = new Quaternionf(Q_Client);
            Q_Client = getQ();
            --lerpStepsQ;
        } else {
            Q_Prev = new Quaternionf(Q_Client);
        }
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (Q.equals(key) && level().isClientSide()) {
            if (firstTick) {
                lerpStepsQ = 0;
                Q_Client = getQ();
                Q_Prev = getQ();
            } else {
                lerpStepsQ = 3;
            }
        }
    }

    // ---- state machine ----

    private void enter(DroneState next) {
        if (next != state) {
            DroneFeedback.log(this, "state " + state + " -> " + next);
        }
        state = next;
        stateTicks = 0;
        pilot.vMax = CraneController.V_MAX;
        pilot.vzMax = CraneController.VZ_MAX;
        entityData.set(TRACKING, next == DroneState.TRACKING);
        if (next != DroneState.TRACKING) {
            trackId = null;
            trackSeen = null;
        }
    }

    private void tickState(ServerLevel level) {
        switch (state) {
            case PARKED -> target = null;
            case TAKEOFF -> {
                double ground = surface(getX(), getZ());
                target = new Vec3(getX(), ground + cruiseAgl, getZ());
                pilot.vMax = 0.05;
                if (getY() >= ground + cruiseAgl - 1.5) {
                    enter(DroneState.PATROL);
                } else if (stateTicks > TAKEOFF_STUCK_TICKS) {
                    // something overhead: climb out sideways along the route instead
                    DroneFeedback.log(this, "takeoff blocked overhead, leaving sideways");
                    enter(DroneState.PATROL);
                }
            }
            case PATROL -> tickPatrol();
            case TRACKING -> tickTracking(level);
            case RETURNING -> {
                flyTo(home, PATROL_SPEED);
                if (horizontal(home) < HOME_RADIUS) {
                    enter(DroneState.LANDING);
                }
            }
            case LANDING -> tickLanding(level);
            default -> { }
        }
    }

    /**
     * Frees a drone boxed in by blocks (launched inside a roof, walled in by a build): one that wants to go somewhere
     * and has not moved for {@link #STUCK_TICKS} is lifted onto the surface of its column.
     */
    private void watchStuck() {
        boolean wantsToMove = target != null && state != DroneState.LANDING && state != DroneState.PARKED
            && (Math.hypot(target.x - getX(), target.z - getZ()) > 2.0 || Math.abs(target.y - getY()) > 2.0);
        if (!wantsToMove) {
            stuckTicks = 0;
            stuckAnchor = null;
            return;
        }
        if (stuckAnchor == null || stuckAnchor.distanceToSqr(position()) > 1.0) {
            stuckAnchor = position();
            stuckTicks = 0;
            return;
        }
        if (++stuckTicks >= STUCK_TICKS) {
            double top = surface(getX(), getZ()) + 1.0;
            if (top > getY()) {
                DroneFeedback.log(this, "boxed in, lifted from " + (int) getY() + " to " + (int) top);
                setPos(getX(), top, getZ());
                phys.v[0] = phys.v[1] = phys.v[2] = 0;
                setDeltaMovement(Vec3.ZERO);
            }
            stuckTicks = 0;
            stuckAnchor = null;
        }
    }

    private void tickPatrol() {
        if (route.isEmpty()) {
            flyTo(home, PATROL_SPEED);
            return;
        }
        if (routeIndex < 0 || routeIndex >= route.size()) {
            routeIndex = 0;
        }
        Vec3 wp = route.get(routeIndex);
        if (horizontal(wp) < ARRIVE_RADIUS) {
            routeIndex++;
            if (routeIndex >= route.size()) {
                if (!loop) {
                    DroneFeedback.log(this, "route complete, returning home");
                    enter(DroneState.RETURNING);
                    return;
                }
                routeIndex = 0;
            }
            wp = route.get(routeIndex);
        }
        flyTo(wp, PATROL_SPEED);
    }

    /** Sets the target over (x, z) of {@code point} at cruise height, climbing first if low. */
    private void flyTo(Vec3 point, double speed) {
        double dx = point.x - getX();
        double dz = point.z - getZ();
        double dist = Math.hypot(dx, dz);
        double cruise = cruiseSurface(dx, dz, dist) + cruiseAgl;
        pilot.vMax = getY() < cruise - CLIMB_FIRST ? CLIMB_FIRST_SPEED : speed;
        target = new Vec3(point.x, cruise, point.z);
    }

    private void tickTracking(ServerLevel level) {
        Entity e = trackId == null ? null : level.getEntity(trackId);
        if (e == null || !e.isAlive() || e.isRemoved()) {
            lostTrack(DroneController.LostReason.GONE);
            return;
        }
        if (Math.hypot(e.getX() - home.x, e.getZ() - home.z) > maxRange) {
            lostTrack(DroneController.LostReason.OUT_OF_RANGE);
            return;
        }
        double view = PatrolDrones.DETECTION_RADIUS * TRACK_VIEW;
        if (horizontal(e.position()) <= view && openToSky(level, e)) {
            trackSeen = e.position();
            trackUnseen = 0;
        } else if (trackSeen != null && horizontal(trackSeen) > view && stateTicks < APPROACH_TICKS) {
            // still flying to where it was last seen: not lost yet
        } else if (++trackUnseen > LOST_TICKS) {
            lostTrack(DroneController.LostReason.OUT_OF_SIGHT);
            return;
        }
        Vec3 c = trackSeen == null ? e.position() : trackSeen;
        orbitAngle = Mth.wrapDegrees(orbitAngle + ORBIT_RATE);
        double a = Math.toRadians(orbitAngle);
        double tx = c.x + ORBIT_RADIUS * Math.cos(a);
        double tz = c.z + ORBIT_RADIUS * Math.sin(a);
        double ground = Math.max(surface(tx, tz), surface(getX(), getZ()));
        pilot.vMax = TRACK_SPEED;
        target = new Vec3(tx, Math.max(c.y + ORBIT_HEIGHT, ground + ORBIT_MIN_AGL), tz);
    }

    private void lostTrack(DroneController.LostReason reason) {
        UUID id = trackId;
        Vec3 seen = trackSeen;
        enter(DroneState.PATROL);
        resumeRoute();
        if (id != null) {
            DroneFeedback.log(this, "lost track of " + id + " (" + reason + ")");
            DroneController c = controller();
            try {
                c.onLostTrack(this, id, seen, reason);
            } catch (RuntimeException | LinkageError ex) {
                controllerFailed("onLostTrack", ex);
            }
        }
    }

    private void tickLanding(ServerLevel level) {
        pilot.vzMax = LAND_RATE;
        double ground = surface(home.x, home.z);
        boolean over = horizontal(home) < LAND_ALIGN;
        // aim below the surface: the heightmap also counts blocks without collision (torches, banners), so the
        // drone sinks until it actually touches something
        target = new Vec3(home.x, over ? ground - 2.0 : Math.max(getY(), ground + LAND_HOLD_AGL), home.z);
        pilot.vMax = 0.2;
        if (onGround() && over || onGround() && stateTicks > 400 || stateTicks > LAND_TIMEOUT) {
            phys.v[0] = phys.v[1] = phys.v[2] = 0;
            setDeltaMovement(Vec3.ZERO);
            target = null;
            enter(DroneState.PARKED);
            DroneRegistry.save(this, level);
            if (stowPending) {
                finishStow(level);
                return;
            }
            DroneController c = controller();
            try {
                c.onReturned(this);
            } catch (RuntimeException | LinkageError ex) {
                controllerFailed("onReturned", ex);
            }
        }
    }

    /** Picks the route point nearest the drone, so a resumed patrol does not double back to the start. */
    private void resumeRoute() {
        if (route.isEmpty()) {
            routeIndex = 0;
            return;
        }
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            double d = horizontal(route.get(i));
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        routeIndex = best;
    }

    // ---- terrain ----

    /** Highest surface under the drone and along the track ahead, in loaded chunks only. */
    private double cruiseSurface(double dx, double dz, double dist) {
        double h = surface(getX(), getZ());
        if (dist < 1.0E-3) {
            return h;
        }
        double ux = dx / dist, uz = dz / dist;
        double reach = Math.min(LOOKAHEAD, dist);
        for (double s = 4; s <= reach; s += 4) {
            for (int side = -2; side <= 2; side += 2) {
                int bx = Mth.floor(getX() + ux * s - uz * side);
                int bz = Mth.floor(getZ() + uz * s + ux * side);
                if (level().hasChunk(bx >> 4, bz >> 4)) {
                    h = Math.max(h, level().getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz));
                }
            }
        }
        return h;
    }

    public double surface(double x, double z) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        if (!level().hasChunk(bx >> 4, bz >> 4)) {
            return getY() - cruiseAgl;
        }
        // any non-air block: banners, lanterns and slabs of decorative blocks are not all motion-blocking
        return level().getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
    }

    public double agl() {
        return getY() - surface(getX(), getZ());
    }

    private double horizontal(Vec3 p) {
        return Math.hypot(p.x - getX(), p.z - getZ());
    }

    /** Standing on or above the terrain surface, i.e. not in a cave, under a roof or underwater deep down. */
    static boolean openToSky(ServerLevel level, Entity e) {
        int bx = Mth.floor(e.getX()), bz = Mth.floor(e.getZ());
        if (!level.hasChunk(bx >> 4, bz >> 4)) {
            return false;
        }
        return e.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) - 1.0;
    }

    // ---- scanning ----

    private void scan(ServerLevel level) {
        double r = PatrolDrones.DETECTION_RADIUS;
        AABB box = new AABB(getX() - r, getY() - SCAN_BELOW, getZ() - r, getX() + r, getY() + SCAN_ABOVE, getZ() + r);
        DroneController c = controller();
        List<LivingEntity> seen = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
            if (e.isSpectator() || horizontal(e.position()) > r || !openToSky(level, e)) {
                continue;
            }
            boolean match;
            try {
                match = c.isTarget(this, e);
            } catch (RuntimeException | LinkageError ex) {
                controllerFailed("isTarget", ex);
                return;
            }
            if (match) {
                seen.add(e);
            }
        }
        seen.sort(Comparator.comparingDouble(e -> e.distanceToSqr(this)));
        Set<UUID> now = new HashSet<>();
        for (LivingEntity e : seen) {
            now.add(e.getUUID());
            if (!seenLast.contains(e.getUUID())) {
                try {
                    c.onSighting(this, e);
                } catch (RuntimeException | LinkageError ex) {
                    controllerFailed("onSighting", ex);
                }
                if (isRemoved()) {
                    return;
                }
            }
        }
        seenLast.clear();
        seenLast.addAll(now);
        try {
            c.onScan(this, seen);
        } catch (RuntimeException | LinkageError ex) {
            controllerFailed("onScan", ex);
        }
        if (autoTrack && state == DroneState.PATROL && !seen.isEmpty() && !isRemoved()) {
            track(seen.get(0));
        }
    }

    private DroneController controller() {
        DroneController c = PatrolDrones.controller(controllerKey);
        return c == null ? DroneFeedback.DEFAULT : c;
    }

    private void controllerFailed(String what, Throwable ex) {
        DroneFeedback.LOGGER.error("Drone #{}: controller '{}' threw from {}", getId(), controllerKey, what, ex);
    }

    // ---- PatrolDrone ----

    @Override
    public UUID id() {
        return getUUID();
    }

    @Override
    public Entity asEntity() {
        return this;
    }

    @Override
    public boolean isValid() {
        return !isRemoved();
    }

    @Override
    public DroneState state() {
        return state;
    }

    @Override
    public DroneStatus status() {
        return new DroneStatus(getUUID(), getId(), state, position(), home, route.size(), route.isEmpty() ? -1 : routeIndex,
            loop, trackId, trackSeen, getHealth(), MAX_HEALTH, maxRange, controllerKey);
    }

    @Override
    public Vec3 home() {
        return home;
    }

    @Override
    public void setHome(Vec3 home) {
        this.home = home;
        this.homeSet = true;
    }

    @Override
    public List<Vec3> route() {
        return List.copyOf(route);
    }

    @Override
    public boolean loops() {
        return loop;
    }

    @Override
    public int setRoute(List<Vec3> points, boolean loop) {
        route.clear();
        int refused = 0;
        for (Vec3 p : points) {
            if (Math.hypot(p.x - home.x, p.z - home.z) > maxRange) {
                refused++;
            } else {
                route.add(new Vec3(p.x, 0, p.z));
            }
        }
        this.loop = loop;
        if (state.airborne()) {
            resumeRoute();
        } else {
            routeIndex = 0;
        }
        if (refused > 0) {
            DroneFeedback.log(this, refused + " waypoint(s) refused: beyond " + (int) maxRange + " blocks of home");
        }
        if (state != DroneState.TRACKING) {
            launch();
        }
        return refused;
    }

    @Override
    public void setMaxRange(double blocks) {
        maxRange = Mth.clamp(blocks, 16.0, PatrolDrones.MAX_RANGE);
    }

    @Override
    public double maxRange() {
        return maxRange;
    }

    @Override
    public void setCruiseHeight(double blocksAboveTerrain) {
        cruiseAgl = Mth.clamp(blocksAboveTerrain, MIN_CRUISE_AGL, MAX_CRUISE_AGL);
    }

    @Override
    public void setScanInterval(int ticks) {
        scanInterval = Mth.clamp(ticks, 5, 100);
    }

    @Override
    public void setAutoTrack(boolean autoTrack) {
        this.autoTrack = autoTrack;
    }

    @Override
    public void launch() {
        if (state == DroneState.FALLING) {
            return;
        }
        stowPending = false;
        if (state == DroneState.PARKED) {
            enter(DroneState.TAKEOFF);
        } else if (state == DroneState.RETURNING || state == DroneState.LANDING) {
            enter(onGround() ? DroneState.TAKEOFF : DroneState.PATROL);
            resumeRoute();
        }
    }

    @Override
    public void track(Entity target) {
        if (state == DroneState.FALLING || target == this) {
            return;
        }
        if (Math.hypot(target.getX() - home.x, target.getZ() - home.z) > maxRange) {
            return;
        }
        if (state == DroneState.TRACKING && target.getUUID().equals(trackId)) {
            return;
        }
        if (state == DroneState.TRACKING && trackId != null) {
            lostTrack(DroneController.LostReason.RELEASED);
        }
        stowPending = false;
        enter(DroneState.TRACKING);
        trackId = target.getUUID();
        trackSeen = target.position();
        trackUnseen = 0;
        orbitAngle = Math.toDegrees(Math.atan2(getZ() - target.getZ(), getX() - target.getX()));
        DroneFeedback.log(this, "tracking " + target.getName().getString() + " at " + DroneFeedback.fmt(target.position()));
    }

    @Override
    public void stopTracking() {
        if (state == DroneState.TRACKING) {
            lostTrack(DroneController.LostReason.RELEASED);
        }
    }

    @Override
    public @Nullable UUID trackedTarget() {
        return state == DroneState.TRACKING ? trackId : null;
    }

    @Override
    public @Nullable Vec3 trackedPosition() {
        return state == DroneState.TRACKING ? trackSeen : null;
    }

    @Override
    public void returnHome() {
        if (state == DroneState.FALLING || state == DroneState.PARKED || state == DroneState.LANDING) {
            return;
        }
        if (state == DroneState.TRACKING) {
            stopTracking();
        }
        enter(DroneState.RETURNING);
    }

    @Override
    public void stow() {
        if (state == DroneState.FALLING) {
            return;
        }
        stowPending = true;
        if (state == DroneState.PARKED && level() instanceof ServerLevel level) {
            finishStow(level);
        } else {
            returnHome();
        }
    }

    private void finishStow(ServerLevel level) {
        stowPending = false;
        ItemStack item = toItem();
        DroneRegistry.forget(this, level);
        boolean taken = false;
        try {
            taken = controller().onStowed(this, item);
        } catch (RuntimeException | LinkageError ex) {
            controllerFailed("onStowed", ex);
        }
        if (!taken) {
            spawnAtLocation(level, item);
        }
        DroneFeedback.log(this, "stowed" + (taken ? "" : ", item dropped"));
        discard();
    }

    @Override
    public ItemStack pack() {
        ItemStack item = toItem();
        if (level() instanceof ServerLevel level) {
            DroneRegistry.forget(this, level);
        }
        DroneFeedback.log(this, "packed");
        discard();
        return item;
    }

    public ItemStack toItem() {
        ItemStack stack = new ItemStack(SimplePlanesItems.PATROL_DRONE_ITEM.get());
        if (getHealth() < MAX_HEALTH) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("health", Math.max(1, getHealth()));
            stack.set(SimplePlanesComponents.ENTITY_TAG.get(), tag);
        }
        if (hasCustomName()) {
            stack.set(DataComponents.CUSTOM_NAME, getCustomName());
        }
        return stack;
    }

    @Override
    public String controllerKey() {
        return controllerKey;
    }

    @Override
    public String controllerData() {
        return controllerData;
    }

    @Override
    public void setController(String key, String data) {
        controllerKey = key == null ? "" : key;
        controllerData = data == null ? "" : data;
    }

    @Override
    public @Nullable UUID owner() {
        return owner;
    }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
    }

    public @Nullable Vec3 getTarget() {
        return target;
    }

    public int scanInterval() {
        return scanInterval;
    }

    public double cruiseAgl() {
        return cruiseAgl;
    }

    public boolean autoTrack() {
        return autoTrack;
    }

    // ---- damage and death ----

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (isRemoved() || isInvulnerableToBase(source) || damageTimeout > 0 || state == DroneState.FALLING) {
            return false;
        }
        try {
            if (controller().ignoresDamage(this, source)) {
                return false;
            }
        } catch (RuntimeException | LinkageError ex) {
            controllerFailed("ignoresDamage", ex);
        }
        setTimeSinceHit(20);
        damageTimeout = 10;
        if (state == DroneState.PARKED && source.getDirectEntity() instanceof Player player) {
            if (player.getAbilities().instabuild) {
                destroyed(level, source, "removed by a creative player");
                discard();
            } else {
                pickedUp(level, player);
            }
            return true;
        }
        applyDamage(level, amount, source);
        return true;
    }

    private void applyDamage(ServerLevel level, float amount, @Nullable DamageSource source) {
        setHealth((int) Math.ceil(getHealth() - amount));
        boolean creative = source != null && source.getEntity() instanceof Player player && player.getAbilities().instabuild;
        if (creative) {
            destroyed(level, source, "removed by a creative player");
            discard();
        } else if (getHealth() <= 0) {
            enter(DroneState.FALLING);
            destroyed(level, source, String.format(Locale.ROOT, "shot down at %s (agl %.1f), falling", DroneFeedback.fmt(position()), agl()));
        }
    }

    private void destroyed(ServerLevel level, @Nullable DamageSource source, String message) {
        DroneFeedback.report(this, "destroyed: " + message);
        DroneRegistry.forget(this, level);
        try {
            controller().onDestroyed(this, position(), source);
        } catch (RuntimeException | LinkageError ex) {
            controllerFailed("onDestroyed", ex);
        }
    }

    private void pickedUp(ServerLevel level, Player player) {
        ItemStack item = toItem();
        DroneRegistry.forget(this, level);
        DroneFeedback.log(this, "picked up by " + player.getName().getString());
        try {
            controller().onPickedUp(this, player);
        } catch (RuntimeException | LinkageError ex) {
            controllerFailed("onPickedUp", ex);
        }
        if (level.getGameRules().get(GameRules.ENTITY_DROPS)) {
            spawnAtLocation(level, item);
        }
        discard();
    }

    private void tickFalling(ServerLevel level) {
        phys.thrust = 0;
        pilot.saturated = false;
        phys.attitude(phys.pitch + 2.0, phys.roll + 3.0, phys.yaw + 10.0);
        physicsStep(level);
        syncOut();
        if (onGround() || horizontalCollision || verticalCollision || stateTicks > FALL_TIMEOUT) {
            level.sendParticles(ParticleTypes.EXPLOSION, getX(), getY() + 0.3, getZ(), 1, 0, 0, 0, 0);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 0.3, getZ(), 12, 0.4, 0.3, 0.4, 0.02);
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.NEUTRAL, 0.5F, 1.6F);
            DroneFeedback.log(this, "crashed at " + DroneFeedback.fmt(position()));
            discard();
        }
    }

    // ---- persistence ----

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        setHealth(input.getIntOr("health", MAX_HEALTH));
        state = input.getString("state").map(PatrolDroneEntity::parseState).orElse(DroneState.PARKED);
        home = input.read("home", Vec3.CODEC).orElse(position());
        homeSet = true;
        route.clear();
        route.addAll(input.read("route", Vec3.CODEC.listOf()).orElse(List.of()));
        loop = input.getBooleanOr("loop", true);
        routeIndex = input.getIntOr("route_index", 0);
        cruiseAgl = Mth.clamp(input.getDoubleOr("cruise_agl", DEFAULT_CRUISE_AGL), MIN_CRUISE_AGL, MAX_CRUISE_AGL);
        maxRange = Mth.clamp(input.getDoubleOr("max_range", PatrolDrones.MAX_RANGE), 16.0, PatrolDrones.MAX_RANGE);
        scanInterval = Mth.clamp(input.getIntOr("scan_interval", DEFAULT_SCAN_INTERVAL), 5, 100);
        autoTrack = input.getBooleanOr("auto_track", false);
        trackId = input.read("track", UUIDUtil.CODEC).orElse(null);
        trackSeen = input.read("track_seen", Vec3.CODEC).orElse(null);
        stowPending = input.getBooleanOr("stow", false);
        owner = input.read("owner", UUIDUtil.CODEC).orElse(null);
        controllerKey = input.getStringOr("controller", "");
        controllerData = input.getStringOr("controller_data", "");
        if (state == DroneState.TRACKING && trackId == null) {
            state = DroneState.PATROL;
        }
        if (state == DroneState.FALLING) {
            // it was already reported destroyed; finish the fall
            stateTicks = 0;
        }
        entityData.set(TRACKING, state == DroneState.TRACKING);
        physInit = false;
        Quaternionf q = MathUtil.toQuaternionf(getYRot(), 0, 0);
        setQ(q);
        Q_Client = new Quaternionf(q);
        Q_Prev = new Quaternionf(q);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putInt("health", getHealth());
        output.putString("state", state.name());
        output.store("home", Vec3.CODEC, home);
        output.store("route", Vec3.CODEC.listOf(), List.copyOf(route));
        output.putBoolean("loop", loop);
        output.putInt("route_index", routeIndex);
        output.putDouble("cruise_agl", cruiseAgl);
        output.putDouble("max_range", maxRange);
        output.putInt("scan_interval", scanInterval);
        output.putBoolean("auto_track", autoTrack);
        output.storeNullable("track", UUIDUtil.CODEC, trackId);
        output.storeNullable("track_seen", Vec3.CODEC, trackSeen);
        output.putBoolean("stow", stowPending);
        output.storeNullable("owner", UUIDUtil.CODEC, owner);
        output.putString("controller", controllerKey);
        output.putString("controller_data", controllerData);
    }

    private static DroneState parseState(String name) {
        try {
            return DroneState.valueOf(name);
        } catch (IllegalArgumentException e) {
            return DroneState.PARKED;
        }
    }

    /** Loads the drone's saved fields from a raw tag (the item's entity tag). */
    public void loadFromItemTag(CompoundTag tag) {
        readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, registryAccess(), tag));
        state = DroneState.PARKED;
    }
}
