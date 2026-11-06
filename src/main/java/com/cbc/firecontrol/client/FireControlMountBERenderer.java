package com.cbc.firecontrol.client;

import static com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer.getAngleForBe;
import static com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer.kineticRotationTransform;
import static com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer.shaft;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

/**
 * Non-Flywheel BE renderer fallback for the half-slab Fire Control Mount.
 *
 * Renders only the pitchShaft and yawShaft interface visuals, matching {@link FireControlMountVisual}.
 *
 * CBC's original CannonMountBlockEntityRenderer also renders a carriage axle partial at
 * {@code translate(0, upsideDown ? -2.0d : 2.0d, 0)} and a ROTATING_MOUNT disc at ±1, sized for CBC's
 * 2-block bridge between mount and cannon breech. Under our half-slab layout with assembly pos ±1
 * (adjacent), a carriage axle at ±2 would float past the cannon in empty air, and a disc at ±1 would
 * sit inside the cannon breech cell. Both are omitted here.
 *
 * In normal operation this class rarely runs: Flywheel is a hard dependency of Create, so
 * {@link VisualizationManager#supportsVisualization} almost always returns true, causing this method
 * to return immediately. We keep it as a defensive fallback for the edge case where Flywheel rendering
 * is disabled (some optifine configurations, older Flywheel versions, etc.).
 */
public class FireControlMountBERenderer extends SafeBlockEntityRenderer<CannonMountBlockEntity> {

	public FireControlMountBERenderer(BlockEntityRendererProvider.Context context) { }

	@Override
	protected void renderSafe(CannonMountBlockEntity be, float partialTicks, PoseStack ms,
			MultiBufferSource buffer, int light, int overlay) {
		if (VisualizationManager.supportsVisualization(be.getLevel())) return;

		BlockState state = be.getBlockState();
		Direction vertical = state.getValue(BlockStateProperties.VERTICAL_DIRECTION);
		VertexConsumer solidBuf = buffer.getBuffer(RenderType.solid());

		ms.pushPose();

		// Yaw interface shaft: SHAFT_HALF partial aligned with mount's vertical direction (DOWN or UP).
		SuperByteBuffer yawShaft = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, vertical);
		KineticBlockEntity yawInterface = be.getYawInterface();
		kineticRotationTransform(
				yawShaft, yawInterface, Direction.Axis.Y,
				getAngleForBe(yawInterface, be.getBlockPos(), Direction.Axis.Y), light)
				.renderInto(ms, solidBuf);

		// Pitch interface shaft: full SHAFT partial oriented along mount's pitch axis.
		Direction.Axis pitchAxis = ((IRotate) state.getBlock()).getRotationAxis(state);
		SuperByteBuffer pitchShaft = CachedBuffers.block(shaft(pitchAxis));
		KineticBlockEntity pitchInterface = be.getPitchInterface();
		kineticRotationTransform(
				pitchShaft, pitchInterface, pitchAxis,
				getAngleForBe(pitchInterface, be.getBlockPos(), pitchAxis), light)
				.renderInto(ms, solidBuf);

		ms.popPose();
	}
}