package com.cbc.firecontrol.item;

import com.cbc.firecontrol.block.entity.TrajectoryCalculatorBlockEntity;
import com.cbc.firecontrol.init.CBCItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

import net.neoforged.neoforge.common.ItemAbility;
import net.neoforged.neoforge.common.ItemAbilities;

/**
 * Designator - a faithful copy of vanilla's spyglass behaviour under our own item id, so the fire-control
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
 * {@link com.cbc.firecontrol.client.TargetDesignatorClientEvents} through NeoForge's {@code ComputeFovModifierEvent}.
 *
 * <p>On top of the optics it carries a binding to ONE Trajectory Calculator - right-click the console to bind,
 * sneak-right-click that console to unbind, sneak-right-click air to clear - and while the scope is up a middle
 * click locks the block the crosshair sits on into that calculator and orders its mounts onto it. The binding is
 * the credential for that order: binding requires standing next to the console, so a spotter who was allowed to
 * bind is allowed to shoot the console from wherever their designator reaches.
 */
public class TargetDesignatorItem extends Item {

	/** Vanilla {@code SpyglassItem.USE_DURATION}: the scope can be held until the player releases or swaps. */
	public static final int USE_DURATION = 1200;
	/** Vanilla {@code SpyglassItem.ZOOM_FOV_MODIFIER}: 10 % of the normal FOV while scoping. */
	public static final float ZOOM_FOV_MODIFIER = 0.1F;

	/**
	 * The far end of the scope line a target is locked along. There is no fixed length any more: the line runs out
	 * toward the world border, and the cell walk in {@code TargetDesignatorClientEvents} ends on its own the moment
	 * it leaves simulated space (the first cell whose chunk is not loaded). What the world and the loaded chunks
	 * allow is the reach; the console accepts whatever comes out of it. A line that stops on unloaded terrain is
	 * handed to the bound console, which loads along it until either a block inside its mounts' reach turns up or
	 * the line leaves that reach (see {@code DesignatorProbe}).
	 */
	public static Vec3 scopeEndPoint(Level level, Player player) {
		Vec3 eye = player.getEyePosition(1.0F);
		Vec3 look = player.getLookAngle();
		double reach = level.getWorldBorder().getDistanceToBorder(player);
		if (!Double.isFinite(reach))
			reach = 1.0;
		// Sitting on or past the border leaves nothing ahead to look for; a unit length keeps the answer honest
		// ("nothing on the line") instead of producing a degenerate zero-length ray.
		return eye.add(look.scale(Math.max(reach, 1.0)));
	}

	/** The calculator this designator reports to, or null when it is not bound to one yet. */
	public static TargetDesignatorLinkData bindingOf(ItemStack stack) {
		return stack.get(CBCItems.TARGET_DESIGNATOR_LINK_DATA.get());
	}

	public TargetDesignatorItem(Properties properties) {
		super(properties);
	}

	/**
	 * Server-side binding handler invoked from {@link com.cbc.firecontrol.block.TrajectoryCalculatorBlock#useItemOn}
	 * when this item is right-clicked onto a Trajectory Calculator. One console per designator, following the same
	 * rules {@link CalculatorLinkItem#applyLink} uses for mounts:
	 *   - plain right-click on a console  -> binds (or re-binds when the old console is gone / no longer a
	 *     calculator, e.g. it was picked up and placed somewhere else);
	 *   - sneak right-click on that same console -> unbinds the designator;
	 *   - plain right-click while the designator still points at another live console -> refused with a hint, so a
	 *     stray click cannot silently steal a designator that is working for another gun line.
	 * The block guarantees the clicked target really is a Trajectory Calculator.
	 */
	public static void applyBind(ItemStack stack, Level level, BlockPos pos, Player player) {
		TargetDesignatorLinkData data = bindingOf(stack);
		BlockPos bound = data == null ? null : data.calculator();
		UUID boundId = data == null ? null : data.calculatorIdOrNull();
		TrajectoryCalculatorBlockEntity clickedConsole =
				level.getBlockEntity(pos) instanceof TrajectoryCalculatorBlockEntity calculator ? calculator : null;

		if (player.isShiftKeyDown()) {
			if (sameConsole(bound, boundId, pos, clickedConsole)) {
				stack.remove(CBCItems.TARGET_DESIGNATOR_LINK_DATA.get());
				player.displayClientMessage(Component.translatable("item.cbc_firecontrol.target_designator.unbound"), true);
			}
			return;
		}

		if (clickedConsole == null || !clickedConsole.canPlayerUse(player))
			return;
		boolean designatorInUse = data != null && !sameConsole(bound, boundId, pos, clickedConsole)
				&& bound != null && level.getBlockEntity(bound) instanceof TrajectoryCalculatorBlockEntity;
		if (designatorInUse) {
			player.displayClientMessage(Component.translatable("item.cbc_firecontrol.target_designator.rebind_hint",
					bound.getX(), bound.getY(), bound.getZ()), true);
			return;
		}

		UUID calculatorId = clickedConsole.calculatorId();
		stack.set(CBCItems.TARGET_DESIGNATOR_LINK_DATA.get(), new TargetDesignatorLinkData(pos,
				calculatorId == null ? TargetDesignatorLinkData.NO_ID : calculatorId.toString()));
		player.displayClientMessage(Component.translatable("item.cbc_firecontrol.target_designator.bound",
				pos.getX(), pos.getY(), pos.getZ()), true);
	}

