package com.cbc.firecontrol;

import org.slf4j.Logger;

import com.cbc.firecontrol.init.CBCFCBlockEntities;
import com.cbc.firecontrol.init.CBCFCBlocks;
import com.cbc.firecontrol.init.CBCFCCreativeTabs;
import com.mojang.logging.LogUtils;
import com.simibubi.create.foundation.data.CreateRegistrate;

import dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;

import rbasamoyai.createbigcannons.cannon_control.config.CannonMountPropertiesHandler;
import rbasamoyai.createbigcannons.cannon_control.config.SimpleBlockMountProperties;
import rbasamoyai.createbigcannons.cannon_control.cannon_types.CannonContraptionTypeRegistry;

import net.minecraft.resources.ResourceLocation;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

@Mod(CBCFireControl.MODID)
public class CBCFireControl {

	public static final String MODID = "cbc_firecontrol";
	public static final Logger LOGGER = LogUtils.getLogger();

	/**
	 * CreateRegistrate instance for this mod — same pattern CBC uses in its own
	 * CreateBigCannons class. Instances created by {@code CreateRegistrate.create()}
	 * are automatically wired into Create's visual / renderer registration callbacks,
	 * so {@code .visual(...)} and {@code .renderer(...)} in CBCFCBlockEntities hook
	 * into Flywheel without a manual EntityRenderersEvent handler.
	 */
	public static final CreateRegistrate REGISTRATE = CreateRegistrate.create(MODID);

	public CBCFireControl(IEventBus modEventBus, ModContainer modContainer) {
		// CRITICAL ORDER: registrate.registerEventListeners(...) MUST run BEFORE any
		// CreateRegistrate.block(...).register() / blockEntity(...).visual(...).register()
		// chains. Reason: CreateBlockEntityBuilder.visual(...) internally uses
		// OneTimeEventReceiver.addModListener(...), which:
		//   - queues the listener if owner.getModEventBus() == null
		//   - only flushes queued listeners once (globally, via a static seenModBus flag)
		//     for the FIRST registrate that ever calls addModListener with a non-null bus
		// Create mod (loaded before us) sets seenModBus=true during its own setup, so once
		// Create's flush has happened, no later mod's queued listeners get flushed. If we
		// queue visualizer listeners before our bus is attached, they sit in the waiting
		// map forever and Flywheel never gets a visualizer for our BE type -> nothing
		// renders (CBC's BEGS renderer early-returns whenever Flywheel is loaded).
		// CBC's own @Mod class (CreateBigCannonsNeoForge) does registerEventListeners
		// FIRST then CBCBlocks/CBCBlockEntities class-init; we mirror that exactly.
		REGISTRATE.registerEventListeners(modEventBus);

		// Now that bus is attached, force registration classes to load; their static
		// CreateRegistrate chains will enqueue blocks/items/BEs (flushed via RegisterEvent
		// later) and attach visualizer / renderer listeners to our mod bus immediately
		// (since bus is non-null on those OneTimeEventReceiver.addModListener calls).
		CBCFCBlocks.init();
		CBCFCBlockEntities.init();

		// Creative tab uses plain NeoForge DeferredRegister (independent of CreateRegistrate).
		CBCFCCreativeTabs.register(modEventBus);

		modEventBus.addListener(this::commonSetup);
		// LOWEST priority so this diagnostic runs AFTER CBC's own visualizer wiring
		// (which fires at NORMAL priority). If "NOT REGISTERED" appears here, our visual
		// wiring really didn't happen and no amount of CBC subclassing tricks will render
		// the rotating/pitching parts.
		modEventBus.addListener(EventPriority.LOWEST, this::clientSetupDiag);
		NeoForge.EVENT_BUS.register(this);
	}

