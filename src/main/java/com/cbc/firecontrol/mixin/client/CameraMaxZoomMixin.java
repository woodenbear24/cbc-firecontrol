package com.cbc.firecontrol.mixin.client;

import com.cbc.firecontrol.client.ExternalViewClientState;
import com.cbc.firecontrol.client.ExternalViewTrace;
import com.cbc.firecontrol.client.SableCameraViewBridge;

import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3f;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Places the external view's eye: one placement, reached on whichever of the two perspective identifiers the view
 * is carried by, and differing only in who measured the orbit radius.
 *
 * <p>What vanilla does: {@code Camera#setup} writes the eye at the entity's interpolated position plus its eye
 * height, then retreats it by {@code move(-getMaxZoom(eventDistance * scale), 0, 0)}. {@code Camera#move} rotates
 * {@code (dx, dy, -zoom)} by the camera's own quaternion, so the eye lands at {@code position + forwards*-zoom +
 * up*dy} - which is the shape this class reuses rather than recomputes: the orbit is taken along the vectors the
 * lens renders with, so the placement and the direction cannot disagree, and the offset off the axis rides the
 * camera's own up, which is what holds the craft at {@code atan(lift/orbit)} below the centre of the frame at any
 * pitch.
 *
 * <p>Why the radius has two sources. On Sable's unlocked perspective the retreat it hands over is already what we
 * want: its {@code camera.camera_zoom.CameraMixin} sizes the orbit from the plot's bounding box and clamps it
 * against the hull ({@code sable$getMaxZoomHead}, {@code sable$clampZoom}), and its own {@code sable$setup} has
 * already written the hull's <em>render</em> pose as the starting point. Taking both, and adding only the offset off
 * the axis, is the whole contribution on that path - rebuilding the pivot from a logical pose is exactly the second
 * curve that made the ship swim against the camera. On borrowed third person nothing else sizes the view, so the
 * radius comes from the measured box and the pivot from {@link ExternalViewClientState#vehiclePivotFrame(float)},
 * and the collision walk is skipped (see {@link #cbcfc$keepExternalOrbitDistance}) because a sailor on a deck has
 * the hull itself behind them: asking for more cannot help, since the walk returns the nearest hit.
 *
 * <p>What is given up: while the view is active the camera no longer retreats from geometry. That is accepted
 * behaviour for an external view - a hull or a train larger than the orbit can surround the eye - and the radius is
 * floored outside the measured box ({@code EYE_MARGIN}) so it cannot sit inside the craft it frames. Everything is
 * scoped to the player's own un-flipped detached camera on one of the two identifiers, so first person, mirrored
 * third person, a spectator camera and every other perspective keep vanilla's handling untouched.
 */
@Mixin(Camera.class)
public abstract class CameraMaxZoomMixin {

	/** The eye's position; on Sable's perspective this is already the hull's render-pose origin. */
	@Shadow
	private Vec3 position;
	/** The entity the camera is attached to - the view speaks only for the player's own camera. */
	@Shadow
	private Entity entity;
	/** The direction the lens renders with, and its own up; both are unit vectors rotated by the camera's rotation. */
	@Shadow
	private Vector3f forwards;
	@Shadow
	private Vector3f up;
	@Shadow
	private float xRot;
	@Shadow
	private float yRot;
	/** The retreat vanilla performs; shadowed so the redirect can still reach it for the cases that are not ours. */
	@Shadow
	protected abstract void move(float zoom, float dy, float dx);
	/** Shadowed because {@code Camera} is not this class at compile time, only at runtime. */
	@Shadow
	protected abstract void setPosition(Vec3 position);
	/** The partial tick {@code setup} was called with, which the pivot interpolation reads. */
	@Shadow
	public abstract float getPartialTickTime();

	/**
	 * Keeps vanilla's collision walk off the vehicle on the path that sizes the orbit itself.
	 *
	 * <p>Returning the argument unchanged is what skips the walk: {@code getMaxZoom} would otherwise clip the distance
	 * to the first surface behind the eye, and a deck has the whole hull behind it, so the orbit would be cut back to
	 * plain third person. Sable's own {@code getMaxZoom} injections are left alone because they only act for its camera
	 * types, and whichever HEAD injector runs first the other stays harmless.
	 */
	@Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
	private void cbcfc$keepExternalOrbitDistance(float maxZoom, CallbackInfoReturnable<Float> cir) {
		if (ExternalViewClientState.isActive() && !ExternalViewClientState.usesSableView())
			cir.setReturnValue(maxZoom);
	}

	/**
	 * The single placement, replacing vanilla's retreat while the view is active.
	 *
	 * <p>The guards are the whole scope of the override. A non-zero {@code dy} means something else already owns that
	 * axis - vanilla uses it for the sleeping-camera offset - and a non-negative {@code zoom} means the distance arrived
	 * negated, which is the mirrored perspective; lifting along the camera's up there would put the craft above the
	 * frame instead of below it. Anything that is not the player's own camera belongs to whatever is being spectated.
	 */
	@Redirect(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;move(FFF)V"))
	private void cbcfc$placeExternalOrbit(Camera instance, float zoom, float dy, float dx) {
		if (dy != 0.0F || zoom >= 0.0F || !ExternalViewClientState.isActive()) {
			this.move(zoom, dy, dx);
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || this.entity != mc.player) {
			this.move(zoom, dy, dx);
			return;
		}
		boolean sable = ExternalViewClientState.usesSableView();
		CameraType perspective = mc.options.getCameraType();
		if (sable ? !SableCameraViewBridge.isUnlockedView(perspective)
			: perspective != CameraType.THIRD_PERSON_BACK) {
			this.move(zoom, dy, dx);
			return;
		}
		float orbit;
		Vec3 pivot;
		if (sable) {
			orbit = -zoom;
			pivot = this.position;
		} else {
			orbit = ExternalViewClientState.currentDistance();
			if (Float.isNaN(orbit)) {
				this.move(zoom, dy, dx);
				return;
			}
			Vec3 measured = ExternalViewClientState.vehiclePivotFrame(this.getPartialTickTime());
			pivot = measured == null ? this.position : measured;
		}
		float lift = ExternalViewClientState.orbitLift(orbit);
		Vec3 wanted = pivot.add(new Vec3(this.forwards).scale(-orbit)).add(new Vec3(this.up).scale(lift));
		setPosition(wanted);
		if (sable)
			ExternalViewClientState.noteSableLift(orbit, lift);
		// One row per frame on either path, so the two are comparable on the same axis and a curve mismatch can be told
		// apart from a rotation problem without a second instrumentation pass.
		ExternalViewTrace.record(sable ? "sable-unlocked" : "own-orbit", this.getPartialTickTime(), orbit, pivot,
			wanted, this.yRot, this.xRot, mc);
	}
}
