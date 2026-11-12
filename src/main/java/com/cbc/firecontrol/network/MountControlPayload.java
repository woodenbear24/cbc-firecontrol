package com.cbc.firecontrol.network;

import com.cbc.firecontrol.CBCFireControl;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: a console button pressed on an Advanced Cannon Mount at {@code pos}. */
public record MountControlPayload(BlockPos pos, MountAction action, int amount) implements CustomPacketPayload {

	public static final Type<MountControlPayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "mount_control"));

	public static final StreamCodec<FriendlyByteBuf, MountControlPayload> STREAM_CODEC =
			new StreamCodec<FriendlyByteBuf, MountControlPayload>() {
				@Override
				public MountControlPayload decode(FriendlyByteBuf buf) {
					BlockPos pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
					int a = buf.readVarInt();
					int amount = buf.readInt();
					MountAction action = (a >= 0 && a < MountAction.ACTIONS.length)
							? MountAction.ACTIONS[a] : MountAction.REQUEST;
					return new MountControlPayload(pos, action, amount);
				}

				@Override
				public void encode(FriendlyByteBuf buf, MountControlPayload p) {
					buf.writeInt(p.pos().getX());
					buf.writeInt(p.pos().getY());
					buf.writeInt(p.pos().getZ());
					buf.writeVarInt(p.action().ordinal());
					buf.writeInt(p.amount());
				}
			};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
