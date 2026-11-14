package com.cbc.firecontrol.block.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/**
 * One fire mission: an impact point a console was ordered to put on its mounts.
 *
 * <p>The coordinates are absolute world positions in the same convention the console's own target uses
 * (the designator locks whole block cells, manual entry types them), so a mission and the console target
 * are comparable without conversion. A mission carries no ballistics of its own: what a mount must do to
 * hit it is solved per mount, per check, by the console's solver.
 */
public record FireMission(double x, double y, double z) {

	public Vec3 asVec() {
		return new Vec3(x, y, z);
	}

	/** True when every coordinate is a usable number; wild values are noise and are refused at entry. */
	public boolean isFinite() {
		return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
	}

	public CompoundTag write(CompoundTag tag) {
		tag.putDouble("X", x);
		tag.putDouble("Y", y);
		tag.putDouble("Z", z);
		return tag;
	}

	public static FireMission read(CompoundTag tag) {
		return new FireMission(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
	}
}
