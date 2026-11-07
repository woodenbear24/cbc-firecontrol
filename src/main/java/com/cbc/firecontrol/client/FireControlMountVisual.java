package com.cbc.firecontrol.client;

import java.util.function.Consumer;

import org.joml.Quaternionf;

import com.mojang.math.Axis;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.OrientedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

import com.cbc.firecontrol.init.CBCFCBlockPartials;

/**
 * Flywheel visualizer for the half-slab Fire Control Mount. Emits two OrientedInstances
 * (fire_control_rotating turntable cradle + carriage axle) and NO kinetic interface shaft instances.
 *
 * Positioning:
 *   Both instances sit at mount cell corner + 1 block in cannon direction (= assembly pos
 *   cell corner in our adjacent-cannon layout). Model coordinates are unchanged from the user's
 *   bbmodel export (fire_control_rotating Y 0..20, carriage_axle Y 6..10), so under this
 *   positioning the rotating plate lands at world Y 1.0..1.125 (assembly cell bottom face) with
 *   a visible 0.5-block pedestal gap above the half-slab plinth top at world 0.5, and the carriage
 *   axle lands at world Y 1.375..1.625 (assembly cell middle). Both are consistent with how CBC's
 *   own mount partial reads when placed on top of its full-block mount; our half-slab variant
 *   shows the gap because the plinth only fills the lower half of the mount cell.
 *
 * Rotation:
 *   rotatingMount: mount's yaw only (CBC convention; the cradle plate + posts + arms yaw with
 *   the mount as a rigid structure but do NOT pitch - the axle pitches through them which is how
 *   a real trunnion mount cannon recoils/pivots inside its cradle without the cradle tilting).
 *   carriageAxle: mount's yaw then pitch (qyaw * qpitch) so the trunnion axle tilts with cannon pitch.
 *
 * Interface shafts (CBC's pitchShaft + yawShaft SHAFT/SHAFT_HALF instances) are deliberately
 * omitted. See class-body javadoc block further below for the visual-clutter rationale; kinetic
 * network logic is unaffected (Create's hasShaftTowards is a face predicate, not rendered geometry).
 */
public class FireControlMountVisual extends KineticBlockEntityVisual<CannonMountBlockEntity> implements SimpleDynamicVisual {

	private final OrientedInstance rotatingMount;
	private final OrientedInstance carriageAxle;

	public FireControlMountVisual(VisualizationContext ctx, CannonMountBlockEntity be, float partialTick) {
		super(ctx, be, partialTick);

		Direction vertical = be.getBlockState().getValue(BlockStateProperties.VERTICAL_DIRECTION);
		Direction cannonDir = vertical.getOpposite();

		// Position: mount cell corner + 1 block in cannon dir = assembly pos cell corner.
		// Same formula as CBC's rotatingMount instance (relative(cannonDir)).
		// CBC's carriageAxle used relative(vertical, -2) (assembly pos in CBC's 2-block-away layout);
		// we use relative(cannonDir) = relative(vertical, -1), moving the axle 1 block closer to mount
		// so it lines up with our new adjacent assembly pos.
		// translatePosition Y -0.5 shifts instance origin from assembly cell corner (integer Y)
		// down 0.5 blocks so the model plate bottom (model Y 0) sits at mount cell top boundary
		// world Y mount_cell_Y + 0.5, exactly touching the half-slab plinth top for SHAPE_LOWER_HALF
		// upright mount. For upside-down mount (SHAPE_UPPER_HALF) the Z-180 flip makes model Y 0
		// the plate's world-highest corner, so the same mount_cell_Y + 0.5 origin lands plate top
		// at plinth bottom boundary → both cases touch the plinth surface without any model-file Y edit.
		// carriageAxle uses model Y 6..10 (CBC convention) which, at the same shifted origin, lands
		// at world mount_cell_Y + 0.875 .. mount_cell_Y + 1.125 for upright mount (inside cannon
		// breech region), matching where the cannon pivots.
		BlockPos assemblyCell = this.getVisualPosition().relative(cannonDir);
		float mountCellTopY = (float) this.getVisualPosition().getY() + 0.5f;
		this.rotatingMount = instancerProvider()
				.instancer(InstanceTypes.ORIENTED, Models.partial(CBCFCBlockPartials.FIRE_CONTROL_ROTATING))
				.createInstance()
				.position(assemblyCell.getX(), mountCellTopY, assemblyCell.getZ());

		this.carriageAxle = instancerProvider()
				.instancer(InstanceTypes.ORIENTED, Models.partial(CBCFCBlockPartials.CARRIAGE_AXLE))
				.createInstance()
				.position(assemblyCell.getX(), mountCellTopY, assemblyCell.getZ());

		transformModels(partialTick);
	}

	@Override
	public void beginFrame(DynamicVisual.Context ctx) {
		transformModels(ctx.partialTick());
	}

	private void transformModels(float partialTicks) {
		boolean upsideDown = this.blockState.getValue(BlockStateProperties.VERTICAL_DIRECTION) == Direction.UP;

		float yaw = this.blockEntity.getYawOffset(partialTicks - 1);
		Quaternionf qyaw = upsideDown
				? Axis.ZP.rotationDegrees(180).mul(Axis.YP.rotationDegrees(yaw))
				: Axis.YP.rotationDegrees(-yaw);
		this.rotatingMount.rotation(qyaw);

		float pitch = this.blockEntity.getPitchOffset(partialTicks - 1);
		Quaternionf qpitch = upsideDown
				? Axis.XP.rotationDegrees(pitch)
				: Axis.XP.rotationDegrees(-pitch);
		Quaternionf qyaw1 = new Quaternionf(qyaw);
		qyaw1.mul(qpitch);
		this.carriageAxle.rotation(qyaw1);

		this.rotatingMount.setChanged();
		this.carriageAxle.setChanged();
	}

	@Override
	public void updateLight(float partialTick) {
		Direction cannonDir = this.blockState.getValue(BlockStateProperties.VERTICAL_DIRECTION).getOpposite();
		BlockPos assemblyCellPos = this.pos.relative(cannonDir);
		this.relight(assemblyCellPos, this.rotatingMount);
		this.relight(assemblyCellPos, this.carriageAxle);
	}

	@Override
	public void collectCrumblingInstances(Consumer<Instance> consumer) {
		consumer.accept(this.rotatingMount);
		consumer.accept(this.carriageAxle);
	}

	@Override
	public void _delete() {
		this.rotatingMount.delete();
		this.carriageAxle.delete();
	}
}

