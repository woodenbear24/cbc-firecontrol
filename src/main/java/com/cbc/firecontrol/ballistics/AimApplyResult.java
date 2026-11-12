package com.cbc.firecontrol.ballistics;

/**
 * What driving one mount to a solved aim actually did.
 *
 * <p>The angles a solved aim carries (see {@link InverseBallistics}) are already stated in the convention the
 * mount's console accepts - yaw clockwise from south, pitch elevation above level - so applying one is only a
 * signed delta per axis. What can still go wrong is that the mount cannot physically reach the requested
 * elevation, because CBC clamps its pitch to the cannon's own limits; that is reported here rather than
 * pretending the barrel is where the solution said it would be.
 */
public record AimApplyResult(Status status, double yawDelta, double pitchDelta, boolean clamped) {

	public enum Status {
		/** The mount was driven; the barrel now sits at the solved aim unless {@link #clamped()} says its own
		 *  limits stopped the pitch part way. */
		APPLIED,
		/** Already on the solved aim within the deadband: nothing was driven, which is the normal state of a
		 *  row that is being re-applied every time the gunner nudges the target. */
		ALREADY_AIMED,
		/** There is no aim to apply: the slot is not linked, the target is out of reach, or the solved path is
		 *  blocked by terrain. */
		NO_SOLUTION,
		/** No assembled cannon to turn (mount disassembled, unloaded, or cannon-less). */
		NOT_ASSEMBLED
	}

	public static AimApplyResult of(Status status) {
		return new AimApplyResult(status, 0.0, 0.0, false);
	}

	/** True when this mount now points where the solution said to (possibly short of it, if clamped). */
	public boolean drove() {
		return status == Status.APPLIED;
	}
}
