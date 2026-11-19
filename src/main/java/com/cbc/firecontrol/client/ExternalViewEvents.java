package com.cbc.firecontrol.client;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.compat.SableShipBounds;

import com.simibubi.create.content.trains.CameraDistanceModifier;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Client wiring for the external view: the orbit distance, and the housekeeping that ends the view when the
 * vehicle it was sized against is gone.
 *
 * <p>Everything here is event-driven on purpose. {@link CalculateDetachedCameraDistanceEvent} is fired inside
 * {@code Camera#setup} before the distance is multiplied by the entity's scale and passed to
 * {@code Camera#getMaxZoom}, which is where vanilla walks a ray-cast to keep the camera out of blocks - so
 * overriding the distance through the event inherits that collision handling instead of having to reimplement
 * it, and needs no mixin of its own.
 */
@EventBusSubscriber(modid = CBCFireControl.MODID, value = Dist.CLIENT)
public final class ExternalViewEvents {

	/**
	 * First application is logged once and never again: it is the only proof that the distance override really
	 * ran, and a view that looks wrong is either explained by this line or has to be chased through the camera.
	 */
	private static boolean loggedApply;

	private ExternalViewEvents() {
	}

	/**
	 * Pull the third-person camera back to the size of the vehicle being ridden — a Create contraption or a Sable
	 * ship, whichever {@link ExternalViewClientState#vehicleSpan} resolves for the player.
	 *
	 * <p>What the returned distance becomes is {@code eventDistance * entityScale * createZoomMultiplier}: the
	 * scale is applied by {@code Camera#setup} right after the event, and Create's
	 * {@code CameraMixin} wraps {@code Camera#getMaxZoom} so its train camera zoom is multiplied in on the way
	 * through. Both are divided back out here, because the requested distance is the vehicle's own bounding box
	 * and must not be resized by the player's scale or by however far Create's zoom key happens to be pulled.
	 *
	 * <p>Only the un-flipped camera on the player's own perspective qualifies: a mirrored third-person view runs
	 * the distance negative, and a spectator camera belongs to whatever entity is being watched, not to a rider.
	 *
	 * <p>Since the lift and pitch decoupling landed, {@code CameraMaxZoomMixin} recomputes the position at the tail
	 * of {@code setup} from {@link ExternalViewClientState#currentDistance()} directly, so this event is no longer
	 * what places the camera - it stays because it is the cleanest proof the view's number reached the camera code,
	 * and because the HEAD bypass means the walk this value feeds is never run against the hull.
	 */
	@SubscribeEvent
	public static void onDetachedCameraDistance(CalculateDetachedCameraDistanceEvent event) {
		if (!ExternalViewClientState.isActive() || event.isCameraFlipped())
			return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || event.getCamera().getEntity() != mc.player)
			return;

		float desired = ExternalViewClientState.currentDistance();
		if (Float.isNaN(desired)) {
			// Only reachable if activation raced with the vehicle vanishing: a view with no measured size has
			// nothing to ask the camera for, so it ends rather than falling back to vanilla's short distance.
			ExternalViewClientState.deactivate("no orbit size measured");
			return;
		}

		float scale = event.getEntityScalingFactor();
		float createZoom = CameraDistanceModifier.getMultiplier();
		if (scale <= 0.0F || createZoom <= 0.0F)
			return;
		float written = desired / (scale * createZoom);
		event.setDistance(written);
		if (CBCFireControl.DEBUG_CAMERA && !loggedApply) {
			loggedApply = true;
			CBCFireControl.LOGGER.info("[cbc_firecontrol] external distance applied: {} blocks requested, "
					+ "{} written to the event (entity scale {}, create zoom {})",
					String.format("%.1f", desired), String.format("%.2f", written),
					String.format("%.2f", scale), String.format("%.2f", createZoom));
		}
	}

	/**
	 * TEMPORARY PROBE - delete once the numbers below have been read, along with {@code DIAG_INTERVAL_TICKS} and
	 * {@code diagTicks}.
	 *
	 * <p>What it answers: the view asks the camera for {@code requested} blocks, but {@code Camera#setup} hands
	 * that number to {@code Camera#getMaxZoom}, and Sable's {@code camera.camera_zoom.CameraMixin} redirects the
	 * clip inside that walk to {@code Sable.HELPER.distanceSquaredWithSubLevels}, so the orbit radius the player
	 * actually gets is not the number we wrote. A realized distance far below the requested one means the hull is
	 * clipping the camera back to plain third person, which is the difference between "the override never ran" and
	 * "something downstream undid it" - and only this line can tell them apart.
	 */
	private static final int DIAG_INTERVAL_TICKS = 20;
	private static int diagTicks;

	/**
	 * Dismounting is not a key press and can happen from damage, a contraption stopping, or the vehicle being
	 * removed, so the view is re-checked every tick rather than only when the perspective key is read.
	 */
	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		if (!ExternalViewClientState.isActive()) {
			loggedApply = false;
			diagTicks = 0;
			return;
		}
		ExternalViewClientState.onClientTick(mc);
		if (!ExternalViewClientState.isActive()) {
			loggedApply = false;
			diagTicks = 0;
			return;
		}
		if (!CBCFireControl.DEBUG_CAMERA || ++diagTicks < DIAG_INTERVAL_TICKS)
			return;
		diagTicks = 0;

		Camera camera = mc.gameRenderer.getMainCamera();
		Entity lookedAt = camera.getEntity();
		double realized = Double.NaN;
		double elevation = Double.NaN;
		if (lookedAt != null) {
			Vec3 delta = camera.getPosition().subtract(lookedAt.getEyePosition());
			realized = delta.length();
			elevation = delta.y;
		}
		// The vehicle is logged because Sable's own ship camera (SUB_LEVEL_VIEW) only takes over when the camera
		// entity rides something: if the player rides a ship entity, Sable already frames the hull and our step is
		// the duplicate; if they merely stand on deck blocks, only our override can size the view.
		Entity vehicle = mc.player == null ? null : mc.player.getVehicle();
		CBCFireControl.LOGGER.info("[cbc_firecontrol] external view diag: requested {} blocks, realized {} "
						+ "({} above the eye, zoom x{}) (camera entity {}, detached {}, perspective {}, vehicle {}, "
						+ "sable zoom {})",
				String.format("%.1f", ExternalViewClientState.currentDistance()),
				Double.isNaN(realized) ? "unmeasured" : String.format("%.1f", realized),
				Double.isNaN(elevation) ? "?" : String.format("%.1f", elevation),
				String.format("%.2f", ExternalViewClientState.zoomFactor()),
				lookedAt == null ? "none" : lookedAt.getClass().getSimpleName(),
				camera.isDetached(),
				mc.options.getCameraType().name(),
				vehicle == null ? "none" : vehicle.getClass().getSimpleName(),
				String.format("%.2f", SableShipBounds.cameraZoomAmount(camera)));
	}
}