	private void commonSetup(FMLCommonSetupEvent event) {
		// Must run AFTER CBC's own commonSetup (dependency order guarantees that). CBC's
		// DefaultCannonMountPropertiesSerializers.init() runs first and registers CBC's
		// own CANNON_MOUNT BE with SimpleBlockMountProperties.Serializer. CBC's datapack
		// reload listener (CannonMountPropertiesHandler.BlockEntityReloadListener) later
		// scans data/<ns>/cannon_mounts/block_entities/<be_name>/<cannon_ns>/<cannon_name>.json
		// and SKIPS entries whose BE type has no registered serializer:
		//     if (!BLOCK_MOUNT_SERIALIZERS.containsKey(beType)) continue;
		// Without a serializer for OUR BE type, our datapack JSONs are ignored,
		// BLOCK_MOUNT_PROPERTIES lacks our BE type, getProperties() returns FALLBACK_BLOCK
		// (maximumElevation=0, maximumDepression=0), and CannonMountBlockEntity.tick()
		// clamps cannonPitch to [0,0] every tick -> mount physically can never pitch.
		event.enqueueWork(() -> {
			try {
				CannonMountPropertiesHandler.registerBlockMountSerializer(
						CBCFCBlockEntities.FIRE_CONTROL_MOUNT_BE.get(),
						new SimpleBlockMountProperties.Serializer());
				LOGGER.info("CBC Fire Control: registered SimpleBlockMountProperties serializer for our mount BE");
			} catch (IllegalStateException already) {
				// Idempotent guard for cases where commonSetup fires more than once
				// (e.g., some NeoForge lifecycle paths). Safe to ignore.
				LOGGER.warn("CBC Fire Control: mount properties serializer already registered: {}",
						already.getMessage());
			}
			LOGGER.info("CBC Fire Control loaded — Fire Control Mount registered: {}",
					CBCFCBlocks.FIRE_CONTROL_MOUNT.getId());
		});
	}

	private void clientSetupDiag(FMLClientSetupEvent event) {
		event.enqueueWork(() -> {
			var beType = CBCFCBlockEntities.FIRE_CONTROL_MOUNT_BE.get();
			BlockEntityVisualizer<?> visualizer = VisualizerRegistry.getVisualizer(beType);
			LOGGER.info("Flywheel visualizer lookup for {} -> {}",
					beType.toString(),
					visualizer != null ? visualizer.getClass().getName() : "NOT REGISTERED (visual will not run)");
		});
	}

	@SubscribeEvent
	public void onServerStarting(ServerStartingEvent event) {
		LOGGER.info("CBC Fire Control server starting");
		// Diagnostic: query CannonMountPropertiesHandler for our mount + each cannon type.
		// Data pack reload listeners ran during resource load (before ServerStartingEvent),
		// so BLOCK_MOUNT_PROPERTIES should already be populated with entries parsed from
		// our data/cbc_firecontrol/cannon_mounts/block_entities/fire_control_mount/** JSONs.
		// If lookup returns elevation=0/depression=0, our JSONs were not loaded (serializer
		// registered too late, BE registry key mismatch, or reload listener hit an exception).
		try {
			var ourBEType = CBCFCBlockEntities.FIRE_CONTROL_MOUNT_BE.get();
			var dummyState = CBCFCBlocks.FIRE_CONTROL_MOUNT.get().defaultBlockState();
			for (String cannonTypeName : new String[] { "big_cannon", "autocannon", "drop_mortar", "handle_autocannon" }) {
				var contraptionType = CannonContraptionTypeRegistry.getOptional(
						ResourceLocation.fromNamespaceAndPath("createbigcannons", cannonTypeName));
				if (contraptionType.isEmpty()) {
					LOGGER.warn("Mount properties diagnostic: cannon contraption type 'createbigcannons:{}' not found", cannonTypeName);
					continue;
				}
				var props = CannonMountPropertiesHandler.getProperties(ourBEType, contraptionType.get());
				float elev = props.maximumElevation(null, dummyState, null);
				float dep = props.maximumDepression(null, dummyState, null);
				LOGGER.info("Mount properties [{} + {}]: elev={} dep={} {}",
						"cbc_firecontrol:fire_control_mount", cannonTypeName, elev, dep,
						(elev == 0 && dep == 0) ? " <-- FALLBACK (JSON not loaded!)" : " <-- loaded OK");
			}
		} catch (Throwable t) {
			LOGGER.warn("Mount properties diagnostic failed: {}", t.toString());
		}
	}
}


