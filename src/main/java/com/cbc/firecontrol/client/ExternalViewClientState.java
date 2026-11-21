package com.cbc.firecontrol.client;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.compat.SableShipBounds;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * The external view: one camera placement, running on whichever perspective the player is standing in.
 *
 * <p>What the view is: the craft held in a band low in the frame, the orbit turned about the craft itself rather
 * than about the sailor standing on it, the mouse turning the lens one-to-one, and the wheel pushing in and out.
 * That is War Thunder's external camera, which is what was asked for instead of vanilla's third person pulled a
 * little further back.
 *
 * <p>Why there are two perspective identifiers and one implementation: {@code CameraType} is an enum, so the view
 * cannot be a fourth constant. Inside a Sable plot it runs on Sable's own unlocked ship perspective, because that
 * perspective places the eye on the pose the hull is actually drawn with - {@code camera.camera_zoom.CameraMixin}
 * writes {@code ClientSubLevel#renderPose().position()} into {@code Camera#setup} - and the complaint being
 * answered was a camera and a hull following two different curves. Outside a plot it must be vanilla's
 * {@code THIRD_PERSON_BACK}: Sable's {@code GameRendererMixin#sable$setupCamera} forces {@code FIRST_PERSON} on
 * every frame whenever the camera entity's vehicle is not inside a sub-level, so a cannon carriage on rails or a
 * Create train cannot borrow its enum at all. What the two identifiers share is everything below:
 * {@link #vehiclePivotFrame(float)} for the point the orbit turns about, {@link #currentDistance()} for its
 * radius, and {@link #orbitLift(float)} for the offset off the axis. Only the radius has two sources - Sable's
 * plot-sized, hull-clamped zoom when its perspective is borrowed, our own measured span otherwise.
 *
 * <p>Nothing here eases or drifts behind anything. The rotation is whatever the lens already carries: on Sable's
 * perspective that is the mouse alone, because its {@code EntitySubLevelRotationHelper} declines to fold the hull
 * pose into the camera of a local player on that perspective, and on borrowed third person it is the player's own
 * view angles - vanilla's behaviour, where a Create seat turns the seated player and a train heading therefore
 * turns the orbit with it. The pivot is interpolated from the vehicle's own previous and current positions exactly
 * as vanilla interpolates an entity, which is what makes a craft that moves 20 times a second placeable on a frame
 * running at 60 to 240 without freezing and jumping; and the radius changes only when the wheel or the measured box
 * changes. The earlier build held an inertial azimuth of its own and ramped both the pivot and the distance across
 * each tick, which was a second smoothing stacked on vanilla's - and it was not the cause of the shake, because a
 * pivot taken from the hull's logical pose travels a different curve than the pose the hull is drawn with, and no
 * ramp over two logical poses can remove that.
 */
public final class ExternalViewClientState {

	/**
	 * Floor and ceiling for the orbit radius. The floor is vanilla's own default (4.0 blocks), so a vehicle
	 * smaller than the player still gets a view that reads as third person; the ceiling keeps the camera inside a
	 * sane chunk radius instead of letting a hundred-block flying machine push it past the render distance.
	 */
	private static final float MIN_DISTANCE = 4.0F;
	private static final float MAX_DISTANCE = 120.0F;

	/**
	 * The framing base, taken from Sable rather than invented here. Its ship camera sizes the orbit as
	 * {@code max(incomingMaxZoom, halfDiagonalOfPlot) * (1.75 + zoomAmount)}
	 * ({@code camera.camera_zoom.CameraMixin#sable$getMaxZoomHead}), so using the same base puts a craft of the
	 * same size at the same distance on either path, and the only difference between them is who measured the box.
	 */
	private static final float ORBIT_BASE = 1.75F;

	/**
	 * How much further out than the craft's own half diagonal the eye is held. Sable protects the same thing by
	 * walking the ray backwards and shortening the orbit when it hits geometry ({@code sable$clampZoom}), which we
	 * cannot do because the view deliberately keeps that walk off the vehicle; expressed as a floor on the radius
	 * it needs no walk at all, and an eye inside the box would otherwise be looking at the inside of the hull.
	 */
	private static final float EYE_MARGIN = 0.5F;

	/**
	 * How far below the centre of the frame the craft is meant to sit, as the tangent of the angle: the off-axis
	 * lift is this times the orbit radius, so zooming moves the offset in step with the radius and the craft holds
	 * the same band either way. 0.55 is about 29 degrees below the centre - low enough in the frame to work the
	 * controls above it, and still fully visible.
	 */
	private static final float SCREEN_BAND_RATIO = 0.55F;

	/**
	 * Wheel zoom range and step, as a multiplier of the framing base. The floor lets you push in close on a small
	 * mount, the ceiling pulls far enough out to see a battleship in context; the result is still floored by
	 * {@link #EYE_MARGIN} and clamped by {@link #MIN_DISTANCE} and {@link #MAX_DISTANCE} afterwards.
	 */
	private static final float ZOOM_MIN = 0.25F;
	private static final float ZOOM_MAX = 6.0F;
	/** One notch of a discrete wheel, as a multiplier, so a notch is the same proportion at any zoom. */
	private static final float ZOOM_STEP = 1.15F;

	/**
	 * Ticks an empty vehicle lookup has to persist before the view ends. A Sable sub-level answers null on
	 * individual ticks while its tracking state is refreshed, so one miss is not evidence that the player left the
	 * deck - and a view that ended itself a few hundred milliseconds after entering is exactly what this sizing
	 * path exists to prevent.
	 */
	private static final int VEHICLE_MISS_GRACE_TICKS = 20;

	/** The two lines Sable prints when its own perspective is entered; ours replaces them (see {@link #takePerspectiveAnnouncement}). */
	private static final String SABLE_UNLOCKED_KEY = "camera_type.sub_level_view_unlocked";
	private static final String SABLE_LOCKED_KEY = "camera_type.sub_level_view";
	/** Our own line for the view: its name, nothing more. */
	private static final String ENTERED_KEY = "cbc_firecontrol.external_view.entered";
	/** The grey Sable's own announcement uses, so the HUD does not change weight when the text does. */
	private static final int ANNOUNCEMENT_COLOR = 0xffaaaaaa;

	private static boolean active;
	/**
	 * True while the view runs on Sable's unlocked ship perspective instead of borrowed third person, decided once
	 * at activation because the perspective must not change underneath the player mid-view. Everything that would
	 * compete with what Sable already does for that camera - its orbit radius, its hull-clamped ray cast, its wheel
	 * - is gated on this.
	 */
	private static boolean sableView;
	/**
	 * One-shot: the perspective key put the player onto Sable's perspective, so the announcement Sable prints for
	 * that switch speaks for our view and belongs to us. Set by {@code MinecraftCameraTypeCycleMixin} before the
	 * perspective is written, consumed by {@code GuiExternalViewMessageMixin} when the line is actually printed,
	 * and dropped by {@link #onClientTick} if it never arrived.
	 */
	private static boolean perspectiveKeySwitch;

	/** The measured size of whatever is aboard, refreshed once a tick; NaN until something has been measured. */
	private static double spanCache = Double.NaN;
	private static double halfDiagCache = Double.NaN;
	/** The radius the last tick produced, for the step log and the diagnostic line only; the frame uses {@link #currentDistance()}. */
	private static float orbitDistance = Float.NaN;
	private static int vehicleMisses;
	/** The wheel's multiplier; reset every time the view is entered, so a new craft starts at its own size. */
	private static float zoomFactor = 1.0F;
	/** True when the pivot comes from a measured vehicle rather than from the rider's eye. */
	private static boolean pivotFromVehicle;
	private static boolean liftLogged;

	private ExternalViewClientState() {
	}

	/** Whether the external placement should run this frame. */
	public static boolean isActive() {
		return active;
	}

	/** Whether the view is running on Sable's unlocked ship perspective, whose camera Sable already places. */
	public static boolean usesSableView() {
		return active && sableView;
	}

	/**
	 * Turns the view on at the size measured right now. Called only from the perspective key, so logging and the
	 * announcement here cannot spam. Radius, lift and pivot source are written out because those three numbers are
	 * the whole definition of the view, and "it never engaged" versus "it engaged on the wrong thing" are only
	 * separable through this line. A rider with nothing aboard does not get the view at all.
	 */
	public static void activate(@Nullable Entity rider) {
		if (active)
			return;
		// Entered at the vehicle's own size, so the wheel starts from the framing the span gives rather than from
		// whatever the last craft was zoomed to.
		zoomFactor = 1.0F;
		float radius = desiredDistance(rider);
		if (Float.isNaN(radius))
			return;
		active = true;
		// Borrowing Sable's perspective is only safe where Sable itself keeps it, which is decided by the ride
		// relation rather than by standing inside a plot - see SableShipBounds#ridesShip.
		sableView = SableCameraViewBridge.unlockedView() != null && SableShipBounds.ridesShip(rider);
		refreshSizing(rider);
		if (sableView) {
			// Sable prints its own line for this switch and the player asked for ours instead, so the switch is
			// flagged for GuiExternalViewMessageMixin rather than announced here: two lines would fight, and the
			// last one written wins.
			perspectiveKeySwitch = true;
			CBCFireControl.LOGGER.info("[cbc_firecontrol] external view on Sable's unlocked perspective "
				+ "(orbit {} blocks would have been ours; Sable sizes it from the plot)",
				String.format("%.1f", radius));
		} else {
			announce(rider);
		}
		if (CBCFireControl.DEBUG_CAMERA)
			CBCFireControl.LOGGER.info("[cbc_firecontrol] external view on, orbit {} blocks, lift {} (+{} deg below "
			+ "frame centre), pivot {}, sized by {}",
			String.format("%.1f", radius), String.format("%.1f", orbitLift(radius)),
			String.format("%.1f", bandDegrees()),
			pivotFromVehicle ? "vehicle centre" : "rider eye", vehicleKind(rider));
	}

	/**
	 * Turns it off, leaving vanilla's third-person radius in place. The reason is logged because three independent
	 * paths end the view (the perspective key, the per-tick aboard check, a size that never measured a vehicle),
	 * and "why did it stop" is otherwise unanswerable from the log.
	 */
	public static void deactivate(String reason) {
		if (!active)
			return;
		active = false;
		sableView = false;
		perspectiveKeySwitch = false;
		spanCache = Double.NaN;
		halfDiagCache = Double.NaN;
		orbitDistance = Float.NaN;
		pivotFromVehicle = false;
		vehicleMisses = 0;
		liftLogged = false;
		ExternalViewTrace.flush();
		CBCFireControl.LOGGER.info("[cbc_firecontrol] external view off ({})", reason);
	}

	/**
	 * The radius the orbit holds this frame: the size measured on the last tick and the wheel as it stands now.
	 * The wheel is read here rather than once a tick so a notch takes effect on the frame it happens, and the box
	 * is not - it does not move when the player spins the wheel, and re-measuring it every frame is a reflective
	 * Sable sub-level lookup 200+ times a second, which is what made the perspective key stutter.
	 */
	public static float currentDistance() {
		if (!active || Double.isNaN(spanCache))
			return Float.NaN;
		return orbitFromSpan(spanCache, halfDiagCache);
	}

	/**
	 * The radius for a rider measured now rather than from the cache: the perspective key has to know whether
	 * there is a vehicle to size against at all, and NaN is how every caller reads "nothing aboard" without
	 * knowing which kind was there.
	 */
	public static float desiredDistance(@Nullable Entity rider) {
		AABB box = vehicleBox(rider);
		if (box == null)
			return Float.NaN;
		return orbitFromSpan(spanOf(box), halfDiagonalOf(box));
	}

	/**
	 * Sable's shape, deliberately: the larger of the craft's longest side and its half diagonal, times the framing
	 * base and the wheel, floored so the eye stays outside the craft and then clamped to the sane range. The half
	 * diagonal is what Sable itself measures (its plot box's {@code extents.scale(0.5).length()}) and it is the
	 * term that keeps a squat but wide hull framed, so the longest side alone is not trusted here either.
	 */
	private static float orbitFromSpan(double span, double halfDiagonal) {
		double reference = Math.max(span, halfDiagonal);
		double outside = halfDiagonal + EYE_MARGIN;
		float orbit = (float) Math.max(reference * ORBIT_BASE * zoomFactor, outside);
		return Mth.clamp(orbit, MIN_DISTANCE, MAX_DISTANCE);
	}

	private static double spanOf(AABB box) {
		return Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize()));
	}

	private static double halfDiagonalOf(AABB box) {
		return 0.5D * Math.sqrt(box.getXsize() * box.getXsize() + box.getYsize() * box.getYsize()
		+ box.getZsize() * box.getZsize());
	}

	/**
	 * The once-a-tick measurement of whatever the rider is aboard, returning false when nothing is. Tick rate is
	 * both cheap enough and the rate at which a ship's footprint actually moves; every per-frame read below comes
	 * from what this stored.
	 */
	private static boolean refreshSizing(@Nullable Entity rider) {
		AABB box = vehicleBox(rider);
		if (box == null)
			return false;
		double span = spanOf(box);
		double halfDiagonal = halfDiagonalOf(box);
		// A box that changes size moves the eye by blocks, which reads as a jerk in the frame, so it is logged
		// when the camera debug flag is on rather than guessed at afterwards from the trace.
		if (CBCFireControl.DEBUG_CAMERA && !Double.isNaN(spanCache) && Math.abs(span - spanCache) > 0.4D)
			CBCFireControl.LOGGER.info("[cbc_firecontrol] measured box stepped {} -> {} blocks long (orbit follows it)",
			String.format("%.1f", spanCache), String.format("%.1f", span));
		spanCache = span;
		halfDiagCache = halfDiagonal;
		orbitDistance = orbitFromSpan(span, halfDiagonal);
		pivotFromVehicle = true;
		return true;
	}

	/**
	 * The bounding box of whatever the rider is aboard, in world space: a Create contraption's own box, or a Sable
	 * ship's world-space footprint. Null when they are on foot.
	 *
	 * <p>Contraption first because it is the cheap direct read, and a flying machine that also sits inside a ship's
	 * footprint should be framed by its own box rather than the ship's. The Sable answer comes from
	 * {@link SableShipBounds}, which resolves the hull reflectively; a hull has no rideable entity, so a sailor
	 * standing on a deck is matched by Sable's tracking and containment lookups instead of by the vehicle chain.
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

	/**
	 * The Create contraption the rider is sitting on, or null when they are not on one. The chain is walked rather
	 * than the immediate mount read because a Create seat entity is itself a vehicle whose vehicle is the
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
	 * The point the orbit turns about this frame, for the path that does not borrow Sable's camera: the vehicle's
	 * own interpolated position when it is an entity, the hull's render pose when it is a Sable sub-level, and null
	 * when neither can be placed - which leaves the eye where vanilla put it, and vanilla already interpolates the
	 * rider it is looking at.
	 *
	 * <p>Interpolating from the vehicle's previous and current tick positions is the same operation vanilla performs
	 * for the entity the camera is attached to ({@code Camera#setup} lerps {@code xo} into {@code getX()}), and it
	 * is what a craft that moves 20 times a second needs on a frame running at up to 240: the raw tick position
	 * freezes the eye for three frames and then jumps by a whole tick of travel. A Sable hull has no such entity
	 * pair, so its render pose is asked for instead, because that is the pose the sub-level renderer transforms
	 * hull geometry with, and it is the only curve the hull and the camera can agree on - a pivot built from
	 * logical poses does not follow it.
	 */
	@Nullable
	public static Vec3 vehiclePivotFrame(float partialTick) {
		if (!active)
			return null;
		Entity rider = Minecraft.getInstance().player;
		AbstractContraptionEntity contraption = riddenContraption(rider);
		if (contraption != null)
			return new Vec3(Mth.lerp(partialTick, contraption.xo, contraption.getX()),
			Mth.lerp(partialTick, contraption.yo, contraption.getY()),
			Mth.lerp(partialTick, contraption.zo, contraption.getZ()));
		return SableShipBounds.renderPoseOrigin(rider);
	}

	/**
	 * The camera's offset off the orbit axis, along the camera's own up rather than along world up. The identity
	 * worth knowing: the eye is placed at {@code pivot - forwards*orbit + up*lift} and looks along
	 * {@code forwards}, so the pivot sits {@code atan(lift/orbit)} below the centre of the FRAME whatever the pitch
	 * is - the craft keeps the same band while you look down at it, which an offset along world Y cannot promise.
	 * Because the lift is a fraction of the orbit, that angle is {@link #SCREEN_BAND_RATIO} at every zoom, so
	 * pushing in or pulling out changes how big the craft is rather than where it sits.
	 */
	public static float orbitLift(float orbit) {
		return SCREEN_BAND_RATIO * orbit;
	}

	/**
	 * The angle the craft holds below the centre of the frame. It is constant because the lift is a fixed fraction
	 * of the orbit radius, which is what makes zooming change the framing without moving the craft up or down the
	 * screen.
	 */
	public static float bandDegrees() {
		return (float) Math.toDegrees(Math.atan(SCREEN_BAND_RATIO));
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
		if (CBCFireControl.DEBUG_CAMERA)
			CBCFireControl.LOGGER.info("[cbc_firecontrol] external zoom x{} -> orbit {} blocks, lift {} blocks",
			String.format("%.2f", zoomFactor),
			String.format("%.1f", currentDistance()),
			String.format("%.1f", orbitLift(currentDistance())));
	}

	/** The wheel's current multiplier, for the diagnostic line. */
	public static float zoomFactor() {
		return zoomFactor;
	}

	/**
	 * Proof that the off-axis lift really reached the camera. The placement that applies it is governed by
	 * {@code injectors.defaultRequire = 0} in this mod's mixin config, so a build where it silently fails to apply
	 * is indistinguishable from one where it applied and simply did not change what the player sees. The first
	 * frame it runs is the only cheap evidence, so it is written once per activation rather than once per frame.
	 */
	public static void noteSableLift(float orbit, float lift) {
		if (liftLogged)
			return;
		liftLogged = true;
		CBCFireControl.LOGGER.info("[cbc_firecontrol] external lift applied on Sable's perspective: orbit {} blocks, "
			+ "lift {} blocks ({} deg below the centre of the frame)",
			String.format("%.1f", orbit), String.format("%.1f", lift), String.format("%.1f", bandDegrees()));
	}

	/**
	 * Whether {@code message} is the announcement Sable prints for the perspective this view borrowed, consuming
	 * the one-shot flag so it only happens for the switch the perspective key caused. Matching on the translation
	 * contents rather than the rendered text means a different language, Sable's own colour, or a mod that added
	 * siblings cannot stop the match, and every other actionbar line - Create's notices, our own messages, a
	 * settings-screen confirmation - passes through untouched.
	 */
	public static boolean takePerspectiveAnnouncement(Component message) {
		if (!perspectiveKeySwitch)
			return false;
		if (!message.getContents().equals(Component.translatable(SABLE_UNLOCKED_KEY).getContents())
			&& !message.getContents().equals(Component.translatable(SABLE_LOCKED_KEY).getContents()))
		return false;
		perspectiveKeySwitch = false;
		return true;
	}

	/** The line the view prints for itself: its name, in the grey Sable's own announcement uses. */
	public static Component externalViewAnnouncement() {
		return Component.translatable(ENTERED_KEY).withColor(ANNOUNCEMENT_COLOR);
	}

	/** Announced on the borrowed-third-person path, where no other mod speaks for the switch. */
	private static void announce(@Nullable Entity rider) {
		if (rider instanceof Player player)
			player.displayClientMessage(externalViewAnnouncement(), true);
	}

	/**
	 * Which sizing source answered, written into the activation line because the two complaints this view draws
	 * are "it never engaged" and "it engaged on the wrong thing", and a bare radius cannot tell them apart.
	 */
	private static String vehicleKind(@Nullable Entity rider) {
		if (riddenContraption(rider) != null)
			return "Create contraption";
		if (SableShipBounds.boxOf(rider) != null)
			return "Sable ship";
		return "nothing aboard";
	}

	/**
	 * The view's once-a-tick upkeep: refresh the measured size, and end the view once the vehicle is gone or the
	 * perspective has moved off one of the two identifiers this view is carried by.
	 *
	 * <p>Falling out of a flying machine into freefall mid-flight would otherwise leave a camera pulled back for a
	 * vehicle that is no longer there, so the view also hands the perspective back to first person. Walking off a
	 * Sable deck does the same, and for the same reason: the ship is still there, but it is no longer what the
	 * player is aboard. The perspective check covers the paths that change it without asking this class: a settings
	 * screen, an accessibility toggle, or Sable's own camera types, which its {@code CameraTypeMixin} splices into
	 * {@code CameraType#cycle} right after {@code THIRD_PERSON_BACK}. The view is carried by
	 * {@code THIRD_PERSON_BACK} or by Sable's unlocked perspective, so any other value means it is already gone and
	 * the placement must not outlive it.
	 *
	 * <p>The announcement flag is dropped here as well: if Sable did not print the line the flag was waiting for,
	 * the flag must not survive to alter a later switch the player caused themselves.
	 */
	public static void onClientTick(Minecraft mc) {
		if (!active || mc.player == null)
			return;
		perspectiveKeySwitch = false;
		CameraType perspective = mc.options.getCameraType();
		if (perspective != CameraType.THIRD_PERSON_BACK && !SableCameraViewBridge.isUnlockedView(perspective)) {
			deactivate("perspective changed elsewhere");
			return;
		}
		if (refreshSizing(mc.player)) {
			vehicleMisses = 0;
			return;
		}
		if (vehicleMisses++ < VEHICLE_MISS_GRACE_TICKS)
			return;
		deactivate("nothing aboard for " + VEHICLE_MISS_GRACE_TICKS + " ticks");
		mc.options.setCameraType(CameraType.FIRST_PERSON);
	}
}
