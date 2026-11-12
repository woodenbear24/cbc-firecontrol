package com.cbc.firecontrol.block;

import com.cbc.firecontrol.block.entity.TrajectoryCalculatorBlockEntity;
import com.cbc.firecontrol.init.CBCFCBlockEntities;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Trajectory Calculator - currently a purely decorative, placeable block that presents the
 * Blockbench model at .cbc_src/trajectory_calculator.bbmodel in-world. No ballistic computation
 * / block entity yet; that can be layered on later (see CBCFCBlocks registration + SKILL.md note).
 *
 * The modeled geometry (three cubes: a 16x1 base plate, a 16x2 mid slab inset on Z, and a tall
 * 13x10 panel body at z=8..16) has its "front" authored toward +Z, i.e. SOUTH, matching the same
 * model-orientation convention as AdvancedCannonMountBlock. So the blockstates rotate the model with
 * the standard horizontal mapping (south=0, north=180, east=270, west=90) and the panel faces the
 * block's HORIZONTAL_FACING.
 *
 * Placement follows the mount's convention: facing = the player's horizontal look direction.
 * No VERTICAL_DIRECTION (this block always sits upright on the floor), no kinematics.
 * It implements Create's IWrenchable: right-click with a wrench rotates the panel clockwise to the
 * next compass facing; sneak + right-click picks it up / disassembles it (Create's default handling).
 *
 * It also implements Create's IBE so the block instantiates a {@link TrajectoryCalculatorBlockEntity}
 * (5 link-card slots + the server-computed firing solutions), and plain right-click with nothing in hand
 * opens that block's 5-slot console. Wrench behaviour is unchanged: Create routes the wrench interaction
 * before useWithoutItem, so wrench-rotate / sneak-pickup still win over opening the GUI.
 */
public class TrajectoryCalculatorBlock extends Block implements IWrenchable, IBE<TrajectoryCalculatorBlockEntity> {

	public static final DirectionProperty HORIZONTAL_FACING = BlockStateProperties.HORIZONTAL_FACING;

	public TrajectoryCalculatorBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(HORIZONTAL_FACING, Direction.SOUTH));
	}

	@Override
	protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
		builder.add(HORIZONTAL_FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return this.defaultBlockState().setValue(HORIZONTAL_FACING, context.getHorizontalDirection());
	}

	@Override
	public BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(HORIZONTAL_FACING, rotation.rotate(state.getValue(HORIZONTAL_FACING)));
	}

	@Override
	public BlockState mirror(BlockState state, Mirror mirror) {
		return state.setValue(HORIZONTAL_FACING, mirror.mirror(state.getValue(HORIZONTAL_FACING)));
	}

	@Override
	public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
		// Create routes a wrench right-click here for any block implementing IWrenchable. Create's
		// default implementation only understands Create's own kinetic block properties (it never calls
		// Block#rotate), so for this plain, non-kinetic block override it: turn the panel's horizontal
		// facing clockwise around the vertical axis -> each wrench click faces the next compass direction.
		return originalState.setValue(HORIZONTAL_FACING,
				originalState.getValue(HORIZONTAL_FACING).getClockWise());
	}

	@Override
	public Class<TrajectoryCalculatorBlockEntity> getBlockEntityClass() {
		return TrajectoryCalculatorBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends TrajectoryCalculatorBlockEntity> getBlockEntityType() {
		return CBCFCBlockEntities.TRAJECTORY_CALCULATOR_BE.get();
	}

	/** Bare hand right-click opens the 5 link-card console (wrench interactions are handled by IWrenchable first). */
	@Override
	public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
			BlockHitResult hit) {
		if (level.isClientSide)
			return InteractionResult.SUCCESS;
		if (level.getBlockEntity(pos) instanceof TrajectoryCalculatorBlockEntity be) {
			player.openMenu(be, pos);
			return InteractionResult.CONSUME;
		}
		return InteractionResult.PASS;
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
		if (!state.is(newState.getBlock())
				&& level.getBlockEntity(pos) instanceof TrajectoryCalculatorBlockEntity be) {
			// Link cards are real inventory items: give them back before the block entity goes away.
			if (!level.isClientSide) {
				var handler = be.getInventory();
				for (int slot = 0; slot < handler.getSlots(); slot++) {
					ItemStack stack = handler.extractItem(slot, 1, false);
					if (!stack.isEmpty())
						Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
				}
			}
			// Create's recommended STE cleanup path (destroy() + removeBlockEntity), replacing super.onRemove.
			IBE.onRemove(state, level, pos, newState);
			return;
		}
		super.onRemove(state, level, pos, newState, moving);
	}
}
