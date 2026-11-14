package com.cbc.firecontrol.ballistics;

/**
 * What one mount can throw its NEXT shot, measured from the mount itself.
 *
 * <p>{@link InverseBallistics#maximumReach} derives it from the load the gun actually holds - the same
 * {@link MountSnapshot} the readouts are built from, and the same elevation window the solver is allowed to
 * use - so it answers "how far can THIS gun shoot right now", not "how far could it shoot with a full charge
 * stack". A calculator cannot add powder, so an under-charged big cannon honestly reports the shorter reach
 * of the charge it has loaded.
 *
 * <p>The landing point is where the shot descends back through the muzzle's own altitude, which is the
 * definition the solver already uses for plan range (see {@link InverseBallistics}). {@link #straightRange}
 * is the 3D distance from the muzzle to that landing point, which is what a spotter compares a sight line
 * against: the designator's search is bounded by it, so terrain is only loaded as far as the guns can shoot.
 * Terrain itself is NOT consulted - this is the envelope of the gun, not of the ground in front of it.
 *
 * @param reachable     whether there is a shot at all (an unassembled or unloaded gun, or one with no charge,
 *                      has none)
 * @param planRange     horizontal distance from the muzzle to the landing point of the farthest shot
 * @param straightRange 3D distance from the muzzle to that landing point - the mount's own reach
 * @param pitchDeg      elevation that produced it, in {@link InverseBallistics}'s convention
 * @param flightTicks   ticks that shot spends in the air
 */
public record MaxReach(boolean reachable, double planRange, double straightRange, double pitchDeg,
		int flightTicks) {

	/** Nothing to reach with: no cannon, no round, or no charge. */
	public static final MaxReach NONE = new MaxReach(false, 0.0, 0.0, Double.NaN, 0);

	static MaxReach of(double planRange, double straightRange, double pitchDeg, int flightTicks) {
		return new MaxReach(true, planRange, straightRange, pitchDeg, flightTicks);
	}

	/**
	 * NaN-safe comparison, the same reason {@code TrajectoryCalculatorBlockEntity#sameAim} exists: record
	 * equality reads NaN as unequal, which would make every recompute look like a change and push the whole
	 * client packet every interval. Only the reachable fields can be NaN here, so this also treats
	 * "not reachable" as a single shared state.
	 */
	public static boolean same(MaxReach a, MaxReach b) {
		if (a == b)
			return true;
		if (a == null || b == null)
			return false;
		if (a.reachable() != b.reachable())
			return false;
		if (!a.reachable())
			return true;
		return a.planRange() == b.planRange() && a.straightRange() == b.straightRange()
				&& a.pitchDeg() == b.pitchDeg() && a.flightTicks() == b.flightTicks();
	}
}
