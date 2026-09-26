package xyz.przemyk.simpleplanes.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/** The FPV drone's render state; its type alone tells {@code DroneMetalModel} to draw the charge. */
@Environment(EnvType.CLIENT)
public class FpvDroneRenderState extends PlaneRenderState {
}
