package com.cbc.firecontrol.network;

import com.cbc.firecontrol.CBCFireControl;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -&gt; server: which page of the Trajectory Calculator console is open.
 *
 * <p>The menu needs the answer, not only the screen. Hiding a slot range stops vanilla from drawing it, but a
 * menu that does not know the page would still resolve a click that lands on the coordinates of an invisible
 * slot and would still offer that slot as a shift-click destination. One byte is the whole protocol; the server
 * re-checks that this player really has this console open before it stores the page.
 */
public record CalculatorTabPayload(BlockPos pos, int tab) implements CustomPacketPayload {

	public static final Type<CalculatorTabPayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "calculator_tab"));

	public static final StreamCodec<FriendlyByteBuf, CalculatorTabPayload> STREAM_CODEC =
			new StreamCodec<FriendlyByteBuf, CalculatorTabPayload>() {
				@Override
				public CalculatorTabPayload decode(FriendlyByteBuf buf) {
					return new CalculatorTabPayload(new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()),
							buf.readByte());
				}

				@Override
				public void encode(FriendlyByteBuf buf, CalculatorTabPayload p) {
					buf.writeInt(p.pos().getX());
					buf.writeInt(p.pos().getY());
					buf.writeInt(p.pos().getZ());
					buf.writeByte(p.tab());
				}
			};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
