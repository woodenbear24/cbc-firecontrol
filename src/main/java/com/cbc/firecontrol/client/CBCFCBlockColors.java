package com.cbc.firecontrol.client;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.block.ArmoredSteelBlock;
import com.cbc.firecontrol.init.CBCFCBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/**
 * The faint colour mask for dyed armored steel.
 *
 * <p>Registered as a NeoForge block color on the mod bus. {@code DyeColor#getTextureDiffuseColor()} is the same
 * value vanilla's wool and carpet tinting uses; it is mixed toward white by {@link #MASK_STRENGTH} so the dye
 * reads as a light wash over the German grey body rather than a solid colour. {@link DyeColor#WHITE} is the
 * un-dyed state and returns a plain white tint (no change).
 *
 * <p>Tint is multiplicative, so it lands on every pixel of the tile equally: the authored base texture keeps dark
 * structural lines and a light body precisely so that dyeing colours the body while rivets and seams stay legible.
 */
@EventBusSubscriber(modid = CBCFireControl.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class CBCFCBlockColors {

	/**
	 * How far the dye colour is pushed toward full saturation as a block tint. 0.4 makes the dye clearly visible
	 * (user-chosen) while leaving the German grey body and its dark seams dominant.
	 */
	private static final float MASK_STRENGTH = 0.4F;

	@SubscribeEvent
	public static void onRegisterBlockColors(RegisterColorHandlersEvent.Block event) {
		event.register(CBCFCBlockColors::armoredSteelTint, CBCFCBlocks.ARMORED_STEEL_BLOCK.get());
	}

	/** The multiplicative RGB tint applied to armored steel, from the dye stored in its block state. */
	public static int armoredSteelTint(BlockState state, BlockAndTintGetter level, BlockPos pos, int tintIndex) {
		DyeColor dye = state.getValue(ArmoredSteelBlock.COLOR);
		if (dye == DyeColor.WHITE)
			return 0xFFFFFF;
		int rgb = dye.getTextureDiffuseColor();
		int r = blendChannel((rgb >> 16) & 0xFF);
		int g = blendChannel((rgb >> 8) & 0xFF);
		int b = blendChannel(rgb & 0xFF);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/** Pull one 0-255 dye channel toward white by {@link #MASK_STRENGTH} (0 = stay white, 1 = full dye colour). */
	private static int blendChannel(int dyeChannel) {
		return Math.round(0xFF + (dyeChannel - 0xFF) * MASK_STRENGTH);
	}
}
