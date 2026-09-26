package xyz.przemyk.simpleplanes.client.render.models;

import net.minecraft.resources.Identifier;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.entities.AirlinerLayout;

import java.util.Map;

/**
 * The numbers that make one airliner size, px in model space (AIRLINER-MODEL.md): the wide mini airliner and the
 * narrow regional airliner are built by the same code in {@link AirlinerAirframe}, {@link AirlinerMetalModel}
 * and {@link AirlinerFanModel} from one of these. Both have the same cross-section heights, cockpit, fin and
 * seats; the width, the cabin length, the wings, the engines and the gear differ.
 */
public final class AirlinerShape {

    /** Mini airliner: 58 px wide, 22 seats. */
    public static final AirlinerShape WIDE = wide();
    /** Regional airliner: 40 px wide, 14 seats. */
    public static final AirlinerShape REGIONAL = regional();

    /** The body starts at the windscreen. */
    static final int BODY_FRONT = -78;
    /** Where the wide body ends; the tail, fin and stabiliser of a longer body move back by the difference. */
    private static final int WIDE_BODY_REAR = 46;

    final AirlinerLayout seats;
    final Identifier metalTexture;
    final Identifier skinTexture;
    final Map<String, int[]> metalUv;
    final Map<String, int[]> skinUv;
    /** Width and height of the skin texture. */
    final int[] skinSize;

    /** Half width of the fuselage body; the end of the body. */
    int halfWidth, bodyRear;
    /** Half widths of the rounding steps: the shoulders above and below the walls, the crown's two steps, the keel. */
    int shoulder, crownLow, crown, keel;
    /** The wing-to-body fairing: half width, front z, length. */
    int[] fairing;
    /** Window openings along z, [start, end): two cockpit windows, then one per seat row and one between rows. */
    int[][] windows;
    /** The nose steps, back to front, {x, y, z, w, h, d}. */
    int[][] nose;
    /** Half widths of the four tail-cone steps. */
    int[] tailCone;
    /** Wing root pivot x and chord steps {x, y, z, w, h, d} in the wing's frame. */
    float wingRoot;
    float[][] wing;
    /** Horizontal stabiliser root pivot x and steps. */
    float stabRoot;
    float[][] stab;
    /** Engine centre line x, axis height, cowl half size, intake front z, cowl and pylon lengths. */
    int engineX, engineY, engineRadius, engineFront, cowlLength, pylonLength;
    /** Winglet x (the wing tip) and the tip's trailing edge z. */
    int wingletX, wingletRear;
    /** Main gear strut x and z. */
    int mainGearX, mainGearZ;
    /** Half widths of the upper and lower windscreen and the instrument panel. */
    int windscreenUpper, windscreenLower, dash;
    /** Window belt start and length; front and rear door z. */
    int beltFront, beltLength, frontDoor, rearDoor;

    private AirlinerShape(AirlinerLayout seats, String name, Map<String, int[]> metalUv, Map<String, int[]> skinUv, int[] skinSize) {
        this.seats = seats;
        this.metalTexture = SimplePlanesMod.texture(name + "_metal.png");
        this.skinTexture = SimplePlanesMod.texture(name + "_skin.png");
        this.metalUv = metalUv;
        this.skinUv = skinUv;
        this.skinSize = skinSize;
    }

    public AirlinerLayout seats() {
        return seats;
    }

    public Identifier metalTexture() {
        return metalTexture;
    }

    /** The painted metal skin, drawn instead of the material's block texture. */
    public Identifier skinTexture() {
        return skinTexture;
    }

    /** How far the tail cone, fin, stabilisers and logos sit behind the wide airliner's. */
    int tailShift() {
        return bodyRear - WIDE_BODY_REAR;
    }

