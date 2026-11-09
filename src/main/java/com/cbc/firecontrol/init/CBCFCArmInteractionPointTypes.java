package com.cbc.firecontrol.init;

import javax.annotation.Nullable;

import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import com.cbc.firecontrol.CBCFireControl;

import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.breeches.quickfiring_breech.CannonMountPoint;

/**
 * Registers a Create Mechanical-Arm interaction point for OUR radio mount so it behaves exactly
 * like a CBC Cannon Mount (CBCArmInteractionPointTypes.CannonMountType). CBC's own type only
 * matches CBCBlocks.CANNON_MOUNT / FIXED_CANNON_MOUNT / CANNON_MOUNT_EXTENSION / YAW_CONTROLLER,
 * so our custom cbc_firecontrol:fire_control_mount block was NOT bindable to a Mechanical Arm.
 *
 * We reuse CBC's public CannonMountPoint (DepositOnlyArmInteractionPoint): its insert() finds the
 * assembled cannon via ExtendsCannonMount.getCannonMount() -> mount.getContraption() and dispatches
 * by contraption type (MountedBigCannonContraption -> loadShell/loadCartridge through
 * BigCannonBehavior.tryLoadingBlock; MountedAutocannonContraption -> autocannon magazine). It works
 * for BOTH big-cannon (quickfiring breech) and autocannon because our BE extends CannonMountBlockEntity
 * (hence instanceof ExtendsCannonMount).
 */
public final class CBCFCArmInteractionPointTypes {

	private CBCFCArmInteractionPointTypes() {}

	private static boolean registered = false;

	public static class FireControlMountArmType extends ArmInteractionPointType {
		@Override
		public boolean canCreatePoint(Level level, BlockPos pos, BlockState state) {
			return CBCFCBlocks.FIRE_CONTROL_MOUNT.has(state)
					&& level.getBlockEntity(pos) instanceof CannonMountBlockEntity;
		}

		@Nullable
		@Override
		public ArmInteractionPoint createPoint(Level level, BlockPos pos, BlockState state) {
			return new CannonMountPoint(this, level, pos, state);
		}
	}

	public static void register() {
		if (registered)
			return;
		registered = true;
		Registry.register(CreateBuiltInRegistries.ARM_INTERACTION_POINT_TYPE,
				ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "fire_control_mount"),
				new FireControlMountArmType());
		CBCFireControl.LOGGER.info("CBC Fire Control: registered ArmInteractionPointType 'fire_control_mount' "
				+ "(Mechanical Arm can load our radio mount; reuses CBC CannonMountPoint)");
	}

}