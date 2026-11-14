package com.cbc.firecontrol.client;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.init.CBCItems;
import com.cbc.firecontrol.item.TargetDesignatorItem;
import com.cbc.firecontrol.item.TargetDesignatorLinkData;
import com.cbc.firecontrol.network.DesignatorProbePayload;
import com.cbc.firecontrol.network.TargetDesignatorFixPayload;

import com.mojang.blaze3d.platform.InputConstants;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-only designator behaviour: the spyglass zoom, and the middle click that reports what the scope is pointed
 * at back to the bound Trajectory Calculator.
 *
 * <p>Vanilla 1.21.1's {@code GameRenderer#getFov} multiplies FOV by {@code SpyglassItem.ZOOM_FOV_MODIFIER} only
 * when the item being used IS {@code minecraft:spyglass}, so a modded copy gets the vignette and the arm pose for
 * free (via {@code ItemAbilities.SPYGLASS_SCOPE}) but not the zoom. NeoForge's {@code ComputeFovModifierEvent} is
 * the supported hook for the rest. Gated to OUR item and to the main hand being the one in use, so a vanilla
 * spyglass held at the same time is never scaled twice.
 */
@EventBusSubscriber(modid = CBCFireControl.MODID, value = Dist.CLIENT)
public class TargetDesignatorClientEvents {

	@SubscribeEvent
	public static void onComputeFovModifier(ComputeFovModifierEvent event) {
		Player player = event.getPlayer();
		if (player == null || !player.isUsingItem() || player.getUsedItemHand() != net.minecraft.world.InteractionHand.MAIN_HAND)
			return;
		ItemStack used = player.getUseItem();
		if (!used.is(CBCItems.TARGET_DESIGNATOR.asItem()))
			return;
		event.setNewFovModifier(event.getNewFovModifier() * TargetDesignatorItem.ZOOM_FOV_MODIFIER);
	}

	/**
	 * Middle click while the scope is up: lock the block the crosshair sits on into the designator's bound
	 * calculator, which also slews that console's mounts onto it (see
	 * {@link com.cbc.firecontrol.network.CBCFCNetwork#handleTargetDesignatorFix} - one packet carries both halves, because
	 * a target nothing is aimed at is only half a request).
	 *
	 * <p>Deliberately gated to the scoping state, so a middle click outside the scope stays vanilla pick-block and
	 * the mod never steals that key from the player's building workflow. The event is cancelled only when the click
	 * was actually consumed, so an unbound designator still picks blocks as vanilla would.
	 *
	 * <p>What gets locked is the first cell the sight line passes through that holds a block - see
	 * {@link #walkSightLine}. Every answer the designator gives goes to the actionbar rather than to chat: the
	 * spotter reads it while looking through the scope, and the console's own reply (which block it took, and how
	 * many mount queues took the order) lands on that same line, so one message per click is what survives.
	 * Neither side gates the lock on a distance any more: whatever the scope line finds is a real cell, so the
	 * console takes it and whether it can be hit is the ballistics' answer, not a range rule's.
	 */
	@SubscribeEvent
	public static void onMouseButton(InputEvent.MouseButton.Pre event) {
		if (event.getAction() != InputConstants.PRESS
				|| event.getButton() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE)
			return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || mc.screen != null)
			return;
		Player player = mc.player;
		if (!player.isUsingItem())
			return;
		ItemStack used = player.getUseItem();
		if (!(used.getItem() instanceof TargetDesignatorItem))
			return;
		TargetDesignatorLinkData link = TargetDesignatorItem.bindingOf(used);
		if (link == null) {
			player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.target_designator.unbound"), true);
			return;
		}

		Vec3 eye = player.getEyePosition(1.0F);
		Vec3 far = TargetDesignatorItem.scopeEndPoint(mc.level, player);
		Sight sight = walkSightLine(mc.level, eye, far);

		if (sight.cell() != null) {
			BlockPos target = sight.cell();
			PacketDistributor.sendToServer(
					new TargetDesignatorFixPayload(link.calculator(), target.getX(), target.getY(), target.getZ()));
			/*
			 * No echo here: the console answers the click itself, on the actionbar, with the block it took and how
			 * many mount queues accepted the order. Printing the lock here as well would only overwrite one line
			 * with the other, and the console's answer is the one that says what actually happened.
			 */
			event.setCanceled(true);
			return;
		}

