package com.cbc.firecontrol.client;

import org.jetbrains.annotations.Nullable;

import com.cbc.firecontrol.block.ArmoredSteelBlock;
import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import com.simibubi.create.foundation.block.connected.ConnectedTextureBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The connection rule for armored steel: connect with the same block only, and only when the dye matches.
 *
 * <p>Extending {@code ConnectedTextureBehaviour.Base} keeps Create's own default test
 * ({@code !isBeingBlocked(...) && state.getBlock() == other.getBlock()}) - which is exactly "connect with my own
 * kind", with nothing to do with {@code CreateClient.CASING_CONNECTIVITY}. {@code EncasedCTBehaviour} is the one
 * that consults that registry and groups blocks by their shared casing sprite; using it (or registering through
 * {@code CasingConnectivity#makeCasing}) would connect armored steel to {@code create:andesite_casing}, which is
 * not wanted here.
 *
 * <p>The extra same-dye condition keeps a colour boundary visible: two differently dyed neighbours would
 * otherwise paint the same seam-less cell pair and read as one continuous panel in two colours. Drop the colour
 * comparison to get cross-colour seamless connections instead.
 */
public class ArmoredSteelCTBehaviour extends ConnectedTextureBehaviour.Base {

	private final CTSpriteShiftEntry shift;

	public ArmoredSteelCTBehaviour(CTSpriteShiftEntry shift) {
		this.shift = shift;
	}

	@Override
	@Nullable
	public CTSpriteShiftEntry getShift(BlockState state, Direction direction, @Nullable TextureAtlasSprite sprite) {
		// One tile for every face (cube_all), so every quad whose sprite is the base tile shifts into the sheet.
		return shift;
	}

	@Override
	public boolean connectsTo(BlockState state, BlockState other, BlockAndTintGetter reader, BlockPos pos,
			BlockPos otherPos, Direction face) {
		if (!super.connectsTo(state, other, reader, pos, otherPos, face))
			return false;
		// super.connectsTo already guarantees `other` is this same block, so the property is guaranteed present.
		return other.getValue(ArmoredSteelBlock.COLOR) == state.getValue(ArmoredSteelBlock.COLOR);
	}
}
