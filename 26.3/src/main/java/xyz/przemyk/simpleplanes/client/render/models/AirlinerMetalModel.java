package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;
import xyz.przemyk.simpleplanes.entities.AirlinerSeats;

import java.util.Map;

import static java.util.Map.entry;

/**
 * Mini airliner, metal layer: the two-tier windscreen and instrument panel, the cockpit side windows, the
 * passenger window belts and doors, the 22 seats, the two underwing turbofan nacelles with their pylons, the
 * winglets, the airline logo on the fin, the APU cone and the tricycle landing gear. Uses
 * {@code textures/plane_upgrades/airliner_metal.png} (256x256). Shared by the wooden ({@link AirlinerModel})
 * and the metal-skinned ({@link AirlinerSkinModel}) airliner.
 *
 * <p>The windscreen panes are tinted and opaque; their inner faces are transparent. Belts, doors, side windows and logos are flush plates just outside the skin ({@code CubeDeformation}
 * 0.05); every window in them is transparent and so is every face that points into the cabin, so the seats
 * show through the windows. Rendered with {@link RenderTypes#entityCutoutCull} so that riders keep their view
 * out. The fan discs are in {@link AirlinerFanModel}.
 *
 * <p>The fin carries one of {@link #LOGO_COUNT} fictional airline logos, chosen per aircraft with
 * {@link #setLogo(int)}; see AIRLINER-MODEL.md for how the entity rolls, saves and passes it.
 */
public class AirlinerMetalModel extends EntityModel<PlaneRenderState> {
    /** Number of airline logos: 0 Terntide, 1 Glimmerwing, 2 Pinewind, 3 Puffcloud Express, 4 Coralline, 5 Marigold Hop. */
    public static final int LOGO_COUNT = 6;
    /** Logo shown before {@link #setupAnim} has run. */
    public static final int DEFAULT_LOGO = 0;
    /** Engine centre line, px from the fuselage centre line. */
    static final int ENGINE_X = 52;
    /** Engine axis height, local y. */
    static final int ENGINE_Y = -11;

    // texOffs of every metal and fan cube in airliner_metal.png; generated together with the texture.
    // @UV-BEGIN
    private static final Map<String, int[]> UV = Map.ofEntries(
            entry("belt", new int[]{0, 0}),
            entry("cockpit_side", new int[]{47, 16}),
            entry("ws_upper", new int[]{88, 9}),
            entry("ws_lower", new int[]{88, 15}),
            entry("dash", new int[]{88, 0}),
            entry("door", new int[]{240, 18}),
            entry("cowl", new int[]{0, 0}),
            entry("pylon", new int[]{202, 0}),
            entry("logo_0", new int[]{170, 0}),
            entry("logo_1", new int[]{88, 22}),
            entry("logo_2", new int[]{173, 59}),
            entry("logo_3", new int[]{0, 95}),
            entry("logo_4", new int[]{61, 95}),
            entry("logo_5", new int[]{122, 95}),
            entry("crew_base", new int[]{120, 22}),
            entry("crew_back", new int[]{88, 22}),
            entry("crew_head", new int[]{88, 39}),
            entry("seat_base", new int[]{0, 32}),
            entry("seat_back", new int[]{0, 45}),
            entry("seat_head", new int[]{141, 43}),
            entry("winglet_a", new int[]{227, 0}),
            entry("winglet_b", new int[]{149, 29}),
            entry("lip_h", new int[]{46, 0}),
            entry("lip_v", new int[]{0, 0}),
            entry("exhaust", new int[]{32, 42}),
            entry("plug", new int[]{70, 7}),
            entry("apu", new int[]{120, 37}),
            entry("nose_strut", new int[]{227, 0}),
            entry("main_strut", new int[]{66, 15}),
            entry("nose_tyre", new int[]{244, 0}),
            entry("main_tyre", new int[]{202, 0}),
            entry("spinner", new int[]{46, 10}),
            entry("blade", new int[]{46, 6}));
    // @UV-END

    private final ModelPart[] logos = new ModelPart[LOGO_COUNT];
    private final ModelPart[] seatBacks = new ModelPart[AirlinerSeats.COUNT];

    public AirlinerMetalModel(ModelPart root) {
        super(root, RenderTypes::entityCutoutCull);
        ModelPart seats = root.getChild("Metal").getChild("Seats");
        for (int i = 0; i < AirlinerSeats.COUNT; i++) {
            this.seatBacks[i] = seats.getChild("seat_" + i);
        }
        ModelPart tail = root.getChild("Metal").getChild("Tail");
        for (int i = 0; i < LOGO_COUNT; i++) {
            this.logos[i] = tail.getChild("logo_" + i);
        }
        setLogo(DEFAULT_LOGO);
    }

