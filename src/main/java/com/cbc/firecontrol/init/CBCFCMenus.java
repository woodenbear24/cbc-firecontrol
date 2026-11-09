package com.cbc.firecontrol.init;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.menu.MountFrequencyMenu;

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

	public static void register(IEventBus modEventBus) {
		MENUS.register(modEventBus);
	}
}
