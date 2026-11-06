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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.CannonContraptionProviderBlock;

/**
 * Fire Control Mount BlockEntity - half-slab mount variant.
 *
 * Inherits almost everything from CBC's CannonMountBlockEntity:
 *   - Kinetic pitch/yaw interfaces (pitchInterface, yawInterface)
 *   - applyRotation() logic that syncs cannonYaw/cannonPitch -> mountedContraption
 *   - disassemble(), calculateCannonStressApplied()
 *   - Goggle tooltip via {@link #addToGoggleTooltip} (inherited, calls ExtendsCannonMount.addCannonInfoToTooltip)
 *   - Assembly exception display via getLastAssemblyException() (inherited)
 *   - Item handler / capabilities for the mounted contraption (inherited)
 *
 * Overridden for half-slab layout differences:
 *   - assemble(): assemblyPos = 1 grid in cannon direction (was 2 in CBC).
 *   - resetContraptionToOffset(): CBC's body hardcodes relative(vertical, -2); ours uses -1 so the
 *     mounted cannon entity ends at the correct world position matching our new assemblyPos.
 *   - createRenderBoundingBox(): CBC expanded +/-2 blocks (sized for CBC's ROTATING_MOUNT disc at
 *     mount-pos +/-1 plus carriage axle at mount-pos +/-2). Ours expands +/-1 (only interface shaft
 *     visuals at mount cell plus mount model's guard structure reaching mount cell top boundary;
 *     assembled cannon entity has its own AABB covering the whole cannon structure).
 *
 * Overridden for CBC-vs-our-block identity handling:
 *   - Constructor: initializes cannonYaw for our block (CBC only does so for CBCBlocks.CANNON_MOUNT)
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
	private static final Field PREV_YAW_FIELD;
	private static final Field PREV_PITCH_FIELD;
	static {
		try {
			RUNNING_FIELD = CannonMountBlockEntity.class.getDeclaredField("running");
			RUNNING_FIELD.setAccessible(true);
			PREV_YAW_FIELD = CannonMountBlockEntity.class.getDeclaredField("prevYaw");
			PREV_YAW_FIELD.setAccessible(true);
			PREV_PITCH_FIELD = CannonMountBlockEntity.class.getDeclaredField("prevPitch");
			PREV_PITCH_FIELD.setAccessible(true);
		} catch (NoSuchFieldException e) {
			throw new IllegalStateException(
					"CBC field layout changed: 'running'/'prevYaw'/'prevPitch' missing on CannonMountBlockEntity; "
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

	private static void setPrevYaw(CannonMountBlockEntity target, float value) {
		try {
			PREV_YAW_FIELD.setFloat(target, value);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(
					"Could not write CBC CannonMountBlockEntity.prevYaw (CBC version mismatch?).", e);
		}
	}

	private static void setPrevPitch(CannonMountBlockEntity target, float value) {
		try {
			PREV_PITCH_FIELD.setFloat(target, value);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(
					"Could not write CBC CannonMountBlockEntity.prevPitch (CBC version mismatch?).", e);
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
		// Half-slab layout: assemblyPos = 1 grid in cannon direction (was 2 in CBC's full-block mount).
		//   - vertical=DOWN (upright mount, cannon above): assemblyPos = mount.above(1) = mountPos+1 (adjacent cell)
		//   - vertical=UP   (inverted mount, cannon below): assemblyPos = mount.below(1) = mountPos-1 (adjacent cell)
		BlockPos assemblyPos = this.worldPosition.relative(vertical, -1);
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

	/**
	 * Override CBC's protected resetContraptionToOffset() which hardcodes relative(vertical, -2)
	 * when positioning the mounted cannon contraption entity. Our half-slab layout moved
	 * assemblyPos to relative(vertical, -1); entity position MUST match assemblyPos formula, otherwise
	 * the assembled cannon ends up one block away from where the assembly scan actually placed it.
	 */
	@Override
	protected void resetContraptionToOffset() {
		if (this.mountedContraption == null) return;
		this.cannonPitch = 0;
		this.cannonYaw = this.getContraptionDirection().toYRot();
		// prevYaw/prevPitch are private in CBC; write them via reflection helpers above.
		// They back the client-side lerp interpolation used by getPitchOffset/getYawOffset each frame;
		// setting both to the freshly-reset cannonYaw/cannonPitch prevents a first-frame visual snap
		// from a stale prior value.
		setPrevPitch(this, this.cannonPitch);
		setPrevYaw(this, this.cannonYaw);

		this.mountedContraption.pitch = this.cannonPitch;
		this.mountedContraption.yaw = this.cannonYaw;
		this.mountedContraption.prevPitch = this.mountedContraption.pitch;
		this.mountedContraption.prevYaw = this.mountedContraption.yaw;

		this.mountedContraption.setXRot(this.cannonPitch);
		this.mountedContraption.setYRot(this.cannonYaw);
		this.mountedContraption.xRotO = this.mountedContraption.getXRot();
		this.mountedContraption.yRotO = this.mountedContraption.getYRot();

		Direction vertical = this.getBlockState().getValue(BlockStateProperties.VERTICAL_DIRECTION);
		// Formula MUST match assemble()'s assemblyPos line (both use relative(vertical, -1)).
		Vec3 vec = Vec3.atBottomCenterOf((this.worldPosition.relative(vertical, -1)));
		this.mountedContraption.setPos(vec);
	}

	@Override
	public AABB createRenderBoundingBox() {
		// Half-slab mount:
		//   - plinth + cap model geometry lives inside AABB(pos) (plinth 0..8 model units, cap 9..11,
		//     guards up to 16 = mount cell top boundary; all within AABB(getBlockPos()) 0..1 range).
		//   - Interface shaft visuals (SHAFT, SHAFT_HALF) render at mount cell center inside AABB(pos).
		//   - Expand +/-1 in cannon direction for margin (mount cell top boundary at y=1.0 world;
		//     +1 covers the adjacent cannon-assembly cell in case mount-side visuals bleed a few units
		//     past the boundary when the mount's SHAFT partial pivots slightly during wrench rotation).
		// The assembled mounted cannon entity has its OWN AABB covering the entire cannon structure,
		// so mount BE does not need to extend into the cannon cell interior.
		boolean upsideDown = this.getBlockState().getValue(BlockStateProperties.VERTICAL_DIRECTION) == Direction.UP;
		return new AABB(this.getBlockPos()).expandTowards(0, upsideDown ? -1 : 1, 0);
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


