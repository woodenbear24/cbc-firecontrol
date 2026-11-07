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
 * Positioning (local-coordinate system):
 *   Flywheel orients an instance as  world = origin + pivot + R*(v - pivot), so the flip/rotation
 *   happens about the point (origin + pivot). Both instances use origin = mount-cell mid-plane
 *   corner (pos.x, pos.y+0.5, pos.z) with an EXPLICIT pivot (0.5, 0.0, 0.5), so origin+pivot lands
 *   exactly on the mount CELL CENTER (pos+0.5,0.5,0.5) for BOTH orientations. The inverted mount's
 *   Z-180 flip (see transformModels) then mirrors the whole cradle through the cell center - this
 *   is what fixes the "turntable floats off to the side / on the wrong half" bug in the inverted
 *   screenshots (Flywheel's DEFAULT pivot (0.5,0.5,0.5) put the center at the cell TOP pos.y+1.0,
 *   so the flip orbited the cradle the wrong way). The model is authored with the plate at model
 *   Y 0, so the mid-plane origin seats the plate on the plinth surface whether upright (lower-half
 *   top) or flipped (upper-half bottom). The carriage axle uses the same origin+pivot, so its model
 *   Y 6..10 mirrors through the cell center right along with the comb opening it sits in - aligned
 *   in both orientations with AXLE_LOCAL_LIFT=0 (no per-orientation world-Y hack).
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

		// Flywheel orients an instance as world = position + pivot + R*(v - pivot); the rotation and
		// the upside-down flip all act about (position + pivot).
		//
		// The cradle (fire_control_rotating) and the carriage axle (carriage_axle) are ONE physical
		// assembly whose single pivot is the cannon trunnion / shaft axis: the horizontal shaft line
		// (model Y8,Z8) crossed by the vertical yaw axis (model X8,Z8) = block (0.5, 0.5, 0.5) in BOTH
		// partials' local model space (their geometry centres). They must rotate about that same point.
		//
		// CRADLE: seats the turntable plate (model Y0) on the plinth inner face. position = mid-plane
		// corner (pos.y + 0.5), pivot (0.5, 0, 0.5) => centre at the CELL CENTRE, so the qyaw Z-180
		// mirrors the cradle through the cell mid-plane in both orientations (plate on plinth; comb /
		// shaft at cell top when upright, cell bottom when inverted).
		//
		// AXLE: must PITCH about the shaft itself, so its pivot sits ON the shaft line = its own
		// geometry centre (block 0.5,0.5,0.5). Then (shaft - pivot) = 0, so qyaw1's pitch spins the
		// axle about its own shaft and the SHAFT MIDPOINT STAYS FIXED - the axle only rotates, it
		// never translates out of the comb (the drift was because the old pivot Y=0 left the shaft 0.5
		// below the centre, so pitch swung it sideways). position is chosen so position + pivot lands on
		// the CANNON-BODY centre = assembly-cell centre, mirroring CBC whose carriage axle sits half a
		// block INTO the assembly cell: upright => pos+1.5 (offset +1.0), inverted => pos-0.5 (offset
		// -1.0). The mount cradle fork prongs span pos+0.94..1.75, so the shaft also stays gripped by
		// the comb. Yaw is shared because it is about the same vertical line (x .5, z .5).
		boolean upsideDown = this.blockState.getValue(BlockStateProperties.VERTICAL_DIRECTION) == Direction.UP;
		float originX = this.getVisualPosition().getX();
		float originZ = this.getVisualPosition().getZ();

		this.rotatingMount = instancerProvider()
				.instancer(InstanceTypes.ORIENTED, Models.partial(CBCFCBlockPartials.FIRE_CONTROL_ROTATING))
				.createInstance()
				.position(originX, this.getVisualPosition().getY() + 0.5f, originZ)
				.pivot(0.5f, 0.0f, 0.5f);

		this.carriageAxle = instancerProvider()
				.instancer(InstanceTypes.ORIENTED, Models.partial(CBCFCBlockPartials.CARRIAGE_AXLE))
				.createInstance()
				.position(originX, this.getVisualPosition().getY() + (upsideDown ? -1.0f : 1.0f), originZ)
				.pivot(0.5f, 0.5f, 0.5f);

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

