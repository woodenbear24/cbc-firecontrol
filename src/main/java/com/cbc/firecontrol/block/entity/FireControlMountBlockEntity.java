package com.cbc.firecontrol.block.entity;

import java.lang.reflect.Field;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.init.CBCFCBlocks;

import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.contraptions.AssemblyException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.CannonContraptionProviderBlock;

/**
 * Fire Control Mount BlockEntity.
 *
 * Inherits almost everything from CBC's CannonMountBlockEntity:
 *   - Kinetic pitch/yaw interfaces (pitchInterface, yawInterface)
 *   - applyRotation() logic that syncs cannonYaw/cannonPitch → mountedContraption
 *   - disassemble(), resetContraptionToOffset(), calculateCannonStressApplied()
 *   - Goggle tooltip via {@link #addToGoggleTooltip} (inherited, calls ExtendsCannonMount.addCannonInfoToTooltip)
 *   - Assembly exception display via getLastAssemblyException() (inherited)
 *   - Item handler / capabilities for the mounted contraption (inherited)
 *
 * Overridden:
 *   - Constructor: initializes cannonYaw for our block (CBC only does so for CBCBlocks.CANNON_MOUNT)
 *   - assemble(): CBC's own body has "if (!CBCBlocks.CANNON_MOUNT.has(state)) return;"
 *                 which rejects our mount. We inline equivalent logic, gated on our block instead.
 *                 Uses the AT-exposed 'running' field to activate CBC's tick rotation path.
 *   - tick(): CBC's early-return "reset from state" branch only fires for CBCBlocks.CANNON_MOUNT;
 *             our mount needs the same reset so cannonYaw visually follows wrench rotations.
 *   - getTypeId(): returns a cbc_firecontrol-namespaced id so CBC code paths that key
 *                  off mount-type identity don't mistake our mount for a stock CBC mount.
 */
public class FireControlMountBlockEntity extends CannonMountBlockEntity {

	/**
	 * Reflection handle to CBC's private boolean 'running' field.
	 * ModDevGradle's compile-time AT application on third-party mod jars is unreliable,
	 * so we access via reflection instead. NeoForge 1.21 loads mods into an unnamed module,
	 * so Field.setAccessible(true) on another mod's private field works without --add-opens.
	 */
	private static final Field RUNNING_FIELD;
	static {
		try {
			RUNNING_FIELD = CannonMountBlockEntity.class.getDeclaredField("running");
			RUNNING_FIELD.setAccessible(true);
		} catch (NoSuchFieldException e) {
			throw new IllegalStateException(
					"CBC field layout changed: 'running' missing on CannonMountBlockEntity; "
							+ "CBC Fire Control needs a matching update.", e);
		}
	}

	private static void setRunning(CannonMountBlockEntity target, boolean value) {
		try {
			RUNNING_FIELD.setBoolean(target, value);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(
					"Could not write CBC CannonMountBlockEntity.running (CBC version mismatch?).", e);
		}
	}

	public FireControlMountBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		if (CBCFCBlocks.FIRE_CONTROL_MOUNT.has(state)) {
			this.cannonYaw = state.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
		}
	}

	@Override
	protected void assemble() throws AssemblyException {
		// CBC's own assemble() is gated on CBCBlocks.CANNON_MOUNT.has(state); ours is gated on our block.
		if (!CBCFCBlocks.FIRE_CONTROL_MOUNT.has(this.getBlockState())) return;

		Direction vertical = this.getBlockState().getValue(BlockStateProperties.VERTICAL_DIRECTION);
		BlockPos assemblyPos = this.worldPosition.relative(vertical, -2);
		if (this.getLevel().isOutsideBuildHeight(assemblyPos)) {
			throw cannonBlockOutsideOfWorld(assemblyPos);
		}

		// Inline CBC's private helper getContraption(assemblyPos):
		BlockState assemblyState = this.getLevel().getBlockState(assemblyPos);
		if (!(assemblyState.getBlock() instanceof CannonContraptionProviderBlock providerBlock)) return;
		AbstractMountedCannonContraption mountedCannon = providerBlock.getCannonContraption();
		if (mountedCannon == null) return;
		if (!mountedCannon.assemble(this.getLevel(), assemblyPos)) return;

		Direction facing = this.getBlockState().getValue(CannonMountBlock.HORIZONTAL_FACING);
		Direction facing1 = mountedCannon.initialOrientation();
		if (facing.getAxis() != facing1.getAxis() && facing1.getAxis().isHorizontal()) return;

		// Activates CBC's tick rotation branch. Written via reflection (see RUNNING_FIELD).
		setRunning(this, true);

		mountedCannon.removeBlocksFromWorld(this.getLevel(), BlockPos.ZERO);
		PitchOrientedContraptionEntity contraptionEntity = PitchOrientedContraptionEntity.create(
				this.getLevel(), mountedCannon, facing1, this);
		this.mountedContraption = contraptionEntity;
		this.resetContraptionToOffset();
		this.getLevel().addFreshEntity(contraptionEntity);

		this.sendData();
		AllSoundEvents.CONTRAPTION_ASSEMBLE.playOnServer(this.getLevel(), this.worldPosition);
	}

	@Override
	public void tick() {
		// CBC's inherited tick() does the "reset from state" dance ONLY for CBCBlocks.CANNON_MOUNT.
		// Our mount block bypasses that reset, leaving cannonYaw stale after wrench rotations.
		// Do the equivalent reset ourselves for our block, then hand off to CBC's tick() which will
		// (correctly) not touch it again and will proceed into the rotation-update block once
		// running=true is set by a successful assemble.
		BlockState state = this.getBlockState();
		if (!this.isRunning() && CBCFCBlocks.FIRE_CONTROL_MOUNT.has(state)) {
			this.cannonYaw = state.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
			this.cannonPitch = 0;
		}
		super.tick();
	}

	@Override
	public ResourceLocation getTypeId() {
		return ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "fire_control_mount");
	}
}


