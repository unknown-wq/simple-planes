package xyz.przemyk.simpleplanes.api.map;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * The server's answer to a silo request: {@link AviationMap#requestLaunch}, {@link AviationMap#requestLoad} or
 * {@link AviationMap#requestUnload}. The same text is shown on the player's action bar.
 *
 * @param silo     the silo the request named (the master position when the server found one)
 * @param accepted true when the launch sequence started, or the missile was loaded / unloaded
 * @param message  what happened, or why not
 * @param targetX  the target the server resolved (block centre); meaningful for an accepted {@link SiloAction#LAUNCH}
 * @param targetY  the resolved target height (see {@code MISSILES.md}, "Launching from the map")
 * @param action   which request this answers
 */
public record LaunchResult(BlockPos silo, boolean accepted, Component message,
                           double targetX, double targetY, double targetZ, SiloAction action) {}
