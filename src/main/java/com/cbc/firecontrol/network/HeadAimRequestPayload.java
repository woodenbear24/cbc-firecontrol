package com.cbc.firecontrol.network;

import com.cbc.firecontrol.CBCFireControl;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -&gt; server: the player wants the Trajectory Calculator at {@code pos} to head-aim for them
 * ({@code engage} true) or to stop doing so (false).
 *
 * <p>Head aiming is the designator's off-hand mode: the console drives its mounts so their bores stay parallel to
 * wherever the spotter is looking, and paints the predicted impact of each gun back onto their screen. Because a
 * console can only follow one sight line at a time, the request is a claim on the console rather than a command to
 * it - the first spotter to put a bound designator in their off hand holds it, and anyone else asking is refused
 * until that spotter lets go.
 *
 * <p>The server does not trust this packet to keep the claim alive: the console re-checks every tick that its
 * holder is still online and still holding the very designator this console is bound to in their off hand, so a
 * dropped item, a death or a logout ends the mode without needing a release packet to arrive.
 */
public record HeadAimRequestPayload(BlockPos calculator, boolean engage) implements CustomPacketPayload {

	public static final Type<HeadAimRequestPayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "head_aim_request"));

	public static final StreamCodec<FriendlyByteBuf, HeadAimRequestPayload> STREAM_CODEC =
			new StreamCodec<FriendlyByteBuf, HeadAimRequestPayload>() {
				@Override
				public HeadAimRequestPayload decode(FriendlyByteBuf buf) {
					return new HeadAimRequestPayload(new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()),
							buf.readBoolean());
				}

				@Override
				public void encode(FriendlyByteBuf buf, HeadAimRequestPayload p) {
					buf.writeInt(p.calculator().getX());
					buf.writeInt(p.calculator().getY());
					buf.writeInt(p.calculator().getZ());
					buf.writeBoolean(p.engage());
				}
			};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
