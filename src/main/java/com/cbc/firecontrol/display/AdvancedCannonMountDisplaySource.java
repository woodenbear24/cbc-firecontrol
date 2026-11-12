package com.cbc.firecontrol.display;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cbc.firecontrol.block.entity.AdvancedCannonMountBlockEntity;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBehavior;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;

/**
 * Create DisplaySource for {@link com.cbc.firecontrol.block.AdvancedCannonMountBlock}.
 *
 * A Create {@code DisplayLink} bound to an Advanced Cannon Mount reads (one line each):
 *   1. Position          - the mount block position.
 *   2. Barrel pointing   - the cannon yaw + pitch (degrees) from the mount.
 *   3. Propulsion        - total charge power of the propellant / integrated-propellant currently
 *                          loaded in the assembled cannon (sum of BigCannonPropellantBlock#
 *                          getChargePower over each cannon block's contained round), i.e. how "hard"
 *                          the loaded round will be pushed.
 *   4. Loaded shell type - the warhead(s) (ProjectileBlock) loaded in the assembled cannon, shown by
 *                          their official CBC name, with counts (e.g. "High Explosive (HE) Shell"). An
 *                          integrated-propellant cartridge is NOT listed as a shell here; it only feeds
 *                          the propulsion line.
 *
 * The loaded round is read the same way CBC's own fireShot reads it: from each mounted cannon block
 * entity's behavior contained block (cannon.presentBlockEntities -> IBigCannonBlockEntity#
 * cannonBehavior().block()), NOT from the contraption's structural blocks (which are just the barrels
 * / breech). presentBlockEntities and BigCannonBehavior are synced to the client, so the DisplaySource
 * sees the loaded round. When the mount has no assembled cannon (or is not our mount BE) lines 3/4
 * fall back to a translatable "no cannon" label.
 *
 * All labels are translatable lang keys under
 * "cbc_firecontrol.display_source.advanced_cannon_mount.*" (see assets lang files) so DisplayBoard /
 * flap / label targets show them localized.
 */
public class AdvancedCannonMountDisplaySource extends DisplaySource {

	private static final String KEY = "cbc_firecontrol.display_source.advanced_cannon_mount";

	@Override
	public List<MutableComponent> provideText(DisplayLinkContext context, DisplayTargetStats stats) {
		List<MutableComponent> lines = new ArrayList<>();

		BlockEntity be = context.getSourceBlockEntity();
		if (!(be instanceof AdvancedCannonMountBlockEntity mount)) {
			lines.add(Component.translatable(KEY + ".no_mount"));
			return lines;
		}

		// (1) Position
		BlockPos pos = mount.getBlockPos();
		lines.add(Component.translatable(KEY + ".position", pos.getX(), pos.getY(), pos.getZ()));

		// (2) Barrel pointing (yaw / pitch, degrees)
		float yaw = Mth.wrapDegrees(mount.consoleYaw());
		float pitch = mount.consolePitch();
		lines.add(Component.translatable(KEY + ".barrel",
				Component.literal(String.format("%.1f", yaw)),
				Component.literal(String.format("%.1f", pitch))));

		// (3)+(4) Propulsion + loaded shell type from the assembled cannon structure (read via a public
		// accessor on our BE - CBC's mountedContraption field is protected / cross-package inaccessible).
		AbstractMountedCannonContraption cannon = mount.mountedCannonOrNull();
		if (cannon == null) {
			MutableComponent none = Component.translatable(KEY + ".none");
			lines.add(Component.translatable(KEY + ".propulsion", none));
			lines.add(Component.translatable(KEY + ".shell", none));
			return lines;
		}

		Map<Block, Integer> shells = new LinkedHashMap<>();
		float chargePower = 0.0f;
		// The loaded round is NOT in the contraption's structural blocks (those are the barrels /
		// breech themselves). CBC's own fireShot reads the loaded propellant / shell / cartridge from
		// each cannon block entity's behavior: cannonBehavior().block() (the block "contained" in
		// that cannon position). Mirror that: iterate the mount's present block entities and inspect
		// each IBigCannonBlockEntity's contained state. presentBlockEntities also exists on the client
		// copy (rebuilt from synced NBT) and BigCannonBehavior is synced, so the DisplaySource sees it.
		for (BlockEntity raw : cannon.presentBlockEntities.values()) {
			if (!(raw instanceof IBigCannonBlockEntity cbe))
				continue;
			BigCannonBehavior behavior = cbe.cannonBehavior();
			if (behavior == null)
				continue;
			StructureBlockInfo contained = behavior.block();
			if (contained == null || contained.state() == null || contained.state().isAir())
				continue;
			BlockState state = contained.state();
			Block block = state.getBlock();
			// SHELL lists the warhead ONLY: a projectile block loaded in the bore. An
			// integrated-propellant cartridge (BigCartridgeBlock) is NOT a warhead and is deliberately
			// excluded here - it contributes only to propulsion below, because it implements
			// BigCannonPropellantBlock.
			if (block instanceof ProjectileBlock<?>)
				shells.merge(block, 1, Integer::sum);
			// Propulsion: charge power of every propellant-bearing block. This also INCLUDES
			// integrated-propellant cartridge blocks: BigCartridgeBlock implements BigCannonPropellantBlock,
			// so getChargePower(contained) reads its stored/integrated propellant amount alongside loose propellant.
			if (block instanceof BigCannonPropellantBlock propellant)
				chargePower += Math.max(0.0f, propellant.getChargePower(contained));
		}

		MutableComponent shellText = Component.empty();
		boolean first = true;
		// Render the warhead's official CBC name. CBC localizes each shell under a block.* translation
		// key (e.g. block.createbigcannons.he_shell = "High Explosive (HE) Shell"); the shell's item form
		// shares that same official name, so Block#getName() -> translatable(key) shows the official,
		// localized munition name on a DisplayBoard / flap.
		for (Map.Entry<Block, Integer> e : shells.entrySet()) {
			if (!first)
				shellText.append(", ");
			first = false;
			shellText.append(e.getKey().getName());
			if (e.getValue() > 1)
				shellText.append(Component.literal(" x" + e.getValue()));
		}
		if (shells.isEmpty())
			shellText.append(Component.translatable(KEY + ".empty"));

		lines.add(Component.translatable(KEY + ".propulsion",
				Component.literal(String.format("%.1f", chargePower))));
		lines.add(Component.translatable(KEY + ".shell", shellText));
		return lines;
	}
}
