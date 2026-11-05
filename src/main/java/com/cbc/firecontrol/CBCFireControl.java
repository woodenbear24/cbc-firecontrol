package com.cbc.firecontrol;

import org.slf4j.Logger;

import com.cbc.firecontrol.init.CBCFCBlockEntities;
import com.cbc.firecontrol.init.CBCFCBlocks;
import com.cbc.firecontrol.init.CBCFCCreativeTabs;
import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(CBCFireControl.MODID)
public class CBCFireControl {
	// Define mod id in a common place for everything to reference
	public static final String MODID = "cbc_firecontrol";
	// Directly reference a slf4j logger
	public static final Logger LOGGER = LogUtils.getLogger();

	// The constructor for the mod class is the first code that is run when your mod is loaded.
	// FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
	public CBCFireControl(IEventBus modEventBus, ModContainer modContainer) {
		// Register the commonSetup method for modloading
		modEventBus.addListener(this::commonSetup);

		// Register all DeferredRegisters to the mod event bus
		CBCFCBlocks.register(modEventBus);
		CBCFCBlockEntities.register(modEventBus);
		CBCFCCreativeTabs.register(modEventBus);

		// Register ourselves for server and other game events we are interested in.
		NeoForge.EVENT_BUS.register(this);
	}

	private void commonSetup(FMLCommonSetupEvent event) {
		// Some common setup code
		LOGGER.info("HELLO FROM CBC FIRE CONTROL COMMON SETUP");
		LOGGER.info("This mod depends on Create and Create Big Cannons.");
		LOGGER.info("Fire Control Mount registered: {}", CBCFCBlocks.FIRE_CONTROL_MOUNT.getId());
	}

	// You can use SubscribeEvent and let the Event Bus discover methods to call
	@SubscribeEvent
	public void onServerStarting(ServerStartingEvent event) {
		// Do something when the server starts
		LOGGER.info("HELLO from CBC Fire Control server starting");
	}
}

