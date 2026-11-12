package com.cbc.firecontrol.network;

import com.cbc.firecontrol.CBCFireControl;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -&gt; server: the target point entered on the Trajectory Calculator at {@code pos}.
 *
 * <p>Absolute world coordinates, because that is what the GUI fields hold and what the ballistics solver
 * consumes directly; the calculator converts them into muzzle-relative plan range / bearing itself. Sent once
 * per commit (Enter in a field, the SET button, or the CLEAR button), never per keystroke.
 */
public record CalculatorTargetPayload(BlockPos pos, double x, double y, double z, boolean clear)
		implements CustomPacketPayload {

	public static final Type<CalculatorTargetPayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "calculator_target"));

	public static final StreamCodec<FriendlyByteBuf, CalculatorTargetPayload> STREAM_CODEC =
			new StreamCodec<FriendlyByteBuf, CalculatorTargetPayload>() {
				@Override
				public CalculatorTargetPayload decode(FriendlyByteBuf buf) {
					BlockPos pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
					return new CalculatorTargetPayload(pos, buf.readDouble(), buf.readDouble(), buf.readDouble(),
							buf.readBoolean());
				}

				@Override
				public void encode(FriendlyByteBuf buf, CalculatorTargetPayload p) {
					buf.writeInt(p.pos().getX());
					buf.writeInt(p.pos().getY());
					buf.writeInt(p.pos().getZ());
					buf.writeDouble(p.x());
					buf.writeDouble(p.y());
					buf.writeDouble(p.z());
					buf.writeBoolean(p.clear());
				}
			};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
