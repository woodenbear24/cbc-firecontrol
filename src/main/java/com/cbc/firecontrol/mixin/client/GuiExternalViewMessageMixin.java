package com.cbc.firecontrol.mixin.client;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.client.ExternalViewClientState;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

/**
 * Replaces Sable's perspective announcement with the external view's own line.
 *
 * <p>Why this is needed: while the player is aboard a hull inside a plot the external view borrows Sable's
 * unlocked ship perspective ({@code SUB_LEVEL_VIEW_UNLOCKED}), because that is the only perspective whose camera is
 * placed on the pose the hull is actually drawn with. Sable prints its own name for that switch -
 * {@code camera.new_camera_types.MinecraftMixin} posts {@code camera_type.sub_level_view_unlocked} to the actionbar
 * whenever the perspective becomes one of its constants - so a line reading "Unlocked Sub Level View" appears for a
 * view the player entered with our key, and it looks as though a different mod owned it.
 *
 * <p>Why the line is rewritten at the screen rather than prevented at the key: NeoForge 1.21.1 patches neither
 * {@code LocalPlayer#displayClientMessage} nor {@code Gui#setOverlayMessage}, so no event fires for a locally
 * posted actionbar line, and {@code @WrapOperation} cannot reach Sable's call because that call sits inside code
 * Sable injected into {@code Minecraft#handleKeybinds} rather than in vanilla bytecode. Wrapping the method is the
 * one hook that does not depend on another mod's injected instructions: {@code @WrapMethod} replaces the body, so
 * every caller passes through it, whoever the caller is.
 *
 * <p>What is not touched: the substitution happens only while {@link ExternalViewClientState} says this particular
 * switch was our perspective key's doing, and only for the two lines Sable prints for its own camera types. A
 * player who cycles to Sable's perspective themselves still sees Sable's text, and every other actionbar message -
 * Create's notices, our own calculator lines - passes through untouched.
 *
 * <p>If {@code Gui#setOverlayMessage} moves in a future build this injector simply does not apply: this mod's mixin
 * config sets {@code injectors.defaultRequire = 0}, so the game still starts and the player sees Sable's line
 * instead of ours.
 */
@Mixin(Gui.class)
public abstract class GuiExternalViewMessageMixin {

	@WrapMethod(method = "setOverlayMessage(Lnet/minecraft/network/chat/Component;Z)V")
	private void cbcfc$speakForTheExternalView(Component message, boolean allowDuplicateMessages,
		Operation<Void> original) {
		if (!ExternalViewClientState.takePerspectiveAnnouncement(message)) {
			original.call(message, allowDuplicateMessages);
			return;
		}
		original.call(ExternalViewClientState.externalViewAnnouncement(), allowDuplicateMessages);
		if (CBCFireControl.DEBUG_CAMERA)
			CBCFireControl.LOGGER.info("[cbc_firecontrol] external view claimed Sable's actionbar line for itself");
	}
}