	/**
	 * True when the clicked block IS the console the designator already points at. Coordinates normally decide, but
	 * a matching calculator id also counts: after a rebuild the SAME console sits at new coordinates, and clicking
	 * its old cell (now something else) must not be read as "unbind from this other block".
	 */
	private static boolean sameConsole(BlockPos boundCoords, UUID boundId, BlockPos clicked,
			TrajectoryCalculatorBlockEntity clickedConsole) {
		if (clicked.equals(boundCoords))
			return true;
		return boundId != null && clickedConsole != null && boundId.equals(clickedConsole.calculatorId());
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
		ItemStack stack = player.getItemInHand(hand);
		// Sneak-right-click in the air (a console was aimed at nothing, so useItemOn never ran): clear the binding.
		// FAIL instead of sidedSuccess so the client does not raise the scope for a split second over an unbind.
		if (player.isShiftKeyDown() && stack.has(CBCItems.TARGET_DESIGNATOR_LINK_DATA.get())) {
			if (!level.isClientSide) {
				stack.remove(CBCItems.TARGET_DESIGNATOR_LINK_DATA.get());
				player.displayClientMessage(Component.translatable("item.cbc_firecontrol.target_designator.unbound"), true);
			}
			return InteractionResultHolder.fail(stack);
		}
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

	/** Bound to a console -> enchantment glint, the same "this tool is armed" visual as the Calculator Link. */
	@Override
	public boolean isFoil(ItemStack stack) {
		return stack.has(CBCItems.TARGET_DESIGNATOR_LINK_DATA.get());
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
			TooltipFlag flag) {
		TargetDesignatorLinkData data = bindingOf(stack);
		if (data == null) {
			tooltip.add(Component.translatable("item.cbc_firecontrol.target_designator.hint").withStyle(ChatFormatting.GRAY));
			return;
		}
		BlockPos pos = data.calculator();
		tooltip.add(Component.translatable("item.cbc_firecontrol.target_designator.linked").withStyle(ChatFormatting.BLUE));
		var line = Component.literal("  " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ())
				.withStyle(ChatFormatting.DARK_GRAY);
		UUID calculatorId = data.calculatorIdOrNull();
		if (calculatorId != null) {
			// Short form: enough to tell two consoles apart in a log or a screenshot.
			line.append(Component.literal(" \u00b7 "));
			line.append(Component.translatable("item.cbc_firecontrol.target_designator.calculator_id",
					calculatorId.toString().substring(0, 8)));
		}
		tooltip.add(line);
		Level level = context.level();
		// Console details come from the block entity, so they are only available when it is loaded client-side;
		// the bound coordinates above are shown either way.
		if (level != null && level.getBlockEntity(pos) instanceof TrajectoryCalculatorBlockEntity calculator) {
			if (calculator.hasTarget())
				tooltip.add(Component.translatable("item.cbc_firecontrol.target_designator.stored_target",
						Math.round(calculator.targetX()), Math.round(calculator.targetY()),
						Math.round(calculator.targetZ())).withStyle(ChatFormatting.GRAY));
			/*
			 * What the bound console can shoot at, which is also the bound on a middle click: when the scope
			 * line finds nothing inside the terrain the client has simulated, the console loads along that line
			 * only as far as its own mounts can throw a shot. Past that there is nothing for it to lock.
			 */
			double range = calculator.maxConsoleRange();
			if (range > 0.0)
				tooltip.add(Component.translatable("item.cbc_firecontrol.target_designator.console_range",
						String.format("%.0f", range)).withStyle(ChatFormatting.DARK_AQUA));
		}
		tooltip.add(Component.translatable("item.cbc_firecontrol.target_designator.usage_note").withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.translatable("item.cbc_firecontrol.target_designator.range_note")
				.withStyle(ChatFormatting.DARK_GRAY));
	}

	private void stopUsing(LivingEntity entity) {
		entity.playSound(SoundEvents.SPYGLASS_STOP_USING, 1.0F, 1.0F);
	}
}
