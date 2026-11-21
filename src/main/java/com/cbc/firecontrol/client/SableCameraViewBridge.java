package com.cbc.firecontrol.client;

import net.minecraft.client.CameraType;

import org.jetbrains.annotations.Nullable;

/**
 * Sable's unlocked ship perspective, reached without depending on Sable.
 *
 * <p>Why this is the view the external perspective should borrow when the player is aboard a hull: Sable adds two
 * extra constants to {@code CameraType} ({@code camera.new_camera_types.CameraTypeMixin} appends them to the
 * enum's own {@code $VALUES}), and the unlocked one is the only third-person view in the game that (a) places the
 * eye on the hull's <em>render</em> pose, so the camera and the ship cannot disagree about where the ship is,
 * (b) sizes the orbit from the plot's own bounding box and clamps it against the hull with a margin
 * ({@code camera.camera_zoom.CameraMixin#getMaxZoom}), (c) keeps the wheel zoom interpolated rather than
 * stepped, and (d) refuses to fold the hull's pose into the view direction - {@code EntitySubLevelRotationHelper}
 * returns null for the camera whenever {@code shouldCameraRotate()} is false, which is exactly this constant.
 * That last point is the answer to the open question of whether an external orbit should absorb the vehicle's
 * heading: upstream says no, and the camera therefore turns only when the mouse turns it.
 *
 * <p>How the constant is obtained: Sable's own {@code SableCameraTypes} reads it with
 * {@code Enum.valueOf(CameraType.class, "SUB_LEVEL_VIEW_UNLOCKED")}, so the enum's static {@code valueOf} answers
 * once Sable's mixin has applied and throws {@code IllegalArgumentException} before it has. Nothing here needs a
 * Sable class, which keeps the mod loadable without it. Probed lazily and both outcomes remembered, because the
 * perspective key and the tick check both ask.
 */
public final class SableCameraViewBridge {

	@Nullable
	private static CameraType unlocked;
	private static boolean probed;

	private SableCameraViewBridge() {
	}

	/** Sable's unlocked ship perspective, or null when Sable is absent or did not add it. */
	@Nullable
	public static CameraType unlockedView() {
		if (!probed) {
			probed = true;
			try {
				unlocked = CameraType.valueOf("SUB_LEVEL_VIEW_UNLOCKED");
			} catch (IllegalArgumentException e) {
				unlocked = null;
			}
		}
		return unlocked;
	}

	/** Whether {@code perspective} is that constant, so callers can stop overriding what Sable already owns. */
	public static boolean isUnlockedView(@Nullable CameraType perspective) {
		return perspective != null && perspective == unlockedView();
	}
}
