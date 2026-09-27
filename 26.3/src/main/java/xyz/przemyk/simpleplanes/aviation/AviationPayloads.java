package xyz.przemyk.simpleplanes.aviation;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.api.map.AviationSnapshot;
import xyz.przemyk.simpleplanes.api.map.LaunchResult;
import xyz.przemyk.simpleplanes.api.map.SiloAction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The seven payloads of the aviation map. Registered on both sides from {@link AviationService#init()}.
 * Clientbound payloads are only ever sent in reply to a request, and only to a player whose client
 * registered the channel ({@code ServerPlayNetworking.canSend}).
 */
public final class AviationPayloads {

    private AviationPayloads() {}

    /**
     * Protocol version carried by the snapshot request; bumped on an incompatible payload change. The server
     * answers only a client that asked with this version (see {@code AviationService}). 2: load / unload
     * request, action on the reply, service and air-defence fields on each silo. 3: pending flag on the reply
     * (remote launch). 4: the warhead fields on each silo; the warhead launch and warhead setting requests, which
     * are payloads of their own so the three older requests keep their exact bytes.
     */
    public static final int PROTOCOL = 4;

    public static final int MAX_AIRFIELDS = 128;
    public static final int MAX_HELIPADS = 128;
    public static final int MAX_ROUTES = 128;
    public static final int MAX_FLIGHTS = 64;
    public static final int MAX_SILOS = 256;
    private static final int MAX_STRING = 256;

    /** C2S: "send me a snapshot". */
    public record SnapshotRequest(int protocol) implements CustomPacketPayload {
        public static final Type<SnapshotRequest> TYPE = new Type<>(id("aviation_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotRequest> CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, SnapshotRequest::protocol, SnapshotRequest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: the snapshot. */
    public record Snapshot(AviationSnapshot snapshot) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(id("aviation_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC =
            StreamCodec.of((buf, p) -> writeSnapshot(buf, p.snapshot()), buf -> new Snapshot(readSnapshot(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C2S: launch from {@code silo} at block column {@code x, z}. {@code y} is the client's idea of the
     * target height, or {@link xyz.przemyk.simpleplanes.api.map.AviationMap#SURFACE} to let the server decide.
     */
    public record LaunchRequest(BlockPos silo, int x, int y, int z) implements CustomPacketPayload {
        public static final Type<LaunchRequest> TYPE = new Type<>(id("aviation_launch"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LaunchRequest> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, LaunchRequest::silo,
            ByteBufCodecs.VAR_INT, LaunchRequest::x,
            ByteBufCodecs.INT, LaunchRequest::y,
            ByteBufCodecs.VAR_INT, LaunchRequest::z,
            LaunchRequest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C2S: a {@link LaunchRequest} with the warhead of this one launch, {@code piercing} or the ordinary blast, over
     * the silo's own setting. A payload of its own rather than a field on {@link LaunchRequest}: a server without it
     * is simply not sent one ({@code canSend}), instead of failing to decode a longer launch request.
     */
    public record WarheadLaunchRequest(BlockPos silo, int x, int y, int z, boolean piercing) implements CustomPacketPayload {
        public static final Type<WarheadLaunchRequest> TYPE = new Type<>(id("aviation_launch_warhead"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WarheadLaunchRequest> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, WarheadLaunchRequest::silo,
            ByteBufCodecs.VAR_INT, WarheadLaunchRequest::x,
            ByteBufCodecs.INT, WarheadLaunchRequest::y,
            ByteBufCodecs.VAR_INT, WarheadLaunchRequest::z,
            ByteBufCodecs.BOOL, WarheadLaunchRequest::piercing,
            WarheadLaunchRequest::new);

        public LaunchRequest launch() {
            return new LaunchRequest(silo, x, y, z);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: set the strike warhead of {@code silo}: piercing, or the ordinary blast. Answered by a snapshot. */
    public record WarheadRequest(BlockPos silo, boolean piercing) implements CustomPacketPayload {
        public static final Type<WarheadRequest> TYPE = new Type<>(id("aviation_warhead"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WarheadRequest> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, WarheadRequest::silo,
            ByteBufCodecs.BOOL, WarheadRequest::piercing,
            WarheadRequest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: load or unload {@code silo}. {@link SiloAction#LAUNCH} is not valid here and is refused. */
    public record SiloRequest(BlockPos silo, SiloAction action) implements CustomPacketPayload {
        public static final Type<SiloRequest> TYPE = new Type<>(id("aviation_silo"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SiloRequest> CODEC = StreamCodec.of(
            (buf, p) -> {
                BlockPos.STREAM_CODEC.encode(buf, p.silo());
                buf.writeVarInt(p.action().ordinal());
            },
            buf -> new SiloRequest(BlockPos.STREAM_CODEC.decode(buf), decodeAction(buf.readVarInt())));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: the answer to a {@link LaunchRequest} or {@link SiloRequest}. */
    public record LaunchReply(LaunchResult result) implements CustomPacketPayload {
        public static final Type<LaunchReply> TYPE = new Type<>(id("aviation_launch_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LaunchReply> CODEC = StreamCodec.of(
            (buf, p) -> {
                LaunchResult r = p.result();
                BlockPos.STREAM_CODEC.encode(buf, r.silo());
                buf.writeBoolean(r.accepted());
                ComponentSerialization.STREAM_CODEC.encode(buf, r.message());
                buf.writeDouble(r.targetX());
                buf.writeDouble(r.targetY());
                buf.writeDouble(r.targetZ());
                buf.writeVarInt(r.action().ordinal());
                buf.writeBoolean(r.pending());
            },
            buf -> new LaunchReply(new LaunchResult(BlockPos.STREAM_CODEC.decode(buf), buf.readBoolean(),
                ComponentSerialization.STREAM_CODEC.decode(buf), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                decodeAction(buf.readVarInt()), buf.readBoolean())));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------ snapshot codec

    private static void writeSnapshot(RegistryFriendlyByteBuf buf, AviationSnapshot s) {
        buf.writeUtf(s.dimension(), MAX_STRING);
        buf.writeLong(s.gameTime());
        buf.writeVarInt(s.nearRadius());
        buf.writeBoolean(s.launchPermitted());
        buf.writeVarInt(s.snapshotRadius());
        writeList(buf, s.airfields(), MAX_AIRFIELDS, (b, a) -> {
            b.writeUtf(a.name(), MAX_STRING);
            BlockPos.STREAM_CODEC.encode(b, a.thresholdA());
            BlockPos.STREAM_CODEC.encode(b, a.thresholdB());
            b.writeVarInt(a.width());
            b.writeUtf(a.designatorA(), 8);
            b.writeUtf(a.designatorB(), 8);
            b.writeBoolean(a.usable());
        });
        writeList(buf, s.helipads(), MAX_HELIPADS, (b, h) -> {
            b.writeUtf(h.name(), MAX_STRING);
            BlockPos.STREAM_CODEC.encode(b, h.centre());
            b.writeVarInt(h.radius());
        });
        writeList(buf, s.routes(), MAX_ROUTES, (b, r) -> {
            b.writeVarInt(r.id());
            b.writeUtf(r.fieldA(), MAX_STRING);
            b.writeUtf(r.fieldB(), MAX_STRING);
            BlockPos.STREAM_CODEC.encode(b, r.endA());
            BlockPos.STREAM_CODEC.encode(b, r.endB());
            b.writeUtf(r.state(), 32);
            b.writeUtf(r.aircraftType(), 32);
            b.writeBoolean(r.hostile());
            b.writeVarInt(r.legs());
            b.writeUtf(r.note(), MAX_STRING);
        });
        writeList(buf, s.flights(), MAX_FLIGHTS, (b, f) -> {
            b.writeVarInt(f.entityId());
            b.writeUtf(f.aircraftType(), 32);
            b.writeBoolean(f.hostile());
            b.writeDouble(f.x());
            b.writeDouble(f.y());
            b.writeDouble(f.z());
            b.writeUtf(f.kind(), 32);
            b.writeUtf(f.mode(), 32);
            b.writeBoolean(f.hasDestination());
            b.writeDouble(f.destX());
            b.writeDouble(f.destZ());
            b.writeUtf(f.destination(), MAX_STRING);
        });
        writeList(buf, s.silos(), MAX_SILOS, (b, o) -> {
            BlockPos.STREAM_CODEC.encode(b, o.pos());
            b.writeVarInt(o.tier());
            b.writeBoolean(o.strike());
            b.writeBoolean(o.loaded());
            b.writeUtf(o.phase(), 32);
            b.writeBoolean(o.chunkLoaded());
            b.writeDouble(o.mouthX());
            b.writeDouble(o.mouthZ());
            b.writeVarInt(o.minRange());
            b.writeVarInt(o.maxRange());
            b.writeDouble(o.distance());
            b.writeBoolean(o.usable());
            ComponentSerialization.STREAM_CODEC.encode(b, o.status());
            b.writeBoolean(o.serviceable());
            ComponentSerialization.STREAM_CODEC.encode(b, o.serviceStatus());
            b.writeDouble(o.detectionRadius());
            b.writeDouble(o.engagementRange());
            b.writeBoolean(o.piercing());
            b.writeVarInt(o.pierceMinRange());
            b.writeDouble(o.pierceRadius());
        });
    }

    private static AviationSnapshot readSnapshot(RegistryFriendlyByteBuf buf) {
        String dimension = buf.readUtf(MAX_STRING);
        long gameTime = buf.readLong();
        int nearRadius = buf.readVarInt();
        boolean permitted = buf.readBoolean();
        int radius = buf.readVarInt();
        List<AviationSnapshot.Airfield> airfields = readList(buf, MAX_AIRFIELDS, b -> new AviationSnapshot.Airfield(
            b.readUtf(MAX_STRING), BlockPos.STREAM_CODEC.decode(b), BlockPos.STREAM_CODEC.decode(b), b.readVarInt(),
            b.readUtf(8), b.readUtf(8), b.readBoolean()));
        List<AviationSnapshot.Helipad> helipads = readList(buf, MAX_HELIPADS, b -> new AviationSnapshot.Helipad(
            b.readUtf(MAX_STRING), BlockPos.STREAM_CODEC.decode(b), b.readVarInt()));
        List<AviationSnapshot.Route> routes = readList(buf, MAX_ROUTES, b -> new AviationSnapshot.Route(
            b.readVarInt(), b.readUtf(MAX_STRING), b.readUtf(MAX_STRING), BlockPos.STREAM_CODEC.decode(b),
            BlockPos.STREAM_CODEC.decode(b), b.readUtf(32), b.readUtf(32), b.readBoolean(), b.readVarInt(),
            b.readUtf(MAX_STRING)));
        List<AviationSnapshot.Flight> flights = readList(buf, MAX_FLIGHTS, b -> new AviationSnapshot.Flight(
            b.readVarInt(), b.readUtf(32), b.readBoolean(), b.readDouble(), b.readDouble(), b.readDouble(),
            b.readUtf(32), b.readUtf(32), b.readBoolean(), b.readDouble(), b.readDouble(), b.readUtf(MAX_STRING)));
        List<AviationSnapshot.Silo> silos = readList(buf, MAX_SILOS, b -> new AviationSnapshot.Silo(
            BlockPos.STREAM_CODEC.decode(b), b.readVarInt(), b.readBoolean(), b.readBoolean(), b.readUtf(32),
            b.readBoolean(), b.readDouble(), b.readDouble(), b.readVarInt(), b.readVarInt(), b.readDouble(),
            b.readBoolean(), ComponentSerialization.STREAM_CODEC.decode(b), b.readBoolean(),
            ComponentSerialization.STREAM_CODEC.decode(b), b.readDouble(), b.readDouble(), b.readBoolean(), b.readVarInt(),
            b.readDouble()));
        return new AviationSnapshot(dimension, gameTime, nearRadius, permitted, radius,
            airfields, helipads, routes, flights, silos);
    }

    private static <T> void writeList(RegistryFriendlyByteBuf buf, List<T> list, int max,
                                      BiConsumer<RegistryFriendlyByteBuf, T> writer) {
        int n = Math.min(list.size(), max);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) writer.accept(buf, list.get(i));
    }

    private static <T> List<T> readList(RegistryFriendlyByteBuf buf, int max, Function<RegistryFriendlyByteBuf, T> reader) {
        int n = buf.readVarInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("aviation snapshot list of " + n + " exceeds " + max);
        List<T> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(reader.apply(buf));
        return List.copyOf(out);
    }

    private static SiloAction decodeAction(int id) {
        SiloAction action = SiloAction.byId(id);
        if (action == null) throw new IllegalArgumentException("unknown silo action " + id);
        return action;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, path);
    }

    /** Shorthand used by the service for translatable text with an English fallback (dedicated server consoles). */
    static Component text(String key, String english, Object... args) {
        return Component.translatableWithFallback(SimplePlanesMod.MODID + ".aviation." + key, english, args);
    }
}
