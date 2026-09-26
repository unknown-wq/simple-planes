package xyz.przemyk.simpleplanes.airdefence;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Guidance law and fuse geometry for air-defence missiles. Pure math, no world access.
 *
 * <p>The law is predicted-intercept (lead) pursuit: the target is assumed to keep its measured velocity, the
 * missile aims at the point where a straight flight at its own speed would meet it, and the turn toward that
 * point is limited by the tier's turn rate. Against a target holding course this is what proportional
 * navigation converges to; when no intercept exists (the target is faster and moving away) the aim point
 * degrades to a bounded lead ahead of the target, i.e. a tail chase that the missile loses on range.
 */
public final class Pursuit {

    private static final Vec3 UP = new Vec3(0, 1, 0);
    /** Longest look-ahead used for the lead point, in ticks. */
    public static final double MAX_LEAD_TICKS = 80.0;
    /** Hard cap on the close-in turn rate, radians per tick. */
    private static final double MAX_CLOSE_TURN = Math.toRadians(40.0);

    private Pursuit() {}

    /**
     * Smallest positive time at which a point leaving {@code m} at speed {@code s} can meet a target at {@code p}
     * moving with {@code v}, or -1 if there is none.
     */
    public static double interceptTime(Vec3 m, double s, Vec3 p, Vec3 v) {
        Vec3 d = p.subtract(m);
        double a = v.dot(v) - s * s;
        double b = 2.0 * d.dot(v);
        double c = d.dot(d);
        if (Math.abs(a) < 1.0E-9) {
            return b < 0 ? -c / b : -1.0;
        }
        double disc = b * b - 4 * a * c;
        if (disc < 0) return -1.0;
        double sq = Math.sqrt(disc);
        double t1 = (-b - sq) / (2 * a);
        double t2 = (-b + sq) / (2 * a);
        double t = Double.POSITIVE_INFINITY;
        if (t1 > 0) t = t1;
        if (t2 > 0 && t2 < t) t = t2;
        return Double.isInfinite(t) ? -1.0 : t;
    }

    /** The lead point: the predicted intercept, or a bounded lead along the target's track if there is none. */
    public static Vec3 aimPoint(Vec3 m, double s, Vec3 p, Vec3 v) {
        double t = interceptTime(m, s, p, v);
        if (t < 0) t = p.distanceTo(m) / Math.max(s, 1.0E-3);
        return p.add(v.scale(Math.min(t, MAX_LEAD_TICKS)));
    }

    /**
     * Next flight direction: toward {@code aim}, turning at most {@code turnRate} (degrees per tick), or faster
     * close in so the turn radius stays under half the remaining distance and the missile cannot orbit.
     */
    public static Vec3 steer(Vec3 nose, Vec3 dir, double speed, Vec3 aim, double turnRate) {
        Vec3 to = aim.subtract(nose);
        double d = to.length();
        if (d < 1.0E-6) return dir;
        double omega = Math.min(MAX_CLOSE_TURN, Math.max(Math.toRadians(turnRate), 2.2 * speed / Math.max(d, 0.5)));
        return rotateToward(dir, to.scale(1.0 / d), omega);
    }

    public static Vec3 rotateToward(Vec3 from, Vec3 to, double maxAngle) {
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

    /** Result of a fuse check over one tick of relative motion. */
    public record Approach(double distance, double t, Vec3 missileAt, Vec3 targetAt) {}

    /**
     * Closest approach over one tick between the missile nose moving {@code m0 -> m1} and the target box moving
     * by {@code p0 -> p1} (box centres). Distance is from the nose to the box surface, 0 inside it.
     */
    public static Approach closestApproach(Vec3 m0, Vec3 m1, Vec3 p0, Vec3 p1, AABB box) {
        Vec3 r0 = p0.subtract(m0);
        Vec3 dr = p1.subtract(p0).subtract(m1.subtract(m0));
        double len2 = dr.lengthSqr();
        double t = len2 < 1.0E-12 ? 1.0 : Mth.clamp(-r0.dot(dr) / len2, 0.0, 1.0);
        Vec3 m = m0.add(m1.subtract(m0).scale(t));
        Vec3 p = p0.add(p1.subtract(p0).scale(t));
        AABB moved = box.move(p.subtract(box.getCenter()));
        double dx = Math.max(Math.max(moved.minX - m.x, 0.0), m.x - moved.maxX);
        double dy = Math.max(Math.max(moved.minY - m.y, 0.0), m.y - moved.maxY);
        double dz = Math.max(Math.max(moved.minZ - m.z, 0.0), m.z - moved.maxZ);
        return new Approach(Math.sqrt(dx * dx + dy * dy + dz * dz), t, m, p);
    }
}
