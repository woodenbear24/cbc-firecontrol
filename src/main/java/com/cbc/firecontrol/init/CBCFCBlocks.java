package com.cbc.firecontrol.init;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.block.FireControlMountBlock;
import com.tterrag.registrate.util.entry.BlockEntry;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.Blocks;

import rbasamoyai.createbigcannons.index.CBCDisplaySources;

import static com.simibubi.create.api.behaviour.display.DisplaySource.displaySource;

/**
 * Block + auto-BlockItem registration via CreateRegistrate.
 *
 * Uses the same style CBC uses for its own CannonMount: `.initialProperties(() -> <Block>)`
 * to copy base Properties from a vanilla Block, then chain `.properties(...)` calls to adjust.
 * (Passing a method reference like `SomeClass::methodRef` here would be ambiguous between
 * multiple `.initialProperties` overloads in Registrate, so a lambda is used instead.)
 */
public class CBCFCBlocks {

	public static final BlockEntry<FireControlMountBlock> FIRE_CONTROL_MOUNT =
			CBCFireControl.REGISTRATE
					.block("fire_control_mount", FireControlMountBlock::new)
					.initialProperties(() -> Blocks.IRON_BLOCK)
					.properties(p -> p.noOcclusion())
					.properties(p -> p.isRedstoneConductor((state, level, pos) -> false))
					// Same render-layer attachment CBC's cannonMount() transformer adds so
					// the base mount model bakes into both default (whatever the parent model
					// uses) and the mipped-cutout chunk layer, matching CBC's appearance.
					.addLayer(() -> RenderType::cutoutMipped)
					.lang("Fire Control Mount")
					// Explicit BlockItem creation - Registrate's BlockBuilder does NOT
					// auto-create an item; CBC's own cannonMount() transformer uses the
					// same .item().build() pattern internally. The BlockItem inherits
					// the block's lang key via vanilla's BlockItem name resolution, so
					// our en_us.json entry "block.cbc_firecontrol.fire_control_mount"
					// covers both block and item display names.
					.item()
					.build()
					// Attach CBC's own CannonMountDisplaySource to our mount block so that
					// Create DisplayLink/DisplayBoard reads work on our mount (yaw / pitch
					// value output). Directly reuse CBC's registered DisplaySource instance
					// instead of registering our own.
					.transform(displaySource(CBCDisplaySources.CANNON_MOUNT))
					.register();

	/** Empty no-op referenced from CBCFireControl's constructor to force class-init. */
	public static void init() { }
}


