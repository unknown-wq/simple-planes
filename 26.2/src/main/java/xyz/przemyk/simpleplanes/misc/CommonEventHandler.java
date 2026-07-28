package xyz.przemyk.simpleplanes.misc;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;

/**
 * Contract C5 — the NeoForge {@code @EventBusSubscriber} class is gone; the logic is now
 * registered as Fabric API callbacks from {@code SimplePlanesMod.onInitialize()}.
 */
public class CommonEventHandler {

    public static void register() {
        UseItemCallback.EVENT.register(CommonEventHandler::interact);
    }

    private static InteractionResult interact(Player player, Level level, InteractionHand hand) {
        Entity entity = player.getRootVehicle();
        if (entity instanceof PlaneEntity planeEntity) {
            ItemStack itemStack = player.getItemInHand(hand);

            if (!itemStack.isEmpty()) {
                for (Upgrade upgrade : planeEntity.upgrades.values()) {
                    upgrade.onItemRightClick(player, hand);
                }
            }
        }
        return InteractionResult.PASS;
    }
}
