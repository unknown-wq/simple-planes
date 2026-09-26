package xyz.przemyk.simpleplanes.airdefence;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.UUID;

/**
 * The pursuit half of an air-defence missile: which aircraft it chases, its measured track, the lead point,
 * the fuse and the range budget. The missile entity owns one of these when launched in air-defence mode and
 * asks it for a direction before it moves and for a verdict after.
 */
public final class Interceptor {

    public enum Verdict { NONE, DETONATE, LOST, OUT_OF_RANGE }

    /** Weight of the newest sample in the target velocity estimate. */
    private static final double TRACK_GAIN = 0.5;
    /** A jump larger than this between two samples is a teleport, not flight. */
    private static final double TELEPORT = 16.0;
    /** Minimum height above the ground the lead point is allowed to drag the missile down to. */
    private static final double GROUND_CLEARANCE = 3.0;

    public final InterceptorSpec spec;
    private final Engagements.MissileEngager engager;
    private UUID targetId;
    private int targetEntityId;
    private @Nullable Vec3 lastSeen;
    private Vec3 velocity = Vec3.ZERO;
    private Vec3 aim = Vec3.ZERO;
    private double closest = Double.POSITIVE_INFINITY;
    private int retargets;
    private @Nullable Vec3 detonation;

    public Interceptor(InterceptorSpec spec, PlaneEntity target, int missileId) {
        this.spec = spec;
        this.engager = new Engagements.MissileEngager(missileId);
        setTarget(target);
    }

    private void setTarget(PlaneEntity target) {
        targetId = target.getUUID();
        targetEntityId = target.getId();
        lastSeen = AircraftRoster.aimPoint(target);
        velocity = target.getDeltaMovement();
        aim = lastSeen;
        closest = Double.POSITIVE_INFINITY;
    }

    public UUID targetId() { return targetId; }
    public int targetEntityId() { return targetEntityId; }
    public Vec3 aim() { return aim; }
    public Vec3 targetVelocity() { return velocity; }
    public double closest() { return closest; }
    public int retargets() { return retargets; }
    public @Nullable Vec3 detonation() { return detonation; }
    public @Nullable Vec3 lastSeen() { return lastSeen; }

    private @Nullable PlaneEntity resolve(ServerLevel level) {
        Entity e = level.getEntity(targetId);
        return e instanceof PlaneEntity p && AircraftRoster.isEngageable(p, level) ? p : null;
    }

    /** Refreshes the track and the engagement claim; re-targets if the aircraft is gone. False if nothing is left to chase. */
    public boolean track(ServerLevel level, Vec3 nose) {
        PlaneEntity target = resolve(level);
        if (target == null) {
            Engagements.release(engager);
            PlaneEntity next = TargetSelector.reacquire(level, nose, spec, engager);
            if (next == null) return false;
            retargets++;
            setTarget(next);
            target = next;
        }
        Vec3 now = AircraftRoster.aimPoint(target);
        Vec3 step = lastSeen == null ? Vec3.ZERO : now.subtract(lastSeen);
        if (lastSeen == null || step.length() > TELEPORT) {
            velocity = target.getDeltaMovement();
        } else {
            velocity = velocity.scale(1.0 - TRACK_GAIN).add(step.scale(TRACK_GAIN));
        }
        lastSeen = now;
        Engagements.claim(engager, targetId, level.getGameTime(), Engagements.MISSILE_CLAIM_TICKS);
        return true;
    }

    /** Direction for this tick. */
    public Vec3 steer(ServerLevel level, Vec3 nose, Vec3 dir, double speed) {
        if (lastSeen == null) return dir;
        Vec3 lead = Pursuit.aimPoint(nose, Math.max(speed, spec.speed() * 0.5), lastSeen, velocity);
        int ground = groundAt(level, lead.x, lead.z);
        if (lead.y < ground + GROUND_CLEARANCE) lead = new Vec3(lead.x, ground + GROUND_CLEARANCE, lead.z);
        aim = lead;
        return Pursuit.steer(nose, dir, speed, lead, spec.turnRate);
    }

    /**
     * Fuse and budget, after the missile moved from {@code nose0} to {@code nose1}. {@code path} is the path
     * flown since the missile left the tube.
     */
    public Verdict check(ServerLevel level, Vec3 nose0, Vec3 nose1, double path) {
        PlaneEntity target = resolve(level);
        if (target != null && lastSeen != null && path >= InterceptorSpec.ARMING_PATH) {
            // the aircraft may tick after the missile in this tick: extrapolate it over the same interval
            Vec3 p0 = AircraftRoster.aimPoint(target);
            Pursuit.Approach a = Pursuit.closestApproach(nose0, nose1, p0, p0.add(velocity), target.getBoundingBox());
            closest = Math.min(closest, a.distance());
            if (a.distance() <= spec.fuseRadius && (a.t() < 1.0 || a.distance() <= 1.0)) {
                detonation = a.missileAt();
                return Verdict.DETONATE;
            }
        }
        if (path >= spec.range) return Verdict.OUT_OF_RANGE;
        return Verdict.NONE;
    }

    /** Report fragment: the aircraft, its health now, the closest approach and the re-targets. */
    public String describe(ServerLevel level) {
        Entity e = level.getEntity(targetId);
        String state = e instanceof PlaneEntity p
            ? (p.isRemoved() || !p.isAlive() ? "destroyed" : "hp=" + p.getHealth() + "/" + p.getMaxHealth())
            : "gone";
        return String.format(java.util.Locale.ROOT, "target=#%d %s closest=%.2f tvel=%.2f retargets=%d",
            targetEntityId, state, closest, velocity.length(), retargets);
    }

    /** Releases the engagement claim; call once when the flight ends. */
    public void end() {
        Engagements.release(engager);
    }

    private static int groundAt(ServerLevel level, double x, double z) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        return level.hasChunkAt(bx, bz) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) : level.getMinY();
    }
}
