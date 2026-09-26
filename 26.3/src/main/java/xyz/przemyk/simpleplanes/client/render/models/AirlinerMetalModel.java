package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderState;
import xyz.przemyk.simpleplanes.entities.AirlinerLayout;

/**
 * Airliner, metal layer, for either size ({@link AirlinerShape}): the two-tier windscreen and instrument panel,
 * the cockpit side windows, the passenger window belts and doors, the seats, the two underwing turbofan nacelles
 * with their pylons, the winglets, the airline logo on the fin, the APU cone and the tricycle landing gear. Uses
 * the size's {@code *_metal.png} (256x256). Shared by the wooden ({@link AirlinerModel}) and the metal-skinned
 * ({@link AirlinerSkinModel}) airliner.
 *
 * <p>The windscreen panes are tinted and opaque; their inner faces are transparent. Belts, doors, side windows
 * and logos are flush plates just outside the skin ({@code CubeDeformation} 0.05); every window in them is
 * transparent and so is every face that points into the cabin, so the seats show through the windows. Rendered
 * with {@link RenderTypes#entityCutoutCull} so that riders keep their view out. The fan discs are in
 * {@link AirlinerFanModel}.
 *
 * <p>The fin carries one of {@link #LOGO_COUNT} fictional airline logos, chosen per aircraft with
 * {@link #setLogo(int)}; see AIRLINER-MODEL.md for how the entity rolls, saves and passes it.
 */
public class AirlinerMetalModel extends EntityModel<PlaneRenderState> {
    /** Number of airline logos: 0 Terntide, 1 Glimmerwing, 2 Pinewind, 3 Puffcloud Express, 4 Coralline, 5 Marigold Hop. */
    public static final int LOGO_COUNT = 6;
    /** Logo shown before {@link #setupAnim} has run. */
    public static final int DEFAULT_LOGO = 0;

    private final ModelPart[] logos = new ModelPart[LOGO_COUNT];
    private final ModelPart[] seatBacks;

