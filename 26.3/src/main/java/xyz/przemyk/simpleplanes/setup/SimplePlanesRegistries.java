package xyz.przemyk.simpleplanes.setup;

import net.fabricmc.fabric.api.event.registry.FabricRegistryBuilder;
import net.fabricmc.fabric.api.event.registry.RegistryAttribute;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;

@SuppressWarnings("unused")
public class SimplePlanesRegistries {

    public static final ResourceKey<Registry<UpgradeType>> UPGRADE_TYPE_REGISTRY_KEY =
        ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "upgrade_types"));

    public static final Registry<UpgradeType> UPGRADE_TYPE = FabricRegistryBuilder
        .create(UPGRADE_TYPE_REGISTRY_KEY)
        .attribute(RegistryAttribute.SYNCED)
        .buildAndRegister();

    /** Class-load hook; the registry itself is created by the static initializer above. */
    public static void init() {
    }
}
