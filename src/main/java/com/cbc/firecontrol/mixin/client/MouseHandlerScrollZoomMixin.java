package com.cbc.firecontrol.mixin.client;

import com.cbc.firecontrol.client.ExternalViewClientState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives the mouse wheel to the external view, the way War Thunder's external cameras take it.
 *
 * <p>Why a mixin rather than an event: NeoForge fires no in-world scroll event - {@code ScreenEvent.MouseScrolling}
 * is screen-scoped and {@code InputEvent} carries buttons only - and the scroll delta exists solely as the GLFW
 * callback {@code MouseHandler#onScroll}, which is also where vanilla applies the {@code discreteMouseScroll} and
 * {@code mouseWheelSensitivity} options. Those two options are re-read here so the zoom steps the same way the
 * player's own settings say it should.
 *
 * <p>What the cancel costs while the view is active: vanilla's hotbar scrolling and Sable's own wheel zoom
 * ({@code camera.camera_zoom.MouseHandlerMixin}, which feeds {@code sable$setZoomAmount} for its
 * {@code SUB_LEVEL_VIEW} types) do not run. Number keys still change the held item, and Sable's zoom is irrelevant
 * because the external view sets its own orbit distance - so nothing is being double-driven. Screens still scroll
 * normally, since a screen owns the wheel (the trajectory calculator's sliders included).
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerScrollZoomMixin {

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void cbcfc$zoomExternalView(long windowPointer, double xOffset, double yOffset, CallbackInfo ci) {
		if (!ExternalViewClientState.isActive())
			return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.screen != null)
			return;
		double sensitivity = mc.options.mouseWheelSensitivity().get();
		double amount = (mc.options.discreteMouseScroll().get() ? Math.signum(yOffset) : yOffset) * sensitivity;
		if (amount == 0.0D)
			return;
		ExternalViewClientState.applyWheelZoom(amount);
		ci.cancel();
	}
}