    private static AirlinerShape wide() {
        AirlinerShape s = new AirlinerShape(AirlinerLayout.WIDE, "airliner", AirlinerUv.WIDE_METAL, AirlinerUv.WIDE_SKIN, AirlinerUv.WIDE_SKIN_SIZE);
        s.halfWidth = 29;
        s.bodyRear = 46;
        s.shoulder = 26;
        s.crownLow = 22;
        s.crown = 15;
        s.keel = 19;
        s.fairing = new int[]{25, -20, 52};
        s.windows = new int[][]{
                {-76, -71}, {-69, -64},
                {-48, -44}, {-39, -35}, {-30, -26}, {-21, -17}, {-12, -8}, {-3, 1}, {6, 10}, {15, 19}, {24, 28}};
        s.nose = new int[][]{
                {-29, -44, -82, 58, 30, 4}, {-28, -40, -86, 56, 26, 4}, {-26, -35, -90, 52, 20, 4}, {-23, -33, -94, 46, 17, 4},
                {-19, -30, -98, 38, 13, 4}, {-14, -27, -101, 28, 8, 3}, {-8, -25, -103, 16, 4, 2}};
        s.tailCone = new int[]{27, 22, 15, 9};
        s.wingRoot = 28.0F;
        s.wing = new float[][]{
                {0, -3, -18, 14, 3, 49}, {14, -3, -10, 13, 3, 42}, {27, -3, -3, 13, 3, 36},
                {40, -2, 4, 12, 2, 30}, {52, -2, 11, 12, 2, 23}, {64, -2, 17, 12, 2, 18}};
        s.stabRoot = 18.0F;
        s.stab = new float[][]{{0, -2, 56, 12, 2, 20}, {12, -2, 62, 10, 2, 15}, {22, -2, 67, 10, 2, 11}, {32, -2, 72, 7, 2, 7}};
        s.engineX = 52;
        s.engineY = -11;
        s.engineRadius = 7;
        s.engineFront = -28;
        s.cowlLength = 17;
        s.pylonLength = 20;
        s.wingletX = 76;
        s.wingletRear = 35;
        s.mainGearX = 25;
        s.mainGearZ = 15;
        s.windscreenUpper = 26;
        s.windscreenLower = 25;
        s.dash = 26;
        s.beltFront = -52;
        s.beltLength = 85;
        s.frontDoor = -60;
        s.rearDoor = 35;
        return s;
    }

    /** Six rows at a 17 px pitch fill a body 4 px longer than the wide one's; everything across is about 0.7 times. */
    private static AirlinerShape regional() {
        AirlinerShape s = new AirlinerShape(AirlinerLayout.REGIONAL, "regional_airliner", AirlinerUv.REGIONAL_METAL, AirlinerUv.REGIONAL_SKIN,
                AirlinerUv.REGIONAL_SKIN_SIZE);
        s.halfWidth = 20;
        s.bodyRear = 50;
        s.shoulder = 17;
        s.crownLow = 14;
        s.crown = 9;
        s.keel = 13;
        s.fairing = new int[]{17, -16, 43};
        s.windows = new int[][]{
                {-76, -71}, {-69, -64},
                {-52, -48}, {-44, -40}, {-35, -31}, {-27, -23}, {-18, -14}, {-10, -6}, {-1, 3}, {7, 11}, {16, 20}, {24, 28}, {33, 37}};
        s.nose = new int[][]{
                {-20, -44, -82, 40, 30, 4}, {-19, -40, -86, 38, 26, 4}, {-18, -35, -90, 36, 20, 4}, {-16, -33, -94, 32, 17, 4},
                {-13, -30, -98, 26, 13, 4}, {-10, -27, -101, 20, 8, 3}, {-6, -25, -103, 12, 4, 2}};
        s.tailCone = new int[]{18, 15, 11, 7};
        s.wingRoot = 19.0F;
        s.wing = new float[][]{
                {0, -3, -14, 12, 3, 39}, {12, -3, -8, 12, 3, 34}, {24, -2, -2, 12, 2, 28},
                {36, -2, 4, 12, 2, 22}, {48, -2, 9, 11, 2, 16}};
        s.stabRoot = 11.0F;
        s.stab = new float[][]{{0, -2, 60, 10, 2, 17}, {10, -2, 65, 9, 2, 13}, {19, -2, 69, 8, 2, 9}, {27, -2, 73, 6, 2, 6}};
        s.engineX = 38;
        s.engineY = -12;
        s.engineRadius = 6;
        s.engineFront = -24;
        s.cowlLength = 14;
        s.pylonLength = 17;
        s.wingletX = 59;
        s.wingletRear = 25;
        s.mainGearX = 17;
        s.mainGearZ = 11;
        s.windscreenUpper = 17;
        s.windscreenLower = 16;
        s.dash = 17;
        s.beltFront = -56;
        s.beltLength = 97;
        s.frontDoor = -63;
        s.rearDoor = 42;
        return s;
    }
}
