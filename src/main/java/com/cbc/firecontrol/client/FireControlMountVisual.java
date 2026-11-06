package com.cbc.firecontrol.client;

import java.util.function.Consumer;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.KineticDebugger;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

/**
 * Flywheel visualizer for the half-slab Fire Control Mount.
 *
 * Renders ONLY the pitch and yaw interface shaft visuals (SHAFT + SHAFT_HALF partials at mount cell
 * center). Identical to CBC's mount visual for these two instances. Deliberately OMITS CBC's
 * ROTATING_MOUNT disc and CANNON_CARRIAGE_AXLE visuals because their original positions collide with
 * our new assembly layout:
 *
 *   CBC original (full-block mount):
 *     - ROTATING_MOUNT disc at mount pos ±1 (visual bridge between mount cell and cannon breech cell)
 *     - CANNON_CARRIAGE_AXLE at mount pos ±2 = assembly pos (embedded inside cannon breech body lower half)
 *     - assembly pos = mount pos ±2 (cannon breech cell 2 blocks away from mount cell, 1 bridge slot between)
 *
 *   Our half-slab layout:
 *     - assembly pos = mount pos ±1 (cannon breech cell DIRECTLY adjacent, no bridge slot)
 *     - Placing ROTATING_MOUNT at mount pos ±1 (CBC's original position) would put it INSIDE the
 *       assembled cannon breech cell. Placing carriage axle at new assembly pos mount pos ±1 also collides.
 *
 *   Instead the half-slab appearance is provided by the block model itself
 *   (assets/cbc_firecontrol/models/block/fire_control_mount{,_flipped}.json): a plinth occupying the
 *   physical half-cell + a thin cap/guards structure visually reaching up to the mount cell top boundary,
 *   standing in for what CBC's ROTATING_MOUNT disc + carriage visuals used to convey. When the cannon
 *   assembles, the mounted contraption entity renders its own cannon structure (own AABB + Flywheel
 *   instances handle cannon visual), and the mount cell's static model remains as the pedestal below.
 *
 * Kinetic interface visuals still spin with interface speeds:
 *   - pitchShaft rotates with getPitchSpeed() around pitch axis (X or Z depending on HORIZONTAL_FACING)
 *   - yawShaft rotates with getYawSpeed() around Y
 * These show kinetic input to mount regardless of assembled state, same as CBC.
 */
public class FireControlMountVisual extends KineticBlockEntityVisual<CannonMountBlockEntity> implements SimpleDynamicVisual {

	private final RotatingInstance pitchShaft;
	private final RotatingInstance yawShaft;

	public FireControlMountVisual(VisualizationContext ctx, CannonMountBlockEntity be, float partialTick) {
		super(ctx, be, partialTick);

		Direction vertical = be.getBlockState().getValue(BlockStateProperties.VERTICAL_DIRECTION);
		Direction facing = be.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
		Direction.Axis pitchAxis = facing.getAxis() == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;

		this.pitchShaft = instancerProvider()
				.instancer(AllInstanceTypes.ROTATING, Models.partial(AllPartialModels.SHAFT))
				.createInstance()
				.rotateToFace(pitchAxis)
				.setup(this.blockEntity.getPitchInterface())
				.setPosition(this.getVisualPosition());

		this.yawShaft = instancerProvider()
				.instancer(AllInstanceTypes.ROTATING, Models.partial(AllPartialModels.SHAFT_HALF))
				.createInstance()
				.rotateToFace(Direction.SOUTH, vertical)
				.setup(this.blockEntity.getYawInterface())
				.setPosition(this.getVisualPosition());

		this.transformModels();
	}

	@Override
	public void _delete() {
		this.pitchShaft.delete();
		this.yawShaft.delete();
	}

	@Override
	public void beginFrame(DynamicVisual.Context ctx) {
		this.transformModels();
	}

	private void transformModels() {
		Direction facing = this.blockState.getValue(BlockStateProperties.HORIZONTAL_FACING);
		Direction.Axis pitchAxis = facing.getAxis() == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
		this.updateRotation(this.pitchShaft, pitchAxis, this.blockEntity.getPitchSpeed(), true);
		this.updateRotation(this.yawShaft, Direction.Axis.Y, this.blockEntity.getYawSpeed(), false);
	}

	// Copied from KineticBlockEntityInstance (matches CBC's CannonMountVisual helper verbatim).
	private void updateRotation(RotatingInstance instance, Direction.Axis axis, float speed, boolean pitch) {
		instance.setRotationAxis(axis)
				.setRotationOffset(rotationOffset(this.blockState, axis, this.pos))
				.setRotationalSpeed(speed * RotatingInstance.SPEED_MULTIPLIER);
		if (KineticDebugger.isActive()) {
			instance.setColor(pitch ? this.blockEntity.getPitchInterface() : this.blockEntity.getYawInterface());
		}
		instance.setChanged();
	}

	@Override
	public void updateLight(float partialTick) {
		this.relight(this.pos, this.pitchShaft);
		this.relight(this.pos, this.yawShaft);
	}

	@Override
	public void collectCrumblingInstances(Consumer<Instance> consumer) {
		consumer.accept(this.pitchShaft);
		consumer.accept(this.yawShaft);
	}
}