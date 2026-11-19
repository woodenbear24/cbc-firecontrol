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

import org.joml.Quaternionf;
import org.joml.Vector3f;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Places the external view's camera: keeps the orbit distance out of vanilla's collision walk, and puts the eye
 * off the orbit axis and around the vehicle so the craft holds a constant band in the frame at every zoom.
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
 * <p>Why the position has to be rewritten rather than only the distance: the orbit has to turn around the vehicle
 * rather than around the sailor standing on it, and it can only do that coherently if it is built from the angles
 * the lens actually renders with, so the placement is rebuilt from {@code this.xRot}/{@code this.yRot}. The
 * rotation is written from {@link ExternalViewClientState}'s inertial angles and bound to world axes by
 * {@link #cbcfc$bindRotationToWorld}, because {@code Camera#setRotation} maps the angles it is handed through the
 * ship pose - no choice of arguments through it can pin the lens to the world. The offset off the axis runs along
 * the camera's own up - perpendicular to the
 * lens, never along world Y - which puts the vehicle at a constant angle below the centre of the frame whatever the
 * pitch is, and being off the axis is what makes the pulled-back distance read as a view of the ship instead of a
 * graze across the deck.
 *
 * <p>The pivot is {@link ExternalViewClientState#orbitPivot}: the vehicle's own centre when the view can place it
 * in the world, the rider eye when it cannot. That is the War Thunder shape - the craft stays framed as it pitches
 * and rolls, and a pivot fixed to the vehicle is what keeps it in the band while the wheel zoom changes the orbit
 * radius.
 */
@Mixin(Camera.class)
public abstract class CameraMaxZoomMixin {

	@Shadow
	private float yRot;

	@Shadow
	private float xRot;

	@Shadow
	protected abstract void setPosition(Vec3 position);
	/**
	 * Writes the rotation the lens renders.  Re-added on purpose, but it is not a plain setter here: this build
	 * maps the arguments through the ship pose, so the write pre-inverts them (see the mapping comment in
	 * {@code cbcfc$liftExternalOrbitOffAxis}).  The descriptor must name what the target class really has -
	 * yaw first - or mixin application fails at startup.
	 */
	@Shadow
	protected abstract void setRotation(float yRot, float xRot, float roll);
	/**
	 * The direction the frame renders with. In 1.21.1 Camera holds forwards/up/left as fields that setRotation
	 * rotates in place, and {@code getLookVector()} hands back the same vector - the {@code forwardVector()}
	 * accessor belongs to newer builds, and shadowing it crashed mixin application on startup.
	 */
	@Shadow
	private Vector3f forwards;
	/**
	 * The quaternion {@code setRotation} derives its vectors from, and the other two vectors it derives. The world
	 * binding replaces all four, so the hull's pose cannot reach the lens however the setter maps its arguments.
	 *
	 * <p>Declared without {@code final} on purpose: the target fields are final, but a final shadow is a field
	 * javac demands an initializer for, and Mixin only matches shadows on name and descriptor, not on modifiers.
	 */
	@Shadow
	private Vector3f up;
	@Shadow
	private Vector3f left;
	@Shadow
	private Quaternionf rotation;
	@Shadow
	private float roll;
	/** The un-rotated seeds {@code Camera#setRotation} rotates into the three vectors above (Camera.java L26-28). */
	@Shadow
	private static Vector3f FORWARDS;
	@Shadow
	private static Vector3f UP;
	@Shadow
	private static Vector3f LEFT;

	/**
	 * TEMPORARY, alongside {@code CBCFireControl.DEBUG_CAMERA}: the placement runs every rendered frame, and a log
	 * line plus two {@code String.format} calls per frame measurably costs frames. One line per ~60 frames is enough
	 * to read the numbers. Delete with the debug block.
	 */
	private static int placementLogTicks;

	/**
	 * Binds the camera's orientation to world axes: the quaternion and the three direction vectors are rebuilt
	 * from the inertial yaw and pitch with world Y as the up seed and no roll, which is what
	 * {@code Camera#setRotation} does with vanilla angles (Camera.java L117-125).
	 *
	 * <p>Doing it here rather than through the setter is the point of the binding. The setter maps whatever it is
	 * handed through the ship pose - measured this session, a written yaw comes back out as "yaw + heading - 180"
	 * and a pitch as "pitch - hull pitch" - so arguments can only be pre-inverted against that, and the inversion
	 * is an additive approximation of a quaternion composition: it drifts apart as the hull pitches and rolls.
	 * Overwriting the state afterwards means the hull's rotation cannot reach the lens at all, which is the
	 * world-space orientation being asked for.
	 */
	private void cbcfc$bindRotationToWorld(float yaw, float pitch) {
		this.rotation.rotationYXZ((float) Math.PI - yaw * Mth.DEG_TO_RAD, -pitch * Mth.DEG_TO_RAD, 0.0F);
		FORWARDS.rotate(this.rotation, this.forwards);
		UP.rotate(this.rotation, this.up);
		LEFT.rotate(this.rotation, this.left);
		this.xRot = pitch;
		this.yRot = yaw;
		this.roll = 0.0F;
	}

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
		// Entity#calculateViewVector (L1616-1624) inlined on purpose: that method is exactly what Sable redirects
		// to fold the sub-level pose into the view vector, so the orbit must not call it.
		float mousePitch = mc.player.getViewXRot(partialTick);
		float mouseYaw = mc.player.getViewYRot(partialTick);
		// What the camera carried when vanilla's setup reached us: the composed angle, player yaw plus the hull
		// heading minus 180 (and mouse pitch plus hull pitch), which is why an unwritten camera turns with the hull.
		float beforePitch = this.xRot;
		float beforeYaw = this.yRot;
		// The orientation is bound to world axes rather than to Sable's hull frame, from the view's own inertial
		// pair: the mouse is the only thing that moves those angles, and nothing drifts them back toward the hull.
		// Roll is pinned to zero inside the binding so a heeling hull cannot tilt the horizon.
		float hullHeading = SableShipBounds.hullYRot(mc.player);
		float hullPitch = SableShipBounds.hullXRot(mc.player);
		ExternalViewClientState.updateInertia(this.yRot, mouseYaw, mousePitch);
		float inertiaYaw = ExternalViewClientState.inertiaYaw();
		float inertiaPitch = ExternalViewClientState.inertiaPitch();
		// Written directly rather than through setRotation, whose arguments this build maps through the ship pose
		// (a written yaw came back as yaw + heading - 180, a pitch as pitch - hull pitch, on every debug line).
		cbcfc$bindRotationToWorld(inertiaYaw, inertiaPitch);
		// Read back after the write, so the orbit and the lens are built from the SAME angles. That disagreement
		// is what made the vehicle fly around the screen while the mouse moved.
		float pitch = this.xRot;
		float yaw = this.yRot;
		// The frame renders the vectors the binding derives rather than the fields, so the diagnostic reads
		// the direction the render actually uses and the frame up with it: up.y is cos(pitch) while the
		// roll is world-zero, so any other reading means the hull pose is still reaching the lens. The
		// direction in 1.21.1 is the forwards field itself, rotated in place by setRotation.
		Vector3f rendered = this.forwards;
		float renderedPitch = (float) Math.toDegrees(Math.asin(-rendered.y));
		float renderedYaw = Mth.wrapDegrees(-(float) Math.toDegrees(Math.atan2(rendered.x, rendered.z)));
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
		// Placed directly, not eased: the orbit answers the pivot and the view the camera itself carries,
		// on this frame, so there is no lag that would read as a sensitivity that changes with distance.
		setPosition(wanted);

		if (CBCFireControl.DEBUG_CAMERA && ++placementLogTicks % 60 == 0) {
				CBCFireControl.LOGGER.info("[cbc_firecontrol] external camera placed: before {} / {} deg, inertia {} / {} "
						+ "deg, after {} / {} deg, rendered dir {} / {} deg, frame up y {}, mouse {} / {} deg, hull pitch "
						+ "{} heading {} deg, orbit {}, lift {}, pivot {}, band {} deg below frame centre; the fields, "
						+ "the rendered direction and the frame up must all be world-space, so neither the hull pitch "
						+ "nor its heading may appear in any of them",
						String.format("%.1f", beforePitch), String.format("%.1f", beforeYaw),
						String.format("%.1f", inertiaYaw), String.format("%.1f", inertiaPitch),
						String.format("%.1f", yaw), String.format("%.1f", pitch),
						String.format("%.1f", renderedYaw), String.format("%.1f", renderedPitch),
						String.format("%.2f", this.up.y),
						String.format("%.1f", mousePitch), String.format("%.1f", mouseYaw),
						String.format("%.1f", hullPitch), String.format("%.1f", hullHeading),
						String.format("%.1f", orbit), String.format("%.1f", lift),
						ExternalViewClientState.pivotIsVehicle() ? "vehicle centre" : "rider eye",
						String.format("%.1f", ExternalViewClientState.bandDegrees()));
		}
	}
}