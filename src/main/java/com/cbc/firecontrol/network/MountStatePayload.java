package com.cbc.firecontrol.network;

import com.cbc.firecontrol.CBCFireControl;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: authoritative mount state sent back to the GUI after open / after each action. */
public record MountStatePayload(BlockPos pos, boolean running, float yaw, float pitch) implements CustomPacketPayload {

	public static final Type<MountStatePayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "mount_state"));

	public static final StreamCodec<FriendlyByteBuf, MountStatePayload> STREAM_CODEC =
			new StreamCodec<FriendlyByteBuf, MountStatePayload>() {
				@Override
				public MountStatePayload decode(FriendlyByteBuf buf) {
					BlockPos pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
					boolean running = buf.readBoolean();
					float yaw = buf.readFloat();
					float pitch = buf.readFloat();
					return new MountStatePayload(pos, running, yaw, pitch);
				}

				@Override
				public void encode(FriendlyByteBuf buf, MountStatePayload p) {
					buf.writeInt(p.pos().getX());
					buf.writeInt(p.pos().getY());
					buf.writeInt(p.pos().getZ());
					buf.writeBoolean(p.running());
					buf.writeFloat(p.yaw());
					buf.writeFloat(p.pitch());
				}
			};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
