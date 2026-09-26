import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Standalone dumper: calls XxxModel.createBodyLayer() from the compiled mod classes, bakes it with
 * the real LayerDefinition#bakeRoot(), and walks the real ModelPart tree with ModelPart#visit while
 * a PoseStack carries exactly the transforms PlaneRenderer#submit applies (identity plane rotation,
 * no hit wobble). Output: entity-space (blocks, Y up) quads with the baked UVs.
 *
 * usage: ModelDump out.json typeTx typeTy typeTz  name=fqcn=texture ...
 */
public class ModelDump {
    static java.lang.reflect.Field CHILDREN;
    /** Same traversal as ModelPart#render: honours visible / skipDraw, then translateAndRotate. */
    @SuppressWarnings("unchecked")
    static void walk(ModelPart part, PoseStack ps, String path, ModelPart.Visitor v) throws Exception {
        if (CHILDREN == null) { CHILDREN = ModelPart.class.getDeclaredField("children"); CHILDREN.setAccessible(true); }
        if (!part.visible) return;
        java.util.Map<String, ModelPart> ch = (java.util.Map<String, ModelPart>) CHILDREN.get(part);
        java.lang.reflect.Field cf = ModelPart.class.getDeclaredField("cubes"); cf.setAccessible(true);
        java.util.List<ModelPart.Cube> cubes = (java.util.List<ModelPart.Cube>) cf.get(part);
        if (cubes.isEmpty() && ch.isEmpty()) return;
        ps.pushPose();
        part.translateAndRotate(ps);
        if (!part.skipDraw) for (int i = 0; i < cubes.size(); i++) v.visit(ps.last(), path, i, cubes.get(i));
        for (var e : ch.entrySet()) walk(e.getValue(), ps, path + "/" + e.getKey(), v);
        ps.popPose();
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        String out = args[0];
        float tx = Float.parseFloat(args[1]), ty = Float.parseFloat(args[2]), tz = Float.parseFloat(args[3]);
        try (PrintWriter w = new PrintWriter(out)) {
            w.println("{\"typeTranslate\":[" + tx + "," + ty + "," + tz + "],\"layers\":[");
            for (int i = 4; i < args.length; i++) {
                String[] s = args[i].split("=");
                String name = s[0], cls = s[1], tex = s[2];
                Method m = Class.forName(cls).getMethod("createBodyLayer");
                LayerDefinition def = (LayerDefinition) m.invoke(null);
                ModelPart root = def.bakeRoot();
                // optional ":throttle" suffix on the class: build the real model around the baked root and
                // call its applyThrottle hook (exactly what setupAnim will do once wired)
                if (s.length > 3) {
                    Object model = Class.forName(cls).getConstructor(ModelPart.class).newInstance(root);
                    Class.forName(cls).getMethod("applyThrottle", float.class).invoke(model, Float.parseFloat(s[3]));
                }

                // --- PlaneRenderer#submit, verbatim order ---
                PoseStack ps = new PoseStack();
                ps.translate(0.0F, 0.375F, 0.0F);
                ps.scale(-1.0F, -1.0F, 1.0F);
                ps.mulPose(Axis.YP.rotationDegrees(180.0F));
                ps.mulPose(new Quaternionf()); // state.rotation = identity
                ps.translate(tx, ty, tz);      // per-type translate
                ps.translate(0.0F, -1.1F, 0.0F);
                // Model#renderToBuffer -> root.render(...) -> same translateAndRotate path as visit

                StringBuilder sb = new StringBuilder();
                int[] cubes = {0};
                float[] bb = {1e9f, 1e9f, 1e9f, -1e9f, -1e9f, -1e9f};
                walk(root, ps, "", (pose, path, idx, cube) -> {
                    cubes[0]++;
                    for (ModelPart.Polygon poly : cube.polygons) {
                        Vector3f n = pose.transformNormal(poly.normal(), new Vector3f());
                        if (sb.length() > 0) sb.append(",\n");
                        sb.append("{\"p\":\"").append(path).append("\",\"c\":").append(idx)
                          .append(",\"n\":[").append(n.x).append(',').append(n.y).append(',').append(n.z).append("],\"v\":[");
                        ModelPart.Vertex[] vs = poly.vertices();
                        for (int k = 0; k < vs.length; k++) {
                            Vector3f p = pose.pose().transformPosition(vs[k].worldX(), vs[k].worldY(), vs[k].worldZ(), new Vector3f());
                            bb[0] = Math.min(bb[0], p.x); bb[1] = Math.min(bb[1], p.y); bb[2] = Math.min(bb[2], p.z);
                            bb[3] = Math.max(bb[3], p.x); bb[4] = Math.max(bb[4], p.y); bb[5] = Math.max(bb[5], p.z);
                            if (k > 0) sb.append(',');
                            sb.append('[').append(p.x).append(',').append(p.y).append(',').append(p.z).append(',')
                              .append(vs[k].u()).append(',').append(vs[k].v()).append(']');
                        }
                        sb.append("]}");
                    }
                });
                System.out.printf("%s (%s): %d cubes, bbox x[%.3f..%.3f] y[%.3f..%.3f] z[%.3f..%.3f]%n",
                        name, cls.substring(cls.lastIndexOf('.') + 1), cubes[0], bb[0], bb[3], bb[1], bb[4], bb[2], bb[5]);
                w.println((i > 4 ? "," : "") + "{\"name\":\"" + name + "\",\"texture\":\"" + tex + "\",\"cubes\":" + cubes[0]
                        + ",\"bbox\":[" + bb[0] + "," + bb[1] + "," + bb[2] + "," + bb[3] + "," + bb[4] + "," + bb[5] + "],\"quads\":[\n" + sb + "]}");
            }
            w.println("]}");
        }
    }
}
