package xyz.przemyk.simpleplanes.upgrades;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import xyz.przemyk.simpleplanes.client.gui.PlaneInventoryScreen;
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

    /**
     * Draws behind the plane inventory screen's slots: the furnace's burn arrow, the battery, the
     * fuel tank. Called for every installed upgrade from {@link PlaneInventoryScreen}.
     *
     * <p>The old {@code GuiGraphics} is gone; 26.2 extracts the whole GUI into a render state first,
     * so a screen draws into a {@link GuiGraphicsExtractor} instead. Everything these overlays need
     * is on it — {@code blit}, {@code fill}, {@code item} — so the port is a change of receiver and
     * of the blit signature, which now takes the pipeline and the texture size explicitly.
     *
     * <p><b>Marked client-only, and that is what makes it safe to name client types here.</b> A
     * dedicated server has no {@code GuiGraphicsExtractor} at all, and Fabric's loader strips
     * {@link Environment}-annotated members on the side they do not belong to, so these two methods
     * and every override of them simply do not exist there. It is the same reason the model
     * rendering that used to sit beside them now lives in {@code UpgradesModels} — the difference is
     * that a per-upgrade overlay reads that upgrade's own state, so it belongs next to it.
     */
    @Environment(EnvType.CLIENT)
    public void renderScreenBg(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                               float partialTick, PlaneInventoryScreen screen) {}

    /** As {@link #renderScreenBg}, in front of the slots: the hover tooltips over those gauges. */
    @Environment(EnvType.CLIENT)
    public void renderScreen(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                             float partialTick, PlaneInventoryScreen screen) {}

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
