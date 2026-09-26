package xyz.przemyk.simpleplanes.airdefence;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.Locale;

/**
 * Which side an aircraft is on. Chosen when the aircraft is spawned, saved and synced on {@link PlaneEntity}.
 * Air-defence silos engage {@link #HOSTILE} aircraft only.
 */
public enum Allegiance implements StringRepresentable {
    FRIENDLY("friendly"),
    HOSTILE("hostile");

    public static final Codec<Allegiance> CODEC = StringRepresentable.fromEnum(Allegiance::values);
    /** Key in the entity save data, in {@code /summon} NBT and in the plane item's entity tag. */
    public static final String NBT_KEY = "allegiance";

    private final String name;

    Allegiance(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public static @Nullable Allegiance byName(@Nullable String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT);
        for (Allegiance a : values()) if (a.name.equals(n)) return a;
        return null;
    }

    public static Allegiance byNameOr(@Nullable String name, Allegiance fallback) {
        Allegiance a = byName(name);
        return a == null ? fallback : a;
    }

    /** Status-line tag: empty for friendly, so existing readouts are unchanged. */
    public static String tag(PlaneEntity plane) {
        return plane.getAllegiance() == HOSTILE ? " hostile" : "";
    }
}
