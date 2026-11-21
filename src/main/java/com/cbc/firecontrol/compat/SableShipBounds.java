package com.cbc.firecontrol.compat;

import java.lang.reflect.Method;

import com.cbc.firecontrol.CBCFireControl;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * The world-space size of the Sable ship a client-side entity is standing on, which is what the external view
 * needs to frame a hull it cannot measure as an entity.
 *
 * <p>Why the contraption walk cannot answer this: {@code ExternalViewClientState.riddenContraption} climbs
 * {@code Entity#getVehicle()}, and a Sable hull is never a vehicle — a sailor stands on deck blocks that belong
 * to a sub-level and are moved by Sable's tracking system, so the chain ends at null and the external view had
 * no distance to ask for. Sable instead knows the ship an entity belongs to: {@code Sable.HELPER}
 * ({@code ActiveSableCompanion}) exposes {@code getTrackingOrVehicleSubLevel(Entity)} for a tracked or mounted
 * entity and {@code getContainingClient(Entity)} for one whose world position falls inside a ship's footprint,
 * and both answer with a {@code SubLevel} whose {@code boundingBox()} is the hull's GLOBAL (world-space) bounds.
 * That box is an axis-aligned AABB of a hull that may be yawed, so its longest side is the ship's length to
 * within the hull's own beam — close enough to size a camera orbit, and the only measure available client-side.
 *
 * <p>Sable is an optional dependency and is deliberately NOT on the compile classpath, matching
 * {@link SableShipCoordinates}: every call below is resolved reflectively against the runtime API verified with
 * javap on {@code sable-neoforge-1.21.1-2.0.6} ({@code ActiveSableCompanion}, {@code SubLevelAccess#boundingBox},
 * {@code SubLevelAccess#logicalPose}, {@code BoundingBox3dc#minX..maxZ}). Absent
 * Sable, or no ship under the entity, every accessor returns NaN or null and the caller keeps its no-vehicle
 * behaviour.
 */
public final class SableShipBounds {

	private static volatile boolean attempted;
	private static boolean available;
	private static Object helper;
	/** (Entity) -> SubLevel: the ship the entity is tracked by, or riding. */
	@Nullable
	private static Method trackingOrVehicle;
	/**
	 * (Entity) -> SubLevel: the ship the entity is <em>riding</em>, which is the stricter question Sable's own
	 * camera asks (see {@link #ridesShip}).
	 */
	@Nullable
	private static Method vehicleOnly;
	/** (Entity) -> ClientSubLevel: the ship whose world footprint contains the entity's position. */
	@Nullable
	private static Method containingClient;
	/** () -> BoundingBox3dc, declared on {@code SubLevelAccess} so it dispatches on either answer type. */
	private static Method boundingBox;
	/**
	 * The six corners of that box, resolved once: {@code BoundingBox3dc} declares {@code minX()/minY()/minZ()/}
	 * {@code maxX()/maxY()/maxZ()} as abstract doubles, in this array's order.
	 */
	private static Method[] corners;
	/** () -> Pose3dc, also declared on {@code SubLevelAccess}: the ship's current transform. */
	private static Method pose;
	/** (Vec3) -> Vec3, a default method on {@code Pose3dc}: rotates a vector into world space. */
	private static Method transformNormal;

	private SableShipBounds() {
	}

	/**
	 * Resolves the reflection at client setup rather than at the first perspective key press.
	 *
	 * <p>Without this the lookups below are first exercised inside {@code ExternalViewClientState.desiredDistance},
	 * which means a Sable build whose API has moved answers exactly like a player standing on no ship, and the
	 * single log line the view emits cannot tell the two apart. Running it at setup makes the bridge's own state
	 * (ready / absent / unusable) visible before anyone presses F5. Harmless when Sable is not installed: it only
	 * logs the reason and stays unavailable.
	 */
	public static void prewarm() {
		ensureAvailable();
	}
	/**
	 * The hull's world-space footprint as a vanilla {@link AABB}, or null when there is no ship: the GLOBAL
	 * (world-space) box from {@code SubLevelAccess#boundingBox()}, read corner by corner.
	 *
	 * <p>NaN-free by design — it never throws, because a missing bridge must degrade the perspective cycle rather
	 * than crash it. The corners are what the external view needs rather than just the longest side: its airborne
	 * pivot is the box's centre and its clearance demand is measured to the box's top, and neither can be derived
	 * from a size. Verified with javap that {@code BoundingBox3dc} declares {@code minX()/minY()/minZ()/maxX()/
	 * maxY()/maxZ()} as abstract doubles.
	 */
	@Nullable
	public static AABB boxOf(@Nullable Entity entity) {
		if (entity == null || !ensureAvailable())
			return null;
		Object subLevel = lookup(entity);
		if (subLevel == null)
			return null;
		try {
			Object box = boundingBox.invoke(subLevel);
			if (box == null)
				return null;
			double minX = ((Number) corners[0].invoke(box)).doubleValue();
			double minY = ((Number) corners[1].invoke(box)).doubleValue();
			double minZ = ((Number) corners[2].invoke(box)).doubleValue();
			double maxX = ((Number) corners[3].invoke(box)).doubleValue();
			double maxY = ((Number) corners[4].invoke(box)).doubleValue();
			double maxZ = ((Number) corners[5].invoke(box)).doubleValue();
			if (maxX <= minX || maxY <= minY || maxZ <= minZ)
				return null;
			return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
		} catch (ReflectiveOperationException | RuntimeException e) {
			CBCFireControl.LOGGER.warn("[cbc_firecontrol] Sable ship corner lookup failed: {}", e.toString());
			return null;
		}
	}

	/**
	 * Tracked-or-riding first, position containment second: the first is the direct "the ship I am aboard"
	 * answer, the second catches a sailor standing on a deck whose tracking state has not been resolved yet.
	 */
	@Nullable
	private static Object lookup(Entity entity) {
		try {
			if (trackingOrVehicle != null) {
				Object found = trackingOrVehicle.invoke(helper, entity);
				if (found != null)
					return found;
			}
			if (containingClient != null)
				return containingClient.invoke(helper, entity);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
		return null;
	}
	private static boolean ensureAvailable() {
		if (attempted)
			return available;
		synchronized (SableShipBounds.class) {
			if (attempted)
				return available;
			attempted = true;
			try {
				Class<?> sable = Class.forName("dev.ryanhcode.sable.Sable");
				Object foundHelper = sable.getField("HELPER").get(null);
				Class<?> accessClass = Class.forName("dev.ryanhcode.sable.companion.SubLevelAccess");
				Class<?> boxClass = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3dc");
				Method foundTracking = null;
				Method foundContaining = null;
				Method foundVehicle = null;
				try {
					foundTracking = foundHelper.getClass().getMethod("getTrackingOrVehicleSubLevel", Entity.class);
				} catch (NoSuchMethodException e) {
					// A Sable build without the tracking helper: the position lookup still answers.
				}
				try {
					foundContaining = foundHelper.getClass().getMethod("getContainingClient", Entity.class);
				} catch (NoSuchMethodException e) {
					// A Sable build without the client lookup: tracking still answers for an aboard entity.
				}
				try {
					foundVehicle = foundHelper.getClass().getMethod("getVehicleSubLevel", Entity.class);
				} catch (NoSuchMethodException e) {
					// A Sable build without the riding lookup: the view then never borrows Sable's perspective,
					// which is the safe answer - the stricter gate cannot be checked.
				}
				if (foundTracking == null && foundContaining == null)
					throw new NoSuchMethodException("ActiveSableCompanion has neither "
							+ "getTrackingOrVehicleSubLevel(Entity) nor getContainingClient(Entity)");
				Method foundBoundingBox = accessClass.getMethod("boundingBox");
				// The corners the external view's pivot needs: minX, minY, minZ, maxX, maxY, maxZ, in that order.
				String[] cornerNames = { "minX", "minY", "minZ", "maxX", "maxY", "maxZ" };
				Method[] foundCorners = new Method[cornerNames.length];
				for (int i = 0; i < cornerNames.length; i++)
					foundCorners[i] = boxClass.getMethod(cornerNames[i]);
				if (foundTracking != null)
					foundTracking.setAccessible(true);
				if (foundContaining != null)
					foundContaining.setAccessible(true);
				if (foundVehicle != null)
					foundVehicle.setAccessible(true);
				foundBoundingBox.setAccessible(true);
				for (Method corner : foundCorners)
					corner.setAccessible(true);

				// The pose is optional: hullXRot only feeds the diagnostic, and a Sable build without it must not
				// make the sizing bridge itself unusable.
				Method foundPose = null;
				Method foundTransformNormal = null;
				try {
					foundPose = accessClass.getMethod("logicalPose");
					foundTransformNormal = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc")
							.getMethod("transformNormal", Vec3.class);
					foundPose.setAccessible(true);
					foundTransformNormal.setAccessible(true);
				} catch (ReflectiveOperationException e) {
					CBCFireControl.LOGGER.info("[cbc_firecontrol] Sable pose lookup unavailable; hull pitch "
							+ "diagnostics will read 0.0");
				}

				helper = foundHelper;
				trackingOrVehicle = foundTracking;
				containingClient = foundContaining;
				vehicleOnly = foundVehicle;
				boundingBox = foundBoundingBox;
				corners = foundCorners;
				pose = foundPose;
				transformNormal = foundTransformNormal;
				available = true;
				CBCFireControl.LOGGER.info("[cbc_firecontrol] Sable ship bounds bridge ready "
						+ "(entity -> sub-level global boundingBox via Sable.HELPER)");
			} catch (ClassNotFoundException e) {
				CBCFireControl.LOGGER.info("[cbc_firecontrol] no Sable present; external view sizes Create contraptions only");
			} catch (ReflectiveOperationException | RuntimeException e) {
				CBCFireControl.LOGGER.warn("[cbc_firecontrol] Sable bounds bridge unavailable: {}", e.toString());
			}
			return available;
		}
	}
	/**
	 * The zoom Sable's own camera pipeline currently holds for a {@code Camera} instance, or NaN when Sable is
	 * absent.
	 *
	 * <p>Resolved against the camera instance instead of through the ship bridge above: Sable's
	 * {@code camera.camera_zoom.CameraMixin} implements {@code CameraZoomExtension} on {@code Camera}, so the
	 * accessor exists exactly when Sable is installed, whatever the ship lookup resolved to. Cached because the
	 * caller reads it once a tick, and a Sable build without it is remembered rather than probed again.
	 */
	@Nullable
	private static Method cameraZoomGetter;
	private static boolean cameraZoomMissing;

	public static float cameraZoomAmount(@Nullable Object camera) {
		if (camera == null || cameraZoomMissing)
			return Float.NaN;
		Method getter = cameraZoomGetter;
		if (getter == null) {
			try {
				getter = camera.getClass().getMethod("sable$getZoomAmount");
				getter.setAccessible(true);
				cameraZoomGetter = getter;
			} catch (NoSuchMethodException | RuntimeException e) {
				cameraZoomMissing = true;
				return Float.NaN;
			}
		}
		try {
			Object value = getter.invoke(camera);
			return value instanceof Number n ? n.floatValue() : Float.NaN;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return Float.NaN;
		}
	}

	/**
	 * The hull's pitch in Minecraft's {@code xRot} convention (negative = bow up), 0.0 when it cannot be read.
	 *
	 * <p>The external view deliberately does not use this: it rebuilds the orbit from the mouse pitch, so hull pitch
	 * cannot enter it by construction. This exists for the diagnostic line, which is what proves the claim - if the
	 * camera's own pitch equals the hull's rather than the mouse's, Sable's view-vector redirect is reaching the
	 * camera and the override is doing the work it was written to do.
	 */
	public static float hullXRot(@Nullable Entity entity) {
		if (entity == null || !ensureAvailable() || pose == null || transformNormal == null)
			return 0.0F;
		Object subLevel = lookup(entity);
		if (subLevel == null)
			return 0.0F;
		try {
			Object shipPose = pose.invoke(subLevel);
			if (shipPose == null)
				return 0.0F;
			Object rotated = transformNormal.invoke(shipPose, new Vec3(0.0D, 0.0D, -1.0D));
			if (!(rotated instanceof Vec3 v))
				return 0.0F;
			double length = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
			if (length < 1.0E-6D)
				return 0.0F;
			return (float) Math.toDegrees(Math.asin(-v.y / length));
		} catch (ReflectiveOperationException | RuntimeException e) {
			return 0.0F;
		}
	}
	/**
	 * The hull's heading in Minecraft's {@code yRot} convention - the yaw of the vector the bow points along -
	 * or 0.0 when it cannot be read.
	 *
	 * <p>Read for the playtest question "does the camera keep its own orientation relative to world coordinates
	 * while the vehicle turns". The placement line prints it next to the camera's yaw and the player's raw yaw,
	 * so the three possible causes are distinguishable rather than guessed: the camera field itself tracking the
	 * hull, Sable composing the pose into the view vector at render time, or the player's own rotation being
	 * rewritten to the ship's frame.
	 */
	public static float hullYRot(@Nullable Entity entity) {
		if (entity == null || !ensureAvailable() || pose == null || transformNormal == null)
			return 0.0F;
		Object subLevel = lookup(entity);
		if (subLevel == null)
			return 0.0F;
		try {
			Object shipPose = pose.invoke(subLevel);
			if (shipPose == null)
				return 0.0F;
			Object rotated = transformNormal.invoke(shipPose, new Vec3(0.0D, 0.0D, -1.0D));
			if (!(rotated instanceof Vec3 v))
				return 0.0F;
			if (v.x * v.x + v.z * v.z < 1.0E-12D)
				return 0.0F;
			// Entity#calculateViewVector negates the yaw when it builds the x component, so the yaw of a
			// direction vector is the negative arc tangent of x over z - the same convention the orbit's own
			// forward vector is built from, which is what makes the two comparable in the log.
			double yaw = -Math.toDegrees(Math.atan2(v.x, v.z));
			return (float) (((yaw + 180.0D) % 360.0D + 360.0D) % 360.0D - 180.0D);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return 0.0F;
		}
	}
	/**
	 * (Vec3) -> Vec3 on {@code Pose3dc}: a plot-local point moved into world space. The pose maps plot-local
	 * to world, so transforming the local origin gives the plot's world origin - the point the hull pitches
	 * and rolls about.
	 */
	@Nullable
	private static Method transformPosition;
	/** () -> Vec3 on {@code Pose3dc}, for builds that expose the origin directly. */
	@Nullable
	private static Method posePosition;
	/** The candidates are probed once against the first live pose, so a build with neither is remembered. */
	private static boolean positionProbed;
	/**
	 * The hull's rotation centre in world space, or null when this Sable build exposes neither accessor or
	 * the entity is aboard no ship.
	 *
	 * <p>The external orbit turns around this rather than the bounding-box centre: a box centre is
	 * rotation-sensitive, so a rolling hull drags its own centre and the frame swims with it, while the point
	 * a hull turns about does not translate under pitch or roll at all. That clearance is what keeps the view
	 * steady while the ship pitches, without filtering the mouse.
	 */
	@Nullable
	public static Vec3 rotationCentre(@Nullable Entity entity) {
		if (entity == null || !ensureAvailable() || pose == null)
			return null;
		Object subLevel = lookup(entity);
		if (subLevel == null)
			return null;
		try {
			Object shipPose = pose.invoke(subLevel);
			if (shipPose == null)
				return null;
			probePositionMethods(shipPose.getClass());
			if (transformPosition != null) {
				Object point = transformPosition.invoke(shipPose, Vec3.ZERO);
				return point instanceof Vec3 v ? v : null;
			}
			if (posePosition != null) {
				Object point = posePosition.invoke(shipPose);
				return point instanceof Vec3 v ? v : null;
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
		return null;
	}
	private static void probePositionMethods(Class<?> poseClass) {
		if (positionProbed)
			return;
		positionProbed = true;
		try {
			Method found = poseClass.getMethod("transformPosition", Vec3.class);
			found.setAccessible(true);
			transformPosition = found;
		} catch (ReflectiveOperationException e) {
			// No point transform on this build.
		}
		try {
			Method found = poseClass.getMethod("position");
			found.setAccessible(true);
			posePosition = found;
		} catch (ReflectiveOperationException e) {
			// No direct origin accessor on this build.
		}
		CBCFireControl.LOGGER.info("[cbc_firecontrol] Sable rotation-centre accessors: transformPosition={}, position={}",
				transformPosition != null, posePosition != null);
	}

	/**
	 * Whether the external view can orbit the hull itself this frame: true when the entity is tracked by, riding
	 * in, or standing inside the footprint of a client sub-level.
	 */
	public static boolean aboard(@Nullable Entity entity) {
		if (entity == null || !ensureAvailable())
			return false;
		return lookup(entity) != null;
	}

	/**
	 * Whether Sable's own ship camera accepts this entity, which is stricter than {@link #aboard} in one specific
	 * way that matters: Sable resolves the sub-level through {@code getVehicleSubLevel(cameraEntity)} - the hull
	 * the camera entity is <em>riding</em> - both when it decides whether to keep its perspective
	 * ({@code GameRendererMixin#renderLevel}, {@code MinecraftMixin#handleKeybinds}) and when it decides whether
	 * to fold the hull's pose into the view. A sailor standing on a deck without riding a hull entity is inside a
	 * plot by position and not by vehicle, so borrowing the unlocked perspective for them would have Sable hand
	 * the player straight back to first person, making the perspective key look dead. The external view takes that
	 * perspective only where Sable agrees to keep it.
	 */
	public static boolean ridesShip(@Nullable Entity entity) {
		if (entity == null || !ensureAvailable() || vehicleOnly == null)
			return false;
		try {
			return vehicleOnly.invoke(helper, entity) != null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	/** () -> Pose3dc on the sub-level: the pose its geometry is drawn with this frame. */
	@Nullable
	private static Method renderPose;
	/** () -> a JOML-style vector on {@code Pose3dc}: the pose origin, the point the hull turns about. */
	@Nullable
	private static Method poseOrigin;
	/** The three component reads of that vector, resolved against the live object rather than by class name. */
	@Nullable
	private static Method componentX;
	@Nullable
	private static Method componentY;
	@Nullable
	private static Method componentZ;
	private static boolean renderPoseProbed;

	/**
	 * The hull's rotation centre as the renderer sees it this frame, or null when this build does not answer.
	 *
	 * <p>Why not {@link #rotationCentre}, which reads the logical pose: the ship is drawn through
	 * {@code ClientSubLevel#renderPose()}, which lerps {@code lastPose()} toward {@code logicalPose()} and slerps
	 * the orientation, using the timer's <em>accumulated</em> partial tick. {@code lastPose()} is not the previous
	 * tick's logical pose - it is whatever the snapshot interpolator last sampled from the network - so a pivot
	 * built from logical poses travels along a different curve than the hull it is meant to frame. That
	 * difference is the shake the tick-to-frame ramp cannot remove: ramping between two logical poses is linear
	 * in both endpoints and in time, and the renderer's path is not. Taking the same object the render uses makes
	 * camera and hull share one curve by construction, which is what Sable's own ship camera does -
	 * {@code camera.camera_zoom.CameraMixin#sable$setup} places the eye on {@code renderPose().position()} - and
	 * costs the same one lookup per frame that placement already costs.
	 */
	@Nullable
	public static Vec3 renderPoseOrigin(@Nullable Entity entity) {
		if (entity == null || !ensureAvailable())
			return null;
		Object subLevel = lookup(entity);
		if (subLevel == null)
			return null;
		try {
			if (renderPose == null && !renderPoseProbed) {
				renderPoseProbed = true;
				Method found = subLevel.getClass().getMethod("renderPose");
				found.setAccessible(true);
				renderPose = found;
			}
			if (renderPose == null)
				return null;
			Object shipPose = renderPose.invoke(subLevel);
			if (shipPose == null)
				return null;
			probePoseComponents(shipPose);
			if (poseOrigin == null || componentX == null || componentY == null || componentZ == null)
				return null;
			Object origin = poseOrigin.invoke(shipPose);
			if (origin == null)
				return null;
			double x = ((Number) componentX.invoke(origin)).doubleValue();
			double y = ((Number) componentY.invoke(origin)).doubleValue();
			double z = ((Number) componentZ.invoke(origin)).doubleValue();
			return new Vec3(x, y, z);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * The component reads are taken from the first live pose rather than from a class name: {@code Pose3dc} and
	 * {@code Pose3d} are both in Sable's companion library and either can be the answer, while both expose
	 * {@code position() -> x()/y()/z()}. Probing once against a real object keeps a build whose types moved from
	 * looking like a ship with no pivot.
	 */
	private static void probePoseComponents(Object shipPose) {
		if (poseOrigin != null)
			return;
		try {
			Method foundOrigin = shipPose.getClass().getMethod("position");
			foundOrigin.setAccessible(true);
			Object origin = foundOrigin.invoke(shipPose);
			if (origin == null)
				return;
			Method x = origin.getClass().getMethod("x");
			Method y = origin.getClass().getMethod("y");
			Method z = origin.getClass().getMethod("z");
			x.setAccessible(true);
			y.setAccessible(true);
			z.setAccessible(true);
			poseOrigin = foundOrigin;
			componentX = x;
			componentY = y;
			componentZ = z;
			CBCFireControl.LOGGER.info("[cbc_firecontrol] Sable render-pose pivot ready ({} / {})",
					shipPose.getClass().getSimpleName(), origin.getClass().getSimpleName());
		} catch (ReflectiveOperationException | RuntimeException e) {
			CBCFireControl.LOGGER.info("[cbc_firecontrol] Sable render-pose components unavailable: {}", e.toString());
		}
	}
}

