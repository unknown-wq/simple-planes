package xyz.przemyk.simpleplanes.misc;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

public class MathUtil {

    /**
     * Misnomer inherited from the original mod: this returns the horizontal <em>distance</em>
     * ({@code sqrt(x^2 + z^2)}), not its square. It is <strong>not</strong> interchangeable with
     * vanilla's {@code Vec3.horizontalDistanceSqr()} — see PHYSICS-AUDIT.md, issue P1.
     * Kept only so external callers do not break; nothing inside the mod uses it any more.
     */
    public static double getHorizontalDistanceSqr(Vec3 vec3) {
        return Math.sqrt((vec3.x * vec3.x) + (vec3.z * vec3.z));
    }

    public static double normalizedDotProduct(Vec3 v1, Vec3 v2) {
        double lengths = v1.length() * v2.length();
        // Either vector can legitimately be zero-length (a parked plane has no motion). 0/0 is NaN
        // and NaN propagates straight into setDeltaMovement(), which poisons the entity for good.
        if (lengths < 1.0E-12) {
            return 0;
        }
        return v1.dot(v2) / lengths;
    }

    public static float getPitch(Vec3 motion) {
        double y = motion.y;
        return (float) Math.toDegrees(Math.atan2(y, Math.sqrt(motion.x * motion.x + motion.z * motion.z)));
    }

    public static float getYaw(Vec3 motion) {
        return (float) Math.toDegrees(Math.atan2(-motion.x, motion.z));
    }

    public static float lerpAngle(float perc, float start, float end) {
        return start + perc * Mth.wrapDegrees(end - start);
    }

    public static float lerpAngle180(float perc, float start, float end) {
        if (degreesDifferenceAbs(start, end) > 90)
            end += 180;
        return start + perc * Mth.wrapDegrees(end - start);
    }

    public static double lerpAngle180(double perc, double start, double end) {
        if (degreesDifferenceAbs(start, end) > 90)
            end += 180;
        return start + perc * Mth.wrapDegrees(end - start);
    }

    public static double lerpAngle(double perc, double start, double end) {
        return start + perc * Mth.wrapDegrees(end - start);
    }

    public static double degreesDifferenceAbs(double p_203301_0_, double p_203301_1_) {
        return Math.abs(wrapSubtractDegrees(p_203301_0_, p_203301_1_));
    }

    public static double wrapSubtractDegrees(double p_203302_0_, double p_203302_1_) {
        return Mth.wrapDegrees(p_203302_1_ - p_203302_0_);
    }

    public static Vec3 rotationToVector(double yaw, double pitch) {
        yaw = Math.toRadians(yaw);
        pitch = Math.toRadians(pitch);
        double xzLen = Math.cos(pitch);
        double x = -xzLen * Math.sin(yaw);
        double y = Math.sin(pitch);
        double z = xzLen * Math.cos(-yaw);
        return new Vec3(x, y, z);
    }

    public static Vec3 rotationToVector(double yaw, double pitch, double size) {
        // rotationToVector(yaw, pitch) is a unit vector by construction:
        //   x^2 + y^2 + z^2 = cos^2(p)*sin^2(y) + sin^2(p) + cos^2(p)*cos^2(y) = cos^2(p) + sin^2(p) = 1
        // so the original `size / vec.length()` was a sqrt plus a division that always produced `size`.
        return rotationToVector(yaw, pitch).scale(size);
    }

    public static EulerAngles toEulerAngles(Quaternionf q) {
        return toEulerAngles(q, new EulerAngles());
    }

    /**
     * Allocation-free variant of {@link #toEulerAngles(Quaternionf)}: writes into {@code dest} and
     * returns it. {@code dest} may safely be a long-lived scratch object — nothing here keeps a
     * reference to {@code q}.
     */
    public static EulerAngles toEulerAngles(Quaternionf q, EulerAngles dest) {
        float qx = q.x();
        float qy = q.y();
        float qz = q.z();
        float qw = q.w();

        // roll (rotation around the plane's own forward/Z axis)
        double sinr_cosp = 2 * (qw * qz + qx * qy);
        double cosr_cosp = 1 - 2 * (qz * qz + qx * qx);
        dest.roll = Math.toDegrees(Math.atan2(sinr_cosp, cosr_cosp));

        // pitch (rotation around the plane's own right/X axis); negated to match Minecraft's
        // convention where a negative xRot means "nose up".
        double sinp = 2 * (qw * qx - qy * qz);
        if (Math.abs(sinp) >= 0.999) {
            dest.pitch = -Math.toDegrees(Math.signum(sinp) * Math.PI / 2); // use 90 degrees if out of range
        } else {
            dest.pitch = -Math.toDegrees(Math.asin(sinp));
        }

        // yaw (rotation around the world Y axis)
        double siny_cosp = 2 * (qw * qy + qz * qx);
        double cosy_cosp = 1 - 2 * (qx * qx + qy * qy);
        dest.yaw = Math.toDegrees(Math.atan2(siny_cosp, cosy_cosp));

        return dest;
    }

    /**
     * Fast reciprocal square root (one Newton step), relative error up to ~0.175%.
     *
     * @deprecated no longer used for quaternion normalisation — {@link Math#sqrt(double)} is a JIT
     * intrinsic, so the approximation bought nothing but a per-tick error that accumulated through
     * the three quaternion multiplications {@code PlaneEntity#tick()} performs. Kept for
     * source compatibility.
     */
    @Deprecated
    public static float fastInvSqrt(float number) {
        float f = 0.5F * number;
        int i = Float.floatToIntBits(number);
        i = 1597463007 - (i >> 1);
        number = Float.intBitsToFloat(i);
        return number * (1.5F - f * number * number);
    }

