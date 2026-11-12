package com.cbc.firecontrol.item;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Link payload stored on a {@link CalculatorLinkItem}: the world position of the ONE Advanced Cannon Mount
 * the card points at. Modeled after Create's logistics "Filter Link" item (the link lives in a
 * network-synchronized data component so the item glints and its tooltip can show live target info).
 *
 * Deliberately a single mount rather than a list: the Trajectory Calculator has exactly 5 link slots, so
 * "one card = one mount" is what makes 5 slots mean at most 5 computed firing solutions, and it keeps each
 * slot's readout attributable to one identifiable card.
 */
public record CalculatorLinkData(BlockPos mount, String mountId) {

	/** No identity recorded (a card bound before mount ids existed, or a bind onto something odd). */
	public static final String NO_ID = "";

	public CalculatorLinkData(BlockPos mount) {
		this(mount, NO_ID);
	}

	/** The mount's persistent identity, or null when the card carries only coordinates. */
	public UUID mountIdOrNull() {
		if (mountId == null || mountId.isEmpty())
			return null;
		try {
			return UUID.fromString(mountId);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public CalculatorLinkData withMount(BlockPos newMount) {
		return new CalculatorLinkData(newMount, mountId);
	}

	/** Codec tolerant of the pre-UUID field set, so existing cards keep their link instead of being wiped. */
	public static final Codec<CalculatorLinkData> CODEC =
			RecordCodecBuilder.create(i -> i.group(
					BlockPos.CODEC.fieldOf("mount").forGetter(CalculatorLinkData::mount),
					Codec.STRING.optionalFieldOf("mount_id", NO_ID).forGetter(CalculatorLinkData::mountId)
			).apply(i, CalculatorLinkData::new));

	/** Network codec so the link is synced to the client that holds the stack (drives isFoil + tooltip).
	 *  FriendlyByteBuf is a supertype of RegistryFriendlyByteBuf, so this satisfies networkSynchronized(). */
	public static final StreamCodec<FriendlyByteBuf, CalculatorLinkData> STREAM_CODEC =
			new StreamCodec<FriendlyByteBuf, CalculatorLinkData>() {
				@Override
				public CalculatorLinkData decode(FriendlyByteBuf buf) {
					return new CalculatorLinkData(
							new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()),
							buf.readUtf(36));
				}

				@Override
				public void encode(FriendlyByteBuf buf, CalculatorLinkData data) {
					buf.writeInt(data.mount().getX());
					buf.writeInt(data.mount().getY());
					buf.writeInt(data.mount().getZ());
					buf.writeUtf(data.mountId() == null ? NO_ID : data.mountId());
				}
			};
}