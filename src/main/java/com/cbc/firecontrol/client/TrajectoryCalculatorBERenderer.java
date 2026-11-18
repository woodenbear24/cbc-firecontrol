package com.cbc.firecontrol.client;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.block.entity.TrajectoryCalculatorBlockEntity;
import com.cbc.firecontrol.init.CBCFCBlockPartials;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Transformation;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;

import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Draws the calculator's card rack: one {@link CBCFCBlockPartials#CALCULATOR_LINK_CARD} for every
 * calculator_link item sitting in the block entity's five slots, so the console's physical card rack
 * mirrors what the GUI's slot column holds.
 *
 * Placement follows the geometry the user modelled. The console body carries five rack bars at
 * x = 0..1, each one unit thick, at y = 4..5, 6..7, 8..9, 10..11 and 12..13 (all spanning z = 7..15),
 * and the card model's plate occupies x = 0..8, y = 3..4, z = 7..15 with its tabs rising into
 * y = 4..5. Left at Y = 0 the card therefore hangs from the lowest bar, resting on nothing else;
 * lifting it by whole multiples of two model units hangs it from the next bar up. GUI row 0 is the
 * topmost row of the slot column, so the mapping is inverted: slot 0 hangs from the highest bar
 * (lift 8 units) and slot 4 from the lowest (lift 0). Two constants below carry that decision.
 *
 * The card needs no extra rotation of its own. Its plate's east face samples the copper column of
 * the texture (see the model's credit line), and the monitor panel cube of the body model sits at
 * x = 8..16, i.e. directly on the card's +X side, so the copper connector already points at the
 * display. The blockstate spin is applied here for exactly the same reason: the body model is rotated
 * by blockstates/trajectory_calculator.json, a BE renderer's PoseStack is not, so without reproducing
 * that rotation the cards would stay in the south-facing layout while the console turned - which would
 * put them outside the rack and pointing away from the screen. It is reproduced by taking the
 * calculator's <em>own</em> rotation instead of recomputing one: the angle is the facing's
 * {@code toYRot()}, which is exactly the y value the blockstate declares because vanilla numbers the
 * horizontal facings south=0, west=90, north=180, east=270, and the rotation itself is
 * {@link BlockModelRotation}, the enum every blockstate rotation goes through. The cards thus live in
 * the console's local model space and move with it whichever way the block faces.
 *
 * <p>Going through {@code BlockModelRotation} rather than a hand-written rotation also removes a sign
 * error this renderer used to carry: vanilla builds that rotation as {@code new Quaternionf()
 * .rotateYXZ(-y, -x, 0)}, negating the angle, and {@code FaceBakery#applyModelRotation} rotates every
 * baked vertex about the origin {@code (0.5, 0.5, 0.5)} - the block centre. Applying
 * {@code Axis.YP.rotationDegrees(+y)} instead mirrors the rack for west and east and drops the cards
 * half a turn away from their bars, while south (0 degrees) and north (180 degrees, its own negation)
 * still look correct - which is why the fault only appeared in two of the four orientations. The pivot
 * below is that same origin.
 *
 * This deliberately implements the plain {@link BlockEntityRenderer} rather than Create's
 * {@code SafeBlockEntityRenderer}: that base class returns early whenever Flywheel supports
 * visualization in the current level, and the calculator registers no Flywheel visual
 * (see {@link com.cbc.firecontrol.init.CBCFCBlockEntities}), so the guard would drop the cards in
 * every normal in-world render - Flywheel only takes over block entities that have a registered
 * visual, and leaves the vanilla BE renderer path for the rest.
 */
public class TrajectoryCalculatorBERenderer implements BlockEntityRenderer<TrajectoryCalculatorBlockEntity> {

	/**
	 * Temporary diagnostic: records once per client session whether this renderer is actually invoked
	 * while Flywheel is loaded, and whether Flywheel claims the calculator block entity. Delete it once
	 * the in-game card rack has been verified.
	 */
	private static boolean rackDiagnosticLogged = false;

	/** Model units per block: the card model is authored in the 0..16 Blockbench space. */
	private static final double MODEL_UNIT = 1.0d / 16.0d;
	/** Distance between two rack bars in model units - one bar plus the gap a card's plate fills. */
	private static final double RACK_PITCH = 2.0d * MODEL_UNIT;

	public TrajectoryCalculatorBERenderer(BlockEntityRendererProvider.Context context) { }

	@Override
	public void render(TrajectoryCalculatorBlockEntity be, float partialTicks, PoseStack ms,
			MultiBufferSource buffer, int light, int overlay) {
		Level level = be.getLevel();
		if (level == null)
			return;

		if (!rackDiagnosticLogged) {
			rackDiagnosticLogged = true;
			BlockEntityType<?> type = be.getType();
			int filled = 0;
			for (int slot = 0; slot < TrajectoryCalculatorBlockEntity.SLOTS; slot++)
				if (!be.getInventory().getStackInSlot(slot).isEmpty())
					filled++;
			CBCFireControl.LOGGER.info(
					"TrajectoryCalculatorBERenderer reached the render loop at {} with {} of {} card slots filled; "
							+ "Flywheel visualization {} in this level, visualizer for {} is {}",
					be.getBlockPos(), filled, TrajectoryCalculatorBlockEntity.SLOTS,
					VisualizationManager.supportsVisualization(level) ? "SUPPORTED" : "NOT supported",
					type, VisualizerRegistry.getVisualizer(type) == null
							? "NULL, so the vanilla block entity renderer path is in charge"
							: "registered, so Flywheel owns this block entity");
		}

		BlockState state = be.getBlockState();
		ItemStackHandler inventory = be.getInventory();
		int slots = Math.min(inventory.getSlots(), TrajectoryCalculatorBlockEntity.SLOTS);
		VertexConsumer solid = buffer.getBuffer(RenderType.solid());

		ms.pushPose();
		applyBlockstateRotation(ms, state);
		for (int slot = 0; slot < slots; slot++) {
			if (inventory.getStackInSlot(slot).isEmpty())
				continue;
			double lift = (TrajectoryCalculatorBlockEntity.SLOTS - 1 - slot) * RACK_PITCH;
			ms.pushPose();
			CachedBuffers.partial(CBCFCBlockPartials.CALCULATOR_LINK_CARD, state)
					.translate(0.0d, lift, 0.0d)
					.light(light)
					.renderInto(ms, solid);
			ms.popPose();
		}
		ms.popPose();
	}

	/**
	 * Puts the card geometry into the calculator's local coordinate system by applying the rotation
	 * its blockstate JSON gives the body model, instead of placing cards with a copied angle table.
	 * {@link net.minecraft.core.Direction#toYRot()} is the y value that JSON declares (south 0,
	 * west 90, north 180, east 270), and {@link BlockModelRotation} is the transform Minecraft bakes
	 * it into: the angle negated, about the block centre - the origin {@code FaceBakery} uses for
	 * every baked vertex, which the translate pair below reproduces.
	 */
	private static void applyBlockstateRotation(PoseStack ms, BlockState state) {
		int yRot = (int) state.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
		if (yRot == 0)
			return;
		Transformation rotation = BlockModelRotation.by(0, yRot).getRotation();
		ms.translate(0.5f, 0.5f, 0.5f);
		ms.mulPose(rotation.getMatrix());
		ms.translate(-0.5f, -0.5f, -0.5f);
	}
}
