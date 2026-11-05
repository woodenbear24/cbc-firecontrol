package com.cbc.firecontrol;

import com.cbc.firecontrol.init.CBCFCBlockEntities;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterRenderersEvent;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntityRenderer;

/**
 * Client-side setup: register CBC's own CannonMount BEGSR for our custom BE type.
 * This reuses CBC's renderer (which draws rotating mount + shafts + carriage axle)
 * so our Fire Control Mount looks visually identical to CBC's Cannon Mount.
 *
 * Note: CBC additionally registers a Flywheel-based {@code CannonMountVisual} for
 * its own BE type. Without registering a matching visual for our BE type, Create/Flywheel
 * will fall back to this BEGSR path. This is fine for a Phase 1 mount but may benefit
 * from a full {@code RegisterVisualsEvent} handler later for Flywheel visuals.
 */
@Mod.EventBusSubscriber(modid = CBCFireControl.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class CBCFireControlClient {

	@SubscribeEvent
	public static void onRegisterRenderers(RegisterRenderersEvent.RegisterRenderers event) {
		event.registerBlockEntityRenderer(
				CBCFCBlockEntities.FIRE_CONTROL_MOUNT_BE.get(),
				CannonMountBlockEntityRenderer::new);
	}
}
