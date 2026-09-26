package xyz.przemyk.simpleplanes.api.map;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * The server's answer to {@link AviationMap#requestLaunch}. The same text is shown on the player's action bar.
 *
 * @param silo     the silo the request named
 * @param accepted true when the launch sequence started
 * @param message  what happened, or why not
 * @param targetX  the target the server resolved (block centre); meaningful when accepted
 * @param targetY  the resolved target height (see {@code MISSILES.md}, "Launching from the map")
 */
public record LaunchResult(BlockPos silo, boolean accepted, Component message,
                           double targetX, double targetY, double targetZ) {}
