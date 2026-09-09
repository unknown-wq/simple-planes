package xyz.przemyk.simpleplanes.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The two shapes this mod's world overlay is made of, and how they are drawn.
 *
 * <h2>Why a filled patch and not a thicker outline</h2>
 * A runway is twenty to two hundred blocks long and is looked at from a thousand feet up. A
 * one-pixel wire rectangle at that range is a few grey specks; the eye cannot even tell whether it
 * closed. The usual answer is to fatten the lines, which on a line primitive means building the
 * geometry for a quad per edge and mitring the corners, and it is a large amount of code for a
 * result that is still only an outline. Shading the ground inside the shape instead is four vertices
 * that read as a shape from any distance and any angle, and the thin outline on top of it is then
 * doing the only job it is good at — saying exactly where the edge is when you are standing on it.
 *
 * <p>The vertical post exists for the same reason from the other end: a patch lying flat on the
 * ground is invisible from ground level, which is precisely where a player stands when they mark the
 * first threshold of a runway and want to see which block they hit.
 *
 * <p>Everything here is in world coordinates. The caller is responsible for the pose that puts the
 * camera at the origin, so nothing in this class needs to know where the camera is.
 */
@Environment(EnvType.CLIENT)
public final class GroundOverlay {

    private GroundOverlay() {}

    /** Lifts a patch off the surface it shades, so it does not fight with the block face. */
    private static final double LIFT = 0.02;

    /** Outline width, in pixels. Two rather than one so an edge survives being seen at a distance. */
    public static final float LINE_WIDTH = 2.0F;

    /**
     * A flat quadrilateral of ground, shaded and outlined.
     *
     * @param corners four world positions, in order round the shape; their own {@code y} is used,
     *                so a patch over sloping ground slopes with it
     * @param fill    ARGB of the shading, alpha included; 0 draws no shading
     * @param line    ARGB of the outline; 0 draws no outline
     */
    public record Patch(Vec3[] corners, int fill, int line) {}

    /** A vertical line standing on one point, so a marked block can be seen from the ground. */
    public record Post(Vec3 base, double height, int colour) {}

    /** The four corners of a rectangle {@code 2 * halfWidth} wide running from {@code a} to {@code b}. */
    public static Vec3[] rectangle(Vec3 a, Vec3 b, double halfWidth) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4) {
            return square(a.x, a.z, a.y, halfWidth);
        }
        // Across the line, in the horizontal plane.
        double px = -dz / length * halfWidth;
        double pz = dx / length * halfWidth;
        return new Vec3[] {
            new Vec3(a.x + px, a.y, a.z + pz),
            new Vec3(b.x + px, b.y, b.z + pz),
            new Vec3(b.x - px, b.y, b.z - pz),
            new Vec3(a.x - px, a.y, a.z - pz)
        };
    }

    /** The four corners of an axis-aligned square of side {@code 2 * half} centred on a point. */
    public static Vec3[] square(double centreX, double centreZ, double y, double half) {
        return new Vec3[] {
            new Vec3(centreX - half, y, centreZ - half),
            new Vec3(centreX + half, y, centreZ - half),
            new Vec3(centreX + half, y, centreZ + half),
            new Vec3(centreX - half, y, centreZ + half)
        };
    }

    /** Shades the inside of every patch. Expects a quad render type with blending. */
    public static void fill(PoseStack.Pose pose, VertexConsumer out, List<Patch> patches) {
        for (Patch patch : patches) {
            if (alpha(patch.fill()) == 0) {
                continue;
            }
            for (Vec3 corner : patch.corners()) {
                out.addVertex(pose, (float) corner.x, (float) (corner.y + LIFT), (float) corner.z)
                    .setColor(patch.fill());
            }
        }
    }

    /** Draws the edge of every patch and every post. Expects a line render type. */
    public static void lines(PoseStack.Pose pose, VertexConsumer out, List<Patch> patches, List<Post> posts) {
        for (Patch patch : patches) {
            if (alpha(patch.line()) == 0) {
                continue;
            }
            Vec3[] corners = patch.corners();
            for (int i = 0; i < corners.length; i++) {
                segment(pose, out, corners[i].add(0, LIFT, 0),
                    corners[(i + 1) % corners.length].add(0, LIFT, 0), patch.line());
            }
        }
        for (Post post : posts) {
            segment(pose, out, post.base(), post.base().add(0, post.height(), 0), post.colour());
        }
    }

    /**
     * One line. The normal is the direction of the segment: the line shader expands the primitive
     * across {@code Position + Normal} in screen space, so a zero normal draws nothing at all.
     */
    private static void segment(PoseStack.Pose pose, VertexConsumer out, Vec3 from, Vec3 to, int colour) {
        Vec3 direction = to.subtract(from);
        double length = direction.length();
        if (length < 1.0E-6) {
            return;
        }
        float nx = (float) (direction.x / length);
        float ny = (float) (direction.y / length);
        float nz = (float) (direction.z / length);
        out.addVertex(pose, (float) from.x, (float) from.y, (float) from.z)
            .setColor(colour).setNormal(pose, nx, ny, nz).setLineWidth(LINE_WIDTH);
        out.addVertex(pose, (float) to.x, (float) to.y, (float) to.z)
            .setColor(colour).setNormal(pose, nx, ny, nz).setLineWidth(LINE_WIDTH);
    }

    private static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }
}
