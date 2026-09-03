package xyz.przemyk.simpleplanes.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.container.PlaneWorkbenchContainer;

public class PlaneWorkbenchBlock extends Block implements EntityBlock {

    public static final Component CONTAINER_NAME = Component.translatable(SimplePlanesMod.MODID + ".container.plane_workbench");

    public PlaneWorkbenchBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        } else {
            if (level.getBlockEntity(pos) instanceof PlaneWorkbenchBlockEntity planeWorkbenchBlockEntity) {
                SimpleMenuProvider menu = new SimpleMenuProvider((id, inventory, p) ->
                    new PlaneWorkbenchContainer(id, inventory, pos, planeWorkbenchBlockEntity.itemStackHandler, planeWorkbenchBlockEntity.selectedRecipe), CONTAINER_NAME);
                player.openMenu(menu);
            }
            return InteractionResult.CONSUME;
        }
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
        return new PlaneWorkbenchBlockEntity(blockPos, blockState);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos blockPos, boolean movedByPiston) {
        // 26.2: `onRemove` is gone. Dropping the workbench contents moved to
        // PlaneWorkbenchBlockEntity#preRemoveSideEffects, which still sees the block entity.
        Containers.updateNeighboursAfterDestroy(state, level, blockPos);
    }
}
