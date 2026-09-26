package xyz.przemyk.simpleplanes.airdefence;

import net.minecraft.core.BlockPos;
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
 * the fuse and the engagement claim. The missile entity owns one of these when launched in air-defence mode and
 * asks it for a direction before it moves and for a verdict after. Fuel is the entity's: when it runs out the
 * entity calls {@link #burnout}, which ends guidance and gives the claim up.
 */
public final class Interceptor {

    public enum Verdict { NONE, DETONATE }

    /** Weight of the newest sample in the target velocity estimate. */
    private static final double TRACK_GAIN = 0.5;
    /** A jump larger than this between two samples is a teleport, not flight. */
    private static final double TELEPORT = 16.0;
    /** Minimum height above the ground the lead point is allowed to drag the missile down to. */
    private static final double GROUND_CLEARANCE = 3.0;

    public final InterceptorSpec spec;
    private final Engagements.MissileEngager engager;
    private final BlockPos silo;
    /** Seeker and claim active; false after burnout or the end of the flight. */
    private boolean guided = true;
    private UUID targetId;
    private int targetEntityId;
    private @Nullable Vec3 lastSeen;
    private Vec3 velocity = Vec3.ZERO;
    private Vec3 aim = Vec3.ZERO;
    private double closest = Double.POSITIVE_INFINITY;
    private int retargets;
    private @Nullable Vec3 detonation;

    public Interceptor(InterceptorSpec spec, PlaneEntity target, int missileId, BlockPos silo) {
        this.spec = spec;
        this.engager = new Engagements.MissileEngager(missileId);
        this.silo = silo.immutable();
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
    public boolean guided() { return guided; }
    public Engagements.MissileEngager engager() { return engager; }

    private @Nullable PlaneEntity resolve(ServerLevel level) {
        Entity e = level.getEntity(targetId);
        return e instanceof PlaneEntity p && AircraftRoster.isEngageable(p, level) ? p : null;
    }

    /** Refreshes the track and the engagement claim; re-targets if the aircraft is gone. False if nothing is left to chase. */
    public boolean track(ServerLevel level, Vec3 nose) {
        PlaneEntity target = resolve(level);
        if (target == null) {
            // the aircraft is dead, removed or no longer hostile: nothing to follow up on it
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
        Engagements.claim(engager, targetId, targetEntityId, level.getGameTime(), Engagements.MISSILE_CLAIM_TICKS);
        return true;
    }

    /** From the tracker's level tick: keeps the claim alive even in a tick the missile itself did not run. */
    public void renew(long now) {
        if (guided) Engagements.renew(engager, now, Engagements.MISSILE_CLAIM_TICKS);
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
     * Fuse, after the missile moved from {@code nose0} to {@code nose1}. {@code path} is the path flown since the
     * missile left the tube.
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
        return Verdict.NONE;
    }

    /** Report fragment: the aircraft, its health now, the closest approach and the re-targets. */
    public String describe(ServerLevel level) {
        Entity e = level.getEntity(targetId);
        String state = e instanceof PlaneEntity p
            ? (p.isRemoved() || !p.isAlive() || p.getHealth() <= 0 ? "destroyed" : "hp=" + p.getHealth() + "/" + p.getMaxHealth())
            : "gone";
        return String.format(java.util.Locale.ROOT, "target=#%d %s closest=%s tvel=%.2f retargets=%d", targetEntityId, state,
            Double.isInfinite(closest) ? "-" : String.format(java.util.Locale.ROOT, "%.2f", closest), velocity.length(), retargets);
    }

    /** The motor stopped: no more guidance, and the aircraft is free for a follow-up shot. */
    public void burnout(ServerLevel level) {
        giveUp(level, "OUT_OF_FUEL");
    }

    /** Releases the engagement claim; call once when the flight ends, with the outcome's name. */
    public void end(ServerLevel level, String outcome) {
        giveUp(level, outcome);
    }

    private void giveUp(ServerLevel level, String reason) {
        if (!guided) return;
        guided = false;
        PlaneEntity target = resolve(level);
        if (target == null) {
            Engagements.release(engager);
        } else {
            // the aircraft outlived this missile: remember why, so the next silo can say why it fires again
            Engagements.releaseMissed(engager, targetId, silo, reason + " (target hp " + target.getHealth() + "/"
                + target.getMaxHealth() + ")", level.getGameTime());
        }
    }

    private static int groundAt(ServerLevel level, double x, double z) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        return level.hasChunkAt(bx, bz) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) : level.getMinY();
    }
}
