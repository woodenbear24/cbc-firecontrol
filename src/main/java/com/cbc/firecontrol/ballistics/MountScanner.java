package com.cbc.firecontrol.ballistics;

import java.util.ArrayList;
import java.util.List;
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
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;
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

		/*
		 * Everything below is read in BORE ORDER, from the breech side toward the muzzle, because CBC's
		 * fireShot walks the bore exactly that way and three of its rules depend on the order: an empty cell
		 * before the round costs one charge point, the first propellant decides whether the load ignites at
		 * all, and the round's completeness is judged on the blocks collected up to the muzzle. Reading the
		 * loaded blocks as an unordered set (as this method used to) could not see any of that, which is why
		 * an empty or half-loaded bore still reported a firing solution.
		 */
		double chargePower = 0.0;
		String munitionKey = "";
		List<StructureBlockInfo> roundBlocks = new ArrayList<>();
		ProjectileBlock<?> firstRoundBlock = null;
		boolean ignitionChecked = false;
		boolean ignitable = false;
		for (int i = 0; i < walked; i++) {
			BlockPos boreCell = cannon.getStartPos().relative(orientation, i);
			BlockEntity raw = cannon.presentBlockEntities.get(boreCell);
			if (!(raw instanceof IBigCannonBlockEntity cbe))
				continue;
			BigCannonBehavior behavior = cbe.cannonBehavior();
			StructureBlockInfo contained = behavior == null ? null : behavior.block();
			if (contained == null || contained.state() == null)
				continue;
			Block loaded = contained.state().getBlock();
			if (contained.state().isAir()) {
				// CBC: chargesUsed = max(chargesUsed - 1, 0) for every empty cell met before a round is collected.
				if (firstRoundBlock == null)
					chargePower = Math.max(0.0, chargePower - 1.0);
				continue;
			}
			// A shell that is also propellant (integrated-propellant rounds) contributes to both, exactly as
			// CBC adds addPropellant and then addIntegratedPropellant for the same block.
			if (loaded instanceof BigCannonPropellantBlock propellant) {
				if (!ignitionChecked) {
					ignitionChecked = true;
					ignitable = propellant.canBeIgnited(contained, orientation);
				}
				chargePower += Math.max(0.0f, propellant.getChargePower(contained));
			}
			if (loaded instanceof ProjectileBlock<?> projectile) {
				if (firstRoundBlock == null) {
					firstRoundBlock = projectile;
					munitionKey = loaded.getDescriptionId();
				}
				roundBlocks.add(contained);
			}
		}

		double speed;
		int maxFlightTicks;
		double gravity;
		double drag;
		String materialKey = "";

		RoundState round;
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
			/*
			 * An autocannon's ammunition is an item stack in its breech block entity, not a block in the bore,
			 * so the round is read from the breech itself. canFire() is CBC's own gate, which covers both the
			 * loaded shell and whatever the breech considers blocking. A contraption with no breech entity is
			 * treated as ready rather than empty: this scanner must not claim a gun cannot fire on evidence it
			 * did not actually look at.
			 */
			AbstractAutocannonBreechBlockEntity breech = null;
			for (BlockEntity raw : cannon.presentBlockEntities.values()) {
				if (raw instanceof AbstractAutocannonBreechBlockEntity candidate) {
					breech = candidate;
					break;
				}
			}
			round = breech == null || breech.canFire() ? RoundState.READY : RoundState.NO_ROUND;
		} else {
			// CBC passes the accumulated charge power straight to vanilla Projectile#shoot as launch speed.
			speed = chargePower;
			maxFlightTicks = TrajectoryComputer.MAX_PREDICT_TICKS;
			gravity = TrajectoryComputer.BIG_CANNON_GRAVITY;
			drag = TrajectoryComputer.BIG_CANNON_DRAG;
			if (firstRoundBlock == null)
				round = RoundState.NO_ROUND; // powder alone reaches fireShot with a null projectile: nothing fires
			else if (!ignitionChecked || !ignitable)
				round = RoundState.NOT_IGNITABLE;
			else if (!firstRoundBlock.isComplete(roundBlocks, orientation))
				round = RoundState.INCOMPLETE_ROUND;
			else
				round = RoundState.READY;
		}

		return new MountSnapshot(mountPos, true, autocannon, barrels, chargePower, speed, maxFlightTicks,
				gravity, drag, false, muzzle, direction, munitionKey, materialKey,
				mount.consoleYaw(), mount.consolePitch(), round);
	}
}
