package xyz.przemyk.simpleplanes.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.autopilot.AutopilotConfig;

import java.util.List;

/**
 * The registered airfields and helipads around one player, for the world overlay to draw.
 *
 * <p>Registered fields live in {@code AutopilotSavedData} on the server and the client has never
 * had any of it, so the overlay cannot show what already exists without a packet. What travels is
 * deliberately not an {@code Airfield}: the record on disk carries the approach obstacle counts, the
 * stand rule and the parking list, and none of the first two mean anything to a renderer. Only what
 * gets drawn is sent — the two thresholds, the measured width, the marked stands and, for each of
 * them, whether it is taken, free, or a square the server cannot presently see well enough to say
 * ({@link Runway#unknownStands}) — plus the one derived verdict the drawing colours by
 * ({@link Runway#usable}), computed server-side so the client and {@code AirfieldBrowser} cannot
 * come to disagree about what "usable" means.
 *
 * <p>Sent by {@link AirfieldMarkerSync}, which only sends it when the fields around a player have
 * actually changed. See that class for when, and for why it is not sent on a timer.
 */
public record AirfieldMarkersPacket(List<Runway> runways, List<Pad> pads) implements CustomPacketPayload {

    /**
     * One surveyed runway.
     *
     * @param stands         marked parking spots, each stored on its surface block
     * @param occupiedStands bitmask over {@code stands}: bit <i>i</i> set means that stand had an
     *                       aircraft on it, or claimed by one, when the packet was built. A mask
     *                       rather than a boolean per stand because an airfield may have at most
     *                       {@link AutopilotConfig#MAX_PARKING_SPOTS} of them, which fits in a byte
     * @param unknownStands  bitmask over {@code stands}, in the same shape: bit <i>i</i> set means
     *                       the server could not tell. Fields are sent out to
     *                       {@code AirfieldMarkerSync#MARKER_RADIUS}, which is several times the
     *                       distance entities are loaded to, and on a square whose entities are not
     *                       loaded neither the entity search nor the booking register means
     *                       anything — occupancy answers "taken" there by design, because that is
     *                       the safe answer for something about to taxi onto it, and it is not an
     *                       answer worth drawing as fact. Disjoint from {@code occupiedStands} by
     *                       construction: a stand marked unknown was never asked about
     * @param usable         whether the runway is long enough for sorties to be accepted into it
     */
    public record Runway(String name, BlockPos thresholdA, BlockPos thresholdB, int width,
                         List<BlockPos> stands, int occupiedStands, int unknownStands,
                         boolean usable) {

        public boolean standOccupied(int index) {
            return (occupiedStands & (1 << index)) != 0;
        }

        /** Whether the server had no usable answer for this stand. See {@link #unknownStands}. */
        public boolean standUnknown(int index) {
            return (unknownStands & (1 << index)) != 0;
        }
    }

    /** One surveyed helicopter pad: a square of side {@code 2 * radius + 1} about its centre. */
    public record Pad(String name, BlockPos centre, int radius) {}

    /**
     * How many fields one packet may carry. Well above what a world plausibly has within the
     * radius {@link AirfieldMarkerSync} filters by, and present so a malformed or hostile packet
     * cannot make the client allocate without bound.
     *
     * <p>Public because a sized list codec enforces its bound when <em>writing</em> as well as when
     * reading: a payload built with more than this many entries does not travel truncated, it
     * throws {@code EncoderException} out of the pipeline. Nothing bounds how many airfields a world
     * may register, so the sender has to know the number and cap what it builds — see
     * {@code AirfieldMarkerSync#closest}.
     */
    public static final int MAX_FIELDS = 64;

    private static final StreamCodec<ByteBuf, Runway> RUNWAY_CODEC = StreamCodec.composite(
        ByteBufCodecs.stringUtf8(256), Runway::name,
        BlockPos.STREAM_CODEC, Runway::thresholdA,
        BlockPos.STREAM_CODEC, Runway::thresholdB,
        ByteBufCodecs.VAR_INT, Runway::width,
        BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(AutopilotConfig.MAX_PARKING_SPOTS)), Runway::stands,
        ByteBufCodecs.VAR_INT, Runway::occupiedStands,
        ByteBufCodecs.VAR_INT, Runway::unknownStands,
        ByteBufCodecs.BOOL, Runway::usable,
        Runway::new);

    private static final StreamCodec<ByteBuf, Pad> PAD_CODEC = StreamCodec.composite(
        ByteBufCodecs.stringUtf8(256), Pad::name,
        BlockPos.STREAM_CODEC, Pad::centre,
        ByteBufCodecs.VAR_INT, Pad::radius,
        Pad::new);

    public static final CustomPacketPayload.Type<AirfieldMarkersPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "airfield_markers"));

    public static final StreamCodec<ByteBuf, AirfieldMarkersPacket> STREAM_CODEC = StreamCodec.composite(
        RUNWAY_CODEC.apply(ByteBufCodecs.list(MAX_FIELDS)), AirfieldMarkersPacket::runways,
        PAD_CODEC.apply(ByteBufCodecs.list(MAX_FIELDS)), AirfieldMarkersPacket::pads,
        AirfieldMarkersPacket::new);

    public boolean isEmpty() {
        return runways.isEmpty() && pads.isEmpty();
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
