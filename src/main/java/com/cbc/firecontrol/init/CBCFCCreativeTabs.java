package com.cbc.firecontrol.init;

import com.cbc.firecontrol.CBCFireControl;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Creative tab registration for CBC Fire Control.
 */
public class CBCFCCreativeTabs {

	public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
			DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CBCFireControl.MODID);

	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CBC_FIRECONTROL_TAB =
			CREATIVE_MODE_TABS.register("main", () -> CreativeModeTab.builder()
					.title(Component.translatable("itemGroup.cbc_firecontrol"))
					.withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
					.icon(() -> CBCFCBlocks.FIRE_CONTROL_MOUNT_ITEM.get().getDefaultInstance())
					.displayItems((parameters, output) -> {
						output.accept(CBCFCBlocks.FIRE_CONTROL_MOUNT_ITEM.get());
					})
					.build());

	public static void register(IEventBus modEventBus) {
		CREATIVE_MODE_TABS.register(modEventBus);
	}
}
