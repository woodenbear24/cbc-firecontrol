package com.cbc.firecontrol.network;

import com.cbc.firecontrol.block.entity.FireControlMountBlockEntity;
import com.cbc.firecontrol.client.FireControlMountScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Payload handlers. {@code handleControl} runs on the server (main thread); {@code handleStateClient} on the client. */
public class CBCFCNetwork {

	public static void handleControl(MountControlPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			var level = player.serverLevel();
			BlockPos pos = payload.pos();
			// Cheap anti-abuse: the mount must be within interaction-ish range of the player.
			double dx = pos.getX() + 0.5 - player.getX();
			double dy = pos.getY() + 0.5 - player.getY();
			double dz = pos.getZ() + 0.5 - player.getZ();
			if (dx * dx + dy * dy + dz * dz > 64.0 * 64.0)
				return;
			if (!(level.getBlockEntity(pos) instanceof FireControlMountBlockEntity mount))
				return;

			switch (payload.action()) {
				case ASSEMBLE -> mount.consoleAssemble();
				case DISASSEMBLE -> mount.consoleDisassemble();
				case FIRE -> mount.consoleFire();
				case YAW -> mount.consoleAdjustYaw(payload.amount());
				case PITCH -> mount.consoleAdjustPitch(payload.amount());
				case REQUEST -> {}
			}

			// Reply authoritative state to the requesting client.
			PacketDistributor.sendToPlayer(player, new MountStatePayload(
					pos, mount.isRunning(), mount.consoleYaw(), mount.consolePitch()));
		});
	}

	public static void handleStateClient(MountStatePayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (Minecraft.getInstance().screen instanceof FireControlMountScreen screen)
				screen.applyState(payload.running(), payload.yaw(), payload.pitch());
		});
	}
}
