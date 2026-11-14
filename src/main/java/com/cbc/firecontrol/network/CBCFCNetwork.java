package com.cbc.firecontrol.network;

import com.cbc.firecontrol.block.entity.AdvancedCannonMountBlockEntity;
import com.cbc.firecontrol.block.entity.TrajectoryCalculatorBlockEntity;
import com.cbc.firecontrol.client.MountFrequencyScreen;
import com.cbc.firecontrol.item.TargetDesignatorItem;
import com.cbc.firecontrol.item.TargetDesignatorLinkData;
import com.cbc.firecontrol.menu.TrajectoryCalculatorMenu;
import com.cbc.firecontrol.network.CalculatorAimPayload;
import com.cbc.firecontrol.network.CalculatorTargetPayload;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Payload handlers. handleControl runs on the server; handleStateClient on the client. */
public class CBCFCNetwork {

	public static void handleControl(MountControlPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			var level = player.serverLevel();
			BlockPos pos = payload.pos();
			double dx = pos.getX() + 0.5 - player.getX();
			double dy = pos.getY() + 0.5 - player.getY();
			double dz = pos.getZ() + 0.5 - player.getZ();
			if (dx * dx + dy * dy + dz * dz > 64.0 * 64.0)
				return;
			if (!(level.getBlockEntity(pos) instanceof AdvancedCannonMountBlockEntity mount))
				return;

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

			calculator.setTarget(payload.x(), payload.y(), payload.z());
			/*
			 * The order is a fire mission rather than a one-off aim: it goes into the console's queue and
			 * into every active mount's own queue, where each mount takes it when it can, turns onto it and
			 * fires it over its wireless fire channel. A mount that cannot take it inside its refusal
			 * chances drops that mission on its own and moves to the next one behind it.
			 */
			int handed = calculator.addMission(payload.x(), payload.y(), payload.z());
			player.displayClientMessage(Component.translatable(
					"cbc_firecontrol.calculator.target_designator.mission", handed), true);
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

	public static void handleStateClient(MountStatePayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (Minecraft.getInstance().screen instanceof MountFrequencyScreen screen)
				screen.applyState(payload.running(), payload.yaw(), payload.pitch());
		});
	}
}
