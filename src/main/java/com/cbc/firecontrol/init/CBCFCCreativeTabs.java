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
 * Uses vanilla's {@link net.minecraft.world.level.block.Block#asItem()} to reference the BlockItem
 * CreateRegistrate auto-generated for each registered block, and lists BOTH mod items explicitly in
 * displayItems. Note: this mod's CreateRegistrate default creative tab is the vanilla SEARCH tab
 * (AbstractRegistrate.defaultCreativeModeTab = CreativeModeTabs.SEARCH), so items built via .item()
 * are NOT auto-added to this custom tab - they must be added here.
 */
public class CBCFCCreativeTabs {

	public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
			DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CBCFireControl.MODID);

	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CBC_FIRECONTROL_TAB =
			CREATIVE_MODE_TABS.register("main", () -> CreativeModeTab.builder()
					.title(Component.translatable("itemGroup.cbc_firecontrol"))
					.withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
					.icon(() -> new ItemStack(CBCFCBlocks.ADVANCED_CANNON_MOUNT.get().asItem()))
					.displayItems((parameters, output) -> {
						// Populate this dedicated tab explicitly with the mod's items. CreateRegistrate /
						// AbstractRegistrate default creative tab = vanilla SEARCH (CreativeModeTabs.SEARCH),
						// so items built via .item() are routed to SEARCH, NOT to this custom tab -> we add
						// them here. Adding to THIS tab cannot collide with SEARCH (separate tabs), so no
						// "Itemstack already exists" duplicate.
						output.accept(CBCFCBlocks.ADVANCED_CANNON_MOUNT.get().asItem());
						output.accept(CBCFCBlocks.TRAJECTORY_CALCULATOR.get().asItem());
						output.accept(new ItemStack(CBCItems.CALCULATOR_LINK.get()));
						output.accept(new ItemStack(CBCItems.TARGET_DESIGNATOR.get()));
					})
					.build());

	public static void register(IEventBus modEventBus) {
		CREATIVE_MODE_TABS.register(modEventBus);
	}
}

