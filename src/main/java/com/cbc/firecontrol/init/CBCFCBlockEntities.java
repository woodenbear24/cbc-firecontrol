package com.cbc.firecontrol.init;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.block.entity.AdvancedCannonMountBlockEntity;
import com.cbc.firecontrol.block.entity.TrajectoryCalculatorBlockEntity;
import com.cbc.firecontrol.client.AdvancedCannonMountBERenderer;
import com.cbc.firecontrol.client.AdvancedCannonMountVisual;
import com.cbc.firecontrol.client.TrajectoryCalculatorBERenderer;
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
 * our layout. Ours (AdvancedCannonMountBERenderer) drops it (rarely invoked in practice — Flywheel
 * is Create's hard dependency, so BERenderer path is skipped when Flywheel is available).
 *
 * CreateRegistrate auto-wires visuals/renderers through Create's CreateRegistrateRegistrationCallback;
 * no manual EntityRenderersEvent or RegisterVisualsEvent handler is needed.
 */
public class CBCFCBlockEntities {

	public static final BlockEntityEntry<AdvancedCannonMountBlockEntity> ADVANCED_CANNON_MOUNT_BE =
			CBCFireControl.REGISTRATE
					.blockEntity("advanced_cannon_mount", AdvancedCannonMountBlockEntity::new)
					.visual(() -> AdvancedCannonMountVisual::new)
					.renderer(() -> AdvancedCannonMountBERenderer::new)
					.validBlock(CBCFCBlocks.ADVANCED_CANNON_MOUNT)
					.register();

	/**
	 * Trajectory Calculator BE: 5 calculator_link slots + the server-computed firing solutions. No Flywheel
	 * visual is attached (the console body is a static model wired through blockstates), but a BE renderer
	 * is: it draws one link-card model into the rack for every slot that holds a card, so the console shows
	 * physically what the GUI slot column holds. Everything else it reports is GUI text, not geometry.
	 */
	public static final BlockEntityEntry<TrajectoryCalculatorBlockEntity> TRAJECTORY_CALCULATOR_BE =
			CBCFireControl.REGISTRATE
					.blockEntity("trajectory_calculator", TrajectoryCalculatorBlockEntity::new)
					.renderer(() -> TrajectoryCalculatorBERenderer::new)
					.validBlock(CBCFCBlocks.TRAJECTORY_CALCULATOR)
					.register();

	/** Empty no-op referenced from CBCFireControl's constructor to force class-init. */
	public static void init() { }
}


