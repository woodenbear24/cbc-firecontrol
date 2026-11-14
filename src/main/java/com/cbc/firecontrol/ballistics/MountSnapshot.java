package com.cbc.firecontrol.ballistics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * The live firing parameters read off one assembled Advanced Cannon Mount, in the units CBC itself uses.
 * Produced server-side by {@link MountScanner} (the mounted cannon contraption entity only exists on the
 * server), consumed by {@link TrajectoryComputer}, and carried into the calculator block entity's
 * per-slot {@link TrajectoryPrediction}.
 */
public record MountSnapshot(
		BlockPos mountPos,
		boolean assembled,
		boolean autocannon,
		int barrelCount,
		double chargePower,
		double muzzleSpeed,
		int maxFlightTicks,
		double gravity,
		double drag,
		boolean quadraticDrag,
		Vec3 muzzle,
		Vec3 direction,
		String munitionKey,
		String materialKey,
		double yawDeg,
		double pitchDeg,
		RoundState round) {

	/**
	 * The snapshot callers had before {@code round} existed. A round is assumed present, which is the right
	 * default for anything that models a cannon abstractly (the solver probes) rather than reading one off a
	 * real assembled mount - only {@link MountScanner} knows what is actually loaded.
	 */
	public MountSnapshot(BlockPos mountPos, boolean assembled, boolean autocannon, int barrelCount,
			double chargePower, double muzzleSpeed, int maxFlightTicks, double gravity, double drag,
			boolean quadraticDrag, Vec3 muzzle, Vec3 direction, String munitionKey, String materialKey,
			double yawDeg, double pitchDeg) {
		this(mountPos, assembled, autocannon, barrelCount, chargePower, muzzleSpeed, maxFlightTicks, gravity, drag,
				quadraticDrag, muzzle, direction, munitionKey, materialKey, yawDeg, pitchDeg, RoundState.READY);
	}

	/** Mount has no assembled cannon (or its contraption entity is gone). */
	public static MountSnapshot unassembled(BlockPos mountPos) {
		return new MountSnapshot(mountPos, false, false, 0, 0.0, 0.0, 0,
				0.0, 0.0, false, Vec3.ZERO, Vec3.ZERO, "", "", 0.0, 0.0, RoundState.NO_ROUND);
	}

	/** True when CBC's own {@code fireShot} prerequisites are all satisfied for this mount's next shot. */
	public boolean canFireNext() {
		return assembled && round != null && round.ready();
	}
}