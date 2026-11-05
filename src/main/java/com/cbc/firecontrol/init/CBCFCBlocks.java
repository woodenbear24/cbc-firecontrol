package com.cbc.firecontrol.init;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.block.FireControlMountBlock;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registration center for the mod's blocks and items.
 */
public class CBCFCBlocks {

	public static final DeferredRegister.Blocks BLOCKS =
			DeferredRegister.createBlocks(CBCFireControl.MODID);
	public static final DeferredRegister.Items ITEMS =
			DeferredRegister.createItems(CBCFireControl.MODID);

	/**
	 * Fire Control Mount: a copy of CBC's Cannon Mount, using our own
	 * BlockEntity type (FireControlMountBlockEntity) but inheriting all
	 * Block behavior (states, placement, wrench, redstone, kinetics) from CBC.
	 */
	public static final DeferredBlock<FireControlMountBlock> FIRE_CONTROL_MOUNT =
			BLOCKS.registerBlock("fire_control_mount",
					FireControlMountBlock::new,
					FireControlMountBlock.defaultProperties());

	public static final DeferredItem<BlockItem> FIRE_CONTROL_MOUNT_ITEM =
			ITEMS.registerSimpleBlockItem("fire_control_mount", FIRE_CONTROL_MOUNT);

	public static void register(net.neoforged.bus.api.IEventBus modEventBus) {
		BLOCKS.register(modEventBus);
		ITEMS.register(modEventBus);
	}
}
