package com.cbc.firecontrol.network;

import com.cbc.firecontrol.CBCFireControl;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server -&gt; client, sent to the head-aiming spotter only: where each of this console's guns would actually put
 * its next shot while it follows their sight line, in ABSOLUTE world coordinates, together with the muzzle each
 * shot leaves from. Absolute is deliberate - the spotter's screen is in world space, and on a Sable ship the
 * console's own readouts are hull-relative, so a marker drawn from those would sit a ship's width away from the
 * terrain they are looking at.
 *
 * <p>One entry per link row, in row order, so the client can colour a marker by the row that fired it.
 * {@code status} says what that row's flight reached:
 * <ul>
 * <li>{@link #STATUS_TERRAIN} - the shot left the bore and struck a block, and {@code impact} is that block.</li>
 * <li>{@link #STATUS_STALL} - the shot ran out of speed, left the world, or hit the simulation's tick cap without
 *     touching anything: {@code impact} is where the flight ended, and the marker reads as "nothing out there".</li>
 * <li>{@link #STATUS_NONE} - this row has nothing to aim with (no card, no assembled cannon, no round), so no
 *     flight was flown and nothing is drawn for it.</li>
 * </ul>
 *
 * <p>Doubles, not floats: a Sable ship sits far enough from the origin that a float's mantissa cannot carry its
 * world coordinates to within a block.
 */
public record HeadAimSyncPayload(BlockPos calculator, UUID owner, int count, double[] muzzle, double[] impact,
		byte[] status) implements CustomPacketPayload {

	/** Nothing was flown for this row: it has no shootable gun. */
	public static final byte STATUS_NONE = 0;
	/** The predicted shot hits a block at {@code impact}. */
	public static final byte STATUS_TERRAIN = 1;
	/** The predicted shot ends in the air, out of the world, or at the tick cap. */
	public static final byte STATUS_STALL = 2;

	public static final Type<HeadAimSyncPayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "head_aim_sync"));

	public static final StreamCodec<FriendlyByteBuf, HeadAimSyncPayload> STREAM_CODEC =
			new StreamCodec<FriendlyByteBuf, HeadAimSyncPayload>() {
				@Override
				public HeadAimSyncPayload decode(FriendlyByteBuf buf) {
					BlockPos pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
					UUID owner = buf.readUUID();
					int count = buf.readUnsignedByte();
					double[] muzzle = new double[count * 3];
					double[] impact = new double[count * 3];
					byte[] status = new byte[count];
					for (int i = 0; i < count; i++) {
						muzzle[i * 3] = buf.readDouble();
						muzzle[i * 3 + 1] = buf.readDouble();
						muzzle[i * 3 + 2] = buf.readDouble();
						impact[i * 3] = buf.readDouble();
						impact[i * 3 + 1] = buf.readDouble();
						impact[i * 3 + 2] = buf.readDouble();
						status[i] = buf.readByte();
					}
					return new HeadAimSyncPayload(pos, owner, count, muzzle, impact, status);
				}

				@Override
				public void encode(FriendlyByteBuf buf, HeadAimSyncPayload p) {
					buf.writeInt(p.calculator().getX());
					buf.writeInt(p.calculator().getY());
					buf.writeInt(p.calculator().getZ());
					buf.writeUUID(p.owner());
					buf.writeByte(p.count());
					for (int i = 0; i < p.count(); i++) {
						buf.writeDouble(p.muzzle()[i * 3]);
						buf.writeDouble(p.muzzle()[i * 3 + 1]);
						buf.writeDouble(p.muzzle()[i * 3 + 2]);
						buf.writeDouble(p.impact()[i * 3]);
						buf.writeDouble(p.impact()[i * 3 + 1]);
						buf.writeDouble(p.impact()[i * 3 + 2]);
						buf.writeByte(p.status()[i]);
					}
				}
			};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
