package com.cbc.firecontrol.block.entity;

import com.cbc.firecontrol.init.CBCFCBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

/**
 * Fire Control Mount BlockEntity - a copy of CBC's CannonMountBlockEntity.
 *
 * By extending CannonMountBlockEntity we inherit all of CBC's:
 *   - Cannon assembly / disassembly logic (assemble, disassemble, markForReassembly)
 *   - Firing / launching logic (launch, onRedstoneUpdate with firePower handling)
 *   - Kinetic interfaces (pitchInterface / yawInterface virtual BlockEntities)
 *   - Yaw / Pitch tracking and rotation (applyRotation, cannonYaw, cannonPitch)
 *   - Contraption mounting (mountedContraption: PitchOrientedContraptionEntity)
 *   - Goggle tooltip display (addToGoggleTooltip)
 *   - Item handler capability for mounted contraption inventory
 *   - Stress calculation (calculateCannonStressApplied)
 *
 * We override ONLY the constructor to also initialize cannonYaw for OUR block
 * (CBC's parent constructor only initializes yaw for CBCBlocks.CANNON_MOUNT states).
 */
public class FireControlMountBlockEntity extends CannonMountBlockEntity {

	public FireControlMountBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		// CBC's constructor only handles CBCBlocks.CANNON_MOUNT.has(state).
		// For our own block we must initialize the yaw ourselves.
		if (state.is(CBCFCBlocks.FIRE_CONTROL_MOUNT.get())) {
			this.cannonYaw = state.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
		}
	}
}

