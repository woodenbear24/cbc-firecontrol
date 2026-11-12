package com.cbc.firecontrol.block;

import com.cbc.firecontrol.init.CBCFCBlockEntities;
import com.cbc.firecontrol.item.CalculatorLinkItem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.LevelReader;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

/**
 * Advanced Cannon Mount - half-slab variant of CBC's Cannon Mount Block.
 *
 * Physical block occupancy is HALF the cell:
 *   VERTICAL_DIRECTION=DOWN (upright, base plate on floor, cannon above)  -> lower half [0,0,0]-[16,8,16]
 *   VERTICAL_DIRECTION=UP   (inverted, base plate on ceiling, cannon below) -> upper half [0,8,0]-[16,16,16]
 *
 * Block model files (see assets/cbc_firecontrol/models/block/) render a plinth occupying the physical
 * half plus thin bracket structure (neck + cap + guards) extending up into the empty half of the cell.
 * Model geometry exceeds collision so mount visually reads as "pedestal with bracket arms reaching
 * toward the cannon cell" while entities only collide with the plinth half.
 *
 * Cannon assembly position = 1 grid cell in the cannon direction (adjacent, not 2 like CBC's default).
 * See AdvancedCannonMountBlockEntity.assemble() and resetContraptionToOffset() for the -1 offset formula.
 *
 * Kinetic network (hasShaftTowards inherited from CannonMountBlock) unchanged: Create's connection logic
 * is logical (face predicate match), not physical overlap. Half-slab mount still validly accepts shafts
 * on 2 horizontal pitch-axis faces + VERTICAL_DIRECTION face.
 *
 * Wrench cycle (getRotatedBlockState), redstone-driven assembly/fire signals (neighborChanged/tick),
 * onPlace kinetic state preservation, and updateIndirectNeighbourShapes all inherited unchanged.
 */
public class AdvancedCannonMountBlock extends CannonMountBlock {

	private static final VoxelShape SHAPE_LOWER_HALF = Shapes.box(0, 0, 0, 1, 0.5, 1);
	private static final VoxelShape SHAPE_UPPER_HALF = Shapes.box(0, 0.5, 0, 1, 1, 1);

	public AdvancedCannonMountBlock(Properties properties) {
		super(properties);
	}

	/** Convenience factory for Properties that mimic CBC's cbcMachine() defaults. */
	public static Properties defaultProperties() {
		return BlockBehaviour.Properties.of()
				.mapColor(MapColor.METAL)
				.strength(3.0f)
				.requiresCorrectToolForDrops()
				.noOcclusion()
				.isRedstoneConductor((state, level, pos) -> false);
	}

	/** Override ONLY the BE type to point at our own registered BlockEntityType. */
	@Override
	public BlockEntityType<? extends CannonMountBlockEntity> getBlockEntityType() {
		return CBCFCBlockEntities.ADVANCED_CANNON_MOUNT_BE.get();
	}

	/**
	 * Empty-hand right-click opens the mount's Create-style radio frequency menu
	 * {@link com.cbc.firecontrol.menu.MountFrequencyMenu} (server-side {@code openMenu}; the mount block
	 * entity is a {@link net.minecraft.world.MenuProvider}). Both hands must be empty so wrench rotation,
	 * kinetic shaft attachment and block placement (items in hand) fall through to
	 * {@code super.useWithoutItem} unchanged. The menu lets the player assign the 5 channel frequencies
	 * (Create redstone-link Couple) that drive Yaw+/Yaw-/Pitch+/Pitch-/Fire wirelessly.
	 */
	@Override
	public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty()) {
			if (!level.isClientSide
					&& level.getBlockEntity(pos) instanceof com.cbc.firecontrol.block.entity.AdvancedCannonMountBlockEntity mount
					&& player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
				serverPlayer.openMenu(mount, buf -> buf.writeBlockPos(pos));
			}
			return InteractionResult.sidedSuccess(level.isClientSide);
		}
		return super.useWithoutItem(state, level, pos, player, hit);
	}

	/**
	 * Right-clicking the mount while holding a {@link CalculatorLinkItem} links / unlinks this mount to the
	 * held item (server-side {@link CalculatorLinkItem#applyLink}; sneak-right-click removes this mount).
	 * Every other held item (wrench, shaft, block, ...) falls through to CBC's default handling unchanged.
	 */
	@Override
	public ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
			Player player, InteractionHand hand, BlockHitResult hitResult) {
		if (stack.getItem() instanceof CalculatorLinkItem) {
			if (!level.isClientSide)
				CalculatorLinkItem.applyLink(stack, level, pos, player);
			return ItemInteractionResult.sidedSuccess(level.isClientSide);
		}
		return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
	}

    /**
     * Kinetic / stress input is restricted to the mount's BASE face only.
     * Upright (VERTICAL_DIRECTION=DOWN): the DOWN (bottom) face is the sole power/stress
     * input; inverted (UP): the UP face. CBC's two horizontal pitch-axis side faces are no
     * longer accepted, so a shaft / Rotation Speed Controller cannot be attached to the
     * mount's sides - the mount draws its kinetic stress requirement purely from below.
     */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(BlockStateProperties.VERTICAL_DIRECTION);
    }


	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return slabShape(state);
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return slabShape(state);
	}

	private static VoxelShape slabShape(BlockState state) {
		return state.getValue(BlockStateProperties.VERTICAL_DIRECTION) == Direction.DOWN
				? SHAPE_LOWER_HALF
				: SHAPE_UPPER_HALF;
	}
}

