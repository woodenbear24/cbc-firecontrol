package com.cbc.firecontrol.ballistics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * A firing solution produced for one Trajectory Calculator link slot.
 *
 * <p>All distances are in blocks, all speeds in blocks/tick, all angles in degrees; the numbers describe the
 * shot CBC would actually fire from the linked mount in its CURRENT state (current yaw/pitch, current loaded
 * round, current assembled structure), which is why the calculator recomputes continuously instead of once.
 *
 * @param status          why this slot has / has not a solution
 * @param impactX/Y/Z     where the predicted shot left the simulated flight (block hit, world floor, or the
 *                        position reached when the shot's flight time ended)
 * @param horizontalRange plan distance from muzzle to impact, for the GUI readout
 * @param straightRange   3D distance from muzzle to impact
 * @param muzzleSpeed     launch speed used by the simulation (blocks/tick)
 * @param flightTicks     simulated ticks until impact
 * @param barrelCount     barrels counted in the assembled cannon
 * @param chargePower     loaded charge power (big cannons); 0 for autocannons
 * @param munitionKey     translation key of the loaded warhead block ("" when unknown)
 * @param materialKey     autocannon barrel material id ("" for big cannons)
 * @param yawDeg/pitchDeg the mount's current aiming as used by the simulation
 */
public record TrajectoryPrediction(
		LinkStatus status,
		double impactX, double impactY, double impactZ,
		double horizontalRange, double straightRange,
		double muzzleSpeed, int flightTicks,
		int barrelCount, double chargePower,
		String munitionKey, String materialKey,
		double yawDeg, double pitchDeg) {

	public static final TrajectoryPrediction EMPTY =
			new TrajectoryPrediction(LinkStatus.EMPTY_SLOT, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", "", 0, 0);

	/** A slot-level failure (no simulation ran); keeps the mount aiming readout when it is known. */
	public static TrajectoryPrediction failure(LinkStatus status, double yawDeg, double pitchDeg) {
		return new TrajectoryPrediction(status, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", "", yawDeg, pitchDeg);
	}
}
