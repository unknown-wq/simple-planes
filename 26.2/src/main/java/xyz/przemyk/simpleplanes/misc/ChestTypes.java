package xyz.przemyk.simpleplanes.misc;

import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import xyz.przemyk.simpleplanes.SimplePlanesMod;

import java.util.function.Consumer;

/**
 * Chest-geometry helper. Replaces the deleted {@code compat.ironchest.IronChestsCompat}:
 * only the vanilla-chest layout survives (the Iron Chests / Quark integrations were cut,
 * see PORT-STATUS "Disabled content"), but the method shapes are preserved so the storage
 * container and screen keep working unchanged.
 */
public class ChestTypes {

    public static final String VANILLA_CHEST_NAME = "minecraft:chest";

    public static final Identifier VANILLA_CHEST_GUI =
        Identifier.fromNamespaceAndPath(SimplePlanesMod.MODID, "textures/gui/vanilla_chest.png");

    public static int getSize(String chestType) {
        return 27;
    }

    public static int getRowLength(String chestType) {
        return 9;
    }

    public static int getRowCount(String chestType) {
        return getSize(chestType) / getRowLength(chestType);
    }

    /**
     * Panel width of the chest screen. This is <strong>184</strong>, not the vanilla 176: the
     * bundled {@code textures/gui/vanilla_chest.png} is a 184x168 panel whose slot cells sit at
     * {@code x = 12 + column * 18}, so the container geometry below and {@code StorageScreen} have
     * to follow the texture rather than {@code ChestMenu}.
     */
    public static int getXSize(String chestType) {
        return 184;
    }

    public static int getYSize(String chestType) {
        return 114 + getRowCount(chestType) * 18;
    }

    public static Identifier getGuiTexture(String chestType) {
        return VANILLA_CHEST_GUI;
    }

    public static int getTextureYSize(String chestType) {
        return 256;
    }

    /**
     * Slot layout for {@code vanilla_chest.png}. Measured against the texture: the chest cells are
     * painted at {@code x = 12 + column * 18}, {@code y = 18 + row * 18}; for the 27-slot chest the
     * player rows are painted at y = 86 / 104 / 122 and the hotbar at y = 144.
     */
    public static void addSlots(String chestType, Container container, int rowCount, Inventory playerInventory, Consumer<Slot> addSlotFunction) {
        int rowLength = getRowLength(chestType);
        for (int row = 0; row < rowCount; ++row) {
            for (int column = 0; column < rowLength; ++column) {
                addSlotFunction.accept(new Slot(container, column + row * rowLength, 12 + column * 18, 18 + row * 18));
            }
        }

        int ySize = getYSize(chestType);
        int leftColumn = (getXSize(chestType) - 162) / 2 + 1;

        for (int row = 0; row < 3; ++row) {
            for (int column = 0; column < 9; ++column) {
                addSlotFunction.accept(new Slot(playerInventory, column + row * 9 + 9, leftColumn + column * 18, ySize - (4 - row) * 18 - 10));
            }
        }

        for (int column = 0; column < 9; ++column) {
            addSlotFunction.accept(new Slot(playerInventory, column, leftColumn + column * 18, ySize - 24));
        }
    }
}
