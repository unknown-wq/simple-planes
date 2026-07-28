package xyz.przemyk.simpleplanes.setup;

import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.datapack.PlaneLiquidFuelReloadListener;
import xyz.przemyk.simpleplanes.datapack.PlanePayloadReloadListener;

public class SimplePlanesDatapack {

    public static void init() {
        ResourceLoader dataLoader = ResourceLoader.get(PackType.SERVER_DATA);
        dataLoader.registerReloadListener(
            Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "plane_payload"),
            new PlanePayloadReloadListener());
        dataLoader.registerReloadListener(
            Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "plane_liquid_fuels"),
            new PlaneLiquidFuelReloadListener());
    }
}