    public AirlinerMetalModel(ModelPart root, AirlinerShape shape) {
        super(root, RenderTypes::entityCutoutCull);
        ModelPart seats = root.getChild("Metal").getChild("Seats");
        this.seatBacks = new ModelPart[shape.seats.count()];
        for (int i = 0; i < seatBacks.length; i++) {
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

    public static LayerDefinition createBodyLayer(AirlinerShape shape) {
        return LayerDefinition.create(create(shape, AirlinerAirframe.table(shape.metalUv)), 256, 256);
    }

    static MeshDefinition create(AirlinerShape shape, AirlinerAirframe.UvLayout uv) {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();
        Cubes c = new Cubes(uv);

        PartDefinition Metal = partdefinition.addOrReplaceChild("Metal", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));
        int hw = shape.halfWidth;

        // Two-tier raked windscreen on the nose steps; the instrument panel under the glare shield.
        CubeListBuilder cockpit = CubeListBuilder.create();
        c.box(cockpit, "ws_upper", -shape.windscreenUpper, -44, -83, 2 * shape.windscreenUpper, 4, 1, 0.0F);
        c.box(cockpit, "ws_lower", -shape.windscreenLower, -40, -87, 2 * shape.windscreenLower, 5, 1, 0.0F);
        c.box(cockpit, "dash", -shape.dash, -33, -78, 2 * shape.dash, 5, 3, 0.0F);
        c.pair(cockpit, "cockpit_side", hw, -39, -78, 1, 9, 16, 0.05F);
        Metal.addOrReplaceChild("Cockpit", cockpit, PartPose.ZERO);

        // Window belts over the window band, front and rear doors.
        CubeListBuilder cabin = CubeListBuilder.create();
        c.pair(cabin, "belt", hw, -39, shape.beltFront, 1, 9, shape.beltLength, 0.05F);
        c.pair(cabin, "door", hw, -37, shape.frontDoor, 1, 21, 7, 0.04F);
        c.pair(cabin, "door", hw, -37, shape.rearDoor, 1, 21, 7, 0.04F);
        Metal.addOrReplaceChild("Cabin", cabin, PartPose.ZERO);

        // Seats: cushion, back and headrest; the crew seats stand 2 px higher, eye level with the lower windscreen.
        // Each seat's back and headrest are a part of their own, hidden for the rider sitting in it (setupAnim).
        AirlinerLayout seats = shape.seats;
        CubeListBuilder cushions = CubeListBuilder.create();
        for (int seat = 0; seat < seats.count(); seat++) {
            int x = seats.modelX(seat);
            int z = seats.modelZ(seat);
            if (AirlinerLayout.isCockpit(seat)) {
                c.box(cushions, "crew_base", x - 5, -22, z - 6, 10, 6, 8, 0.0F);
            } else {
                c.box(cushions, "seat_base", x - 5, -20, z - 6, 10, 4, 8, 0.0F);
            }
        }
        PartDefinition Seats = Metal.addOrReplaceChild("Seats", cushions, PartPose.ZERO);
        for (int seat = 0; seat < seats.count(); seat++) {
            int x = seats.modelX(seat);
            int z = seats.modelZ(seat);
            CubeListBuilder back = CubeListBuilder.create();
            if (AirlinerLayout.isCockpit(seat)) {
                c.box(back, "crew_back", x - 5, -35, z + 2, 10, 13, 3, 0.0F);
                c.box(back, "crew_head", x - 4, -40, z + 4, 8, 5, 2, 0.0F);
            } else {
                c.box(back, "seat_back", x - 5, -31, z + 2, 10, 11, 3, 0.0F);
                c.box(back, "seat_head", x - 4, -36, z + 4, 8, 5, 2, 0.0F);
            }
            Seats.addOrReplaceChild("seat_" + seat, back, PartPose.ZERO);
        }

        // Turbofan nacelles: an open intake ring, the cowl (its front face is the fan case), exhaust and plug.
        Metal.addOrReplaceChild("engine_left", c.engine(shape, false), PartPose.ZERO);
        Metal.addOrReplaceChild("engine_right", c.engine(shape, true), PartPose.ZERO);

        // Winglets ride on the wing tips, so they share the wings' pivots and dihedral (see AirlinerAirframe).
        int tip = shape.wingletX;
        int rear = shape.wingletRear;
        CubeListBuilder wingletLeft = CubeListBuilder.create();
        c.box(wingletLeft, "winglet_a", tip, -7, rear - 12, 2, 5, 12, 0.0F);
        c.box(wingletLeft, "winglet_b", tip, -12, rear - 8, 2, 5, 8, 0.0F);
        Metal.addOrReplaceChild("winglet_left", wingletLeft, PartPose.offsetAndRotation(shape.wingRoot,
                AirlinerAirframe.WING_Y, 0.0F, 0.0F, 0.0F, -AirlinerAirframe.WING_DIHEDRAL));
        CubeListBuilder wingletRight = CubeListBuilder.create();
        c.mbox(wingletRight, "winglet_a", -tip - 2, -7, rear - 12, 2, 5, 12, 0.0F);
        c.mbox(wingletRight, "winglet_b", -tip - 2, -12, rear - 8, 2, 5, 8, 0.0F);
        Metal.addOrReplaceChild("winglet_right", wingletRight, PartPose.offsetAndRotation(-shape.wingRoot,
                AirlinerAirframe.WING_Y, 0.0F, 0.0F, 0.0F, AirlinerAirframe.WING_DIHEDRAL));

        // Airline logos: one flush plate per side of the fin, covering its upper five steps. Texels outside the
        // fin's outline are transparent. Only the logo chosen with setLogo is visible.
        int t = shape.tailShift();
        CubeListBuilder apu = CubeListBuilder.create();
        c.box(apu, "apu", -3, -42, 80 + t, 6, 6, 4, 0.0F);
        PartDefinition Tail = Metal.addOrReplaceChild("Tail", apu, PartPose.ZERO);
        for (int i = 0; i < LOGO_COUNT; i++) {
            CubeListBuilder logo = CubeListBuilder.create();
            c.box(logo, "logo_" + i, 0, -87, 50 + t, 1, 29, 29, 0.05F);
            c.mbox(logo, "logo_" + i, -1, -87, 50 + t, 1, 29, 29, 0.05F);
            Tail.addOrReplaceChild("logo_" + i, logo, PartPose.ZERO);
        }

        // Tricycle gear: a nose leg with twin tyres, two main legs with a tyre either side.
        int gx = shape.mainGearX;
        int gz = shape.mainGearZ;
        CubeListBuilder gear = CubeListBuilder.create();
        c.box(gear, "nose_strut", -1, -13, -75, 2, 9, 2, 0.0F);
        c.box(gear, "nose_tyre", -3, -4, -76, 2, 4, 4, 0.0F);
        c.mbox(gear, "nose_tyre", 1, -4, -76, 2, 4, 4, 0.0F);
        c.box(gear, "main_strut", gx, -15, gz, 2, 10, 2, 0.0F);
        c.box(gear, "main_tyre", gx - 3, -6, gz - 2, 3, 6, 6, 0.0F);
        c.box(gear, "main_tyre", gx + 2, -6, gz - 2, 3, 6, 6, 0.0F);
        c.mbox(gear, "main_strut", -gx - 2, -15, gz, 2, 10, 2, 0.0F);
        c.mbox(gear, "main_tyre", -gx, -6, gz - 2, 3, 6, 6, 0.0F);
        c.mbox(gear, "main_tyre", -gx - 5, -6, gz - 2, 3, 6, 6, 0.0F);
        Metal.addOrReplaceChild("Gear", gear, PartPose.ZERO);

        return meshdefinition;
    }

    /** Cube helpers bound to one UV layout. */
    private record Cubes(AirlinerAirframe.UvLayout uv) {

        /** One nacelle on the engine centre line; the right-hand one is the left one mirrored in x. */
        CubeListBuilder engine(AirlinerShape shape, boolean right) {
            CubeListBuilder b = CubeListBuilder.create();
            int c = shape.engineX;
            int y = shape.engineY;
            int r = shape.engineRadius;
            int z = shape.engineFront;
            int l = shape.cowlLength;
            side(b, right, "lip_h", c - r - 1, y - r - 1, z, 2 * r + 2, 2, 3);
            side(b, right, "lip_h", c - r - 1, y + r - 1, z, 2 * r + 2, 2, 3);
            side(b, right, "lip_v", c - r - 1, y - r + 1, z, 2, 2 * r - 2, 3);
            side(b, right, "lip_v", c + r - 1, y - r + 1, z, 2, 2 * r - 2, 3);
            side(b, right, "cowl", c - r, y - r, z + 3, 2 * r, 2 * r, l);
            side(b, right, "exhaust", c - r + 1, y - r + 1, z + 3 + l, 2 * r - 2, 2 * r - 2, 5);
            side(b, right, "plug", c - 2, y - 2, z + 8 + l, 4, 4, 3);
            side(b, right, "pylon", c - 1, y - r - 1, z + 5, 2, 2, shape.pylonLength);
            return b;
        }

        private void side(CubeListBuilder b, boolean right, String cube, float x, float y, float z, float w, float h, float d) {
            if (right) {
                mbox(b, cube, -x - w, y, z, w, h, d, 0.0F);
            } else {
                box(b, cube, x, y, z, w, h, d, 0.0F);
            }
        }

        /** A left-hand plate at {@code x} and its mirrored right-hand twin. */
        void pair(CubeListBuilder b, String cube, float x, float y, float z, float w, float h, float d, float grow) {
            box(b, cube, x, y, z, w, h, d, grow);
            mbox(b, cube, -x - w, y, z, w, h, d, grow);
        }

        void box(CubeListBuilder b, String cube, float x, float y, float z, float w, float h, float d, float grow) {
            int[] t = uv.texOffs(cube, x, y, z, w, h, d);
            b.texOffs(t[0], t[1]).addBox(x, y, z, w, h, d, new CubeDeformation(grow));
        }

        void mbox(CubeListBuilder b, String cube, float x, float y, float z, float w, float h, float d, float grow) {
            int[] t = uv.texOffs(cube, x, y, z, w, h, d);
            b.texOffs(t[0], t[1]).mirror().addBox(x, y, z, w, h, d, new CubeDeformation(grow)).mirror(false);
        }
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
