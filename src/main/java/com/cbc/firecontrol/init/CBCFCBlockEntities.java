package com.cbc.firecontrol.init;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.block.entity.FireControlMountBlockEntity;
import com.tterrag.registrate.util.entry.BlockEntityEntry;

import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntityRenderer;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountVisual;

/**
 * BlockEntity registration via CreateRegistrate, with CBC's Flywheel Visual and
 * BEGSR Renderer attached to our own BE type.
 *
 * Reusing CBC's {@link CannonMountVisual} makes our mount render identically to
 * CBC's native Cannon Mount — including the rotating mount disk (ROTATING_MOUNT)
 * on top and the carriage axle (CANNON_CARRIAGE_AXLE) which DeferredRegister +
 * bare BEGSR could NOT render (BEGSR path is skipped when Flywheel is available).
 *
 * CannonMountVisual's constructor takes {@code CannonMountBlockEntity}; our subclass
 * {@link FireControlMountBlockEntity} IS-A CannonMountBlockEntity, so Flywheel's
 * {@code new CannonMountVisual(ctx, ourBE, partialTick)} call succeeds via subtyping.
 *
 * Because CreateRegistrate auto-wires visuals/renderers through Create's
 * {@code CreateRegistrateRegistrationCallback}, no {@code EntityRenderersEvent} or
 * {@code RegisterVisualsEvent} handler is required here.
 */
public class CBCFCBlockEntities {

	public static final BlockEntityEntry<FireControlMountBlockEntity> FIRE_CONTROL_MOUNT_BE =
			CBCFireControl.REGISTRATE
					.blockEntity("fire_control_mount", FireControlMountBlockEntity::new)
					.visual(() -> CannonMountVisual::new)
					.renderer(() -> CannonMountBlockEntityRenderer::new)
					.validBlock(CBCFCBlocks.FIRE_CONTROL_MOUNT)
					.register();

	/** Empty no-op referenced from CBCFireControl's constructor to force class-init. */
	public static void init() { }
}