		/*
		 * The line found nothing, but there are two different "nothing"s. If it stopped because it left
		 * simulated space while length of the line was still untested, the spotter cannot see what is out there -
		 * so ask the bound console to load along the line and look. If the whole line was walked and came up
		 * empty, the world really is empty along it and saying so is the honest answer.
		 */
		if (sight.scanned() < sight.ray()) {
			PacketDistributor.sendToServer(new DesignatorProbePayload(link.calculator(), eye,
					far.subtract(eye), sight.scanned(), sight.ray()));
			player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.target_designator.probing"),
					true);
			event.setCanceled(true);
			return;
		}
		player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.target_designator.no_block"),
				true);
	}

	/** Crossing-parameter tolerance for boundaries that share one point (a corner the line passes through). */
	private static final double TIE_EPS = 1e-12;

	/**
	 * What a sight-line walk ended with: the cell it locked (null when it locked nothing), how far along the line
	 * it tested in blocks, and how far the line itself runs before the world border ends it. {@code scanned}
	 * short of {@code ray} is the case where the walk left simulated space rather than crossing an empty world.
	 */
	private record Sight(@Nullable BlockPos cell, double scanned, double ray) {
	}

	/**
	 * The first cell the sight line passes through that holds something the player can actually see, plus how far
	 * the walk got and how far the line could have gone - see {@link Sight}.
	 *
	 * <p>{@code Level#clip} is the wrong tool here even though it is what a crosshair normally uses: it intersects
	 * SHAPES, and the shapes of grass, flowers, snow layers, carpets, signs, lanterns and torches are either empty
	 * (their collision shape, which {@code ClipContext.Block.TERRAIN} reads) or a fraction of the cell
	 * ({@code OUTLINE}, the selection box). A line aimed straight at a hillside covered in such blocks therefore
	 * reports nothing and the locked point becomes air - which is exactly the complaint this replaces. Fire control
	 * needs the CELL, not the exact hit point or face, so the line is walked cell by cell (the standard
	 * Amanatides & Woo voxel traversal) and the first occupied cell wins. Fluid-only cells are skipped: water and
	 * lava are see-through enough to aim past, while a waterlogged fence still counts because its block is a fence
	 * rather than a {@link LiquidBlock}.
	 *
	 * <p>The traversal stops at the first cell whose chunk is not on the client, and reports the distance it
	 * reached rather than a bare "nothing". Beyond simulated space an answer would be invented, and locking
	 * something hidden behind terrain nobody can see is worse than locking nothing - but the console at the other
	 * end of the binding does have that terrain, so the caller hands it the line and lets it look (see
	 * {@link com.cbc.firecontrol.block.entity.DesignatorProbe}).
	 */
	private static Sight walkSightLine(Level level, Vec3 from, Vec3 to) {
		Vec3 delta = to.subtract(from);
		double ray = delta.length();
		// The parameter at which the cell now being tested was entered, so a walk that stops on an unloaded cell
		// reports the distance it actually covered instead of one cell further than it did.
		double entered = 0.0;
		int x = Mth.floor(from.x);
		int y = Mth.floor(from.y);
		int z = Mth.floor(from.z);
		int stepX = delta.x > 0 ? 1 : -1;
		int stepY = delta.y > 0 ? 1 : -1;
		int stepZ = delta.z > 0 ? 1 : -1;
		// t is measured along the ray in units where t = 1 is the far end of the scope line. tDelta is the
		// parameter length of one cell along an axis (always positive); tMax is the parameter at which the next
		// cell boundary on that axis is crossed, which needs the SIGNED divide - multiplying the distance to that
		// boundary by a positive tDelta would flip its sign whenever the ray runs the other way down that axis.
		// An axis that does not change has no boundary to cross, so its next boundary is forever away.
		double tDeltaX = delta.x == 0 ? Double.POSITIVE_INFINITY : stepX / delta.x;
		double tDeltaY = delta.y == 0 ? Double.POSITIVE_INFINITY : stepY / delta.y;
		double tDeltaZ = delta.z == 0 ? Double.POSITIVE_INFINITY : stepZ / delta.z;
		double tMaxX = delta.x == 0 ? Double.POSITIVE_INFINITY
				: (x + (stepX > 0 ? 1 : 0) - from.x) / delta.x;
		double tMaxY = delta.y == 0 ? Double.POSITIVE_INFINITY
				: (y + (stepY > 0 ? 1 : 0) - from.y) / delta.y;
		double tMaxZ = delta.z == 0 ? Double.POSITIVE_INFINITY
				: (z + (stepZ > 0 ? 1 : 0) - from.z) / delta.z;
		// Every step crosses exactly one cell boundary, so this many steps cannot leave the segment.
		int limit = (int) Math.ceil(Math.abs(delta.x) + Math.abs(delta.y) + Math.abs(delta.z)) + 3;

		for (int step = 0; step < limit; step++) {
			// The cell the eye sits in is the spotter's own position and never a target: lying in a bush should
			// not lock the bush around them.
			if (step > 0) {
				BlockPos cell = new BlockPos(x, y, z);
				if (!level.hasChunkAt(cell))
					return new Sight(null, entered * ray, ray);
				if (holdsBlock(level.getBlockState(cell)))
					return new Sight(cell, entered * ray, ray);
			}
			double next = Math.min(Math.min(tMaxX, tMaxY), tMaxZ);
			if (next > 1.0)
				return new Sight(null, ray, ray);
			entered = next;
			// Two or three boundaries can share one crossing parameter: the eye sitting exactly on a corner, or the
			// line passing through a lattice corner. Cross them together, which enters the diagonally adjacent cell
			// - the only one the line spends length inside. Advancing a single axis would instead test a side cell
			// the line touches at that one point, and locking it would name a block the crosshair is not on.
			if (tMaxX <= next + TIE_EPS) {
				x += stepX;
				tMaxX += tDeltaX;
			}
			if (tMaxY <= next + TIE_EPS) {
				y += stepY;
				tMaxY += tDeltaY;
			}
			if (tMaxZ <= next + TIE_EPS) {
				z += stepZ;
				tMaxZ += tDeltaZ;
			}
		}
		return new Sight(null, ray, ray);
	}

	/** Air is not a target, and neither is a cell that is only liquid; everything rendered counts. */
	private static boolean holdsBlock(BlockState state) {
		if (state.isAir())
			return false;
		return !(state.getBlock() instanceof LiquidBlock);
	}
}
