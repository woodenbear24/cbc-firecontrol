package com.cbc.firecontrol.ballistics;

import java.util.Map;

import com.cbc.firecontrol.block.entity.AdvancedCannonMountBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;

import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.AutocannonBarrelBlock;
import rbasamoyai.createbigcannons.cannons.autocannon.AutocannonBaseBlock;
import rbasamoyai.createbigcannons.cannons.autocannon.material.AutocannonMaterial;
import rbasamoyai.createbigcannons.cannons.autocannon.material.AutocannonMaterialProperties;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBehavior;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;

/**
 * Reads the live firing parameters of an ASSEMBLED Advanced Cannon Mount, mirroring CBC 5.11.7's own firing
 * geometry so the prediction matches what that mount will actually shoot next (verified against the cached
 * CBC sources and the bytecode of Mounted{Big,Autocannon}Contraption):
 *
 *   Structure: the bore starts at {@code getStartPos()} - the cell in front of the breech, since CBC reads
 *     the breech as {@code startPos.relative(initialOrientation.getOpposite())} - and runs along
 *     {@code initialOrientation()} to the open (muzzle) end. Walked through public {@code getBlocks()}.
 *   Muzzle: CBC spawns the shot at {@code toGlobalVector(center(currentPos.relative(orientation)))} pulled
 *     back {@code 2 * dir}, i.e. the CENTER OF THE LAST ASSEMBLED CANNON CELL; reproduced with the
 *     contraption entity's public {@code toGlobalVector}.
 *   Big cannon speed: CBC calls vanilla {@code Projectile#shoot(dir, chargesUsed, spread)} on a projectile
 *     extending vanilla Projectile, so launch speed in blocks/tick IS the accumulated charge power, summed
 *     like our DisplaySource reports propulsion ({@code BigCannonBehavior#block()} ->
 *     {@code BigCannonPropellantBlock#getChargePower}).
 *   Autocannon speed: bytecode shows {@code baseSpeed}, then per loadable bore cell
 *     {@code if (barrelCount <= maxSpeedIncreases) speed += speedIncreasePerBarrel}, despawn at
 *     {@code projectileLifetime()}; barrel material from public {@code AutocannonBaseBlock#getAutocannonMaterial()}.
 *
 * Server-side only (the mounted contraption entity exists on the server). Composition over CBC public API
 * plus one inherited-field accessor on our own mount subclass - no mixin, no new reflection.
 */
public final class MountScanner {

	private static final int MAX_BORE_CELLS = 64;

	private MountScanner() {
	}

	public static MountSnapshot scan(AdvancedCannonMountBlockEntity mount) {
		BlockPos mountPos = mount.getBlockPos();
		AbstractMountedCannonContraption cannon = mount.mountedCannonOrNull();
		PitchOrientedContraptionEntity entity = mount.mountedEntityOrNull();
		if (cannon == null || entity == null)
			return MountSnapshot.unassembled(mountPos);

		Direction orientation = cannon.initialOrientation();
		Map<BlockPos, StructureBlockInfo> blocks = cannon.getBlocks();
		boolean autocannon = cannon instanceof MountedAutocannonContraption;

		BlockPos cell = cannon.getStartPos().immutable();
		BlockPos lastCell = cell;
		int barrels = 0;
		int walked = 0;
		AutocannonMaterial material = null;
		for (int i = 0; i < MAX_BORE_CELLS && blocks.containsKey(cell); i++) {
			StructureBlockInfo info = blocks.get(cell);
			if (info == null || info.state() == null)
				break;
			Block block = info.state().getBlock();
			if (block instanceof AutocannonBarrelBlock)
				barrels++;
			if (material == null && block instanceof AutocannonBaseBlock autocannonBlock)
				material = autocannonBlock.getAutocannonMaterial();
			lastCell = cell;
			walked++;
			cell = cell.relative(orientation);
		}
		if (walked == 0)
			return MountSnapshot.unassembled(mountPos);

		Vec3 muzzleRaw = entity.toGlobalVector(
				Vec3.atCenterOf(lastCell.relative(orientation).relative(orientation)), 0.0f);
		Vec3 origin = entity.toGlobalVector(Vec3.atCenterOf(BlockPos.ZERO), 0.0f);
		Vec3 direction = muzzleRaw.subtract(origin);
		if (direction.lengthSqr() < 1.0e-6)
			return MountSnapshot.unassembled(mountPos);
		direction = direction.normalize();
		Vec3 muzzle = muzzleRaw.subtract(direction.scale(2));

		double chargePower = 0.0;
		String munitionKey = "";
		for (BlockEntity raw : cannon.presentBlockEntities.values()) {
			if (!(raw instanceof IBigCannonBlockEntity cbe))
				continue;
			BigCannonBehavior behavior = cbe.cannonBehavior();
			if (behavior == null)
				continue;
			StructureBlockInfo contained = behavior.block();
			if (contained == null || contained.state() == null || contained.state().isAir())
				continue;
			Block block = contained.state().getBlock();
			if (block instanceof BigCannonPropellantBlock propellant)
				chargePower += Math.max(0.0f, propellant.getChargePower(contained));
			if (munitionKey.isEmpty() && block instanceof ProjectileBlock<?>)
				munitionKey = block.getDescriptionId();
		}

		double speed;
		int maxFlightTicks;
		double gravity;
		double drag;
		String materialKey = "";

		if (autocannon) {
			AutocannonMaterialProperties props = material == null ? null : material.properties();
			if (props == null)
				return MountSnapshot.unassembled(mountPos);
			if (material != null)
				materialKey = material.name().toString();
			// CBC adds speedIncreasePerBarrel per loadable cell while the counter is <= maxSpeedIncreases,
			// i.e. exactly min(barrels, maxSpeedIncreases) increases.
			int increases = Math.min(barrels, props.maxSpeedIncreases());
			speed = props.baseSpeed() + props.speedIncreasePerBarrel() * increases;
			maxFlightTicks = Math.max(1, props.projectileLifetime());
			gravity = TrajectoryComputer.AUTOCANNON_GRAVITY;
			drag = TrajectoryComputer.AUTOCANNON_DRAG;
		} else {
			// CBC passes the accumulated charge power straight to vanilla Projectile#shoot as launch speed.
			speed = chargePower;
			maxFlightTicks = TrajectoryComputer.MAX_PREDICT_TICKS;
			gravity = TrajectoryComputer.BIG_CANNON_GRAVITY;
			drag = TrajectoryComputer.BIG_CANNON_DRAG;
		}

		return new MountSnapshot(mountPos, true, autocannon, barrels, chargePower, speed, maxFlightTicks,
				gravity, drag, false, muzzle, direction, munitionKey, materialKey,
				mount.consoleYaw(), mount.consolePitch());
	}
}
