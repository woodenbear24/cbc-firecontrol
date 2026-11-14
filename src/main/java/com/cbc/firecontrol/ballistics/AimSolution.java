package com.cbc.firecontrol.ballistics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * The result of {@link InverseBallistics#solve}: the aim that would put a shot on the requested target, which
 * of the two possible arcs that aim uses, and - when nothing hits - the single number the gunner could change
 * to make it hit.
 *
 * <p>Angles use {@link InverseBallistics}'s direction convention (see {@code directionFromAngles}), NOT the
 * mount's console values - converting a solution into console yaw/pitch deltas is the wiring step, and the
 * {@link #yawDelta(MountSnapshot)} / {@link #pitchDelta(MountSnapshot)} helpers carry the correction relative
 * to how the mount is aimed right now. Those helpers re-derive the mount's CURRENT angle from the scanned
 * barrel direction rather than reading the snapshot's console angles, because the two conventions do not agree
 * on elevation (see {@link #pitchDelta(MountSnapshot)}).
 *
 * @param outcome         whether there is a solution at all, and why not if there is not
 * @param yawDeg          horizontal aim the solver fired along
 * @param pitchDeg        elevation that produced the hit
 * @param chargePower     total charge power assumed for big cannons; 0 for autocannons (fixed speed there)
 * @param muzzleSpeed     launch speed of the solved shot, blocks/tick
 * @param impactX/Y/Z     where the verified free-flight simulation actually put the shot
 * @param targetPlanRange plan distance from muzzle to the requested target
 * @param impactPlanRange plan distance from muzzle to the simulated impact
 * @param altitudeError   impact Y minus target Y (positive = over the top of the target)
 * @param flightTicks     simulated ticks from muzzle to impact
 * @param evaluations     simulated flights used, for cost/telemetry
 * @param arc             which side of the range peak this solution sits on ({@link AimArc})
 * @param alternativeArc  true when a solution on the OTHER arc was also found, i.e. the gunner could have had
 *                        a plunging shot instead of this flat one (or vice versa) at the same charge
 * @param suggestedPitchDeg for {@link AimOutcome#ELEVATION_LIMITED}: the elevation that WOULD hit, else NaN
 * @param suggestedCharge   for {@link AimOutcome#CHARGE_INSUFFICIENT}: the charge that WOULD reach, else NaN
 */
public record AimSolution(
		AimOutcome outcome,
		double yawDeg, double pitchDeg,
		double chargePower, double muzzleSpeed,
		double impactX, double impactY, double impactZ,
		double targetPlanRange, double impactPlanRange, double altitudeError,
		int flightTicks, int evaluations,
		AimArc arc, boolean alternativeArc,
		double suggestedPitchDeg, double suggestedCharge) {

	/** Placeholder for "no solution has been computed for this slot yet" (never rendered as a verdict). */
	public static final AimSolution EMPTY =
			failure(AimOutcome.INVALID_TARGET, MountSnapshot.unassembled(BlockPos.ZERO), 0, 0);

	public static AimSolution failure(AimOutcome outcome, MountSnapshot snapshot, double targetPlanRange,
			int evaluations) {
		return failure(outcome, snapshot, targetPlanRange, Double.NaN, Double.NaN, evaluations);
	}

	/** A failure that also names the elevation and/or charge the gunner would have needed. */
	public static AimSolution failure(AimOutcome outcome, MountSnapshot snapshot, double targetPlanRange,
			double suggestedPitchDeg, double suggestedCharge, int evaluations) {
		return new AimSolution(outcome, snapshot.yawDeg(), snapshot.pitchDeg(), snapshot.chargePower(),
				snapshot.muzzleSpeed(), 0, 0, 0, targetPlanRange, 0, 0, 0, evaluations,
				AimArc.FLAT, false, suggestedPitchDeg, suggestedCharge);
	}

	public boolean hasSolution() {
		return outcome.hasSolution();
	}

	public boolean hasSuggestedPitch() {
		return !Double.isNaN(suggestedPitchDeg);
	}

	public boolean hasSuggestedCharge() {
		return !Double.isNaN(suggestedCharge);
	}

	/** Plan miss: how far short (negative) or long (positive) the verified impact lands of the target. */
	public double planError() {
		return impactPlanRange - targetPlanRange;
	}

	/** 2D miss distance in the vertical plane that contains the shot. */
	public double missDistance() {
		return Math.hypot(planError(), altitudeError);
	}

	public Vec3 impact() {
		return new Vec3(impactX, impactY, impactZ);
	}

	/** Copy with the "the other arc works too" flag set, so the solver can report it after ranking. */
	public AimSolution withAlternativeArc(boolean alternative) {
		return alternative == alternativeArc ? this : new AimSolution(outcome, yawDeg, pitchDeg, chargePower,
				muzzleSpeed, impactX, impactY, impactZ, targetPlanRange, impactPlanRange, altitudeError,
				flightTicks, evaluations, arc, alternative, suggestedPitchDeg, suggestedCharge);
	}

	/** Lang key for this solution's arc label, for the readout that renders {@link #arc}. */
	public String arcLangKey() {
		return arc.langKey();
	}

	/**
	 * How much yaw the mount still has to turn from where it is aimed now (wrapped to -180..180).
	 *
	 * <p>The mount's current angle is re-derived from {@link MountSnapshot#direction()} - the scanned barrel's
	 * world-space direction, which is the very convention these solutions are written in - rather than read off
	 * {@link MountSnapshot#yawDeg()}. Console yaw happens to count the same way as {@link InverseBallistics#yawOf}
	 * (CBC's cannonYaw runs SOUTH 0 -> WEST 90), so reading it would work by luck; reading the barrel works by
	 * construction and also follows a mount that something other than this mod slewed.
	 */
	public double yawDelta(MountSnapshot snapshot) {
		return assembledDelta(snapshot,
				normalizeDelta(yawDeg - InverseBallistics.yawOf(snapshot.direction())));
	}

	/**
	 * How much elevation the mount still has to change from where it is aimed now.
	 *
	 * <p>Re-derived from the scanned barrel for the same reason as {@link #yawDelta}, and here it is not a
	 * stylistic choice: CBC stores the NEGATIVE of the world elevation for a mount assembled barrel-down
	 * ({@code initialOrientation == DOWN}), so subtracting {@link MountSnapshot#pitchDeg()} would send an
	 * inverted mount the wrong way instead of onto the solved aim.
	 */
	public double pitchDelta(MountSnapshot snapshot) {
		return assembledDelta(snapshot, pitchDeg - InverseBallistics.pitchOf(snapshot.direction()));
	}

	/**
	 * A delta worth acting on only exists for a mount that has a cannon to aim. An unassembled snapshot
	 * carries a zero direction, whose {@code atan2} reads as level due north, and reporting a correction
	 * against that would suggest a slew a mount with no barrel cannot perform.
	 */
	private static double assembledDelta(MountSnapshot snapshot, double delta) {
		return snapshot.assembled() ? delta : 0.0;
	}

	/** Wrap to -180..180 so a mount turns the short way round rather than chasing a full circle. */
	private static double normalizeDelta(double value) {
		double wrapped = value;
		while (wrapped > 180.0)
			wrapped -= 360.0;
		while (wrapped < -180.0)
			wrapped += 360.0;
		return wrapped;
	}
}
