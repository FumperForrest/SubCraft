package dev.subcraft.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import dev.subcraft.world.tri.TriStore;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * "Ghost terrain": Subnautica's collision as real Minecraft blocks, so every mod that reasons
 * about blocks sees the seabed. Invisible (Subnautica draws the terrain), unbreakable, no item,
 * waterloggable, and dark to sky light so deep caves are dark in Minecraft's logic too.
 *
 * Phase 0a: every collision cell is a full block. Partial cells (8x8x8 masks) come in Phase 1.
 */
public class TerrainBlock extends Block implements SimpleWaterloggedBlock {
	public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

	public TerrainBlock(Properties properties) {
		super(properties);
		registerDefaultState(this.stateDefinition.any().setValue(WATERLOGGED, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(WATERLOGGED);
	}

	/**
	 * Players move against the host's exact triangles where those are known (TriCollider), so the
	 * voxel approximation must not also stop them: they'd be stair-stepped, and the server's
	 * movement check would disagree with the client.
	 */
	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		if (context instanceof EntityCollisionContext ecc && ecc.getEntity() instanceof Player && TriStore.isKnown(pos.getX(), pos.getY(), pos.getZ())) {
			return Shapes.empty();
		}
		return super.getCollisionShape(state, level, pos, context);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.INVISIBLE;
	}

	@Override
	protected FluidState getFluidState(BlockState state) {
		return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
		return false;
	}

	@Override
	protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
		return 15;
	}
}
