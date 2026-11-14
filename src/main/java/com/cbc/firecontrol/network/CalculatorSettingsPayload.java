package com.cbc.firecontrol.network;

import com.cbc.firecontrol.CBCFireControl;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -&gt; server: the fire-mission settings of the Trajectory Calculator at {@code pos}.
 *
 * <p>One value today: how long a mount may hold an order it cannot take, in seconds. The server clamps it to the
 * bounds the target page itself offers, so a packet from a modified client cannot set an unreasonable window.
 * Sent once per commit (Enter in the field or the SET button), never per keystroke.
 */
public record CalculatorSettingsPayload(BlockPos pos, int missionWaitSeconds) implements CustomPacketPayload {

	public static final Type<CalculatorSettingsPayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "calculator_settings"));

	public static final StreamCodec<FriendlyByteBuf, CalculatorSettingsPayload> STREAM_CODEC =
			new StreamCodec<FriendlyByteBuf, CalculatorSettingsPayload>() {
				@Override
				public CalculatorSettingsPayload decode(FriendlyByteBuf buf) {
					return new CalculatorSettingsPayload(new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()),
							buf.readInt());
				}

				@Override
				public void encode(FriendlyByteBuf buf, CalculatorSettingsPayload p) {
					buf.writeInt(p.pos().getX());
					buf.writeInt(p.pos().getY());
					buf.writeInt(p.pos().getZ());
					buf.writeInt(p.missionWaitSeconds());
				}
			};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
