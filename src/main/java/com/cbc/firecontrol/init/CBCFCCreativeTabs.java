package com.cbc.firecontrol.init;

import com.cbc.firecontrol.CBCFireControl;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Creative tab registration for CBC Fire Control.
 *
 * Uses vanilla's {@link net.minecraft.world.level.block.Block#asItem()} to reference
 * the BlockItem that CreateRegistrate auto-generated for {@link CBCFCBlocks#FIRE_CONTROL_MOUNT}.
 */
public class CBCFCCreativeTabs {

	public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
			DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CBCFireControl.MODID);

	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CBC_FIRECONTROL_TAB =
			CREATIVE_MODE_TABS.register("main", () -> CreativeModeTab.builder()
					.title(Component.translatable("itemGroup.cbc_firecontrol"))
					.withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
					.icon(() -> new ItemStack(CBCFCBlocks.FIRE_CONTROL_MOUNT.get().asItem()))
					.displayItems((parameters, output) -> {
						// Nothing to add: our item was already auto-added to this tab by some
						// default wiring (CreateRegistrate's TAB_LOOKUP or NeoForge mod-tab auto-population),
						// so an explicit output.accept(...) here duplicates it and NeoForge's Output
						// wrapper throws "Itemstack ... already exists in the tab's list".
					})
					.build());

	public static void register(IEventBus modEventBus) {
		CREATIVE_MODE_TABS.register(modEventBus);
	}
}

