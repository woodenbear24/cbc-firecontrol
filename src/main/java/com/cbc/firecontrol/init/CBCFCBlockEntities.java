package com.cbc.firecontrol.init;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.block.entity.FireControlMountBlockEntity;
import com.cbc.firecontrol.client.FireControlMountBERenderer;
import com.cbc.firecontrol.client.FireControlMountVisual;
import com.tterrag.registrate.util.entry.BlockEntityEntry;

/**
 * BlockEntity registration via CreateRegistrate, using OUR OWN Flywheel Visual and
 * BEGSR Renderer (not CBC's originals).
 *
 * Reason: half-slab mount layout changed assembly pos from mount pos ±2 (CBC's original) to
 * mount pos ±1 (adjacent). CBC's CannonMountVisual creates Flywheel instances at positions
 * designed for the ±2 layout:
 *   - ROTATING_MOUNT disc instance at mount pos ±1 (bridge slot between mount and cannon breech)
 *   - CANNON_CARRIAGE_AXLE instance at mount pos ±2 (assembly pos area)
 * Under ±1 assembly those positions collide with the assembled cannon breech cell, so we suppress
 * them entirely. Because CBC's visual class creates instances directly in its constructor (not via
 * overridable factory hooks), the only clean way to omit them is to write our own visualizer that
 * only builds the pitchShaft and yawShaft instances.
 *
 * Similarly CBC's CannonMountBlockEntityRenderer fallback hardcodes a carriage-axle visual at
 * translate(0, ±2, 0) inside renderSafe(); that too would appear past the cannon in empty air for
 * our layout. Ours (FireControlMountBERenderer) drops it (rarely invoked in practice — Flywheel
 * is Create's hard dependency, so BERenderer path is skipped when Flywheel is available).
 *
 * CreateRegistrate auto-wires visuals/renderers through Create's CreateRegistrateRegistrationCallback;
 * no manual EntityRenderersEvent or RegisterVisualsEvent handler is needed.
 */
public class CBCFCBlockEntities {

	public static final BlockEntityEntry<FireControlMountBlockEntity> FIRE_CONTROL_MOUNT_BE =
			CBCFireControl.REGISTRATE
					.blockEntity("fire_control_mount", FireControlMountBlockEntity::new)
					.visual(() -> FireControlMountVisual::new)
					.renderer(() -> FireControlMountBERenderer::new)
					.validBlock(CBCFCBlocks.FIRE_CONTROL_MOUNT)
					.register();

	/** Empty no-op referenced from CBCFireControl's constructor to force class-init. */
	public static void init() { }
}


