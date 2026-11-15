package com.cbc.firecontrol.network;

import com.cbc.firecontrol.CBCFireControl;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -&gt; server: the sight line the head-aiming spotter's CAMERA is looking along, right now.
 *
 * <p>Why the camera and not the head: what a spotter points a gun line at is the centre of their screen, and that
 * is not always where their head is. In a third-person view the camera sits behind (or in front of) the player and
 * looks at their back; while riding, while lying down under an elytra dive, or while a ship's deck tilts under
 * them, the eye position the server has lags a tick behind what the player actually sees. The camera pose is the
 * only thing that matches the crosshair, so the console follows it.
 *
 * <p>The line is an origin plus a unit direction, both in the coordinate scale the client renders in - which on a
 * Sable ship is the ship's sub-level scale, exactly like the spotter's own entity position. The console does not
 * have to know which scale that is: it hands the sight point at the end of the line to the same bridge it uses for
 * a typed target ({@code com.cbc.firecontrol.compat.SableShipCoordinates#resolveTarget}), which converts only when
 * the two really are different scales.
 *
 * <p>{@code range} is how far along that line the sight point sits, so the line carries a point and not just a
 * bearing: the console drives every bore at that one point, and a line of guns therefore converges on it the way
 * a line of aircraft guns converges on the point their camera's laser lands on. The client picks it by dropping
 * the laser on the terrain it can see, and bounds it by {@code com.cbc.firecontrol.ballistics.HeadAimConvergence};
 * the console re-bounds whatever arrives, because a number from a client is a request rather than a fact.
 *
 * <p>Nothing here is trusted to keep the claim alive: {@code HeadAimRequestPayload} still owns who holds a console,
 * and the console only accepts sight lines from its holder. A spotter who stops sending them (paused client, dropped
 * connection) is read from their own head instead, so the bores never freeze mid-slew on a missing packet.
 */
public record HeadAimSightPayload(BlockPos calculator, double originX, double originY, double originZ,
		float dirX, float dirY, float dirZ, float range) implements CustomPacketPayload {

	public static final Type<HeadAimSightPayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "head_aim_sight"));

	public static final StreamCodec<FriendlyByteBuf, HeadAimSightPayload> STREAM_CODEC =
			new StreamCodec<FriendlyByteBuf, HeadAimSightPayload>() {
				@Override
				public HeadAimSightPayload decode(FriendlyByteBuf buf) {
					return new HeadAimSightPayload(new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()),
							buf.readDouble(), buf.readDouble(), buf.readDouble(),
							buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
				}

				@Override
				public void encode(FriendlyByteBuf buf, HeadAimSightPayload p) {
					buf.writeInt(p.calculator().getX());
					buf.writeInt(p.calculator().getY());
					buf.writeInt(p.calculator().getZ());
					buf.writeDouble(p.originX());
					buf.writeDouble(p.originY());
					buf.writeDouble(p.originZ());
					buf.writeFloat(p.dirX());
					buf.writeFloat(p.dirY());
					buf.writeFloat(p.dirZ());
					buf.writeFloat(p.range());
				}
			};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
