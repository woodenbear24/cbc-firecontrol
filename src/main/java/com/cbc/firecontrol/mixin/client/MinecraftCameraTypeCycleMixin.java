package com.cbc.firecontrol.mixin.client;

import com.cbc.firecontrol.client.ExternalViewClientState;

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
 * constant. It is {@code THIRD_PERSON_BACK} plus {@link ExternalViewClientState#isActive()}, whose distance
 * override is what makes that third-person view sit far enough out to frame the whole vehicle. Stepping off the
 * external view therefore hands back the same enum value with the flag cleared, which is why the branch below
 * returns {@code THIRD_PERSON_BACK} without consulting {@code cycle()}: cycling from it would jump to the
 * mirrored view instead of to the plain one.
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
			return CameraType.THIRD_PERSON_BACK;
		}
		// Defensive: the flag can only be carried by THIRD_PERSON_BACK, so any other step means it is stale.
		if (instance != CameraType.THIRD_PERSON_BACK)
			ExternalViewClientState.deactivate("perspective cycled past the view");
		return next;
	}
}
