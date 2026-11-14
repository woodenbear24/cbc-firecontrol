package com.cbc.firecontrol.mixin.compat.sable;

import java.util.List;

import com.cbc.firecontrol.init.CBCFCBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import rbasamoyai.createbigcannons.compat.sable.CBCSableConnectivityUtils;

/**
 * Extends CBC's Sable connectivity rule to the Advanced Cannon Mount.
 *
 * <p>CBC's own {@code SubLevelHeatMapManagerMixin} / {@code SableCommonEventsMixin} ask
 * {@link CBCSableConnectivityUtils} whether a cell is the air gap between the two halves of a cannon mount, so
 * that Sable's heat map treats the gap as solid structure instead of a hole in the ship. The check is written
 * against {@code CBCBlocks.CANNON_MOUNT} by identity, so our mount - a subclass of the same block with the same
 * {@code VERTICAL_DIRECTION} semantics - would still read as a hole, and a ship carrying only advanced mounts
 * would take sub-level damage through the very cells its guns stand on.
 *
 * <p>Adding our block to that verdict is therefore the whole job: CBC's Sable handling (heat map steps, block
 * change propagation) then covers our mount exactly the way it covers its own, with no duplicated Sable code on
 * our side. Both injections run on the return value, so CBC's answer stands whenever it is already yes.
 */
@Mixin(CBCSableConnectivityUtils.class)
public class CBCSableConnectivityMixin {

	/**
	 * Both CBC targets are {@code static}, so the handlers must be static too: mixin rejects a non-static
	 * callback against a static target outright (it is a sanity-check failure, not a missed injector, so
	 * {@code defaultRequire} cannot soften it - it fails the whole transform of the target class).
	 */
	@Inject(method = "isCannonMountAirGap(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Level;)Z",
			at = @At("RETURN"), cancellable = true)
	private static void cbcfc$advancedMountCountsAsAGap(BlockPos pos, Level level,
			CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && cbcfc$isAdvancedMountAirGap(pos, level))
			cir.setReturnValue(true);
	}

	@Inject(method = "getCannonMountGaps(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Level;"
			+ "Lnet/minecraft/core/Direction;)Ljava/util/List;",
			at = @At("RETURN"))
	private static void cbcfc$addAdvancedMountGaps(BlockPos pos, Level level, Direction facing,
			CallbackInfoReturnable<List<BlockPos>> cir) {
		List<BlockPos> gaps = cir.getReturnValue();
		if (gaps == null)
			return;
		boolean vertical = facing.getAxis().isVertical();
		Direction.Axis axis = facing.getAxis();
		cbcfc$addGapOnAxis(gaps, pos, level, vertical, axis, 2, Direction.DOWN);
		cbcfc$addGapOnAxis(gaps, pos, level, vertical, axis, -2, Direction.UP);
	}

	/**
	 * The cell between {@code pos} and the mount two blocks off it, when that mount is upright the way CBC
	 * requires (its base plate faces the cannon) and its bore axis matches the direction being checked. Mirrors
	 * {@code CBCSableConnectivityUtils.getCannonMountGaps}, which uses {@code below(2)} / {@code above(2)}.
	 */
	private static void cbcfc$addGapOnAxis(List<BlockPos> gaps, BlockPos pos, Level level, boolean vertical,
			Direction.Axis axis, int offset, Direction requiredVertical) {
		BlockState state = level.getBlockState(pos.offset(0, offset, 0));
		if (!cbcfc$isAdvancedMount(state))
			return;
		if (state.getValue(BlockStateProperties.VERTICAL_DIRECTION) != requiredVertical)
			return;
		if (!vertical && state.getValue(BlockStateProperties.HORIZONTAL_FACING).getAxis() != axis)
			return;
		gaps.add(pos.offset(0, offset / 2, 0));
	}

	private static boolean cbcfc$isAdvancedMountAirGap(BlockPos pos, Level level) {
		BlockState below = level.getBlockState(pos.below());
		if (cbcfc$isAdvancedMount(below) && below.getValue(BlockStateProperties.VERTICAL_DIRECTION) == Direction.DOWN)
			return true;
		BlockState above = level.getBlockState(pos.above());
		return cbcfc$isAdvancedMount(above) && above.getValue(BlockStateProperties.VERTICAL_DIRECTION) == Direction.UP;
	}

	private static boolean cbcfc$isAdvancedMount(BlockState state) {
		return state.is(CBCFCBlocks.ADVANCED_CANNON_MOUNT.get());
	}
}
