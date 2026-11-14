package com.cbc.firecontrol.init;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.item.CalculatorLinkData;
import com.cbc.firecontrol.item.CalculatorLinkItem;
import com.cbc.firecontrol.item.TargetDesignatorItem;
import com.cbc.firecontrol.item.TargetDesignatorLinkData;
import com.tterrag.registrate.util.entry.ItemEntry;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Standalone (non-block) item registration + this mod's data components.
 *
 * The creative-tab default was nulled in CBCFCBlocks' static block (runs before CBCItems.init() in
 * CBCFireControl's constructor), so this item is NOT auto-routed to vanilla SEARCH; CBCFCCreativeTabs
 * 'main' displayItems is the explicit adder.
 */
public class CBCItems {

	/** Data component holding a Calculator Link item's linked mount positions (see CBCFireControl register()). */
	public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
			DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, CBCFireControl.MODID);

	public static final DeferredHolder<DataComponentType<?>, DataComponentType<CalculatorLinkData>> CALCULATOR_LINK_DATA =
			DATA_COMPONENTS.register("calculator_link", () -> DataComponentType.<CalculatorLinkData>builder()
					.persistent(CalculatorLinkData.CODEC)
					.networkSynchronized(CalculatorLinkData.STREAM_CODEC)
					.cacheEncoding()
					.build());

	/** Data component holding a Designator's bound Trajectory Calculator (see TargetDesignatorLinkData). */
	public static final DeferredHolder<DataComponentType<?>, DataComponentType<TargetDesignatorLinkData>> TARGET_DESIGNATOR_LINK_DATA =
			DATA_COMPONENTS.register("target_designator_link", () -> DataComponentType.<TargetDesignatorLinkData>builder()
					.persistent(TargetDesignatorLinkData.CODEC)
					.networkSynchronized(TargetDesignatorLinkData.STREAM_CODEC)
					.cacheEncoding()
					.build());

	public static final ItemEntry<CalculatorLinkItem> CALCULATOR_LINK =
			CBCFireControl.REGISTRATE
					.item("calculator_link", CalculatorLinkItem::new)
					.properties(p -> p.stacksTo(1))
					.lang("Calculator Link")
					.register();

	/** Behavioural copy of {@code minecraft:spyglass} (see TargetDesignatorItem for what is reused from vanilla). */
	public static final ItemEntry<TargetDesignatorItem> TARGET_DESIGNATOR =
			CBCFireControl.REGISTRATE
					.item("target_designator", TargetDesignatorItem::new)
					.properties(p -> p.stacksTo(1))
					.lang("Target Designator")
					.register();

	/** Empty no-op referenced from CBCFireControl's constructor to force class-init. */
	public static void init() { }
}