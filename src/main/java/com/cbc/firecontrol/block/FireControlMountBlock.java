package com.cbc.firecontrol.block;

import com.cbc.firecontrol.init.CBCFCBlockEntities;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

/**
 * Fire Control Mount - a copy of CBC's Cannon Mount Block.
 *
 * Extending CannonMountBlock inherits from CBC (which itself extends Create's KineticBlock):
 *   - 4 BlockStates: HORIZONTAL_FACING, ASSEMBLY_POWERED, FIRE_POWERED, VERTICAL_DIRECTION
 *   - Placement orientation via BlockPlaceContext
 *   - getRotationAxis / hasShaftTowards (kinetic connections)
 *   - Wrench rotation behavior (onWrenched / getRotatedBlockState / cycle VERTICAL_DIRECTION)
 *   - neighborChanged + tick for redstone-driven assembly/fire signals
 *   - KineticBlock super-behavior (Create integration)
 *   - Kinetic block state preservation on replace (onPlace)
 *   - updateIndirectNeighbourShapes speed updates
 *
 * We only override getBlockEntityType() to point at OUR registered BE type;
 * getBlockEntityClass() is inherited unchanged (returns CBC's parent class,
 * which is assignable from our BE subclass → runtime validation passes).
 */
public class FireControlMountBlock extends CannonMountBlock {

	public FireControlMountBlock(Properties properties) {
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
		return CBCFCBlockEntities.FIRE_CONTROL_MOUNT_BE.get();
	}
}

