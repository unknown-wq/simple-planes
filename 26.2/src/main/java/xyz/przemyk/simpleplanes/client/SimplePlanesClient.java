package xyz.przemyk.simpleplanes.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.gui.screens.MenuScreens;
import xyz.przemyk.simpleplanes.client.gui.ModifyUpgradesScreen;
import xyz.przemyk.simpleplanes.client.gui.PlaneInventoryScreen;
import xyz.przemyk.simpleplanes.client.gui.PlaneWorkbenchScreen;
import xyz.przemyk.simpleplanes.client.gui.StorageScreen;
import xyz.przemyk.simpleplanes.network.SimplePlanesNetworking;
import xyz.przemyk.simpleplanes.setup.SimplePlanesContainers;

/**
 * Client entrypoint (contract C2). Everything the NeoForge build did from
 * {@code @EventBusSubscriber(Dist.CLIENT)} classes happens here.
 */
@Environment(EnvType.CLIENT)
public class SimplePlanesClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        PlanesModelLayers.registerLayers();
        PlanesModelLayers.registerRenderers();

        MenuScreens.register(SimplePlanesContainers.PLANE_WORKBENCH.get(), PlaneWorkbenchScreen::new);
        MenuScreens.register(SimplePlanesContainers.UPGRADES_REMOVAL.get(), ModifyUpgradesScreen::new);
        MenuScreens.register(SimplePlanesContainers.STORAGE.get(), StorageScreen::new);
        MenuScreens.register(SimplePlanesContainers.PLANE_INVENTORY.get(), PlaneInventoryScreen::new);

        ClientEventHandler.registerKeyBindings();
        ClientTickEvents.END_CLIENT_TICK.register(ClientEventHandler::onClientTick);

        HudElementRegistry.addLast(ModBusClientEventHandler.HUD_ELEMENT_ID, ModBusClientEventHandler.INSTANCE);

        // The sound classes remember what they are playing in static tables keyed by entity, and the
        // sound engine drops its own instances on world unload without telling them. Leaving a world
        // is the one point at which those tables are known to be stale.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PlaneSound.clear());

        SimplePlanesNetworking.registerClient();

        // TODO(port-26.2): DISABLED — item colour providers (PlaneItemColors) were removed in 26.x;
        // plane item tints are model-JSON driven now. See PORT-STATUS "Disabled content".
    }
}
