package com.cbc.firecontrol.mixin.client;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.client.ExternalViewClientState;
import com.cbc.firecontrol.compat.SableShipBounds;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Places the external view's camera: keeps the orbit distance out of vanilla's collision walk, lifts the camera off
 * the vehicle's pitch axis, and drives the orbit pitch from the mouse alone so the hull's own pitch cannot swing it.
 *
 * <p>Why the event override alone is not enough: {@code Camera#setup} feeds the distance that
 * {@code CalculateDetachedCameraDistanceEvent} produced into {@code Camera#getMaxZoom}, and that walk is where
 * the camera gets pulled in to the first surface behind the player. Sable makes the walk ship-aware -
 * {@code camera.camera_zoom.CameraMixin} pushes a pose supplier onto {@code ClientLevel} (its
 * {@code clip_overwrite.ClientLevelMixin}) for every third-person frame that is not one of Sable's own
 * {@code SUB_LEVEL_VIEW} camera types, and redirects the measured distance through
 * {@code Sable.HELPER#distanceSquaredWithSubLevels}. A sailor on a deck therefore has the hull itself behind
 * them, so whatever distance the view asks for is clipped back to the distance to the nearest bulwark or cabin
 * and the external view looks exactly like plain third person - which is the whole complaint this class answers.
 *
 * <p>What is given up: the camera no longer retreats from geometry while the view is active, so a hull bigger
 * than the orbit distance can surround the camera. That is accepted behaviour for an external view - War
 * Thunder's ship camera does the same, and keeping the walk cannot frame the hull at all. The override is
 * scoped to the active view, which only ever runs on {@code THIRD_PERSON_BACK} for the player's own camera, so
 * every other perspective keeps vanilla's collision handling.
 *
 * <p>Injected at HEAD rather than wrapping the call: Sable injects at HEAD on this same method and only cancels
 * for its own camera types, so whichever injector applies first the other stays harmless, and skipping the body
 * cannot leave Sable's pose stack unbalanced because its tail pop is guarded by its own pushed flag.
 *
 * <p>Why the position has to be rewritten rather than only the distance: {@code camera.camera_rotation.EntityMixin}
 * rotates the entity's view vector by the sub-level pose ({@code Pose3dc#transformNormal} on the ship's
 * {@code logicalPose}), which is how hull pitch and roll reach a camera that vanilla would otherwise drive from the
 * mouse. So the orbit direction is rebuilt from the camera's own yaw plus {@code LocalPlayer#getXRot()} - the raw
 * mouse pitch - and the offset upward is applied along <b>world</b> Y, never along the tilted camera up. That is the
 * whole of the decoupling: whatever the hull does, the camera keeps the elevation you asked for, and being off the
 * axis is what makes the pulled-back distance read as a view of the ship instead of a graze across the deck.
 *
 * <p>The point the orbit swings around is {@link ExternalViewClientState#orbitPivot}: the player's eye while the
 * vehicle is on the ground or in the water, sliding onto the vehicle box's centre as it climbs. That is the War
 * Thunder shape for an airborne craft - the whole vehicle stays framed as it pitches and rolls instead of the
 * camera orbiting whichever corner of the deck the player happens to stand on - and it also makes the lift
 * meaningful: the sight line crosses the pivot's plane exactly {@code lift} above it, so the airborne lift is
 * measured to the box's top and the view goes <em>over</em> the craft rather than through it.
 */
@Mixin(Camera.class)
public abstract class CameraMaxZoomMixin {

	@Shadow
	private float yRot;

	@Shadow
	private float xRot;

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float xRot, float yRot);

	/**
	 * TEMPORARY, alongside {@code CBCFireControl.DEBUG_CAMERA}: the placement runs every rendered frame, and a log
	 * line plus two {@code String.format} calls per frame measurably costs frames. One line per ~60 frames is enough
	 * to read the numbers. Delete with the debug block.
	 */
	private static int placementLogTicks;

	/**
	 * The distance arriving here is already what the view asked for: {@code Camera#setup} multiplies the event's
	 * distance by the entity scale, and Create's camera zoom multiplier is divided back out in
	 * {@code ExternalViewEvents}, so returning the argument unchanged puts the camera exactly
	 * {@code ExternalViewClientState#currentDistance()} blocks behind the player.
	 */
	@Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
	private void cbcfc$keepExternalOrbitDistance(float maxZoom, CallbackInfoReturnable<Float> cir) {
		if (ExternalViewClientState.isActive())
			cir.setReturnValue(maxZoom);
	}

	/**
	 * Rebuilds the camera position at the end of {@code setup}, after vanilla's positioning, Sable's zoom and
	 * Create's camera code have all run, so this is the last word on where the eye sits.
	 *
	 * <p>Restricted to the un-flipped detached camera on the player: the mirrored view runs the distance negative,
	 * and a camera whose entity is not the player belongs to whatever is being spectated.
	 */
	@Inject(method = "setup", at = @At("TAIL"))
	private void cbcfc$liftExternalOrbitOffAxis(BlockGetter level, Entity entity, boolean detached,
			boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
		if (!detached || thirdPersonReverse || !ExternalViewClientState.isActive())
			return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || entity != mc.player)
			return;
		float distance = ExternalViewClientState.currentDistance();
		if (Float.isNaN(distance))
			return;

		Vec3 eye = mc.player.getEyePosition(partialTick);
		// The orbit turns around the vehicle itself, refreshed once a tick, so the hull rather than the player
		// holds the screen band. Null only before the view has measured one, in which case the eye is the pivot
		// and the framing is vanilla's own third-person shape.
		Vec3 pivot = ExternalViewClientState.orbitPivot();
		if (pivot == null)
			pivot = eye;
		float lift = ExternalViewClientState.orbitLift(distance);
		// The lift is a fraction of the orbit, so the band angle is the same at every zoom: wheel zoom changes
		// how big the vehicle is in the frame, not where it sits in it.
		float orbit = distance;
		// Entity#calculateViewVector (L1616-1624) inlined on purpose: that method is exactly what Sable
		// redirects to fold the sub-level pose into the view vector, so the orbit must not call it. The
		// rotation is forced to the interpolated mouse view first - on a Sable sub-level the camera's own
		// xRot otherwise carries the hull pitch - and the placement is then built from whatever the camera
		// field actually ends up holding, so the orbit and the lens come from the SAME angles and cannot
		// disagree. That disagreement is what made the vehicle fly around the screen while the mouse moved:
		// the orbit was sized from the mouse pitch while the lens rendered something else. Interpolated
		// (getViewXRot/getViewYRot) is still 1:1 with the mouse - the same value sampled once a frame like
		// vanilla does, with no filter, no easing and no dead zone.
		float mousePitch = mc.player.getViewXRot(partialTick);
		float mouseYaw = mc.player.getViewYRot(partialTick);
		setRotation(mousePitch, mouseYaw);
		float pitch = this.xRot;
		float yaw = this.yRot;
		float pitchRad = pitch * Mth.DEG_TO_RAD;
		float yawRad = -yaw * Mth.DEG_TO_RAD;
		float horizontal = Mth.cos(pitchRad);
		Vec3 forward = new Vec3(Mth.sin(yawRad) * horizontal, -Mth.sin(pitchRad), Mth.cos(yawRad) * horizontal);
		// Behind the pivot exactly as vanilla's detached camera does it (Camera#setup calls move with a
		// negative distance), with the off-axis amount along the camera's own up, meaning perpendicular to
		// the lens rather than along world Y. Measured that way the pivot sits atan(lift/orbit) below the
		// centre of the FRAME at every pitch, so looking straight down keeps the craft in the same band
		// instead of sliding it off the screen, which is the War Thunder framing being asked for.
		double lateral = Math.sqrt(forward.x * forward.x + forward.z * forward.z);
		Vec3 frameUp = lateral < 1.0E-6F
				? new Vec3(1.0D, 0.0D, 0.0D)
				: new Vec3(-forward.x * forward.y / lateral, lateral, -forward.z * forward.y / lateral);
		Vec3 wanted = pivot.add(forward.scale(-orbit)).add(frameUp.scale(lift));
		// Placed directly, not eased: the orbit answers the pivot and the mouse on this frame, so there is
		// no lag that would read as a sensitivity that changes with distance.
		setPosition(wanted);

		if (CBCFireControl.DEBUG_CAMERA && ++placementLogTicks % 60 == 0) {
			CBCFireControl.LOGGER.info("[cbc_firecontrol] external camera placed: camera pitch {} yaw {} deg "
					+ "(mouse {} / {} deg), hull {} deg, orbit {}, lift {}, pivot {}, band {} deg below frame "
					+ "centre; the orbit is built from the rotation the camera itself carries",
					String.format("%.1f", pitch), String.format("%.1f", yaw),
					String.format("%.1f", mousePitch), String.format("%.1f", mouseYaw),
					String.format("%.1f", SableShipBounds.hullXRot(mc.player)),
					String.format("%.1f", orbit), String.format("%.1f", lift),
					ExternalViewClientState.pivotIsVehicle() ? "vehicle centre" : "rider eye",
					String.format("%.1f", ExternalViewClientState.bandDegrees()));
		}
	}
}