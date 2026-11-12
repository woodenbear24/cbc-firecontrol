package com.cbc.firecontrol.network;

import com.cbc.firecontrol.block.entity.AdvancedCannonMountBlockEntity;
import com.cbc.firecontrol.block.entity.TrajectoryCalculatorBlockEntity;
import com.cbc.firecontrol.client.MountFrequencyScreen;
import com.cbc.firecontrol.network.CalculatorAimPayload;
import com.cbc.firecontrol.network.CalculatorTargetPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
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

	public static void handleStateClient(MountStatePayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (Minecraft.getInstance().screen instanceof MountFrequencyScreen screen)
				screen.applyState(payload.running(), payload.yaw(), payload.pitch());
		});
	}
}
