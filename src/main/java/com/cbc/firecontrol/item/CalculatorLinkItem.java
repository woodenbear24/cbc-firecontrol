package com.cbc.firecontrol.item;

import java.util.List;
import java.util.UUID;

import com.cbc.firecontrol.block.entity.AdvancedCannonMountBlockEntity;
import com.cbc.firecontrol.block.entity.TrajectoryCalculatorBlockEntity;
import com.cbc.firecontrol.init.CBCItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * Calculator Link - a Create "logistics Filter Link"-style wireless linking tool.
 *
 * Right-click an Advanced Cannon Mount to record its position into this item (the linked list lives in
 * {@link CBCItems#CALCULATOR_LINK_DATA}, a network-synchronized data component). Once linked the item
 * shows an enchantment glint ({@link #isFoil}) and its tooltip lists each linked mount's coordinates
 * plus its current pointing (yaw / pitch), read from the client-side mount block entity.
 *
 * The actual downstream behavior (using the link to drive / configure the linked mounts) is intentionally
 * left for later; for now the item only carries the link + shows the mount info.
 *
 * Sneak-right-click a linked mount -> unlink just that one. Sneak-right-click in the air -> clear the whole link.
 */
public class CalculatorLinkItem extends Item {

	public CalculatorLinkItem(Properties properties) {
		super(properties);
	}

	/**
	 * Server-side link handler invoked from {@link com.cbc.firecontrol.block.AdvancedCannonMountBlock#useItemOn}
	 * when a Calculator Link is right-clicked onto the mount. One mount per card:
	 *   - plain right-click on a mount  -> binds (or re-binds when the card's old target is gone / no longer
	 *     a mount, e.g. the mount was destroyed) to the mount clicked;
	 *   - sneak right-click on that same mount -> unbinds the card;
	 *   - plain right-click while the card still points at another live mount -> refused with a hint, so a
	 *     stray click cannot silently steal a card that is in use by another mount.
	 * The block guarantees the clicked target really is an Advanced Cannon Mount.
	 */
	public static void applyLink(ItemStack stack, Level level, BlockPos pos, Player player) {
		CalculatorLinkData data = stack.get(CBCItems.CALCULATOR_LINK_DATA.get());
		BlockPos linked = data == null ? null : data.mount();
		UUID linkedId = data == null ? null : data.mountIdOrNull();
		AdvancedCannonMountBlockEntity clickedMount =
				level.getBlockEntity(pos) instanceof AdvancedCannonMountBlockEntity mount ? mount : null;

		if (player.isShiftKeyDown()) {
			if (sameMount(linked, linkedId, pos, clickedMount)) {
				stack.remove(CBCItems.CALCULATOR_LINK_DATA.get());
				player.displayClientMessage(Component.translatable("item.cbc_firecontrol.calculator_link.unbound"), true);
			}
			return;
		}

		boolean cardInUse = data != null && !sameMount(linked, linkedId, pos, clickedMount)
				&& linked != null && level.getBlockEntity(linked) instanceof AdvancedCannonMountBlockEntity;
		if (cardInUse) {
			player.displayClientMessage(Component.translatable("item.cbc_firecontrol.calculator_link.rebind_hint",
					linked.getX(), linked.getY(), linked.getZ()), true);
			return;
		}

		UUID mountId = clickedMount == null ? null : clickedMount.mountId();
		stack.set(CBCItems.CALCULATOR_LINK_DATA.get(),
				new CalculatorLinkData(pos, mountId == null ? CalculatorLinkData.NO_ID : mountId.toString()));
		player.displayClientMessage(Component.translatable("item.cbc_firecontrol.calculator_link.bound",
				pos.getX(), pos.getY(), pos.getZ()), true);
	}

	/**
	 * True when the clicked block IS the mount the card already points at. Coordinates normally decide, but a
	 * matching mount id also counts: after a rebuild the SAME mount sits at new coordinates, and clicking its
	 * old cell (now something else) must not be read as "unlink this other block".
	 */
	private static boolean sameMount(BlockPos linkedCoords, UUID linkedId, BlockPos clicked,
			AdvancedCannonMountBlockEntity clickedMount) {
		if (clicked.equals(linkedCoords))
			return true;
		return linkedId != null && clickedMount != null && linkedId.equals(clickedMount.mountId());
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		// Sneak-right-click in air (no target block): clear the entire link.
		if (player.isShiftKeyDown() && stack.has(CBCItems.CALCULATOR_LINK_DATA.get())) {
			if (!level.isClientSide)
				stack.remove(CBCItems.CALCULATOR_LINK_DATA.get());
			return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
		}
		return InteractionResultHolder.pass(stack);
	}

	/** Linked -> enchantment glint, mirroring Create's logistics Filter Link "linked" visual. */
	@Override
	public boolean isFoil(ItemStack stack) {
		return stack.has(CBCItems.CALCULATOR_LINK_DATA.get());
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		CalculatorLinkData data = stack.get(CBCItems.CALCULATOR_LINK_DATA.get());
		if (data == null) {
			tooltip.add(Component.translatable("item.cbc_firecontrol.calculator_link.hint")
					.withStyle(ChatFormatting.GRAY));
			return;
		}
		BlockPos pos = data.mount();
		tooltip.add(Component.translatable("item.cbc_firecontrol.calculator_link.linked")
				.withStyle(ChatFormatting.BLUE));
		var line = Component.literal("  " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ())
				.withStyle(ChatFormatting.DARK_GRAY);
		UUID mountId = data.mountIdOrNull();
		if (mountId != null) {
			// Short form: enough to tell two mounts at different coordinates apart in a log or screenshot.
			line.append(Component.literal(" \u00b7 "));
			line.append(Component.translatable("item.cbc_firecontrol.calculator_link.mount_id",
					mountId.toString().substring(0, 8)));
		}
		Level level = context.level();
		// Pointing info only if the linked mount's BE is loaded client-side; coordinates always shown.
		if (level != null && level.getBlockEntity(pos) instanceof AdvancedCannonMountBlockEntity mount) {
			line.append(Component.literal(" \u00b7 "));
			line.append(Component.translatable("item.cbc_firecontrol.calculator_link.mount_info",
					Math.round(mount.getLinkYaw()), Math.round(mount.getLinkPitch())));
		}
		tooltip.add(line);
		tooltip.add(Component.translatable("item.cbc_firecontrol.calculator_link.range_note",
				TrajectoryCalculatorBlockEntity.LINK_RANGE).withStyle(ChatFormatting.GRAY));
	}
}