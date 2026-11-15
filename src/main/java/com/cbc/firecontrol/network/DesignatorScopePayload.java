package com.cbc.firecontrol.network;

import com.cbc.firecontrol.CBCFireControl;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -&gt; server: put the OFF-hand designator into (or out of) vanilla's item-use state, which is what makes it
 * scope.
 *
 * <p>Why a packet at all: a use is started server-side, and vanilla only ever starts one from the MAIN hand - the
 * use keys go to {@code InteractionHand.MAIN_HAND} no matter what is held. A designator parked in the off hand as a
 * sight line therefore cannot zoom by itself, and the spotter needs some other key to ask with. That key is
 * {@code key.cbc_firecontrol.designator_scope}, and this is how it reaches the server.
 *
 * <p>The console is named rather than left implicit so the server can check the claim against the same binding the
 * head-aim request uses: an unbound designator in the off hand is nothing to aim, and zooming it would be a magnifier
 * rather than a sight line. Nothing else is trusted from here - the server re-reads the off hand itself, and the use
 * state ends on its own when the item leaves it.
 */
public record DesignatorScopePayload(BlockPos calculator, boolean start) implements CustomPacketPayload {

	public static final Type<DesignatorScopePayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "designator_scope"));

	public static final StreamCodec<FriendlyByteBuf, DesignatorScopePayload> STREAM_CODEC =
			new StreamCodec<FriendlyByteBuf, DesignatorScopePayload>() {
				@Override
				public DesignatorScopePayload decode(FriendlyByteBuf buf) {
					return new DesignatorScopePayload(
							new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()),
							buf.readBoolean());
				}

				@Override
				public void encode(FriendlyByteBuf buf, DesignatorScopePayload p) {
					buf.writeInt(p.calculator().getX());
					buf.writeInt(p.calculator().getY());
					buf.writeInt(p.calculator().getZ());
					buf.writeBoolean(p.start());
				}
			};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
