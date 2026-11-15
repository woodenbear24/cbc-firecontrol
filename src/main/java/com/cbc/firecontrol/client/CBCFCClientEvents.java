package com.cbc.firecontrol.client;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.init.CBCFCMenus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
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

	/** The off-hand designator scope key, so it appears in the controls screen and can be rebound like any other. */
	@SubscribeEvent
	public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
		event.register(CBCFCKeyMappings.DESIGNATOR_SCOPE);
	}

	/**
	 * Nothing at all to draw as a scope ring: a fully transparent texture, blitted over the screen exactly where
	 * vanilla's spyglass vignette would be. {@code Gui#renderSpyglassOverlay} takes its texture from this extension
	 * and only ever blits it, so an empty texture leaves the zoom in place (the FOV multiplier comes from
	 * {@code ComputeFovModifierEvent}, not from this overlay) and removes the mask around it.
	 */
	private static final ResourceLocation NO_SCOPE_OVERLAY =
			ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "textures/misc/designator_no_scope.png");

	/**
	 * The designator's renderer. Its item model is {@code minecraft:builtin/entity} (see
	 * {@code models/item/target_designator.json}), which is what makes {@code ItemRenderer} hand every context to
	 * {@link TargetDesignatorRenderer} instead of baking a sprite for it.
	 *
	 * <p>The scope overlay is overridden so that a designator scoped in the OFF hand zooms without a ring. That is
	 * the head-aim sight line: a vignette would cut off the very horizon the spotter is pointing guns along, and
	 * the ring's window is a square the size of the screen anyway ({@code Gui#renderSpyglassOverlay} blits
	 * {@code floor(min(guiWidth, guiHeight) * scopeScale)} with the whole texture mapped into it). A designator
	 * scoped in the main hand keeps vanilla's ring, because there the ring is what makes aiming feel precise and
	 * nothing else depends on seeing past it.
	 */
	@SubscribeEvent
	public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
		event.registerItem(new IClientItemExtensions() {
			@Override
			public BlockEntityWithoutLevelRenderer getCustomRenderer() {
				return new TargetDesignatorRenderer();
			}

			@Override
			public ResourceLocation getScopeOverlayTexture(ItemStack stack) {
				Minecraft mc = Minecraft.getInstance();
				if (mc.player != null && mc.player.isUsingItem()
						&& mc.player.getUsedItemHand() == InteractionHand.OFF_HAND)
					return NO_SCOPE_OVERLAY;
				return IClientItemExtensions.super.getScopeOverlayTexture(stack);
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
