package com.cbc.firecontrol.init;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.block.AdvancedCannonMountBlock;
import com.cbc.firecontrol.block.ArmoredSteelBlock;
import com.cbc.firecontrol.block.TrajectoryCalculatorBlock;
import com.cbc.firecontrol.client.ArmoredSteelCTBehaviour;
import com.cbc.firecontrol.client.CBCFCSpriteShifts;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.tterrag.registrate.util.entry.BlockEntry;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;

// CBC's CannonMountDisplaySource (single yaw/pitch value) is no longer attached to our block;
// our own AdvancedCannonMountDisplaySource (see CBCFCDisplaySources) is used instead.

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

	// Mirror CBC's own CBCBlocks static init (ModGroup.setDefaultTabToNull + REGISTRATE.setCreativeTab(null)).
	// AbstractRegistrate's default creative tab is vanilla SEARCH (CreativeModeTabs.SEARCH); leaving it set makes
	// Registrate auto-accept each mod item into SEARCH during BuildCreativeModeTabContentsEvent, but vanilla's
	// SEARCH tab ALREADY lists every registered item -> "Itemstack 1 cbc_firecontrol:advanced_cannon_mount already
	// exists in the tab's list" IllegalArgumentException -> crash when the creative inventory builds its contents
	// (pressing E). Nulling the default stops Registrate's auto-add entirely, so the explicit CBCFCCreativeTabs
	// 'main' generator is the ONLY explicit adder: each item appears exactly once in the "CBC Fire Control" tab
	// (and still once in SEARCH via vanilla's own auto-listing). MUST run before the block/item builders below.
	static {
		CBCFireControl.REGISTRATE.defaultCreativeTab((ResourceKey<CreativeModeTab>) null);
		CBCFireControl.REGISTRATE.setCreativeTab(null);
	}

	public static final BlockEntry<AdvancedCannonMountBlock> ADVANCED_CANNON_MOUNT =
			CBCFireControl.REGISTRATE
					.block("advanced_cannon_mount", AdvancedCannonMountBlock::new)
					.initialProperties(() -> Blocks.IRON_BLOCK)
					.properties(p -> p.noOcclusion())
					.properties(p -> p.isRedstoneConductor((state, level, pos) -> false))
					// Same render-layer attachment CBC's cannonMount() transformer adds so
					// the base mount model bakes into both default (whatever the parent model
					// uses) and the mipped-cutout chunk layer, matching CBC's appearance.
					.addLayer(() -> RenderType::cutoutMipped)
					.lang("Advanced Cannon Mount")
					// Explicit BlockItem creation - Registrate's BlockBuilder does NOT
					// auto-create an item; CBC's own cannonMount() transformer uses the
					// same .item().build() pattern internally. The BlockItem inherits
					// the block's lang key via vanilla's BlockItem name resolution, so
					// our en_us.json entry "block.cbc_firecontrol.advanced_cannon_mount"
					// covers both block and item display names.
					.item()
					.build()
					// Attach OUR OWN DisplaySource so a Create DisplayLink can bind to this mount and read
					// the full payload: position, barrel pointing (yaw+pitch), propulsion (loaded propellant
					// charge power) and loaded shell type(s), scanned from the assembled cannon structure
					// via Contraption.getBlocks(). See CBCFCDisplaySources + AdvancedCannonMountDisplaySource.
					// (This replaces CBC's single-value CannonMountDisplaySource; it reuses CBC's public
					// mountedContraption/getContraption/getBlocks surface, so no CBC internals are copied.)
					.transform(displaySource(CBCFCDisplaySources.ADVANCED_CANNON_MOUNT))
					.register();

	public static final BlockEntry<TrajectoryCalculatorBlock> TRAJECTORY_CALCULATOR =
			CBCFireControl.REGISTRATE
					.block("trajectory_calculator", TrajectoryCalculatorBlock::new)
					.initialProperties(() -> Blocks.IRON_BLOCK)
					.properties(p -> p.noOcclusion())
					.properties(p -> p.isRedstoneConductor((state, level, pos) -> false))
					// Same render-layer attachment as the mount so the model bakes consistently;
					// the model reuses an opaque brass texture so no real transparency is required.
					.addLayer(() -> RenderType::cutoutMipped)
					.lang("Trajectory Calculator")
					// Explicit BlockItem (Registrate's BlockBuilder does not auto-create one), mirroring
					// ADVANCED_CANNON_MOUNT above. en_us/zh_cn "block.cbc_firecontrol.trajectory_calculator"
					// covers both the block and its item name.
					.item()
					.build()
					.register();

	public static final BlockEntry<ArmoredSteelBlock> ARMORED_STEEL_BLOCK =
			CBCFireControl.REGISTRATE
					.block("armored_steel_block", ArmoredSteelBlock::new)
					.initialProperties(() -> Blocks.IRON_BLOCK)
					// Same map colour CBC's steel block uses, so the un-dyed (white) state reads as German grey
					// rather than vanilla's warm metal tone.
					.properties(p -> p.mapColor(MapColor.TERRACOTTA_GRAY))
					// Plain cube_all: the connected texture is a UV shift of this one tile, so there is one model
					// for all sixteen colours and no RenderType override (the block stays fully opaque like iron).
					// Assets are hand-written under src/main/resources (same as the mount and the calculator):
					// blockstates/armored_steel_block.json lists the sixteen colour variants, each pointing at
					// models/block/armored_steel_block.json.
					.lang("Block of Armored Steel")
					// Create's connected-texture pipeline only: wraps this block's baked model in CTModel. NOT
					// BuilderTransformers.casing(...), NOT EncasedCTBehaviour, NOT CasingConnectivity#makeCasing -
					// those would connect this block with create:andesite_casing (they group by shared casing
					// sprite) and would also pull in the casing transformer's wood sound, loot table and tags.
					.onRegister(CreateRegistrate.connectedTextures(
							() -> new ArmoredSteelCTBehaviour(CBCFCSpriteShifts.ARMORED_STEEL)))
					.tag(BlockTags.MINEABLE_WITH_PICKAXE, BlockTags.NEEDS_IRON_TOOL)
					.item()
					.build()
					.register();

	/** Empty no-op referenced from CBCFireControl's constructor to force class-init. */
	public static void init() { }
}


