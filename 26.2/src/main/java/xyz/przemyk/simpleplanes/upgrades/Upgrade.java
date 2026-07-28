package xyz.przemyk.simpleplanes.upgrades;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;

import java.util.function.Function;

public abstract class Upgrade {

    private final UpgradeType type;
    protected final PlaneEntity planeEntity;
    public boolean updateClient = false;
    public boolean removed = false;

    public PlaneEntity getPlaneEntity() {
        return planeEntity;
    }

    public Upgrade(UpgradeType type, PlaneEntity planeEntity) {
        this.type = type;
        this.planeEntity = planeEntity;
    }

    /**
     * Call it when data is changed, and it needs to be synced to the client.
     * If called on a server, results in calling writePacket method on a server and readPacket on a client
     */
    protected void updateClient() {
        updateClient = true;
    }

    /**
     * Call it to remove this upgrade from the plane.
     */
    public void remove() {
        removed = true;
    }

    public final UpgradeType getType() {
        return type;
    }

    /**
     * Called when a passenger right clicks with an item.
     */
    public void onItemRightClick(Player player, InteractionHand hand) {}

    /**
     * Called every tick by plane entity.
     */
    public void tick() {}

    // TODO(port-26.2): DISABLED — upgrade rendering (render / renderScreen / renderScreenBg).
    // GuiGraphics, MultiBufferSource, RenderType.armorCutoutNoCull and ItemRenderer.getArmorFoilBuffer
    // no longer exist in 26.2 (renderers are render-state based now). Agent C owns the replacement.

    public void save(ValueOutput output) {}

    public void load(ValueInput input) {}

    public void onApply(ItemStack itemStack) {}

    /**
     * Called on the server.
     */
    public abstract void writePacket(RegistryFriendlyByteBuf buffer);

    /**
     * Called on the client.
     */
    public abstract void readPacket(RegistryFriendlyByteBuf buffer);

    /**
     * Called when upgrade is removed using wrench.
     */
    public void onRemoved() {}

    public abstract ItemStack getItemStack();

    public boolean canBeDroppedAsPayload() {
        return false;
    }

    public void dropAsPayload() {}

    public void addContainerData(Function<Slot, Slot> addSlot, Function<DataSlot, DataSlot> addDataSlot) {}
}
