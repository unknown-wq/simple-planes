package xyz.przemyk.simpleplanes.items;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.entities.DroneEntity;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A strike drone as ammunition: not placeable, only launched by the Plane Strike Tool from the
 * other hand, and spent by it.
 */
public class DroneItem extends Item {

    public final Supplier<? extends EntityType<? extends DroneEntity>> droneEntityType;

    public DroneItem(Properties properties, Supplier<? extends EntityType<? extends DroneEntity>> droneEntityType) {
        super(properties.stacksTo(16));
        this.droneEntityType = droneEntityType;
    }

    @Override
    public void appendHoverText(ItemStack itemStack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag tooltipFlag) {
        builder.accept(Component.translatable(SimplePlanesMod.MODID + ".drone_desc"));
    }
}
