package com.cbc.firecontrol.client;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.compat.SableShipBounds;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * The external view: a fourth entry in the perspective cycle, holding a third-person camera pulled far enough
 * back to frame the whole vehicle the player rides or is aboard, the way War Thunder's external cameras do.
 *
 * <p>{@code Camera#setup} computes its detached distance through
 * {@code ClientHooks.getDetachedCameraDistance} (which is
 * {@link net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent}) and feeds
 * {@code distance * entityScale} into {@code Camera#getMaxZoom}, where the block-collision walk happens. The
 * event therefore moves the orbit radius and keeps that walk for free, and {@code CameraMaxZoomMixin} places the
 * camera itself so the orbit turns around the vehicle rather than around the player.
 *
 * <p>The state is the flag below, the orbit distance, and the pivot: the perspective is carried by vanilla's
 * {@code CameraType.THIRD_PERSON_BACK}, because {@code CameraType} is an enum and a fourth constant cannot be
 * added without replacing it wholesale. {@code MinecraftCameraTypeCycleMixin} slots this flag into the F5 cycle.
 *
 * <p>Nothing here filters or eases anything. The frame answers the mouse and the vehicle as they are, which is
 * what keeps the look sensitivity 1:1 at every zoom instead of easing into it, and the pivot is chosen so that a
 * pitching or rolling hull cannot translate it (see {@link #refreshPivot}) - there is therefore no damping left
 * to lag behind the craft or to swallow a small mouse movement.
 */
public final class ExternalViewClientState {

	/**
	 * Floor and ceiling for the orbit distance. The floor is vanilla's own default (4.0 blocks), so a vehicle
	 * smaller than the player still gets a view that reads as third person; the ceiling keeps the camera inside
	 * a sane chunk radius instead of letting a hundred-block flying machine push it past the render distance.
	 */
	private static final float MIN_DISTANCE = 4.0F;
	private static final float MAX_DISTANCE = 120.0F;

	/**
	 * How far behind the vehicle the camera sits, measured in the longest side of its bounding box. One full
	 * side length is what puts the whole vehicle in frame from a normal rear angle: less clips the far end out
	 * of view, more wastes half the screen on sky.
	 */
	private static final float SPAN_FACTOR = 1.0F;

	/**
	 * How far below the centre of the frame the vehicle is meant to sit, as the tangent of the angle: the
	 * off-axis lift is this times the orbit distance, so zooming moves the offset in step with the distance and
	 * the vehicle holds the same band either way. 0.55 is about 29 degrees below the centre - low enough in the
	 * frame to work the controls above it, and still fully visible. Raise it toward the bottom edge, lower it
	 * toward the middle.
	 */
	private static final float SCREEN_BAND_RATIO = 0.55F;

	/**
	 * Wheel zoom range, as a multiplier of the span-derived distance. The floor lets you push in close on a
	 * small mount, the ceiling pulls far enough out to see a battleship in context; the result is still clamped
	 * by {@link #MIN_DISTANCE} and {@link #MAX_DISTANCE} afterwards.
	 */
	private static final float ZOOM_MIN = 0.25F;
	private static final float ZOOM_MAX = 6.0F;
	/** One notch of a discrete wheel, as a multiplier, so a notch is the same proportion at any zoom. */
	private static final float ZOOM_STEP = 1.15F;

	/**
	 * Ticks an empty vehicle lookup has to persist before the view ends. A Sable sub-level answers null on
	 * individual ticks while its tracking state is refreshed, so one miss is not evidence that the player left
	 * the deck - and a view that ended itself a few hundred milliseconds after entering is precisely the
	 * complaint this sizing path exists to answer.
	 */
	private static final int VEHICLE_MISS_GRACE_TICKS = 20;

	private static boolean active;
	/**
	 * The orbit distance the view is running on: the last good measurement, kept across empty lookups so a
	 * transient miss cannot drop the camera back to vanilla's short third-person distance.
	 */
	private static float orbitDistance = Float.NaN;
	private static int vehicleMisses;
	/** The wheel's multiplier on the span-derived distance; reset every time the view is entered. */
	private static float zoomFactor = 1.0F;
	/**
	 * The pivot the orbit turns around, refreshed by {@link #refreshPivot}: the vehicle's own centre when it can
	 * be placed in the world, the rider eye when it cannot. Null until the view has refreshed one.
	 */
	@Nullable
	private static Vec3 pivotPoint;
	/** True when {@link #pivotPoint} came from a vehicle rather than from the eye. */
	private static boolean pivotFromVehicle;

	private ExternalViewClientState() {
	}

	/** Whether the external distance should apply to the detached camera this frame. */
	public static boolean isActive() {
		return active;
	}

	/**
	 * Turns the view on at the size measured right now, and seeds the pivot so the first frame already orbits the
	 * vehicle instead of the eye. Called only from the perspective key, so logging here cannot spam. Distance,
	 * lift and pivot source are all written out because those three numbers are the whole definition of the view,
	 * and "it never engaged" versus "it engaged on the wrong thing" are only separable through this line. A rider
	 * with nothing aboard does not get the view at all.
	 */
	public static void activate(@Nullable Entity rider) {
		if (active)
			return;
		// Entered at the vehicle's own size, so the wheel starts from the framing the span gives rather than
		// from whatever the last ship was zoomed to.
		zoomFactor = 1.0F;
		float distance = desiredDistance(rider);
		if (Float.isNaN(distance))
			return;
		active = true;
		orbitDistance = distance;
		vehicleMisses = 0;
		Vec3 eye = rider == null ? Vec3.ZERO : rider.getEyePosition(1.0F);
		refreshPivot(rider, eye);
		CBCFireControl.LOGGER.info("[cbc_firecontrol] external view on, orbit {} blocks, lift {} (+{} deg below "
				+ "frame centre), pivot {}, sized by {}",
				String.format("%.1f", distance), String.format("%.1f", orbitLift(distance)),
				String.format("%.1f", bandDegrees()),
				pivotFromVehicle ? "vehicle centre" : "rider eye", vehicleKind(rider));
	}

	/**
	 * Turns it off, leaving vanilla's third-person distance in place. The reason is logged because three
	 * independent paths end the view (the perspective key, the per-tick aboard check, a distance that never
	 * measured a size), and "why did it stop" is otherwise unanswerable from the log.
	 */
	public static void deactivate(String reason) {
		if (!active)
			return;
		active = false;
		orbitDistance = Float.NaN;
		pivotPoint = null;
		pivotFromVehicle = false;
		vehicleMisses = 0;
		CBCFireControl.LOGGER.info("[cbc_firecontrol] external view off ({})", reason);
	}

	/**
	 * What the running view asks the camera for. Deliberately the cached measurement rather than a fresh one:
	 * {@code CalculateDetachedCameraDistanceEvent} fires once per rendered frame, and {@link #vehicleBox} costs a
	 * reflective Sable sub-level lookup on a deck, so re-measuring here would run a sub-level search 200+ times a
	 * second - which is what made the perspective key stutter. {@link #onClientTick} refreshes the value once a
	 * tick instead, which is also the resolution at which a ship's motion actually updates.
	 *
	 * <p>NaN only when the view is off or has never measured anything.
	 */
	public static float currentDistance() {
		return active ? orbitDistance : Float.NaN;
	}

	/**
	 * The Create contraption the rider is sitting on, or null when they are not on one. The chain is walked
	 * rather than the immediate mount read because a Create seat entity is itself a vehicle whose vehicle is the
	 * contraption - the same walk {@code HeadAimClientEvents.ridingContraptionExit} does. A Sable hull is not a
	 * contraption and never appears in this chain, which is exactly why {@link #vehicleBox} exists.
	 */
	@Nullable
	public static AbstractContraptionEntity riddenContraption(@Nullable Entity rider) {
		Entity vehicle = rider == null ? null : rider.getVehicle();
		while (vehicle != null) {
			if (vehicle instanceof AbstractContraptionEntity contraption)
				return contraption;
			vehicle = vehicle.getVehicle();
		}
		return null;
	}

	/**
	 * The bounding box of whatever the rider is aboard, in world space: a Create contraption's own box, or a
	 * Sable ship's world-space footprint. Null when they are on foot.
	 *
	 * <p>Contraption first because it is the cheap direct read, and a flying machine that also sits inside a
	 * ship's footprint should be framed by its own box rather than by the ship's. The Sable answer comes from
	 * {@link SableShipBounds}, which resolves the hull reflectively; a hull has no rideable entity, so a sailor
	 * standing on a deck is matched by Sable's tracking / containment lookup instead of by the vehicle chain.
	 *
	 * <p>This is the single source of both numbers the view needs: {@link #vehicleSpan} frames the orbit distance
	 * from the longest side, and {@link #refreshPivot} takes the centre from the same box.
	 */
	@Nullable
	public static AABB vehicleBox(@Nullable Entity rider) {
		if (rider == null)
			return null;
		AbstractContraptionEntity contraption = riddenContraption(rider);
		if (contraption != null)
			return contraption.getBoundingBox();
		return SableShipBounds.boxOf(rider);
	}

	/** The longest side of {@link #vehicleBox}, NaN when nothing is aboard. */
	public static double vehicleSpan(@Nullable Entity rider) {
		AABB box = vehicleBox(rider);
		if (box == null)
			return Double.NaN;
		return Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize()));
	}

	/**
	 * The orbit distance for a rider: their vehicle's longest side times {@link #SPAN_FACTOR} and the wheel's
	 * {@code zoomFactor}, bounded by {@link #MIN_DISTANCE} and {@link #MAX_DISTANCE}. NaN when there is nothing
	 * to size against, which is how every caller reads "no vehicle" without knowing which kind was there.
	 */
	public static float desiredDistance(@Nullable Entity rider) {
		double span = vehicleSpan(rider);
		if (Double.isNaN(span))
			return Float.NaN;
		return Mth.clamp((float) (span * SPAN_FACTOR * zoomFactor), MIN_DISTANCE, MAX_DISTANCE);
	}

	/**
	 * The angle the vehicle is meant to hold below the centre of the frame. It is constant because the lift is a
	 * fixed fraction of the orbit distance, which is what makes zooming change the framing without moving the
	 * vehicle up or down the screen.
	 */
	public static float bandDegrees() {
		return (float) Math.toDegrees(Math.atan(SCREEN_BAND_RATIO));
	}

	/**
	 * The point the orbit swings around: the vehicle itself when the view can place it in the world, the rider
	 * eye when it cannot.
	 *
	 * <p>A Create contraption turns on yaw only, so its entity position is the centre it turns about. A Sable
	 * hull is sized by its bounding box, and the preferred pivot is the ship's rotation centre (its pose
	 * origin): a box centre is rotation-sensitive, because a hull rolling ten degrees changes its own world box
	 * and moves that centre, so the frame would swim with the roll, while the point a hull turns about is fixed
	 * under pitch and roll by definition. That is the margin the camera keeps between the vehicle's own
	 * pitch/roll and its forced pitch - the craft can rotate as far as it likes without the pivot translating,
	 * and no damping is needed to get that. A rotation centre that cannot be placed in the world is not
	 * evidence that there is no vehicle, only that this particular answer is unusable, so the box centre is
	 * tried next and the eye only after that fails too.
	 *
	 * <p>Refreshed once a client tick rather than per frame: the reads behind it are a reflective Sable sub-level
	 * lookup, while the camera event runs every rendered frame. Tick rate is also the rate at which a hull pose
	 * actually updates, so a cached pivot costs nothing a live one would have known.
	 */
	public static void refreshPivot(@Nullable Entity rider, Vec3 eye) {
		Vec3 pivot = eye;
		boolean fromVehicle = false;
		AbstractContraptionEntity contraption = riddenContraption(rider);
		AABB box = vehicleBox(rider);
		Vec3 centre = contraption != null ? contraption.position() : SableShipBounds.rotationCentre(rider);
		if (box != null && (centre == null || !seesEye(centre, box, eye)))
			centre = box.getCenter();
		if (centre != null && box != null && seesEye(centre, box, eye)) {
			pivot = centre;
			fromVehicle = true;
		}
		pivotPoint = pivot;
		pivotFromVehicle = fromVehicle;
	}

	/**
	 * Whether a candidate pivot can see the rider eye. Someone aboard a vehicle is inside its box, so a centre
	 * further from the eye than the box's own diagonal is not a world-space point - Sable answers the hull's box
	 * and its pose from a plot whose origin may itself be local - and orbiting there would frame empty sky,
	 * which is the failure mode the eye fallback exists for.
	 */
	private static boolean seesEye(Vec3 centre, AABB box, Vec3 eye) {
		double reach = box.getXsize() * box.getXsize() + box.getYsize() * box.getYsize()
				+ box.getZsize() * box.getZsize();
		return centre.distanceToSqr(eye) <= reach;
	}

	/** The pivot the orbit ran on at the last tick; null before the view has refreshed one. */
	@Nullable
	public static Vec3 orbitPivot() {
		return pivotPoint;
	}

	/** Whether {@link #orbitPivot()} is a measured vehicle centre rather than the rider eye. */
	public static boolean pivotIsVehicle() {
		return pivotFromVehicle;
	}

	/**
	 * The camera's offset off the orbit axis, along the camera's own up rather than along world up. The identity
	 * worth knowing: the camera is placed at {@code pivot - forward*orbit + frameUp*lift} and looks along
	 * {@code forward}, so the pivot sits {@code atan(lift/orbit)} below the centre of the FRAME whatever the
	 * pitch is - the vehicle keeps the same band while you look down at it, which an offset along world Y
	 * cannot promise. Because the lift is a fraction of the orbit, that angle is {@link #SCREEN_BAND_RATIO} at
	 * every zoom, so pushing in or pulling out changes how big the craft is rather than where it sits.
	 */
	public static float orbitLift(float orbit) {
		return SCREEN_BAND_RATIO * orbit;
	}

	/**
	 * Wheel zoom. {@code amount} is vanilla's own processed delta (discrete scroll and wheel sensitivity already
	 * applied by the caller), so a notch is +1 or -1 and a free-spinning wheel moves proportionally. Multiplying
	 * rather than adding is what keeps the step the same size at 6 blocks as at 60.
	 */
	public static void applyWheelZoom(double amount) {
		if (!active || amount == 0.0D)
			return;
		zoomFactor = Mth.clamp(zoomFactor * (float) Math.pow(ZOOM_STEP, amount), ZOOM_MIN, ZOOM_MAX);
		CBCFireControl.LOGGER.info("[cbc_firecontrol] external zoom x{} -> orbit {} blocks, lift {} blocks",
				String.format("%.2f", zoomFactor),
				String.format("%.1f", Float.isNaN(orbitDistance) ? 0.0F : desiredDistance(Minecraft.getInstance().player)),
				String.format("%.1f", orbitLift(Float.isNaN(orbitDistance) ? 0.0F : desiredDistance(Minecraft.getInstance().player))));
	}

	/** The wheel's current multiplier, for the diagnostic line. */
	public static float zoomFactor() {
		return zoomFactor;
	}

	/**
	 * Which sizing source answered, written into the activation line because the two complaints this view draws
	 * are "it never engaged" and "it engaged on the wrong thing", and a bare distance cannot tell them apart.
	 */
	private static String vehicleKind(@Nullable Entity rider) {
		if (riddenContraption(rider) != null)
			return "Create contraption";
		if (SableShipBounds.boxOf(rider) != null)
			return "Sable ship";
		return "nothing aboard";
	}

	/**
	 * The view's once-a-tick upkeep: refresh the distance and the pivot, and end the view once the vehicle is
	 * gone or the perspective has moved off the camera type the view borrows.
	 *
	 * <p>Falling out of a flying machine into freefall mid-flight would otherwise leave a camera pulled back for
	 * a vehicle that is no longer there, so the view also hands the perspective back to first person, which is
	 * what the player is looking through once they are on foot. Walking off a Sable deck does the same, and for
	 * the same reason: the ship is still there, but it is no longer what the player is aboard. The first check
	 * covers the paths that change the perspective without asking this class: a settings screen, an accessibility
	 * toggle, or Sable's own camera types ({@code SUB_LEVEL_VIEW}, {@code SUB_LEVEL_VIEW_UNLOCKED}), which Sable's
	 * {@code CameraTypeMixin} splices into {@code CameraType#cycle} right after {@code THIRD_PERSON_BACK}. The
	 * flag is carried by {@code THIRD_PERSON_BACK}, so any other value means the view is already gone and the
	 * distance override must not outlive it.
	 *
	 * <p>Measurement lives here rather than in the camera event because the event runs every frame; one tick is
	 * both cheap enough and the rate at which a ship's footprint actually moves.
	 */
	public static void onClientTick(Minecraft mc) {
		if (!active || mc.player == null)
			return;
		if (mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) {
			deactivate("perspective changed elsewhere");
			return;
		}
		float fresh = desiredDistance(mc.player);
		if (!Float.isNaN(fresh)) {
			orbitDistance = fresh;
			refreshPivot(mc.player, mc.player.getEyePosition(1.0F));
			vehicleMisses = 0;
			return;
		}
		if (vehicleMisses++ < VEHICLE_MISS_GRACE_TICKS)
			return;
		deactivate("nothing aboard for " + VEHICLE_MISS_GRACE_TICKS + " ticks");
		mc.options.setCameraType(CameraType.FIRST_PERSON);
	}
}