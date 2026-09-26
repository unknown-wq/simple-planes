package xyz.przemyk.simpleplanes.autopilot;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.api.dispatch.AircraftStatus.Phase;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-dimension record of dispatchable aircraft, their orders and the undelivered event outbox.
 * Stored as a plain compound so fields can be added without a codec arity limit.
 */
public final class DispatchSavedData extends SavedData {

    private static final Codec<DispatchSavedData> CODEC = CompoundTag.CODEC.xmap(
        DispatchSavedData::fromTag, DispatchSavedData::toTag);

    public static final SavedDataType<DispatchSavedData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "dispatch"),
        DispatchSavedData::new,
        CODEC,
        DataFixTypes.LEVEL);

    /** Oldest events are dropped past this, so an owner that never registers cannot grow the file. */
    static final int OUTBOX_LIMIT = 512;

    private final Map<UUID, Sortie> sorties = new LinkedHashMap<>();
    private final List<CompoundTag> outbox = new ArrayList<>();

    public DispatchSavedData() {}

    public static DispatchSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    @Nullable Sortie sortie(UUID aircraft) {
        return sorties.get(aircraft);
    }

    Collection<Sortie> sorties() {
        return sorties.values();
    }

    List<Sortie> sortieList() {
        return new ArrayList<>(sorties.values());
    }

    boolean isEmpty() {
        return sorties.isEmpty() && outbox.isEmpty();
    }

    void put(Sortie sortie) {
        sorties.put(sortie.aircraft, sortie);
        setDirty();
    }

    void remove(UUID aircraft) {
        if (sorties.remove(aircraft) != null) {
            setDirty();
        }
    }

    List<CompoundTag> outbox() {
        return outbox;
    }

    void post(CompoundTag event) {
        outbox.add(event);
        while (outbox.size() > OUTBOX_LIMIT) {
            outbox.remove(0);
        }
        setDirty();
    }

    void changed() {
        setDirty();
    }

    // ------------------------------------------------------------------ serialisation

    private static DispatchSavedData fromTag(CompoundTag tag) {
        DispatchSavedData data = new DispatchSavedData();
        for (Tag entry : tag.getListOrEmpty("sorties")) {
            if (entry instanceof CompoundTag compound) {
                Sortie sortie = Sortie.load(compound);
                if (sortie != null) {
                    data.sorties.put(sortie.aircraft, sortie);
                }
            }
        }
        for (Tag entry : tag.getListOrEmpty("outbox")) {
            if (entry instanceof CompoundTag compound) {
                data.outbox.add(compound);
            }
        }
        return data;
    }

    private CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Sortie sortie : sorties.values()) {
            list.add(sortie.save());
        }
        tag.put("sorties", list);
        ListTag events = new ListTag();
        events.addAll(outbox);
        tag.put("outbox", events);
        return tag;
    }

    /** One aircraft under the dispatch service, and its current order. Mutable; server thread only. */
    static final class Sortie {
        final UUID aircraft;
        String homePad;
        @Nullable String ownerId;
        Phase phase = Phase.IDLE;
        @Nullable UUID orderId;
        @Nullable BlockPos target;
        int searchRadius;
        @Nullable Helipad zone;
        int groundHoldTicks;
        long holdUntil = -1;
        boolean returnHome = true;
        CompoundTag userData = new CompoundTag();
        double cruiseSpeed;
        BlockPos lastKnown = BlockPos.ZERO;
        @Nullable String abortReason;
        int retries;
        boolean departed;
        boolean searchFailed;
        /** Emergency landing zone after the return failed; null means the home pad. */
        @Nullable Helipad returnTo;
        @Nullable String lostReason;

        Sortie(UUID aircraft, String homePad) {
            this.aircraft = aircraft;
            this.homePad = homePad;
        }

        boolean inOrder() {
            return phase == Phase.OUTBOUND || phase == Phase.AT_TARGET || phase == Phase.RETURNING;
        }

        void clearOrder() {
            orderId = null;
            target = null;
            zone = null;
            holdUntil = -1;
            abortReason = null;
            retries = 0;
            departed = false;
            searchFailed = false;
            returnTo = null;
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.store("aircraft", UUIDUtil.CODEC, aircraft);
            tag.putString("home_pad", homePad);
            if (ownerId != null) {
                tag.putString("owner", ownerId);
            }
            tag.putString("phase", phase.name());
            if (orderId != null) {
                tag.store("order", UUIDUtil.CODEC, orderId);
            }
            if (target != null) {
                tag.store("target", BlockPos.CODEC, target);
            }
            tag.putInt("search_radius", searchRadius);
            if (zone != null) {
                tag.store("zone", Helipad.CODEC, zone);
            }
            tag.putInt("hold", groundHoldTicks);
            tag.putLong("hold_until", holdUntil);
            tag.putBoolean("return_home", returnHome);
            tag.put("user_data", userData.copy());
            tag.putDouble("speed", cruiseSpeed);
            tag.store("last_known", BlockPos.CODEC, lastKnown);
            if (abortReason != null) {
                tag.putString("abort", abortReason);
            }
            tag.putInt("retries", retries);
            tag.putBoolean("departed", departed);
            tag.putBoolean("search_failed", searchFailed);
            if (returnTo != null) {
                tag.store("return_to", Helipad.CODEC, returnTo);
            }
            if (lostReason != null) {
                tag.putString("lost", lostReason);
            }
            return tag;
        }

        static @Nullable Sortie load(CompoundTag tag) {
            UUID aircraft = tag.read("aircraft", UUIDUtil.CODEC).orElse(null);
            if (aircraft == null) {
                return null;
            }
            Sortie s = new Sortie(aircraft, tag.getStringOr("home_pad", ""));
            s.ownerId = tag.getString("owner").orElse(null);
            try {
                s.phase = Phase.valueOf(tag.getStringOr("phase", Phase.IDLE.name()));
            } catch (IllegalArgumentException e) {
                s.phase = Phase.IDLE;
            }
            s.orderId = tag.read("order", UUIDUtil.CODEC).orElse(null);
            s.target = tag.read("target", BlockPos.CODEC).orElse(null);
            s.searchRadius = tag.getIntOr("search_radius", 32);
            s.zone = tag.read("zone", Helipad.CODEC).orElse(null);
            s.groundHoldTicks = tag.getIntOr("hold", 0);
            s.holdUntil = tag.getLongOr("hold_until", -1);
            s.returnHome = tag.getBooleanOr("return_home", true);
            s.userData = tag.getCompoundOrEmpty("user_data").copy();
            s.cruiseSpeed = tag.getDoubleOr("speed", 0);
            s.lastKnown = tag.read("last_known", BlockPos.CODEC).orElse(BlockPos.ZERO);
            s.abortReason = tag.getString("abort").orElse(null);
            s.retries = tag.getIntOr("retries", 0);
            s.departed = tag.getBooleanOr("departed", false);
            s.searchFailed = tag.getBooleanOr("search_failed", false);
            s.returnTo = tag.read("return_to", Helipad.CODEC).orElse(null);
            s.lostReason = tag.getString("lost").orElse(null);
            return s;
        }
    }
}
