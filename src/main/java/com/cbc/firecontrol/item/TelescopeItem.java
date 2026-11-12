package com.cbc.firecontrol.item;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import net.neoforged.neoforge.common.ItemAbility;
import net.neoforged.neoforge.common.ItemAbilities;

/**
 * Telescope - a faithful copy of vanilla's spyglass behaviour under our own item id, so the fire-control
 * content can build on it later without re-using {@code minecraft:spyglass}.
 *
 * <p>Everything the vanilla spyglass does on 1.21.1 keys off two things, both of which this item provides:
 * <ol>
 * <li>{@link UseAnim#SPYGLASS} -> {@code AvatarRenderer} maps it to {@code HumanoidModel.ArmPose.SPYGLASS}
 *     (held to the eye) and the first-person hand rendering follows it.</li>
 * <li>{@code ItemAbilities.SPYGLASS_SCOPE} via {@link #canPerformAction} -> NeoForge's patched
 *     {@code Player#isScoping()} becomes true, which is what {@code Gui#render} uses to draw the scope vignette
 *     ({@code Gui#renderSpyglassOverlay}, whose texture comes from
 *     {@code IClientItemExtensions#getScopeOverlayTexture}, i.e. vanilla's
 *     {@code Gui.SPYGLASS_SCOPE_LOCATION}). No client-side overlay code is needed here for that.</li>
 * </ol>
 *
 * <p>The one thing vanilla does NOT give a modded item is the zoom: {@code GameRenderer#getFov} hardcodes
 * {@code minecraft:spyglass}, so the FOV multiplier is added in
 * {@link com.cbc.firecontrol.client.TelescopeClientEvents} through NeoForge's {@code ComputeFovModifierEvent}.
 */
public class TelescopeItem extends Item {

	/** Vanilla {@code SpyglassItem.USE_DURATION}: the scope can be held until the player releases or swaps. */
	public static final int USE_DURATION = 1200;
	/** Vanilla {@code SpyglassItem.ZOOM_FOV_MODIFIER}: 10 % of the normal FOV while scoping. */
	public static final float ZOOM_FOV_MODIFIER = 0.1F;

	public TelescopeItem(Properties properties) {
		super(properties);
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity user) {
		return USE_DURATION;
	}

	@Override
	public UseAnim getUseAnimation(ItemStack stack) {
		return UseAnim.SPYGLASS;
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		player.playSound(SoundEvents.SPYGLASS_USE, 1.0F, 1.0F);
		player.awardStat(Stats.ITEM_USED.get(this));
		return ItemUtils.startUsingInstantly(level, player, hand);
	}

	@Override
	public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
		stopUsing(entity);
		return stack;
	}

	@Override
	public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remainingTime) {
		stopUsing(entity);
	}

	/**
	 * Declaring the spyglass scope ability is what makes {@code Player#isScoping()} true for this item on
	 * NeoForge 1.21.1, and therefore makes the vanilla scope vignette and the scoping arm pose apply.
	 */
	@Override
	public boolean canPerformAction(ItemStack stack, ItemAbility ability) {
		return ItemAbilities.DEFAULT_SPYGLASS_ACTIONS.contains(ability);
	}

	private void stopUsing(LivingEntity entity) {
		entity.playSound(SoundEvents.SPYGLASS_STOP_USING, 1.0F, 1.0F);
	}
}
