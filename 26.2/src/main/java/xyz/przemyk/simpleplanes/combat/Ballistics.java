package xyz.przemyk.simpleplanes.combat;

import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Firing solution for an arrow launched from a hovering platform at a mob on the ground.
 *
 * <h2>Why this is not "aim at the target"</h2>
 * An arrow is not a hitscan. {@code AbstractArrow#tick} integrates, in this order:
 * <pre>
 *   p += v ;  v *= 0.99 ;  v.y -= 0.05
 * </pre>
 * ({@code AbstractArrow#getAirDrag} returns 0.99, {@code getDefaultGravity} returns 0.05, and
 * {@code applyInertia}/{@code applyGravity} run after {@code stepMoveAndHit}.) Over the 12–25 ticks
 * an arrow spends crossing 30–50 blocks that is 4–15 blocks of drop, so a flat aim from a gunship
 * hovering 20 blocks up lands in front of the target every time. It needs elevation, and it needs
 * lead if the target is walking.
 *
 * <h2>The solution</h2>
 * The recurrence above has a closed form. With {@code v_0} the launch velocity and
 * {@code g = (0,-0.05,0)}:
 * <pre>
 *   v_k     = 0.99^k v_0 + 100 g (1 - 0.99^k)
 *   p_n-p_0 = v_0 S(n) + 100 g (n - S(n)),      S(n) = 100 (1 - 0.99^n)
 * </pre>
 * so for a wanted displacement of {@code R} horizontally and {@code dy} vertically, and a fixed
 * launch speed {@code s}, the launch velocity is determined by the flight time {@code n} alone:
 * <pre>
 *   vh = R / S(n)
 *   vy = (dy + 5 (n - S(n))) / S(n)
 *   f(n) = vh^2 + vy^2 - s^2  =  0
 * </pre>
 * One scalar root-find, no iteration over trajectories. {@code f} is large and positive for small
 * {@code n} (a short flight time needs an impossible launch speed), dips below zero, and rises again
 * as the lofted solution runs out of speed — two roots, and the <b>smaller</b> one is the flat, fast
 * shot, which is the one a gunship wants: it arrives sooner, so the target has less time to move,
 * and it arrives faster, so it does more damage ({@code AbstractArrow#onHitEntity} computes
 * {@code damage = ceil(|v| * baseDamage)}).
 *
 * <p>{@code S(n)} saturates at 100, so the greatest horizontal distance an arrow can ever cover is
 * {@code 100 * s} — about 300 blocks at bow speed. Long before that the arrival speed, and with it
 * the damage, has decayed to nothing, which is why the engagement radius is a fraction of it.
 *
 * <p>This models the projectile exactly and the world not at all: it ignores the block the arrow
 * clips on the way (checked separately by the line-of-sight raycast) and water (which changes the
 * inertia to {@code getWaterInertia}). Both are refusals to fire, not corrections to the aim.
 */
public final class Ballistics {

    /** {@code AbstractArrow#getAirDrag()}. */
    public static final double AIR_DRAG = 0.99;
    /** {@code AbstractArrow#getDefaultGravity()}, blocks/tick^2. */
    public static final double GRAVITY = 0.05;

    /**
     * Longest flight time considered, in ticks. Past this the arrow is slower than a walking mob and
     * carries almost no damage, so a solution that needs longer is refused rather than taken.
     */
    private static final int MAX_FLIGHT_TICKS = 120;

    private Ballistics() {}

    /** A launch velocity and the flight time it implies. */
    public record Solution(Vec3 velocity, double flightTicks) {}

    /** {@code S(n) = sum of 0.99^k for k in [0,n)} — the horizontal distance one unit of launch speed covers. */
    private static double travelFactor(double n) {
        return (1.0 - Math.pow(AIR_DRAG, n)) / (1.0 - AIR_DRAG);
    }

    /**
     * Squared launch speed a flight time of {@code n} ticks demands, minus the squared speed
     * available. Zero at a valid solution.
     */
    private static double residual(double n, double range, double dy, double speed) {
        double s = travelFactor(n);
        if (s <= 1.0E-9) {
            return Double.MAX_VALUE;
        }
        double vh = range / s;
        double vy = (dy + GRAVITY * 100.0 * (n - s)) / s;
        return vh * vh + vy * vy - speed * speed;
    }

    /**
     * Launch velocity that puts an arrow fired from {@code muzzle} at speed {@code speed} onto
     * {@code aim}.
     *
     * @return null when the target is out of ballistic reach at that speed
     */
    public static @Nullable Solution solve(Vec3 muzzle, Vec3 aim, double speed) {
        Vec3 d = aim.subtract(muzzle);
        double range = Math.sqrt(d.x * d.x + d.z * d.z);
        double dy = d.y;

        // Scan for the first tick boundary where the residual goes non-positive: that brackets the
        // flat root. A bisection over the whole interval would find the lofted one just as happily,
        // and lobbing arrows over a target's head is not what a machine gun does.
        if (residual(1.0, range, dy, speed) <= 0.0) {
            return solutionAt(1.0, d, range, dy);
        }
        for (int n = 2; n <= MAX_FLIGHT_TICKS; n++) {
            if (residual(n, range, dy, speed) <= 0.0) {
                double lo = n - 1;
                double hi = n;
                for (int i = 0; i < 24; i++) {
                    double mid = 0.5 * (lo + hi);
                    if (residual(mid, range, dy, speed) > 0.0) {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                return solutionAt(hi, d, range, dy);
            }
        }
        return null;
    }

    private static Solution solutionAt(double n, Vec3 d, double range, double dy) {
        double s = travelFactor(n);
        double vy = (dy + GRAVITY * 100.0 * (n - s)) / s;
        if (range < 1.0E-6) {
            return new Solution(new Vec3(0.0, vy, 0.0), n);
        }
        double vh = range / s;
        return new Solution(new Vec3(d.x / range * vh, vy, d.z / range * vh), n);
    }

    /**
     * Where the arrow is after {@code ticks} ticks, from the same closed form. Used for the
     * fire-discipline check: the whole path is swept for anything friendly rather than the straight
     * line between muzzle and target, because at these ranges the arc is several blocks tall.
     */
    public static Vec3 pointAt(Vec3 muzzle, Vec3 velocity, double ticks) {
        double s = travelFactor(ticks);
        return new Vec3(
            muzzle.x + velocity.x * s,
            muzzle.y + velocity.y * s - GRAVITY * 100.0 * (ticks - s),
            muzzle.z + velocity.z * s);
    }
}
