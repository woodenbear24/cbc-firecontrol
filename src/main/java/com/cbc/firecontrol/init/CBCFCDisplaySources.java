package com.cbc.firecontrol.init;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.display.AdvancedCannonMountDisplaySource;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.tterrag.registrate.util.entry.RegistryEntry;

/**
 * Registers our own Create DisplaySource, mirroring how CBC registers
 * {@code CBCDisplaySources.CANNON_MOUNT} (via {@code REGISTRATE.displaySource(name, supplier).register()}).
 *
 * Attached to the Advanced Cannon Mount by a {@code .transform(displaySource(ADVANCED_CANNON_MOUNT))} in
 * {@link CBCFCBlocks}, so a Create DisplayLink can bind to the mount and read:
 * position / barrel pointing (yaw+pitch) / propulsion (propellant charge power) / loaded shell type.
 */
public class CBCFCDisplaySources {

	public static final RegistryEntry<DisplaySource, AdvancedCannonMountDisplaySource> ADVANCED_CANNON_MOUNT =
			CBCFireControl.REGISTRATE
					.displaySource("advanced_cannon_mount", AdvancedCannonMountDisplaySource::new)
					.register();

	/** Empty no-op referenced from CBCFireControl's constructor to force class-init. */
	public static void init() { }
}
