package xyz.przemyk.simpleplanes.upgrades.seats;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;

public class SeatsUpgrade extends Upgrade {

    public static final Identifier TEXTURE = SimplePlanesMod.texture("seats.png");
    public static final Identifier LARGE_TEXTURE = SimplePlanesMod.texture("seats_large.png");
    public static final Identifier CARGO_TEXTURE = SimplePlanesMod.texture("cargo_plane_metal.png");
    public static final Identifier HELI_TEXTURE = SimplePlanesMod.texture("seats_heli.png");

    public SeatsUpgrade(PlaneEntity planeEntity) {
        super(SimplePlanesUpgrades.SEATS.get(), planeEntity);
    }

    @Override
    public void writePacket(RegistryFriendlyByteBuf buffer) {}

    @Override
    public void readPacket(RegistryFriendlyByteBuf buffer) {}

    @Override
    public void onRemoved() {
        planeEntity.ejectPassengers();
    }

    @Override
    public ItemStack getItemStack() {
        return SimplePlanesItems.SEATS.get().getDefaultInstance();
    }
}
