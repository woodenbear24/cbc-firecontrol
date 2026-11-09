package com.cbc.firecontrol.client;

import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

import com.cbc.firecontrol.init.CBCFCBlockPartials;

/**
 * Non-Flywheel BE renderer fallback for the half-slab Fire Control Mount.
 *
 * Renders exactly the same OrientedInstance set as {@link FireControlMountVisual}: a turntable
 * cradle using our custom partial model {@link CBCFCBlockPartials#FIRE_CONTROL_ROTATING}
 * (translate 0.5 to the mount mid-plane + yaw rotation), and a carriage axle using
 * {@link CBCFCBlockPartials#CARRIAGE_AXLE} (translate 0.5, yaw then pitch rotation). Neither
 * interface shaft (SHAFT / SHAFT_HALF) is rendered, matching the visualizer's choice to omit them.
 *
 * In practice this class rarely runs: Flywheel is a hard dependency of Create, so
 * VisualizationManager.supportsVisualization(be.getLevel()) is nearly always true and this
 * method returns at the top. It exists for the edge case (some optifine configurations, Flywheel
 * disabled by config, etc.) where Create would otherwise render nothing on the mount BE's
 * BEGS path and mount visuals disappear entirely.
 *
 * Compared with CBC's CannonMountBlockEntityRenderer fallback:
 *   - CBC applies yawShaft + pitchShaft render first; we skip those two entirely.
 *   - CBC renders CBCBlockPartials.ROTATING_MOUNT with translate(0, +/-1, 0); we render the user's
 *     own CBCFCBlockPartials.FIRE_CONTROL_ROTATING (model coords unchanged from the Blockbench
 *     export, bbmodel Y 0..20) with translate(0, 0.5, 0) for BOTH orientations, seating the plate
 *     (model Y 0) on the mount-cell mid-plane = plinth inner face (SHAPE_LOWER_HALF top or
 *     SHAPE_UPPER_HALF bottom); the Z-180 flip in qyaw inverts the cradle toward the assembly cell
 *     for the upside-down mount, so no per-orientation translate sign is needed.
 *   - CBC renders CBCBlockPartials.CANNON_CARRIAGE_AXLE with translate(0, +/-2, 0) matching
 *     CBC's mount+bridge+cannon-at-+2 layout; we translate(0, 0.5, 0) sharing the mount's mid-plane
 *     frame so the axle (model Y 6..10) sits in the cradle comb opening, mirrored by the qyaw1 flip
 *     for the inverted mount. We use CBCFCBlockPartials.CARRIAGE_AXLE rather than CBC's because
 *     our inline variant resolves the parent texture aliases (#axis, #axis_top) directly,
 *     sidestepping the Flywheel PartialModel bake-time issue where a parent alias can remain
 *     unresolved and render purple/black missing-texture.
 */
public class FireControlMountBERenderer extends SafeBlockEntityRenderer<CannonMountBlockEntity> {

	public FireControlMountBERenderer(BlockEntityRendererProvider.Context context) { }

	@Override
	protected void renderSafe(CannonMountBlockEntity be, float partialTicks, PoseStack ms,
			MultiBufferSource buffer, int light, int overlay) {
		if (VisualizationManager.supportsVisualization(be.getLevel()))
			return;

		BlockState state = be.getBlockState();
		Direction vertical = state.getValue(BlockStateProperties.VERTICAL_DIRECTION);
		boolean upsideDown = vertical == Direction.UP;
		VertexConsumer solidBuf = buffer.getBuffer(RenderType.solid());

		float yaw = be.getYawOffset(partialTicks - 1);
		Quaternionf qyaw = upsideDown
				? Axis.ZP.rotationDegrees(180).mul(Axis.YP.rotationDegrees(yaw))
				: Axis.YP.rotationDegrees(-yaw);

		float pitch = be.getPitchOffset(partialTicks - 1);
		Quaternionf qpitch = upsideDown
				? Axis.XP.rotationDegrees(pitch)
				: Axis.XP.rotationDegrees(-pitch);
		Quaternionf qyaw1 = new Quaternionf(qyaw);
		qyaw1.mul(qpitch);

		// Rotating turntable cradle: local lift 0 → world anchor mount_Y + 0.5 (mount cell
		// mid-plane = plinth inner face in both orientations). translate(0, 0.5, 0) both cases;
		// the Z-180 flip in qyaw inverts the model geometry for upside-down so the cradle hangs
		// toward the assembly cell on the correct side.
		ms.pushPose();
		CachedBuffers.partial(CBCFCBlockPartials.FIRE_CONTROL_ROTATING, state)
				.translate(0.0d, 0.5d, 0.0d)
				.light(light)
				.rotateCentered(qyaw)
				.renderInto(ms, solidBuf);
		ms.popPose();

		// Carriage axle: rotateCentered() pivots about the partial's own box centre (model 8,8,8 =
		// block 0.5,0.5,0.5 = the shaft line), so qyaw1's PITCH spins the axle about its own shaft and
		// the shaft midpoint never translates out of the comb. translate() just lifts that centre onto
		// the comb opening, orientation-dependent so it crosses the CANNON BODY: upright cannon-body
		// centre (pos+1.5) => translate +1.0, inverted (pos-0.5) => translate -1.0, mirroring CBC's
		// carriage-in-assembly-cell placement and the qyaw Z-180 flip. Mirrors FireControlMountVisual's
		// axle pivot (0.5,0.5,0.5) + orientation Y offset; BERenderer is the no-Flywheel fallback path.
		ms.pushPose();
		CachedBuffers.partial(CBCFCBlockPartials.CARRIAGE_AXLE, state)
				.translate(0.0d, upsideDown ? -1.0d : 1.0d, 0.0d)
				.rotateCentered(qyaw1)
				.light(light)
				.renderInto(ms, solidBuf);
		ms.popPose();
	}

}
