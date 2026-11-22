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
 * The colour of armored steel: block tint and inventory tint.
 *
 * <p>Tint is only ever requested for quads that declare one. {@code ModelBlockRenderer} and {@code ItemRenderer}
 * both gate on {@code BakedQuad#isTinted()}, and vanilla's {@code block/cube_all} faces carry no tint index, so the
 * model here is authored as its own six-face cube with {@code "tintindex": 0} on every face
 * ({@code assets/cbc_firecontrol/models/block/armored_steel_block.json}) - that is what makes dyeing work at all.
 * Create's {@code CTModel} clones the quad and rewrites only its UVs, so connected faces keep the tint index too.
 *
 * <p>Multiplicative tint cannot brighten a pixel, so the dye is applied at full strength -
 * {@code DyeColor#getTextureDiffuseColor()}, the same value vanilla wool and carpet use - and the structural
 * contrast comes from the texture, whose body is authored bright ({@code #D6D8DB}) with a dark frame line
 * ({@code #9C9EA1}) and corner ({@code #8A8C8F}). Blending the dye toward white instead, as an earlier version did,
 * turned red into mud.
 *
 * <p>{@link DyeColor#WHITE} is the un-dyed state and cannot be a no-op now that the base is bright: it asks for
 * {@link #UNDYED_TINT}, a slightly cool grey multiplier that puts the plate back at the German grey CBC's steel
 * block uses (mean luminance ~132) while keeping the frame legible. The same multiplier tints the block item,
 * whose stack has no block state to read.
 */
@EventBusSubscriber(modid = CBCFireControl.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class CBCFCBlockColors {

	/**
	 * Un-dyed armored steel: the bright authored plate multiplied down to German grey. Hand-picked so that the
	 * dominant body pixel {@code #D6D8DB} lands back on {@code #888A8D} and the frame pixel {@code #8A8C8F} lands
	 * on {@code #58595B} - within two units of the approved mid-grey texture's {@code #888A8D} / {@code #5A5C5F}.
	 */
	private static final int UNDYED_TINT = 0xFFA2A3A4;

	@SubscribeEvent
	public static void onRegisterBlockColors(RegisterColorHandlersEvent.Block event) {
		event.register(CBCFCBlockColors::armoredSteelTint, CBCFCBlocks.ARMORED_STEEL_BLOCK.get());
	}

	/**
	 * The block item shares the block's model, and its quads are tinted, so the icon would otherwise show the
	 * un-multiplied bright plate. {@code ItemRenderer#renderQuadList} only consults item colours when the stack is
	 * non-empty, which is always true for a rendered icon.
	 */
	@SubscribeEvent
	public static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
		event.register((stack, tintIndex) -> UNDYED_TINT, CBCFCBlocks.ARMORED_STEEL_BLOCK.get().asItem());
	}

	/** The multiplicative RGB tint applied to armored steel, from the dye stored in its block state. */
	public static int armoredSteelTint(BlockState state, BlockAndTintGetter level, BlockPos pos, int tintIndex) {
		DyeColor dye = state.getValue(ArmoredSteelBlock.COLOR);
		return dye == DyeColor.WHITE ? UNDYED_TINT : 0xFF000000 | dye.getTextureDiffuseColor();
	}
}
