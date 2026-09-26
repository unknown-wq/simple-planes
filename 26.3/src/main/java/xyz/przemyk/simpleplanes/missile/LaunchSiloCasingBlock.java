package xyz.przemyk.simpleplanes.missile;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * A dependent block of a launch silo: every block of the tube's volume that is not the master. Its state holds
 * the offset back to the master ({@code master = pos + (-dx, dy, -dz)}), so it can be resolved without a
 * block entity and without searching. Drawn entirely by the master's block entity renderer.
 */
public class LaunchSiloCasingBlock extends Block {

    public static final IntegerProperty DX = IntegerProperty.create("dx", 0, 1);
    public static final IntegerProperty DY = IntegerProperty.create("dy", 0, 4);
    public static final IntegerProperty DZ = IntegerProperty.create("dz", 0, 1);

    public LaunchSiloCasingBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(DX, 0).setValue(DY, 0).setValue(DZ, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(DX, DY, DZ);
    }

    public static BlockPos masterOf(BlockPos pos, BlockState state) {
        return pos.offset(-state.getValue(DX), state.getValue(DY), -state.getValue(DZ));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        SiloStructure.onPartRemoved(level, masterOf(pos, state));
    }
}
