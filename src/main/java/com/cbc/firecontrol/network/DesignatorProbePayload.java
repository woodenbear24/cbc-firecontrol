package com.cbc.firecontrol.network;

import com.cbc.firecontrol.CBCFireControl;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Client -&gt; server: a designator's sight line found nothing, but it did not reach the end of the world
 * either - it ran out of loaded chunks. Asks the bound console to load terrain along the rest of the line and
 * report what is there, because only the server knows what its mounts can actually shoot at.
 *
 * <p>The line travels as an origin plus a direction rather than as an end point: the client's walk stops at an
 * arbitrary cell, and the useful answer is where to keep looking from, not where the client gave up. The two
 * distances carry the client's own bounds - how far it already tested, and how far its scope line reaches before
 * the world border ends it - so the server can stop a walk that could never have found anything.
 *
 * <p>There is no reply payload: a walk that finds a cell writes the target and files the mission exactly as a
 * direct middle click does, and both routes answer the spotter on their actionbar.
 */
public record DesignatorProbePayload(BlockPos calculator, Vec3 origin, Vec3 direction, double scanned,
		double rayDistance) implements CustomPacketPayload {

	public static final Type<DesignatorProbePayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "designator_probe"));

	public static final StreamCodec<FriendlyByteBuf, DesignatorProbePayload> STREAM_CODEC =
			new StreamCodec<FriendlyByteBuf, DesignatorProbePayload>() {
				@Override
				public DesignatorProbePayload decode(FriendlyByteBuf buf) {
					return new DesignatorProbePayload(
							new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()),
							new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
							new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
							buf.readDouble(), buf.readDouble());
				}

				@Override
				public void encode(FriendlyByteBuf buf, DesignatorProbePayload p) {
					buf.writeInt(p.calculator().getX());
					buf.writeInt(p.calculator().getY());
					buf.writeInt(p.calculator().getZ());
					buf.writeDouble(p.origin().x);
					buf.writeDouble(p.origin().y);
					buf.writeDouble(p.origin().z);
					buf.writeDouble(p.direction().x);
					buf.writeDouble(p.direction().y);
					buf.writeDouble(p.direction().z);
					buf.writeDouble(p.scanned());
					buf.writeDouble(p.rayDistance());
				}
			};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