    /**
     * Returns a <em>new</em> normalised quaternion. Callers such as {@code PlaneEntity#tick()} rely
     * on the fresh instance, because the result is handed to {@code setQ()} / {@code setQ_Client()},
     * which store the reference itself.
     */
    public static Quaternionf normalizeQuaternionf(Quaternionf q) {
        return normalizeQuaternionf(q, new Quaternionf());
    }

    /** Allocation-free variant of {@link #normalizeQuaternionf(Quaternionf)}; {@code dest} may alias {@code q}. */
    public static Quaternionf normalizeQuaternionf(Quaternionf q, Quaternionf dest) {
        float f = q.x() * q.x() + q.y() * q.y() + q.z() * q.z() + q.w() * q.w();
        if (f > 1.0E-6F) {
            float f1 = (float) (1.0 / Math.sqrt(f));
            return dest.set(q.x() * f1, q.y() * f1, q.z() * f1, q.w() * f1);
        }
        // Degenerate input. The original returned (0, 0, 0, 0), which is not a rotation at all:
        // Vector3f.rotate() by it collapses every vector to zero and toEulerAngles() reports
        // (0, 0, 0), so a single bad frame silently flattened the plane's orientation
        // (see RotationPacket.isValidRotation, which had to defend against exactly this).
        // The identity quaternion is the only safe answer.
        return dest.set(0, 0, 0, 1);
    }

    public static Quaternionf toQuaternionf(double yaw, double pitch, double roll) { // yaw (Z), pitch (Y), roll (X)
        return toQuaternionf(yaw, pitch, roll, new Quaternionf());
    }

    /**
     * Allocation-free variant of {@link #toQuaternionf(double, double, double)}: writes into
     * {@code dest} and returns it. Never pass a buffer that is going to be stored by
     * {@code PlaneEntity#setQ}/{@code setQ_Client}/{@code setQ_prev} — those keep the reference.
     */
    public static Quaternionf toQuaternionf(double yaw, double pitch, double roll, Quaternionf dest) {
        // Abbreviations for the various angular functions
        yaw = Math.toRadians(yaw);
        pitch = -Math.toRadians(pitch);
        roll = Math.toRadians(roll);

        double cy = Math.cos(yaw * 0.5);
        double sy = Math.sin(yaw * 0.5);
        double cp = Math.cos(pitch * 0.5);
        double sp = Math.sin(pitch * 0.5);
        double cr = Math.cos(roll * 0.5);
        double sr = Math.sin(roll * 0.5);

        float w = (float) (cr * cp * cy + sr * sp * sy);
        float z = (float) (sr * cp * cy - cr * sp * sy);
        float x = (float) (cr * sp * cy + sr * cp * sy);
        float y = (float) (cr * cp * sy - sr * sp * cy);

        return dest.set(x, y, z, w);
    }

    public static Quaternionf lerpQ(float perc, Quaternionf start, Quaternionf end) {
        // Only unit quaternionfs are valid rotations.
        // Normalize to avoid undefined behavior.
        start = normalizeQuaternionf(start);
        end = normalizeQuaternionf(end);

        // Compute the cosine of the angle between the two vectors.
        double dot = start.x() * end.x() + start.y() * end.y() + start.z() * end.z() + start.w() * end.w();

        // If the dot product is negative, slerp won't take
        // the shorter path. Note that v1 and -v1 are equivalent when
        // the negation is applied to all four components. Fix by
        // reversing one quaternionf.
        if (dot < 0.0f) {
            end = new Quaternionf(-end.x(), -end.y(), -end.z(), -end.w());
            dot = -dot;
        }

        double DOT_THRESHOLD = 0.9995;
        if (dot > DOT_THRESHOLD) {
            // If the inputs are too close for comfort, linearly interpolate
            // and normalize the result.

            Quaternionf quaternionf = new Quaternionf(
                start.x() * (1 - perc) + end.x() * perc,
                start.y() * (1 - perc) + end.y() * perc,
                start.z() * (1 - perc) + end.z() * perc,
                start.w() * (1 - perc) + end.w() * perc
            );
            return normalizeQuaternionf(quaternionf);
        }

        // Since dot is in range [0, DOT_THRESHOLD], acos is safe
        double theta_0 = Math.acos(dot);        // theta_0 = angle between input vectors
        double theta = theta_0 * perc;          // theta = angle between v0 and result
        double sin_theta = Math.sin(theta);     // compute this value only once
        double sin_theta_0 = Math.sin(theta_0); // compute this value only once

        float s0 = (float) (Math.cos(theta) - dot * sin_theta / sin_theta_0);  // == sin(theta_0 - theta) / sin(theta_0)
        float s1 = (float) (sin_theta / sin_theta_0);

        Quaternionf quaternionf = new Quaternionf(
            start.x() * (s0) + end.x() * s1,
            start.y() * (s0) + end.y() * s1,
            start.z() * (s0) + end.z() * s1,
            start.w() * (s0) + end.w() * s1
        );
        return normalizeQuaternionf(quaternionf);
    }

    public static class EulerAngles {
        public double pitch, yaw, roll;

        public EulerAngles() {}

        public EulerAngles(EulerAngles a) {
            this.pitch = a.pitch;
            this.yaw = a.yaw;
            this.roll = a.roll;
        }

        public EulerAngles copy() {
            return new EulerAngles(this);
        }

        @Override
        public String toString() {
            return "EulerAngles{" +
                "pitch=" + pitch +
                ", yaw=" + yaw +
                ", roll=" + roll +
                '}';
        }
    }
}
