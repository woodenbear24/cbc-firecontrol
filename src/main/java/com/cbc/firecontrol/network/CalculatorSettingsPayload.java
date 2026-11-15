package com.cbc.firecontrol.network;

import com.cbc.firecontrol.CBCFireControl;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -&gt; server: the configuration page of the Trajectory Calculator at {@code pos}.
 *
 * <p>Two values: how long a mount may hold an order it cannot take, in seconds, and the highest redstone level
 * the console may transmit. The server clamps both to the bounds the config page itself offers, so a packet from
 * a modified client can neither open an arbitrarily long window nor push a mount radio harder than its bounds
 * allow. Sent once per commit (Enter in the field or the SET button), never per keystroke.
 */
public record CalculatorSettingsPayload(BlockPos pos, int missionWaitSeconds, int maxLinkLevel)
		implements CustomPacketPayload {

	public static final Type<CalculatorSettingsPayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "calculator_settings"));

	public static final StreamCodec<FriendlyByteBuf, CalculatorSettingsPayload> STREAM_CODEC =
			new StreamCodec<FriendlyByteBuf, CalculatorSettingsPayload>() {
				@Override
				public CalculatorSettingsPayload decode(FriendlyByteBuf buf) {
					return new CalculatorSettingsPayload(new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()),
							buf.readInt(), buf.readInt());
				}

				@Override
				public void encode(FriendlyByteBuf buf, CalculatorSettingsPayload p) {
					buf.writeInt(p.pos().getX());
					buf.writeInt(p.pos().getY());
					buf.writeInt(p.pos().getZ());
					buf.writeInt(p.missionWaitSeconds());
					buf.writeInt(p.maxLinkLevel());
				}
			};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
