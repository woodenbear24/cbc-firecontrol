package com.cbc.firecontrol.client;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.init.CBCItems;
import com.cbc.firecontrol.item.TelescopeItem;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;

/**
 * Client-only: the spyglass zoom. Vanilla 1.21.1's {@code GameRenderer#getFov} multiplies FOV by
 * {@code SpyglassItem.ZOOM_FOV_MODIFIER} only when the item being used IS {@code minecraft:spyglass}, so a
 * modded copy gets the vignette and the arm pose for free (via {@code ItemAbilities.SPYGLASS_SCOPE}) but not
 * the zoom. NeoForge's {@code ComputeFovModifierEvent} is the supported hook for the rest.
 *
 * Gated to OUR item and to the main hand being the one in use, so a vanilla spyglass held at the same time is
 * never scaled twice.
 */
@EventBusSubscriber(modid = CBCFireControl.MODID, value = Dist.CLIENT)
public class TelescopeClientEvents {

	@SubscribeEvent
	public static void onComputeFovModifier(ComputeFovModifierEvent event) {
		Player player = event.getPlayer();
		if (player == null || !player.isUsingItem() || player.getUsedItemHand() != net.minecraft.world.InteractionHand.MAIN_HAND)
			return;
		ItemStack used = player.getUseItem();
		if (!used.is(CBCItems.TELESCOPE.asItem()))
			return;
		event.setNewFovModifier(event.getNewFovModifier() * TelescopeItem.ZOOM_FOV_MODIFIER);
	}
}
