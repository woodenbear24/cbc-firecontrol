package com.cbc.firecontrol.network;

import com.cbc.firecontrol.CBCFireControl;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -&gt; server: a spotter locked the block their designator was pointed at, and wants the calculator it is
 * bound to to take that block as its target and slew its mounts onto it.
 *
 * <p>One packet rather than a {@link CalculatorTargetPayload} followed by a {@link CalculatorAimPayload}, because
 * the two halves are one order here: a target that nothing is aimed at is only half a request, and sending them
 * together means the server can validate the whole thing at once (held designator, its binding, the coordinate
 * bound) before anything moves. The target travels as absolute integer block coordinates - the designator locks
 * a block, not a free point - and the calculator converts them into muzzle-relative plan range / bearing itself.
 */
public record TargetDesignatorFixPayload(BlockPos calculator, int x, int y, int z) implements CustomPacketPayload {

	public static final Type<TargetDesignatorFixPayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "target_designator_fix"));

	public static final StreamCodec<FriendlyByteBuf, TargetDesignatorFixPayload> STREAM_CODEC =
			new StreamCodec<FriendlyByteBuf, TargetDesignatorFixPayload>() {
				@Override
				public TargetDesignatorFixPayload decode(FriendlyByteBuf buf) {
					return new TargetDesignatorFixPayload(
							new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()),
							buf.readInt(), buf.readInt(), buf.readInt());
				}

				@Override
				public void encode(FriendlyByteBuf buf, TargetDesignatorFixPayload p) {
					buf.writeInt(p.calculator().getX());
					buf.writeInt(p.calculator().getY());
					buf.writeInt(p.calculator().getZ());
					buf.writeInt(p.x());
					buf.writeInt(p.y());
					buf.writeInt(p.z());
				}
			};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}