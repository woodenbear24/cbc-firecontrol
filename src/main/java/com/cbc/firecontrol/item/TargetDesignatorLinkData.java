package com.cbc.firecontrol.item;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Binding stored on a {@link TargetDesignatorItem}: the ONE Trajectory Calculator this designator reports to.
 *
 * <p>Mirrors {@link CalculatorLinkData} in shape and reason: the coordinates are what the server needs to find
 * the block entity, and the calculator's persistent id is what makes the binding survive the calculator being
 * picked up and placed again elsewhere - a designator bound to a gun line that gets rebuilt keeps following that
 * console rather than silently pointing at whatever now occupies the old cell.
 *
 * <p>The binding doubles as the authority credential for a target locked from afar: only a player holding a
 * designator bound to a given calculator may push a target into it (see
 * {@link com.cbc.firecontrol.network.CBCFCNetwork#handleTargetDesignatorFix}), and the binding itself can only be made
 * by standing next to that calculator and right-clicking it.
 */
public record TargetDesignatorLinkData(BlockPos calculator, String calculatorId) {

	/** No identity recorded (a designator bound before calculator ids existed). */
	public static final String NO_ID = "";

	public TargetDesignatorLinkData(BlockPos calculator) {
		this(calculator, NO_ID);
	}

	/** The calculator's persistent identity, or null when the binding carries only coordinates. */
	public UUID calculatorIdOrNull() {
		if (calculatorId == null || calculatorId.isEmpty())
			return null;
		try {
			return UUID.fromString(calculatorId);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Codec tolerant of the pre-UUID field set, so existing designators keep their binding. */
	public static final Codec<TargetDesignatorLinkData> CODEC =
			RecordCodecBuilder.create(i -> i.group(
					BlockPos.CODEC.fieldOf("calculator").forGetter(TargetDesignatorLinkData::calculator),
					Codec.STRING.optionalFieldOf("calculator_id", NO_ID).forGetter(TargetDesignatorLinkData::calculatorId)
			).apply(i, TargetDesignatorLinkData::new));

	/** Network codec so the binding is visible client-side (drives isFoil + tooltip + the middle-click gate).
	 *  FriendlyByteBuf is a supertype of RegistryFriendlyByteBuf, so this satisfies networkSynchronized(). */
	public static final StreamCodec<FriendlyByteBuf, TargetDesignatorLinkData> STREAM_CODEC =
			new StreamCodec<FriendlyByteBuf, TargetDesignatorLinkData>() {
				@Override
				public TargetDesignatorLinkData decode(FriendlyByteBuf buf) {
					return new TargetDesignatorLinkData(
							new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()),
							buf.readUtf(36));
				}

				@Override
				public void encode(FriendlyByteBuf buf, TargetDesignatorLinkData data) {
					buf.writeInt(data.calculator().getX());
					buf.writeInt(data.calculator().getY());
					buf.writeInt(data.calculator().getZ());
					buf.writeUtf(data.calculatorId() == null ? NO_ID : data.calculatorId());
				}
			};
}