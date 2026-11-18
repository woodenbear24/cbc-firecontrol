package com.cbc.firecontrol.client;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.compat.SableShipBounds;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * The external view: a fourth entry in the perspective cycle that is simply a third-person camera pulled far
 * enough back to see the whole vehicle the player is riding or aboard.
 *
 * <p>Why no camera mixin is needed: {@code Camera#setup} computes its detached distance as
 * {@code ClientHooks.getDetachedCameraDistance(camera, flipped, entityScale, 4.0)} (which is
 * {@link net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent}), then feeds
 * {@code distance * entityScale} into {@code Camera#getMaxZoom}, where the block-collision walk that stops the
 * camera from burying itself inside terrain happens. Setting the event's distance therefore moves the orbit
 * radius and keeps that collision walk for free, and the camera keeps pointing wherever the player's mouse
 * points - which is exactly what an external view has to do here, since a free-looking camera would need the
 * crosshair and the head-aim sight line to be untied from it.
 *
 * <p>The state itself is just the flag below plus the vehicle's size: the perspective is carried by vanilla's
 * {@code CameraType.THIRD_PERSON_BACK} because {@code CameraType} is an enum and adding a fourth constant is not
 * possible without replacing it wholesale. {@code MinecraftCameraTypeCycleMixin} is what slots this flag into the
 * F5 cycle.
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
	 * How far behind the player the camera sits, measured in the longest side of the vehicle's bounding box.
	 * One full side length is what puts the whole vehicle in frame from a normal rear angle: less clips the
	 * far end out of view, more wastes half the screen on sky.
	 */
	private static final float SPAN_FACTOR = 1.0F;

	/**
	 * How far above the vehicle's pitch axis the camera sits when the vehicle is on the ground or in the water, as
	 * a fraction of the orbit distance. War Thunder's external cameras do this: an orbit that stays exactly on the
	 * axis grazes the deck and the hull hides everything, so the camera has to leave the axis for the pulled-back
	 * distance to buy you a view of the ship. A fraction of the distance rather than a constant keeps the geometry
	 * similar at every zoom, and it is applied along world up, which is what makes the elevation independent of how
	 * the hull pitches.
	 */
	private static final float GROUND_LIFT_FACTOR = 0.30F;
	/** Never lower than this, so a zoomed-in view of a small cart is still off the axis. */
	private static final float MIN_LIFT = 1.0F;

	/**
	 * Damping per client tick on the pitch the camera is forced to. The value is deliberately slow enough to swallow
	 * the tick-to-step jitter of a hull's pose and of the mouse itself while still tracking a deliberate look-around;
	 * raising it tightens the follow, lowering it makes the camera lag.
	 */
	private static final float PITCH_SMOOTH_ALPHA = 0.25F;
	/**
	 * Damping per rendered frame on the orbit position itself. Vanilla smooths a detached camera's rotation
	 * ({@code Camera#tick} lerps {@code xRot}/{@code yRot}) but never its position, so the sway a player feels on a
	 * pitching hull is the pivot moving the eye and the orbit following it exactly. Frame-rate dependent by
	 * construction, which is the same trade vanilla makes for the rotation it does smooth.
	 */
	private static final float POSITION_SMOOTH_ALPHA = 0.35F;
	/**
	 * How far the orbit is allowed to travel in one frame, in blocks. Without it a teleport, a sub-level that updates
	 * its bounds late, or a hard pitch of the hull would yank the camera the whole way; with it the camera follows a
	 * fast craft at a fixed top speed instead of snapping to it.
	 */
	private static final double POSITION_STEP_LIMIT = 12.0D;
	/**
	 * How far behind the orbit the pitch damping reaches, in degrees. A hull that pitches hard moves the eye with it
	 * while the damped camera does not, so this bounds how far the two are ever allowed to disagree - the "room" the
	 * forced pitch keeps against the vehicle's own. Generous on purpose: the whole point of forcing the pitch is that
	 * the vehicle's pitch must not move the frame, so a small bound would reintroduce the sway through the back door.
	 */
	private static final float PITCH_FOLLOW_LIMIT = 60.0F;

	/**
	 * Extra clearance demanded of the camera once the vehicle is airborne, on top of the box's own top. The orbit
	 * passes exactly {@code lift} above its pivot on the way through (see {@link #orbitLift}), so demanding the
	 * box's top plus this is what makes the sight line go over the craft instead of through it - which is the whole
	 * reason looking down from an airship was useless before.
	 */
	private static final float AIR_CLEARANCE = 2.5F;

	/**
	 * Height above the surface under the vehicle at which the airborne behaviour is fully applied. Below that it
	 * fades in proportionally, which is what makes the camera rise gradually as an airship leaves the deck rather
	 * than snapping when it clears some threshold.
	 */
	private static final double RISE_REFERENCE = 16.0D;
	/**
	 * Altitude above the surface below the player before the airborne behaviour begins. A sailor standing on a deck
	 * already sits a few blocks above the water under the hull, so without this offset every ship at sea would read
	 * partly airborne and the camera would lift for nothing.
	 */
	private static final double AIR_ONSET = 8.0D;
	/** How far down to probe for that surface. A miss is treated as "nothing below within the probe", not airborne. */
	private static final int RISE_PROBE = 96;

	/**
	 * How much of the vehicle's own height sits above the player when their eye is used as the pivot, used only when
	 * the box's absolute coordinates cannot be trusted (see {@link #centreTrusted}). Standing on the deck of a craft
	 * whose rigging reaches {@code height} blocks, the top is roughly this fraction of it above the eye.
	 */
	private static final float TOP_OVER_EYE = 0.6F;
	/** A box centre further from the player than this cannot be the craft they are aboard, so it is not a pivot. */
	private static final double MAX_TRUST_DISTANCE = 64.0D;

	/**
	 * Wheel zoom range, as a multiplier of the span-derived distance. The floor lets you push in close on a small
	 * mount, the ceiling lets you pull far enough out to see a battleship in context; the result is still clamped
	 * by {@link #MIN_DISTANCE} and {@link #MAX_DISTANCE} afterwards.
	 */
	private static final float ZOOM_MIN = 0.25F;
	private static final float ZOOM_MAX = 6.0F;
	/** One notch of a discrete wheel multiplies the distance by this, so zoom is proportional, not additive. */
	private static final float ZOOM_STEP = 1.15F;

	/**
	 * Ticks an empty vehicle lookup has to persist before the view ends. A Sable sub-level answers null on
	 * individual ticks while its tracking state is refreshed, so one miss is not evidence that the player left the
	 * deck - and a view that ended itself a few hundred milliseconds after entering is precisely the complaint
	 * this sizing path exists to answer.
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
	 * The vehicle's box centre and how airborne it are, measured once a tick and interpolated per frame the way
	 * Sable interpolates its own {@code renderPose(partialTick)}: for a hull the centre comes out of a reflective
	 * sub-level lookup, which cannot be re-read on every rendered frame, and reading it only on the tick would make
	 * the pivot visibly step at 20 Hz while the craft moves.
	 */
	private static Vec3 centrePrev = Vec3.ZERO;
	private static Vec3 centreNow = Vec3.ZERO;
	private static float blendPrev;
	private static float blendNow;
	private static double topYNow;
	/** The vehicle box's own height, which is coordinate-space independent and is what the fallback demand uses. */
	private static double heightNow;
	/**
	 * Whether the measured box centre is genuinely in world coordinates. A craft the player is aboard must have its
	 * centre within {@link #MAX_TRUST_DISTANCE} of them; a box answered in the ship's plot-local space will not be,
	 * and using it as the pivot would fling the camera to the world origin.
	 */
	private static boolean centreTrusted;
	/**
	 * The pitch the forced orbit actually uses: the player's own look pitch, damped once a client tick. Damped at
	 * tick rate rather than per frame because the jitter being removed is a tick-rate phenomenon (a hull's pose is
	 * stepped per tick, and a per-frame filter would have to be re-tuned for every frame rate), and because the
	 * player's pitch is already frame-smoothed by vanilla before it reaches the camera.
	 */
	private static float smoothPitch;
	/** True once the damping has a first value, so entering the view starts from the live pitch instead of 0. */
	private static boolean pitchSeeded;
	/** The damped orbit position; null until the view has placed a camera, so entering the view adopts it outright. */
	@Nullable
	private static Vec3 smoothPos;
	/**
	 * TEMPORARY, alongside {@code CBCFireControl.DEBUG_CAMERA}: the surface the airborne probe hit, kept so the
	 * placement line can print it. It exists to settle a question the span measurement cannot — whether
	 * {@code boundingBox()} is answering in WORLD coordinates or in the ship's plot-local ones, because
	 * {@code size()} is identical either way while the centre and the corners are not. Delete with the debug block.
	 */
	private static double surfaceYNow = Double.NaN;
	private static double probeXNow;
	private static double probeZNow;

	private ExternalViewClientState() {
	}

	/** Whether the external distance should apply to the detached camera this frame. */
	public static boolean isActive() {
		return active;
	}

	/**
	 * Turns the external distance on at the size measured right now. Called only from the perspective key, so
	 * logging here cannot spam. Size and source are both written out because those two numbers are the whole
	 * definition of the view, and "it never engaged" versus "it engaged on the wrong thing" are only separable
	 * through this line. A rider with nothing aboard does not get the view at all.
	 */
	public static void activate(@Nullable Entity rider) {
		if (active)
			return;
		// Entered at the vehicle's own size, so the wheel starts from the framing the span gives rather than from
		// whatever the last ship was zoomed to.
		zoomFactor = 1.0F;
		// The damping starts from the live pitch, so entering the view does not swing the frame from level to
		// whatever the last vehicle's damped pitch happened to be.
		pitchSeeded = false;
		smoothPos = null;
		float distance = desiredDistance(rider);
		if (Float.isNaN(distance))
			return;
		measure(rider);
		// With no previous tick to interpolate from, the first frame has to read the fresh measurement rather than
		// lerp from whatever the last vehicle left behind.
		centrePrev = centreNow;
		blendPrev = blendNow;
		active = true;
		orbitDistance = distance;
		vehicleMisses = 0;
		CBCFireControl.LOGGER.info("[cbc_firecontrol] external view on, orbit distance {} blocks (+{} up), sized by "
						+ "{} (airborne {})",
				String.format("%.1f", distance),
				String.format("%.1f", Math.max(MIN_LIFT, GROUND_LIFT_FACTOR * distance)),
				vehicleKind(rider),
				String.format("%.2f", blendNow));
	}

	/**
	 * One measurement of the vehicle: its box centre and top, and how airborne it reads. The previous tick's values
	 * are kept alongside so {@link #orbitPivot} and {@link #orbitLift} can interpolate them per frame.
	 *
	 * <p>An empty lookup leaves the previous measurement in place; that is the point of {@link #VEHICLE_MISS_GRACE_TICKS}
	 * rather than something to reset here.
	 */
	private static void measure(@Nullable Entity rider) {
		AABB box = vehicleBox(rider);
		if (box == null)
			return;
		blendPrev = blendNow;
		centrePrev = centreNow;
		heightNow = box.getYsize();
		Vec3 centre = box.getCenter();
		// A craft the player is aboard has its centre beside them. That is the test for world coordinates: Sable
		// answers the hull's box from a plot whose own origin may be local, and its size is identical either way, so
		// the span path never noticed. Trusting a local centre would move the orbit to the world origin.
		centreTrusted = rider != null && centre.distanceToSqr(rider.position()) <= MAX_TRUST_DISTANCE * MAX_TRUST_DISTANCE;
		centreNow = centreTrusted ? centre : rider == null ? Vec3.ZERO : rider.position();
		topYNow = box.maxY;
		blendNow = airborneBlend(Minecraft.getInstance().level, rider);
	}

	/**
	 * Turns it off, leaving vanilla's third-person distance in place. The reason is logged because three
	 * independent paths end the view (the perspective key, the per-tick aboard check, a frame that never measured
	 * a size), and "why did it stop" is otherwise unanswerable from the log.
	 */
	public static void deactivate(String reason) {
		if (!active)
			return;
		active = false;
		orbitDistance = Float.NaN;
		smoothPos = null;
		vehicleMisses = 0;
		CBCFireControl.LOGGER.info("[cbc_firecontrol] external view off ({})", reason);
	}

	/**
	 * What the running view asks the camera for. Deliberately the cached measurement rather than a fresh one:
	 * {@code CalculateDetachedCameraDistanceEvent} fires once per rendered frame, and {@link #vehicleSpan} costs a
	 * reflective Sable sub-level lookup on a deck, so re-measuring here would run a sub-level search 200+ times a
	 * second - which is what made the perspective key stutter. {@link #onClientTick} refreshes the value once a
	 * tick instead, which is also the resolution the ship's own motion changes at.
	 *
	 * <p>NaN only when the view is off or has never measured anything.
	 */
	public static float currentDistance() {
		return active ? orbitDistance : Float.NaN;
	}

	/**
	 * The Create contraption the rider is sitting on, or null when they are not on one. The chain is walked
	 * rather than the immediate mount read because a Create seat entity is itself a vehicle whose vehicle is
	 * the contraption - the same walk {@code HeadAimClientEvents.ridingContraptionExit} does. A Sable hull is
	 * not a contraption and never appears in this chain, which is exactly why {@link #vehicleSpan} exists.
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
	 * <p>Contraption first because it is the cheap direct read, and a flying machine that is also inside a ship's
	 * footprint should be framed by its own box rather than by the ship's. The Sable answer comes from
	 * {@link SableShipBounds}, which resolves the hull reflectively; a hull has no rideable entity, so a sailor
	 * standing on a deck is matched by Sable's tracking / containment lookup instead of by the vehicle chain.
	 *
	 * <p>This is the single source of both numbers the view needs: {@link #vehicleSpan} frames the orbit distance
	 * from the longest side, and the airborne pivot takes the centre and the top from the same box.
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
	 * {@code zoomFactor}, bounded by {@link #MIN_DISTANCE} and {@link #MAX_DISTANCE}. NaN when there is nothing to
	 * size against, which is how every caller reads "no vehicle" without having to know which kind of vehicle
	 * there was.
	 */
	public static float desiredDistance(@Nullable Entity rider) {
		double span = vehicleSpan(rider);
		if (Double.isNaN(span))
			return Float.NaN;
		return Mth.clamp((float) (span * SPAN_FACTOR * zoomFactor), MIN_DISTANCE, MAX_DISTANCE);
	}

	/**
	 * The point the orbit swings around. On the ground that is the player's own eye, unchanged; as the vehicle
	 * climbs it slides onto the box centre, so a large craft stays framed as it pitches and rolls instead of
	 * orbiting whichever corner of the deck the player happens to be standing on.
	 *
	 * <p>The eye is passed in rather than cached because it is interpolated every frame for free, and the centre
	 * is the value that cannot be measured per frame.
	 */
	public static Vec3 orbitPivot(Vec3 eye, float partialTick) {
		float blend = lerpBlend(partialTick);
		if (blend <= 0.0F || !centreTrusted)
			return eye;
		Vec3 centre = new Vec3(Mth.lerp(partialTick, centrePrev.x, centreNow.x),
				Mth.lerp(partialTick, centrePrev.y, centreNow.y),
				Mth.lerp(partialTick, centrePrev.z, centreNow.z));
		return eye.lerp(centre, blend);
	}

	/**
	 * The camera's offset above the pivot. The identity worth knowing: the camera sits at
	 * {@code pivot − forward·distance + (0, lift, 0)} and looks along {@code forward}, so its sight line crosses
	 * the pivot's own plane exactly {@code lift} above it, whatever the pitch and the distance are. Two consequences
	 * follow, and they are the two things this view is supposed to guarantee:
	 * <ul>
	 *   <li>Demanding the box's top plus {@link #AIR_CLEARANCE} means the sight line passes over the whole craft,
	 *       which is what makes looking down at the terrain under an airborne vehicle work at all.</li>
	 *   <li>Keeping {@code lift} proportional to the distance keeps the angle between the sight line and the pivot
	 *       constant, so the craft holds the same part of the screen at every zoom. That is why the lift is not
	 *       capped here: capping it would decouple the two and let the craft drift up the screen as you zoom in.
	 *       {@link #orbitDistance} turns a demanded lift back into distance instead, so meeting a clearance
	 *       requirement pulls the camera back and up together.</li>
	 * </ul>
	 */
	public static float orbitLift(float distance, Vec3 pivot, float partialTick) {
		// The framing term is pure ratio, so it is what pins the vehicle's screen position.
		float floor = Math.max(MIN_LIFT, GROUND_LIFT_FACTOR * distance);
		float blend = lerpBlend(partialTick);
		if (blend <= 0.0F)
			return floor;
		// Measured from the pivot, because that is the plane the sight line crosses at exactly this height. When the
		// box's absolute coordinates are untrusted the top cannot be subtracted from anything, so the craft's own
		// height (which is the same in either coordinate space) stands in for it.
		float demand = centreTrusted
				? (float) (topYNow - pivot.y) + AIR_CLEARANCE
				: TOP_OVER_EYE * (float) heightNow + AIR_CLEARANCE;
		// Fading from 0 rather than from the framing term: the demanded clearance replaces the framing need, it does
		// not add to it, so a craft that needs 8 blocks over the pivot is lifted 8 and not 8 plus its own framing.
		return Math.max(floor, Mth.clamp(Mth.lerp(blend, 0.0F, demand), MIN_LIFT, GROUND_LIFT_FACTOR * MAX_DISTANCE));
	}

	/**
	 * The orbit distance the lift demands. Dividing the lift by the same ratio that produced it pulls the camera
	 * back exactly as far as it goes up, which is what keeps the vehicle's screen position fixed while the airborne
	 * clearance requirement grows with the craft. Never shorter than the distance the vehicle's span asked for.
	 */
	public static float orbitDistance(float baseDistance, float lift) {
		return Mth.clamp(lift / GROUND_LIFT_FACTOR, baseDistance, MAX_DISTANCE);
	}

	/**
	 * The damped pitch the forced orbit runs on, in degrees. This is the pitch Sable's view-vector redirect would
	 * otherwise overwrite with the hull's own, so it is the number the diagnostic line compares against the camera's
	 * to show the decoupling is actually happening.
	 */
	public static float smoothedPitch() {
		return smoothPitch;
	}

	/**
	 * The orbit position after damping: moves toward the target at {@code POSITION_SMOOTH_ALPHA} of the gap per
	 * frame, never more than {@code POSITION_STEP_LIMIT} blocks. The aimed direction is deliberately not smoothed
	 * here, so looking around stays immediate while a pitching hull cannot shake the frame.
	 */
	public static Vec3 dampedPosition(Vec3 target) {
		Vec3 previous = smoothPos;
		if (previous == null) {
			smoothPos = target;
			return target;
		}
		double x = previous.x + Mth.clamp(target.x - previous.x, -POSITION_STEP_LIMIT, POSITION_STEP_LIMIT)
				* POSITION_SMOOTH_ALPHA;
		double y = previous.y + Mth.clamp(target.y - previous.y, -POSITION_STEP_LIMIT, POSITION_STEP_LIMIT)
				* POSITION_SMOOTH_ALPHA;
		double z = previous.z + Mth.clamp(target.z - previous.z, -POSITION_STEP_LIMIT, POSITION_STEP_LIMIT)
				* POSITION_SMOOTH_ALPHA;
		Vec3 damped = new Vec3(x, y, z);
		smoothPos = damped;
		return damped;
	}

	/**
	 * The ratio that pins the vehicle's screen position, {@code lift / orbit distance}. Its arctangent is the angle
	 * the pivot sits below the centre of the frame, and it is constant by construction because {@link
	 * #orbitDistance(float, float)} derives the distance from the lift with the same ratio.
	 */
	public static float screenDropRatio() {
		return GROUND_LIFT_FACTOR;
	}

	/** How airborne the vehicle currently reads as, 0 to 1, interpolated per frame from the tick measurements. */
	public static float lerpBlend(float partialTick) {
		return Mth.clamp(Mth.lerp(partialTick, blendPrev, blendNow), 0.0F, 1.0F);
	}

	/** The vehicle box top the clearance demand is measured to, for the diagnostic line. */
	public static double vehicleTopY() {
		return topYNow;
	}

	/**
	 * TEMPORARY accessors for the coordinate-space question (see {@code surfaceYNow}): whether the box centre passed
	 * the trust test, the box's own height, and the surface the airborne probe hit. Together these settle whether
	 * {@code boundingBox()} answered in world coordinates or plot-local ones, which the span measurement cannot
	 * distinguish because {@code size()} is identical either way. Delete with the debug block.
	 */
	public static boolean debugCentreTrusted() {
		return centreTrusted;
	}

	public static double debugHeight() {
		return heightNow;
	}

	public static double debugSurfaceY() {
		return surfaceYNow;
	}

	public static double debugProbeX() {
		return probeXNow;
	}

	/**
	 * How far the vehicle's bottom sits above the surface underneath it, as a 0..1 blend.
	 *
	 * <p>One downward {@code Level#clip} per tick, from just under the box: cheap enough (vanilla ray-traces the
	 * crosshair ray every frame) and the only answer that works for both kinds of vehicle. {@code Fluid.ANY} is
	 * deliberate: a ship's keel is already under water, so the clip hits that water at the start of the ray and the
	 * height reads as ~0 — a hull at sea never becomes "airborne" even over a deep ocean floor, which is the
	 * behaviour wanted, since a ship's hull is what the view is supposed to show. A contraption's blocks are not
	 * block states in the level at all, so the ray cannot hit the craft it starts from.
	 *
	 * <p>An unloaded chunk under the vehicle is not evidence of altitude: the surface is then unknown, so the
	 * blend stays where it was rather than guessing. A probe that finds nothing within {@link #RISE_PROBE} is
	 * likewise left unblended rather than read as an infinite climb.
	 */
	private static float airborneBlend(@Nullable Level level, @Nullable Entity rider) {
		if (level == null || rider == null)
			return blendNow;
		// Probed from the player, never from the box: the player's position is world coordinates by definition, while
		// the hull's box may be answered plot-local (see measure), and a local corner would probe the world origin.
		double probeX = rider.getX();
		double probeZ = rider.getZ();
		if (!level.hasChunkAt(BlockPos.containing(probeX, rider.getY(), probeZ)))
			return blendNow;
		Vec3 from = new Vec3(probeX, rider.getY() - 0.5D, probeZ);
		Vec3 to = from.subtract(0.0D, RISE_PROBE, 0.0D);
		BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, rider));
		if (hit == null || hit.getType() == HitResult.Type.MISS)
			return blendNow;
		surfaceYNow = hit.getLocation().y;
		probeXNow = probeX;
		probeZNow = probeZ;
		return (float) Mth.clamp((rider.getY() - surfaceYNow - AIR_ONSET) / RISE_REFERENCE, 0.0D, 1.0D);
	}

	/**
	 * Wheel zoom. {@code amount} is vanilla's own processed delta (discrete scroll and wheel sensitivity already
	 * applied by the caller), so a notch is ±1 and a free-spinning wheel moves proportionally. Multiplying rather
	 * than adding is what keeps the step the same size at 6 blocks as at 60.
	 */
	public static void applyWheelZoom(double amount) {
		if (!active || amount == 0.0D)
			return;
		zoomFactor = Mth.clamp(zoomFactor * (float) Math.pow(ZOOM_STEP, amount), ZOOM_MIN, ZOOM_MAX);
		CBCFireControl.LOGGER.info("[cbc_firecontrol] external zoom x{} -> orbit {} blocks",
				String.format("%.2f", zoomFactor),
				String.format("%.1f", Float.isNaN(orbitDistance) ? 0.0F : desiredDistance(Minecraft.getInstance().player)));
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
	 * The view's once-a-tick upkeep: refresh the measurement, and end the view once the vehicle is gone or the
	 * perspective has moved off the camera type the view borrows.
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
		measure(mc.player);
		// The damping the forced camera pitch runs on. The limit is deliberately wide: a hull that pitches hard moves
		// the eye with it, and the whole point of forcing the pitch is that the vehicle's pitch must not move the
		// frame, so clipping the two together tightly would put the sway back through the other door.
		if (!pitchSeeded) {
			smoothPitch = mc.player.getXRot();
			pitchSeeded = true;
		} else {
			smoothPitch += Mth.clamp(mc.player.getXRot() - smoothPitch, -PITCH_FOLLOW_LIMIT, PITCH_FOLLOW_LIMIT)
					* PITCH_SMOOTH_ALPHA;
		}
		float fresh = desiredDistance(mc.player);
		if (!Float.isNaN(fresh)) {
			orbitDistance = fresh;
			vehicleMisses = 0;
			return;
		}
		if (vehicleMisses++ < VEHICLE_MISS_GRACE_TICKS)
			return;
		deactivate("nothing aboard for " + VEHICLE_MISS_GRACE_TICKS + " ticks");
		mc.options.setCameraType(CameraType.FIRST_PERSON);
	}
}
