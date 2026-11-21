package com.cbc.firecontrol.mixin.client;

import com.cbc.firecontrol.client.ExternalViewClientState;
import com.cbc.firecontrol.client.SableCameraViewBridge;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/**
 * Slots the external view into vanilla's perspective cycle, so the perspective key reads
 * first person &rarr; external &rarr; third person &rarr; mirrored third person &rarr; first person while a
 * vehicle is being ridden or aboard (a Create contraption or a Sable ship), and stays vanilla's three-step
 * cycle otherwise.
 *
 * <p>Where it injects: {@code Minecraft#handleKeybinds} consumes {@code Options.keyTogglePerspective} (F5) and
 * does {@code options.setCameraType(options.getCameraType().cycle())}. {@code CameraType#cycle} has exactly one
 * call site in the whole jar, so wrapping that one call is the smallest possible intervention - no camera, mouse
 * or render class is touched, and nothing outside this key's handling changes. Mixin Extras'
 * {@code @WrapOperation} is used rather than a cancelling inject because vanilla still has to run the code that
 * follows the cycle: the first-person edge detection that calls {@code GameRenderer#checkEntityPostEffect} and
 * {@code LevelRenderer#needsUpdate}, without which a perspective change leaves stale world rendering.
 *
 * <p>How the extra view is represented: {@code CameraType} is an enum, so the external view cannot be a fourth
 * constant, and it cannot be one identifier either. Inside a Sable plot it is Sable's unlocked ship perspective,
 * because that perspective's own camera places the eye on the pose the hull is drawn with - which is what an orbit
 * that follows the ship instead of jittering against it needs. Outside a plot it is {@code THIRD_PERSON_BACK} plus
 * {@link ExternalViewClientState#isActive()}, because Sable forces {@code FIRST_PERSON} on every frame whenever the
 * camera entity's vehicle is not inside a sub-level, so a carriage on rails or a Create train cannot hold its enum
 * at all. Both identifiers carry the same placement ({@code CameraMaxZoomMixin}); only the orbit radius differs, and
 * only in who measured it. Stepping off therefore hands back the plain enum value with the flag cleared, which is
 * why the branch below returns {@code THIRD_PERSON_BACK} without consulting {@code cycle()}: cycling from it would
 * jump to the mirrored view instead of to the plain one.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftCameraTypeCycleMixin {

	/**
	 * The cycle step, replaced only when the external view is being entered or left.
	 *
	 * <p>{@code instance} is the camera type before the key press, which is what vanilla also reads to compare
	 * first-person-ness afterwards; the {@code original} call is kept for every other step so the enum's own
	 * ordering stays authoritative.
	 */
	@WrapOperation(method = "handleKeybinds", at = @At(value = "INVOKE",
	target = "Lnet/minecraft/client/CameraType;cycle()Lnet/minecraft/client/CameraType;"))
	private CameraType cbcfc$stepThroughExternalView(CameraType instance, Operation<CameraType> original) {
		if (ExternalViewClientState.isActive()) {
			ExternalViewClientState.deactivate("perspective key");
			return CameraType.THIRD_PERSON_BACK;
		}
		CameraType next = original.call(instance);
		Entity rider = Minecraft.getInstance().player;
		if (instance == CameraType.FIRST_PERSON && !Float.isNaN(ExternalViewClientState.desiredDistance(rider))) {
			ExternalViewClientState.activate(rider);
			// Aboard a Sable hull the view borrows Sable's own unlocked ship perspective rather than plain third
			// person: that perspective places the eye on the pose the hull is actually drawn with, sizes the orbit
			// from the plot and clamps it against the hull, and declines to fold the hull's rotation into the view
			// direction, so on that path Sable owns the radius and the rotation and this mod contributes only the
			// offset off the orbit axis. Activation takes the mode only when the ride relation resolves, which is
			// the same condition Sable's GameRendererMixin uses to keep the perspective at all, so the two agree
			// about when the ship is there; and it flags the announcement, because Sable prints its own name for
			// the switch and the player asked for ours (see GuiExternalViewMessageMixin).
			CameraType unlocked = SableCameraViewBridge.unlockedView();
			return ExternalViewClientState.usesSableView() && unlocked != null
				? unlocked
				: CameraType.THIRD_PERSON_BACK;
		}
		// Defensive: the flag is carried by THIRD_PERSON_BACK or by Sable's unlocked ship perspective, so any other
		// value means the view is already gone and the overrides must not outlive it.
		if (instance != CameraType.THIRD_PERSON_BACK && !SableCameraViewBridge.isUnlockedView(instance))
			ExternalViewClientState.deactivate("perspective cycled past the view");
		return next;
	}
}
