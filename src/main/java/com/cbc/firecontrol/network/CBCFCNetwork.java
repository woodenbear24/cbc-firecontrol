package com.cbc.firecontrol.network;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.block.entity.AdvancedCannonMountBlockEntity;
import com.cbc.firecontrol.block.entity.TrajectoryCalculatorBlockEntity;
import com.cbc.firecontrol.client.HeadAimClientEvents;
import com.cbc.firecontrol.client.MountFrequencyScreen;
import com.cbc.firecontrol.item.TargetDesignatorItem;
import com.cbc.firecontrol.item.TargetDesignatorLinkData;
import com.cbc.firecontrol.menu.MountFrequencyMenu;
import com.cbc.firecontrol.menu.TrajectoryCalculatorMenu;
import com.cbc.firecontrol.network.CalculatorAimPayload;
import com.cbc.firecontrol.network.CalculatorTargetPayload;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Payload handlers. handleControl runs on the server; handleStateClient on the client. */
public class CBCFCNetwork {

	public static void handleControl(MountControlPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			BlockPos pos = payload.pos();
			/*
			 * No geometric reach check here: a Sable ship reports the player in the sub-level's local scale and
			 * the mount at the ship's footprint scale (verified case: player at 122,-58,75, mount at
			 * 20481032,128,20483080 in minecraft:overworld), and CBC's transformBlockPos does not bridge those
			 * two, so any distance arithmetic either rejects legitimate ship users or accepts nonsense. The
			 * equivalent guarantee is the session: this payload only ever comes from the frequency panel of
			 * exactly this mount, so require that panel to be open for this position.
			 */
			if (payload.action() != MountAction.REQUEST) {
				if (!(player.containerMenu instanceof MountFrequencyMenu menu) || !pos.equals(menu.mountPos())) {
					CBCFireControl.LOGGER.warn("[cbc_firecontrol] mount {} action {} ignored: {} has no frequency panel"
									+ " open for that position (open panel: {})",
							pos.toShortString(), payload.action(), player.getGameProfile().getName(),
							player.containerMenu instanceof MountFrequencyMenu other ? other.mountPos().toShortString()
									: "none");
					return;
				}
			}

			ServerLevel playerLevel = player.serverLevel();
			BlockEntity found = playerLevel.getBlockEntity(pos);
			Level resolved = playerLevel;
			if (!(found instanceof AdvancedCannonMountBlockEntity)) {
				// The panel position may be the ship's footprint coordinate while the player entity lives in the
				// sub-level instance (or the other way round), so also look in the ship's world dimension.
				ServerLevel world = player.server.overworld();
				if (world != playerLevel) {
					BlockEntity inWorld = world.getBlockEntity(pos);
					if (inWorld instanceof AdvancedCannonMountBlockEntity) {
						found = inWorld;
						resolved = world;
					}
				}
			}
			if (!(found instanceof AdvancedCannonMountBlockEntity mount)) {
				CBCFireControl.LOGGER.warn("[cbc_firecontrol] mount {} action {} ignored: no advanced cannon mount"
								+ " at that position (player level {}, searched {} and {}, found: {})",
						pos.toShortString(), payload.action(), playerLevel.getClass().getSimpleName(),
						playerLevel.dimension().location(), resolved.dimension().location(),
						found == null ? "no block entity" : found.getClass().getSimpleName());
				return;
			}

			switch (payload.action()) {
			case ASSEMBLE -> mount.consoleAssemble();
			case DISASSEMBLE -> mount.consoleDisassemble();
			case FIRE -> mount.consoleFire();
			case YAW -> mount.consoleAdjustYaw(payload.amount());
			case PITCH -> mount.consoleAdjustPitch(payload.amount());
			case REQUEST -> {}
			}

			PacketDistributor.sendToPlayer(player, new MountStatePayload(
					pos, mount.isRunning(), mount.consoleYaw(), mount.consolePitch()));
		});
	}

	public static void handleCalculatorTarget(CalculatorTargetPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			BlockPos pos = payload.pos();
			if (!(player.level().getBlockEntity(pos) instanceof TrajectoryCalculatorBlockEntity calculator))
				return;
			// The same authority rule the menu applies: only a player who can use this block edits its target.
			if (!calculator.canPlayerUse(player))
				return;
			if (payload.clear())
				calculator.clearTarget();
			else
				calculator.setTarget(payload.x(), payload.y(), payload.z());
		});
	}

	public static void handleCalculatorAim(CalculatorAimPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			BlockPos pos = payload.pos();
			if (!(player.level().getBlockEntity(pos) instanceof TrajectoryCalculatorBlockEntity calculator))
				return;
			// Same authority rule as the target entry: only a player who can use this block may order its mounts.
			if (!calculator.canPlayerUse(player))
				return;
			calculator.applyAllAims(player);
		});
	}

	/**
	 * Which page the console shows. Only the menu that is open right now, for exactly this console, is allowed to
	 * change: the page decides which slot ranges take input, so a stale packet must not re-gate a menu the player
	 * has already moved on from, and authority is checked the same way the target entry checks it.
	 */
	public static void handleCalculatorTab(CalculatorTabPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			BlockPos pos = payload.pos();
			if (!(player.containerMenu instanceof TrajectoryCalculatorMenu menu)
					|| !menu.getCalculatorPos().equals(pos))
				return;
			if (!(player.level().getBlockEntity(pos) instanceof TrajectoryCalculatorBlockEntity calculator))
				return;
			if (!calculator.canPlayerUse(player))
				return;
			menu.setActiveTab(payload.tab());
		});
	}

	/**
	 * The channels page's SYNC button: copy the linked mounts' non-empty radio columns into this console's filters
	 * immediately. Authority is the block itself, the same rule the aim order uses. The copy itself is the block
	 * entity's own one-way mirror, so a row the gunner configured by hand keeps whatever its mount does not state.
	 */
	public static void handleCalculatorChannelSync(CalculatorChannelSyncPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			BlockPos pos = payload.pos();
			if (!(player.level().getBlockEntity(pos) instanceof TrajectoryCalculatorBlockEntity calculator))
				return;
			if (!calculator.canPlayerUse(player))
				return;
			calculator.pullChannelsFromMounts();
		});
	}

	/**
	 * The config page's two settings: the wait window a mount may hold an order it cannot take, and the highest
	 * redstone level the console may transmit. Authority is the block itself, the same rule the target entry and
	 * the aim order use; the block entity clamps both, so a modified client can neither open an arbitrarily long
	 * window nor drive a mount radio harder than the console's bounds allow.
	 */
	public static void handleCalculatorSettings(CalculatorSettingsPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			BlockPos pos = payload.pos();
			if (!(player.level().getBlockEntity(pos) instanceof TrajectoryCalculatorBlockEntity calculator))
				return;
			if (!calculator.canPlayerUse(player))
				return;
			calculator.setMissionWaitSeconds(payload.missionWaitSeconds());
			calculator.setMaxLinkLevel(payload.maxLinkLevel());
		});
	}

	/**
	 * A spotter locked a block through a designator bound to this console: take it as the console's target and slew
	 * its mounts onto it, as one order.
	 *
	 * <p>The held, bound designator is the authority here rather than {@code canPlayerUse}: binding a designator
	 * required standing next to the console, so whoever was trusted with the console is trusted to shoot it from
	 * wherever their scope reaches - which is the entire point of the item, and {@code canPlayerUse} (a 64-block
	 * check from the console) would defeat it. Everything is still re-checked server-side, including that a stack
	 * in the player's hands right now is a designator bound to the exact console the packet names.
	 */
	public static void handleTargetDesignatorFix(TargetDesignatorFixPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			BlockPos pos = payload.calculator();
			if (!(player.level().getBlockEntity(pos) instanceof TrajectoryCalculatorBlockEntity calculator)) {
				// The console was dismantled or the chunk is gone: the spotter must hear that, not fire into silence.
				player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.target_designator.console_gone"),
						true);
				return;
			}
			if (heldBoundDesignator(player, pos).isEmpty()) {
				// Either the stack was swapped between the click and the packet arriving, or a client that is not
				// the one this handler means. Either way no target is written.
				player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.target_designator.not_bound"),
						true);
				return;
			}
			if (!canDesignatorMark(player, calculator)) {
				player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.head_aim.offhand_lock"),
						true);
				return;
			}

			/*
			 * The order is a fire mission rather than a one-off aim: it goes into the console's queue and into
			 * every active mount's own queue, where each mount takes it when it can, turns onto it and fires it
			 * over its wireless fire channel. A mount that cannot take it waits it out for the window configured
			 * on the console's config page, and drops it from its own queue when that expires; an order the
			 * mount cannot solve at all is dropped at once. The spotter's answer is the one actionbar line
			 * {@link TrajectoryCalculatorBlockEntity#applyDesignatorLock} puts up, naming the block and how many
			 * queues took the order - the same answer a terrain walk gives when it finds something, so both
			 * routes of a middle click read alike.
			 */
			calculator.applyDesignatorLock(player, new BlockPos(payload.x(), payload.y(), payload.z()));
		});
	}

	/**
	 * A designator's sight line found nothing, but only because it left the chunks the client has. Validates the
	 * request exactly as a lock is validated - a designator in these hands, bound to the console the packet
	 * names - and then hands the line to the console, which is the one that knows how far along it is worth
	 * loading terrain: past its mounts' reach there is nothing to find for this console.
	 */
	public static void handleDesignatorProbe(DesignatorProbePayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			BlockPos pos = payload.calculator();
			if (!(player.level().getBlockEntity(pos) instanceof TrajectoryCalculatorBlockEntity calculator)) {
				// The console was dismantled or its chunk went away: the spotter must hear that, not wait in silence.
				player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.target_designator.console_gone"),
						true);
				return;
			}
			if (heldBoundDesignator(player, pos).isEmpty()) {
				player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.target_designator.not_bound"),
						true);
				return;
			}
			if (!canDesignatorMark(player, calculator)) {
				player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.head_aim.offhand_lock"),
						true);
				return;
			}
			calculator.requestDesignatorProbe(player, payload.origin(), payload.direction(), payload.scanned(),
					payload.rayDistance());
		});
	}

	/**
	 * A player putting a bound designator in their off hand asks to head-aim; taking it out says so. The claim is
	 * verified exactly as a lock is - a designator bound to the console the packet names - and it is verified in the
	 * OFF hand only: the main hand stays free to hold a scope, a tool, or a second designator working for another
	 * gun line, and the console refuses a claim from anyone whose off hand is not holding the bound item.
	 *
	 * <p>Whom the console belongs to is answered by the console itself, because a console follows exactly one sight
	 * line: the first spotter to offer one holds it, and anybody else asking is refused with that name.
	 */
	public static void handleHeadAimRequest(HeadAimRequestPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			BlockPos pos = payload.calculator();
			if (!(player.level().getBlockEntity(pos) instanceof TrajectoryCalculatorBlockEntity calculator)) {
				player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.target_designator.console_gone"),
						true);
				return;
			}
			if (!payload.engage()) {
				calculator.releaseHeadAim(player, "cbc_firecontrol.calculator.head_aim.released");
				return;
			}
			if (!offHandBoundDesignator(player, calculator)) {
				player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.head_aim.needs_offhand"),
						true);
				return;
			}
			calculator.claimHeadAim(player);
		});
	}

	/**
	 * The holder of a head-aim claim reporting where their own camera is pointed - the line the console is to keep
	 * its bores parallel to. Nothing is verified here beyond finding the console the packet names: the console itself
	 * refuses a sight line from anybody who is not its holder, because one console follows exactly one spotter. A
	 * console that went away is simply not there to move, so no answer is sent back for that.
	 */
	public static void handleHeadAimSight(HeadAimSightPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			if (!(player.level().getBlockEntity(payload.calculator())
					 instanceof TrajectoryCalculatorBlockEntity calculator))
				return;
			calculator.updateHeadAimSight(player,
					new Vec3(payload.originX(), payload.originY(), payload.originZ()),
					new Vec3(payload.dirX(), payload.dirY(), payload.dirZ()), payload.range());
		});
	}

	/**
	 * The console's answer to a head-aim claim: where each of its guns would put its next shot. Held on the client
	 * only long enough to draw it, and only for the player the console named as its holder.
	 */
	public static void handleHeadAimSync(HeadAimSyncPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> HeadAimClientEvents.applySync(payload));
	}

	/**
	 * A spotter asking to scope with the designator in their OFF hand, or to stop. The server owns the use state, and
	 * vanilla only ever starts one from the main hand, so this is the only route by which an off-hand designator can
	 * zoom: the key is {@code key.cbc_firecontrol.designator_scope}.
	 *
	 * <p>Verified the way a claim is: the OFF hand really holds a designator bound to the console the packet names,
	 * and the main hand is not already using something (a bow mid-draw, a placed block, a main-hand scope - none of
	 * those may be interrupted by another hand's zoom). A stop is applied only to a use that is actually the off
	 * hand's, so releasing the key never cancels a main-hand action.
	 */
	public static void handleDesignatorScope(DesignatorScopePayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			if (!(player.level().getBlockEntity(payload.calculator())
				 instanceof TrajectoryCalculatorBlockEntity calculator))
				return;
			if (!offHandBoundDesignator(player, calculator))
				return;
			if (payload.start()) {
				if (player.isUsingItem())
					return; // Whatever the main hand is doing outranks a zoom asked for by the other one.
				// LivingEntity#startUsingItem re-reads the stack and refuses one that is not usable, so the
				// off-hand check above is the whole verification this needs.
				player.startUsingItem(InteractionHand.OFF_HAND);
				// The main hand's scope hears its own click inside use(); this path has no use() to lean on, and
				// ServerPlayer#playSound reaches only the player who asked.
				player.playSound(SoundEvents.SPYGLASS_USE, 1.0F, 1.0F);
				return;
			}
			if (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.OFF_HAND)
				player.releaseUsingItem();
		});
	}

	/** The designator in either hand that is bound to this console, or EMPTY when the player holds none. */
	private static ItemStack heldBoundDesignator(ServerPlayer player, BlockPos calculator) {
		for (InteractionHand hand : List.of(InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND)) {
			ItemStack stack = player.getItemInHand(hand);
			if (!(stack.getItem() instanceof TargetDesignatorItem))
				continue;
			TargetDesignatorLinkData data = TargetDesignatorItem.bindingOf(stack);
			if (data != null && data.calculator().equals(calculator))
				return stack;
		}
		return ItemStack.EMPTY;
	}

	/**
	 * Whether the designator sitting in this player's OFF hand is bound to this console. Coordinates decide normally,
	 * and a matching console id also counts: a console picked up and placed elsewhere is still the console the
	 * designator was bound to, and a card whose stored coordinates went stale must not be read as somebody else's
	 * console. The main hand is deliberately not considered - it stays free for a scope or a tool while the off
	 * hand holds the sight line.
	 */
	private static boolean offHandBoundDesignator(ServerPlayer player, TrajectoryCalculatorBlockEntity calculator) {
		ItemStack stack = player.getOffhandItem();
		if (!(stack.getItem() instanceof TargetDesignatorItem))
			return false;
		TargetDesignatorLinkData data = TargetDesignatorItem.bindingOf(stack);
		if (data == null)
			return false;
		UUID id = data.calculatorIdOrNull();
		return data.calculator().equals(calculator.getBlockPos())
				|| (calculator.calculatorId() != null && id != null && calculator.calculatorId().equals(id));
	}

	/**
	 * Whether this player may file a target with a designator right now. Marking is the scope's own action, so the
	 * scope has to be raised (the packet's sender is using an item) and it has to be raised in the MAIN hand: a
	 * bound designator sitting in the off hand is a sight line the console is already following, and a target filed
	 * from there would be an order handed to a console that is being pointed by hand - the same thing the console
	 * shuts its Aim button and its fire missions against for the length of a head-aim claim. An off-hand designator
	 * still zooms, because seeing further is how a spotter aims; it just cannot order anything.
	 */
	private static boolean canDesignatorMark(ServerPlayer player, TrajectoryCalculatorBlockEntity calculator) {
		return player.isUsingItem()
				&& player.getUsedItemHand() == InteractionHand.MAIN_HAND
				&& !offHandBoundDesignator(player, calculator);
	}

	public static void handleStateClient(MountStatePayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (Minecraft.getInstance().screen instanceof MountFrequencyScreen screen)
				screen.applyState(payload.running(), payload.yaw(), payload.pitch());
		});
	}
}