    /** Shows logo {@code index} (taken modulo {@link #LOGO_COUNT}) on both sides of the fin and hides the others. */
    public void setLogo(int index) {
        int shown = Math.floorMod(index, LOGO_COUNT);
        for (int i = 0; i < LOGO_COUNT; i++) {
            this.logos[i].visible = i == shown;
        }
    }

    static int[] uv(String cube) {
        int[] t = UV.get(cube);
        if (t == null) {
            throw new IllegalArgumentException("no texOffs for airliner metal cube " + cube);
        }
        return t;
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition Metal = partdefinition.addOrReplaceChild("Metal", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        // Two-tier raked windscreen on the nose steps; the instrument panel under the glare shield.
        CubeListBuilder cockpit = CubeListBuilder.create();
        box(cockpit, "ws_upper", -26, -44, -83, 52, 4, 1, 0.0F);
        box(cockpit, "ws_lower", -25, -40, -87, 50, 5, 1, 0.0F);
        box(cockpit, "dash", -26, -33, -78, 52, 5, 3, 0.0F);
        pair(cockpit, "cockpit_side", 29, -39, -78, 1, 9, 16, 0.05F);
        Metal.addOrReplaceChild("Cockpit", cockpit, PartPose.ZERO);

        // Window belts over the window band, front and rear doors.
        CubeListBuilder cabin = CubeListBuilder.create();
        pair(cabin, "belt", 29, -39, -52, 1, 9, 85, 0.05F);
        pair(cabin, "door", 29, -37, -60, 1, 21, 7, 0.04F);
        pair(cabin, "door", 29, -37, 35, 1, 21, 7, 0.04F);
        Metal.addOrReplaceChild("Cabin", cabin, PartPose.ZERO);

        // Seats: cushion, back and headrest; the crew seats stand 2 px higher, eye level with the lower windscreen.
        // Each seat's back and headrest are a part of their own, hidden for the rider sitting in it (setupAnim).
        CubeListBuilder cushions = CubeListBuilder.create();
        for (int seat = 0; seat < AirlinerSeats.COUNT; seat++) {
            int x = AirlinerSeats.modelX(seat);
            int z = AirlinerSeats.modelZ(seat);
            if (AirlinerSeats.isCockpit(seat)) {
                box(cushions, "crew_base", x - 5, -22, z - 6, 10, 6, 8, 0.0F);
            } else {
                box(cushions, "seat_base", x - 5, -20, z - 6, 10, 4, 8, 0.0F);
            }
        }
        PartDefinition Seats = Metal.addOrReplaceChild("Seats", cushions, PartPose.ZERO);
        for (int seat = 0; seat < AirlinerSeats.COUNT; seat++) {
            int x = AirlinerSeats.modelX(seat);
            int z = AirlinerSeats.modelZ(seat);
            CubeListBuilder back = CubeListBuilder.create();
            if (AirlinerSeats.isCockpit(seat)) {
                box(back, "crew_back", x - 5, -35, z + 2, 10, 13, 3, 0.0F);
                box(back, "crew_head", x - 4, -40, z + 4, 8, 5, 2, 0.0F);
            } else {
                box(back, "seat_back", x - 5, -31, z + 2, 10, 11, 3, 0.0F);
                box(back, "seat_head", x - 4, -36, z + 4, 8, 5, 2, 0.0F);
            }
            Seats.addOrReplaceChild("seat_" + seat, back, PartPose.ZERO);
        }

        // Turbofan nacelles: an open intake ring, the cowl (its front face is the fan case), exhaust and plug.
        Metal.addOrReplaceChild("engine_left", engine(false), PartPose.ZERO);
        Metal.addOrReplaceChild("engine_right", engine(true), PartPose.ZERO);

        // Winglets ride on the wing tips, so they share the wings' pivots and dihedral (see AirlinerAirframe).
        CubeListBuilder wingletLeft = CubeListBuilder.create();
        box(wingletLeft, "winglet_a", 76, -7, 23, 2, 5, 12, 0.0F);
        box(wingletLeft, "winglet_b", 76, -12, 27, 2, 5, 8, 0.0F);
        Metal.addOrReplaceChild("winglet_left", wingletLeft,
                PartPose.offsetAndRotation(AirlinerAirframe.WING_ROOT_X, -14.0F, 0.0F, 0.0F, 0.0F, -AirlinerAirframe.WING_DIHEDRAL));
        CubeListBuilder wingletRight = CubeListBuilder.create();
        mbox(wingletRight, "winglet_a", -78, -7, 23, 2, 5, 12, 0.0F);
        mbox(wingletRight, "winglet_b", -78, -12, 27, 2, 5, 8, 0.0F);
        Metal.addOrReplaceChild("winglet_right", wingletRight,
                PartPose.offsetAndRotation(-AirlinerAirframe.WING_ROOT_X, -14.0F, 0.0F, 0.0F, 0.0F, AirlinerAirframe.WING_DIHEDRAL));

        // Airline logos: one flush plate per side of the fin, covering its upper five steps. Texels outside the
        // fin's outline are transparent. Only the logo chosen with setLogo is visible.
        CubeListBuilder apu = CubeListBuilder.create();
        box(apu, "apu", -3, -42, 80, 6, 6, 4, 0.0F);
        PartDefinition Tail = Metal.addOrReplaceChild("Tail", apu, PartPose.ZERO);
        for (int i = 0; i < LOGO_COUNT; i++) {
            CubeListBuilder logo = CubeListBuilder.create();
            box(logo, "logo_" + i, 0, -87, 50, 1, 29, 29, 0.05F);
            mbox(logo, "logo_" + i, -1, -87, 50, 1, 29, 29, 0.05F);
            Tail.addOrReplaceChild("logo_" + i, logo, PartPose.ZERO);
        }

        CubeListBuilder gear = CubeListBuilder.create();
        box(gear, "nose_strut", -1, -13, -75, 2, 9, 2, 0.0F);
        box(gear, "nose_tyre", -3, -4, -76, 2, 4, 4, 0.0F);
        mbox(gear, "nose_tyre", 1, -4, -76, 2, 4, 4, 0.0F);
        box(gear, "main_strut", 25, -15, 15, 2, 10, 2, 0.0F);
        box(gear, "main_tyre", 22, -6, 13, 3, 6, 6, 0.0F);
        box(gear, "main_tyre", 27, -6, 13, 3, 6, 6, 0.0F);
        mbox(gear, "main_strut", -27, -15, 15, 2, 10, 2, 0.0F);
        mbox(gear, "main_tyre", -25, -6, 13, 3, 6, 6, 0.0F);
        mbox(gear, "main_tyre", -30, -6, 13, 3, 6, 6, 0.0F);
        Metal.addOrReplaceChild("Gear", gear, PartPose.ZERO);

        return LayerDefinition.create(meshdefinition, 256, 256);
    }

    /** One nacelle on the engine centre line; the right-hand one is the left one mirrored in x. */
    private static CubeListBuilder engine(boolean right) {
        CubeListBuilder b = CubeListBuilder.create();
        int c = ENGINE_X;
        side(b, right, "lip_h", c - 8, -19, -28, 16, 2, 3);
        side(b, right, "lip_h", c - 8, -5, -28, 16, 2, 3);
        side(b, right, "lip_v", c - 8, -17, -28, 2, 12, 3);
        side(b, right, "lip_v", c + 6, -17, -28, 2, 12, 3);
        side(b, right, "cowl", c - 7, -18, -25, 14, 14, 17);
        side(b, right, "exhaust", c - 6, -17, -8, 12, 12, 5);
        side(b, right, "plug", c - 2, -13, -3, 4, 4, 3);
        side(b, right, "pylon", c - 1, -19, -23, 2, 2, 20);
        return b;
    }

    private static void side(CubeListBuilder b, boolean right, String cube, float x, float y, float z, float w, float h, float d) {
        if (right) {
            mbox(b, cube, -x - w, y, z, w, h, d, 0.0F);
        } else {
            box(b, cube, x, y, z, w, h, d, 0.0F);
        }
    }

    /** A left-hand plate at {@code x} and its mirrored right-hand twin. */
    private static void pair(CubeListBuilder b, String cube, float x, float y, float z, float w, float h, float d, float grow) {
        box(b, cube, x, y, z, w, h, d, grow);
        mbox(b, cube, -x - w, y, z, w, h, d, grow);
    }

    private static void box(CubeListBuilder b, String cube, float x, float y, float z, float w, float h, float d, float grow) {
        int[] t = uv(cube);
        b.texOffs(t[0], t[1]).addBox(x, y, z, w, h, d, new CubeDeformation(grow));
    }

    private static void mbox(CubeListBuilder b, String cube, float x, float y, float z, float w, float h, float d, float grow) {
        int[] t = uv(cube);
        b.texOffs(t[0], t[1]).mirror().addBox(x, y, z, w, h, d, new CubeDeformation(grow)).mirror(false);
    }

    @Override
    public void setupAnim(PlaneRenderState state) {
        super.setupAnim(state);
        setLogo(state.airlinerLogo);
        for (int i = 0; i < seatBacks.length; i++) {
            seatBacks[i].visible = i != state.airlinerHiddenSeat;
        }
    }
}
