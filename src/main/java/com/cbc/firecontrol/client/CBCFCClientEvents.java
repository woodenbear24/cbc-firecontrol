package com.cbc.firecontrol.client;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.init.CBCFCMenus;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import com.cbc.firecontrol.init.CBCItems;

/**
 * Client-only wiring done on the mod bus: the mount radio and calculator menu screens, and the Target
 * Designator's item renderer.
 *
 * {@code CBCFCMenus} screens are registered so {@code player.openMenu(...)} on the mount block opens
 * {@link MountFrequencyScreen} and on the calculator opens {@link TrajectoryCalculatorScreen}. Gated to
 * Dist.CLIENT so dedicated servers never touch the client screen classes.
 */
@EventBusSubscriber(modid = CBCFireControl.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class CBCFCClientEvents {

	@SubscribeEvent
	public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
		event.register(CBCFCMenus.MOUNT_FREQUENCY.get(), MountFrequencyScreen::new);
		event.register(CBCFCMenus.TRAJECTORY_CALCULATOR.get(), TrajectoryCalculatorScreen::new);
	}

	/**
	 * The designator's renderer. Its item model is {@code minecraft:builtin/entity} (see
	 * {@code models/item/target_designator.json}), which is what makes {@code ItemRenderer} hand every context to
	 * {@link TargetDesignatorRenderer} instead of baking a sprite for it.
	 *
	 * <p>The vignette is deliberately NOT overridden: with no {@code getScopeOverlayTexture} the extension falls
	 * back to vanilla's {@code Gui.SPYGLASS_SCOPE_LOCATION}, so the designator scopes exactly like a spyglass -
	 * one texture, no asset to keep aligned with the model's window. A wide binocular field would need a texture
	 * whose window is itself wide, because {@code Gui#renderSpyglassOverlay} always blits a square
	 * ({@code floor(min(guiWidth, guiHeight) * scopeScale)}) with the whole texture mapped into it.
	 */
	@SubscribeEvent
	public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
		event.registerItem(new IClientItemExtensions() {
			@Override
			public BlockEntityWithoutLevelRenderer getCustomRenderer() {
				return new TargetDesignatorRenderer();
			}
		}, CBCItems.TARGET_DESIGNATOR.get());
	}

	/**
	 * The scope tube model. Nothing references it (the item's own model is the {@code builtin/entity} marker and
	 * {@link TargetDesignatorRenderer} looks the model up by location), so it has to be named here or it is never
	 * baked. It must be a {@code standalone} location: NeoForge 1.21.1 rejects other variants for side-loaded
	 * models, with {@code IllegalArgumentException: Side-loaded models must use the 'standalone' variant}. The tube
	 * itself needs no registration - vanilla bakes {@code ItemRenderer.SPYGLASS_IN_HAND_MODEL}, which this model
	 * parents, already. The flat sprite is registered next to it, for the GUI/GROUND/FIXED contexts the
	 * {@code TargetDesignatorRenderer} routes to it exactly as vanilla does.
	 */
	@SubscribeEvent
	public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
		event.register(ModelResourceLocation.standalone(
				ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "item/target_designator_model")));
		event.register(ModelResourceLocation.standalone(
				ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "item/target_designator_flat")));
	}
}
