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
 * (translate 1 block in cannon direction + yaw rotation), and a carriage axle using
 * {@link CBCFCBlockPartials#CARRIAGE_AXLE} (translate 1 block, yaw then pitch rotation). Neither
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
 *     own CBCFCBlockPartials.FIRE_CONTROL_ROTATING with the same translate so the model coords
 *     stay identical to the Blockbench export (bbmodel Y 0..20 unchanged). With instance Y origin
 *     at world 1 the turntable plate lands at world 1.0 (assembly cell bottom face), leaving a
 *     visible 0.5-block gap between plinth top (0.5, SHAPE_LOWER_HALF) and plate bottom; user
 *     accepts this pedestal-gap look (matches how CBC's own mount reads when the full-block mount
 *     top is flush at Y 1 but the half-slab variant naturally shows a lower plinth).
 *   - CBC renders CBCBlockPartials.CANNON_CARRIAGE_AXLE with translate(0, +/-2, 0) matching
 *     CBC's mount+bridge+cannon-at-+2 layout; we translate(0, +/-1, 0) matching our adjacent
 *     cannon layout (assembly pos is mount pos + 1, so carriage axle sits 1 block above mount
 *     instead of 2). We also use CBCFCBlockPartials.CARRIAGE_AXLE rather than CBC's because
 *     our inline variant resolves the parent texture aliases (#axis, #axis_top) directly,
 *     sidestepping the Flywheel PartialModel bake-time issue where a parent alias can remain
 *     unresolved and render purple/black missing-texture.
 */
public class FireControlMountBERenderer extends SafeBlockEntityRenderer<CannonMountBlockEntity> {

	public FireControlMountBERenderer(BlockEntityRendererProvider.Context context) { }

	@Override
	protected void renderSafe(CannonMountBlockEntity be, float partialTicks, PoseStack ms,
			MultiBufferSource buffer, int light, int overlay) {
		if (VisualizationManager.supportsVisualization(be.getLevel())) return;

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

		// Rotating turntable cradle. Model coords match user's bbmodel export exactly (Y 0..20
		// unchanged). Instance origin shifted to mount cell top boundary (world Y mount_Y + 0.5)
		// via translate(0, 0.5, 0): plate bottom (model Y 0 for upright, world Y = mount_Y + 0.5
		// since Y 0 stays fixed under the Z-180 flip for upside-down too) touches the half-slab
		// plinth top surface (SHAPE_LOWER_HALF upright at Y 0..0.5, SHAPE_UPPER_HALF upside-down
		// at Y 0.5..1) exactly, removing the visible 0.5-block pedestal gap the user reported.
		// Both upright and upside-down cases use the same +0.5 translate; the Z-180 flip already
		// inverts Y direction for upside-down so plate model Y 0 lands at the mount cell top
		// boundary in both orientations.
		ms.pushPose();
		CachedBuffers.partial(CBCFCBlockPartials.FIRE_CONTROL_ROTATING, state)
				.translate(0.0d, 0.5d, 0.0d)
				.light(light)
				.rotateCentered(qyaw)
				.renderInto(ms, solidBuf);
		ms.popPose();

		// Carriage axle at the same shifted origin (mount cell top boundary world Y mount_Y + 0.5)
		// as the rotating cradle, so model Y 6..10 lands at world mount_Y + 0.875 .. mount_Y + 1.125
		// for upright mount. This is the same Y range the rotating arms (model Y 7..20 → world
		// mount_Y + 0.9375 .. mount_Y + 1.75) span, so the axle visually sits inside the cradle
		// comb, and both instances yaw together as a rigid assembly.
		ms.pushPose();
		CachedBuffers.partial(CBCFCBlockPartials.CARRIAGE_AXLE, state)
				.translate(0.0d, 0.5d, 0.0d)
				.rotateCentered(qyaw1)
				.light(light)
				.renderInto(ms, solidBuf);
		ms.popPose();
	}
}
