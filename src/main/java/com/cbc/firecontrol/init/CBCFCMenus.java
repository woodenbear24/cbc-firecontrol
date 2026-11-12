package com.cbc.firecontrol.init;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.menu.MountFrequencyMenu;
import com.cbc.firecontrol.menu.TrajectoryCalculatorMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registers the MenuType for the mount's Create-style radio-link console
 * ({@link MountFrequencyMenu}). Mirrors Create's own AllMenuTypes wiring: a vanilla
 * MenuType built from an IContainerFactory so openMenu(MenuProvider, bufConsumer) sends the
 * mount position to the client, which reconstructs the menu via createOnClient.
 */
public class CBCFCMenus {

	public static final DeferredRegister<MenuType<?>> MENUS =
			DeferredRegister.create(Registries.MENU, CBCFireControl.MODID);

	public static final DeferredHolder<MenuType<?>, MenuType<MountFrequencyMenu>> MOUNT_FREQUENCY =
			MENUS.register("mount_frequency",
					() -> IMenuTypeExtension.create(MountFrequencyMenu::newClient));

	/**
	 * Trajectory Calculator menu: built from the vanilla BlockPos the server writes into the menu buffer via
	 * {@code Player#openMenu(MenuProvider, BlockPos)} (see TrajectoryCalculatorBlock#useWithoutItem), which is
	 * both the menu's slot source (the BE on that pos) and the pos the screen reads synced solutions from.
	 */
	public static final DeferredHolder<MenuType<?>, MenuType<TrajectoryCalculatorMenu>> TRAJECTORY_CALCULATOR =
			MENUS.register("trajectory_calculator",
					() -> IMenuTypeExtension.create((containerId, inventory, buf) ->
							new TrajectoryCalculatorMenu(containerId, inventory, buf.readBlockPos())));

	public static void register(IEventBus modEventBus) {
		MENUS.register(modEventBus);
	}
}
