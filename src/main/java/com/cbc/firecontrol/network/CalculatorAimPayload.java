package com.cbc.firecontrol.network;

import com.cbc.firecontrol.CBCFireControl;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -&gt; server: aim every mount this Trajectory Calculator at the target it already holds.
 *
 * <p>No aim values travel in the payload on purpose. The solutions live in the calculator's block entity, they
 * are recomputed on the server anyway, and the server is the only place allowed to move a cannon - so the
 * request is "apply whatever you have solved for your rows", and the answer comes back as chat text rather
 * than as a second packet. Same authority rules as {@link CalculatorTargetPayload}.
 */
public record CalculatorAimPayload(BlockPos pos) implements CustomPacketPayload {

	public static final Type<CalculatorAimPayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "calculator_aim"));

	public static final StreamCodec<FriendlyByteBuf, CalculatorAimPayload> STREAM_CODEC =
			new StreamCodec<FriendlyByteBuf, CalculatorAimPayload>() {
				@Override
				public CalculatorAimPayload decode(FriendlyByteBuf buf) {
					return new CalculatorAimPayload(new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()));
				}

				@Override
				public void encode(FriendlyByteBuf buf, CalculatorAimPayload p) {
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
