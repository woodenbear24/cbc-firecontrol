package com.cbc.firecontrol.client;

import com.cbc.firecontrol.CBCFireControl;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyMapping;

/**
 * The mod's own keys, held here rather than inside the registration event so the behaviour that reads them - the
 * off-hand designator scope in {@link TargetDesignatorClientEvents} - names the same instance NeoForge holds.
 *
 * <p>{@link #DESIGNATOR_SCOPE} exists because vanilla can only ever start an item use from the MAIN hand: a
 * designator parked in the off hand as a sight line has no right-click of its own, so zooming it needs a key of its
 * own. The key asks; the server owns the resulting use state
 * ({@code com.cbc.firecontrol.network.CBCFCNetwork#handleDesignatorScope}).
 */
public final class CBCFCKeyMappings {

	/** Scope with the designator held in the off hand. Default Z: unclaimed by vanilla, and out of the way of sneak. */
	public static final KeyMapping DESIGNATOR_SCOPE = new KeyMapping(
			"key.cbc_firecontrol.designator_scope",
			GLFW.GLFW_KEY_Z,
			"key.categories.cbc_firecontrol");

	private CBCFCKeyMappings() {
	}
}
