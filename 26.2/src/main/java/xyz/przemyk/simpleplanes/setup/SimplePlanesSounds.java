package xyz.przemyk.simpleplanes.setup;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import xyz.przemyk.simpleplanes.SimplePlanesMod;

import java.util.function.Supplier;

public class SimplePlanesSounds {

    /** Class-load hook — sounds are registered eagerly below (contract C1). */
    public static void init() {
    }

    private static Supplier<SoundEvent> register(String name) {
        Identifier id = Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, name);
        SoundEvent value = Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
        return () -> value;
    }

    public static final Supplier<SoundEvent> PLANE_LOOP_SOUND_EVENT = register("plane_loop");
}
