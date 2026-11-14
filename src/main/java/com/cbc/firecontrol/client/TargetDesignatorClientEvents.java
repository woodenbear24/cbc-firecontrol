package com.cbc.firecontrol.client;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.init.CBCItems;
import com.cbc.firecontrol.item.TargetDesignatorItem;
import com.cbc.firecontrol.item.TargetDesignatorLinkData;
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
	 * {@link #firstLockedCell}. And the spotter hears what was locked, block name included, in chat: the aim
	 * outcome then takes the actionbar line, so "which block did I just order" stays readable after the summary
	 * scrolls past. Neither side gates the lock on a distance any more: whatever the scope line finds is a real
	 * cell, so the console takes it and whether it can be hit is the ballistics' answer, not a range rule's.
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
		BlockPos target = firstLockedCell(mc.level, eye, TargetDesignatorItem.scopeEndPoint(mc.level, player));
		if (target == null) {
			player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.target_designator.no_block"),
					true);
			return;
		}

		PacketDistributor.sendToServer(
				new TargetDesignatorFixPayload(link.calculator(), target.getX(), target.getY(), target.getZ()));
		player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.target_designator.fixed",
				mc.level.getBlockState(target).getBlock().getName(), target.getX(), target.getY(), target.getZ()),
				false);
		event.setCanceled(true);
	}

	/**
	 * The first cell the sight line passes through that holds something the player can actually see, or null when
	 * the whole line is empty space.
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
	 * <p>The traversal stops at the first cell whose chunk is not on the client. Beyond simulated space the answer
	 * would be invented, and locking something hidden behind terrain nobody can see is worse than locking nothing;
	 * the spotter gets the "nothing along the scope line" message instead and can move or look elsewhere.
	 */
	/** Crossing-parameter tolerance for boundaries that share one point (a corner the line passes through). */
	private static final double TIE_EPS = 1e-12;

	private static BlockPos firstLockedCell(Level level, Vec3 from, Vec3 to) {
		Vec3 delta = to.subtract(from);
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
					return null;
				if (holdsBlock(level.getBlockState(cell)))
					return cell;
			}
			double next = Math.min(Math.min(tMaxX, tMaxY), tMaxZ);
			if (next > 1.0)
				return null;
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
		return null;
	}

	/** Air is not a target, and neither is a cell that is only liquid; everything rendered counts. */
	private static boolean holdsBlock(BlockState state) {
		if (state.isAir())
			return false;
		return !(state.getBlock() instanceof LiquidBlock);
	}
}
