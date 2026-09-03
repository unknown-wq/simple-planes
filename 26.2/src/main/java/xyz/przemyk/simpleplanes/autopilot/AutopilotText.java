package xyz.przemyk.simpleplanes.autopilot;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import xyz.przemyk.simpleplanes.SimplePlanesMod;

/**
 * Translatable text for the autopilot, with the English always carried alongside the key.
 *
 * <p>{@code Component.translatable} on its own is not usable here. A dedicated server resolves
 * translatable components through {@link net.minecraft.locale.Language}, which only ever loads
 * {@code /assets/minecraft/lang/en_us.json} out of the vanilla jar — a mod's language files are
 * client assets and are never on that path. A plain translatable key therefore prints as the raw
 * key in {@code console.log}, which is where every one of these subcommands is tested from.
 *
 * <p>{@code translatableWithFallback} solves both halves at once: a client that has the resource
 * pack renders the translation, and the console renders the English fallback compiled in here. The
 * fallback is the source of truth for the headless tests, so it must always read as a complete
 * sentence on its own.
 */
public final class AutopilotText {

    private static final String PREFIX = SimplePlanesMod.MODID + ".autopilot.";

    private AutopilotText() {}

    public static MutableComponent tr(String key, String english, Object... args) {
        return Component.translatableWithFallback(PREFIX + key, english, args);
    }
}
