package com.cbc.firecontrol.client;

import com.cbc.firecontrol.CBCFireControl;
import com.simibubi.create.foundation.block.connected.AllCTTypes;
import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import com.simibubi.create.foundation.block.connected.CTSpriteShifter;

import net.minecraft.resources.ResourceLocation;

/**
 * The connected-texture sprite pair for armored steel.
 *
 * <p>{@link CTSpriteShifter#getCT} builds Create's sprite shift and caches it by
 * {@code blockTexture -> connectedTexture + typeId}. The two sprites are the authored base tile and the
 * connection sheet: {@link AllCTTypes#OMNIDIRECTIONAL} has {@code getSheetSize() == 8}, so the sheet is 8x8 = 64
 * cells of the same tile size (128x128 pixels for 16-pixel tiles), which is the same type Create's casings use -
 * that is why armored steel gets the identical seam behaviour (straight runs, corners, and inner corners) without
 * using any casing behaviour.
 *
 * <p>{@code getCT} resolves its sprites lazily from the block atlas, so the sheet must be stitched into that
 * atlas even though no model references it: see {@code assets/minecraft/atlases/blocks.json}.
 */
public class CBCFCSpriteShifts {

	/** The tile {@code models/block/armored_steel_block.json} paints with; {@code CTModel} shifts only quads whose sprite is this one. */
	public static final ResourceLocation ARMORED_STEEL_TEXTURE =
			ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "block/armored_steel_block");

	/** The 8x8 connection sheet, same tile size as the base, one cell per adjacency combination. */
	public static final ResourceLocation ARMORED_STEEL_CONNECTED_TEXTURE =
			ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "block/armored_steel_block_connected");

	public static final CTSpriteShiftEntry ARMORED_STEEL =
			CTSpriteShifter.getCT(AllCTTypes.OMNIDIRECTIONAL, ARMORED_STEEL_TEXTURE, ARMORED_STEEL_CONNECTED_TEXTURE);

	private CBCFCSpriteShifts() {
	}
}
