package com.cbc.firecontrol.client;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.init.CBCFCMenus;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * Client-only: bind the mount radio menu ({@link CBCFCMenus#MOUNT_FREQUENCY}) to its screen so
 * {@code player.openMenu(...)} on the mount block opens {@link MountFrequencyScreen}. Gated to Dist.CLIENT
 * so dedicated servers never touch the client screen classes.
 */
@EventBusSubscriber(modid = CBCFireControl.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class CBCFCClientEvents {

	@SubscribeEvent
	public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
		event.register(CBCFCMenus.MOUNT_FREQUENCY.get(), MountFrequencyScreen::new);
	}
}
