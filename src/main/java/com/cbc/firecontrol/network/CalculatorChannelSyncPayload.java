package com.cbc.firecontrol.network;

import com.cbc.firecontrol.CBCFireControl;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -&gt; server: pull the linked mounts' radio columns into this Trajectory Calculator's channel filters
 * right now, instead of waiting for the console's periodic mirror to run.
 *
 * <p>No filters travel in the payload. The mount radio is the authority for a channel's frequency pair, the
 * copy is one-way, and only the server can read the mounts - so the request is "re-read what your rows' mounts
 * listen for", exactly what the periodic mirror already does. The button exists because a gunner who edits a
 * mount's radio wants the console to follow immediately, and because a mirror that runs by itself is invisible:
 * pressing the button is what makes the copy a deliberate act the player can see.
 */
public record CalculatorChannelSyncPayload(BlockPos pos) implements CustomPacketPayload {

	public static final Type<CalculatorChannelSyncPayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "calculator_channel_sync"));

	public static final StreamCodec<FriendlyByteBuf, CalculatorChannelSyncPayload> STREAM_CODEC =
			new StreamCodec<FriendlyByteBuf, CalculatorChannelSyncPayload>() {
				@Override
				public CalculatorChannelSyncPayload decode(FriendlyByteBuf buf) {
					return new CalculatorChannelSyncPayload(new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()));
				}

				@Override
				public void encode(FriendlyByteBuf buf, CalculatorChannelSyncPayload p) {
					buf.writeInt(p.pos().getX());
					buf.writeInt(p.pos().getY());
					buf.writeInt(p.pos().getZ());
				}
			};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
