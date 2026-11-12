package com.cbc.firecontrol.ballistics;

/**
 * The aiming limits a solved trajectory has to respect, supplied by the caller.
 *
 * <p>Nothing here reads a block: {@link InverseBallistics} is deliberately unwired, so whichever mount or
 * console eventually uses it decides its own envelope. The shipped defaults are starting points, not facts
 * about CBC - CBC's real limits are {@code AbstractMountedCannonContraption#maximumElevation(ControlPitchContraption)}
 * and {@code maximumDepression(...)}, read through the mount's mounted contraption, and must replace these
 * defaults when the solver gets wired in.
 *
 * @param pitchMinDeg    lowest elevation the gun can be set to (negative = depressed below the horizon)
 * @param pitchMaxDeg    highest elevation the gun can be set to
 * @param minChargePower lowest total charge power the propellant stack can provide (big cannons)
 * @param maxChargePower highest total charge power the propellant stack can provide (big cannons)
 * @param chargeStep     granularity charge is adjusted in; CBC propellant charges accumulate in whole units
 * @param arcPreference  which of the two solutions wins when both hit (see {@link AimArc})
 */
public record AimEnvelope(double pitchMinDeg, double pitchMaxDeg, double minChargePower, double maxChargePower,
		double chargeStep, ArcPreference arcPreference) {

	/** A wide-open envelope: every elevation from 10 degrees depressed to 70 degrees up, 1..16 charge units. */
	public static final AimEnvelope DEFAULT = new AimEnvelope(-10.0, 70.0, 1.0, 16.0, 1.0);

	/** Autocannons have no charge: their muzzle speed comes from the barrel count and material. */
	public static final AimEnvelope AUTOCANNON = new AimEnvelope(-10.0, 70.0, 0.0, 0.0, 1.0);

	/** Convenience form for callers that do not care which arc they get (flat first, as before). */
	public AimEnvelope(double pitchMinDeg, double pitchMaxDeg, double minChargePower, double maxChargePower,
			double chargeStep) {
		this(pitchMinDeg, pitchMaxDeg, minChargePower, maxChargePower, chargeStep, ArcPreference.FLAT_FIRST);
	}

	public AimEnvelope {
		if (pitchMaxDeg < pitchMinDeg) {
			double swap = pitchMinDeg;
			pitchMinDeg = pitchMaxDeg;
			pitchMaxDeg = swap;
		}
		if (maxChargePower < minChargePower) {
			double swap = minChargePower;
			minChargePower = maxChargePower;
			maxChargePower = swap;
		}
		chargeStep = Math.max(0.01, chargeStep);
		if (arcPreference == null)
			arcPreference = ArcPreference.FLAT_FIRST;
		/*
		 * A window that reaches into the zenith has nothing measurable up there: a shot fired at vertical has
		 * zero plan range and no solution to find. Clamping short of it keeps the search (and its widened
		 * failure diagnostic, which probes past the requested limits) from ever aiming at the sky.
		 */
		pitchMinDeg = Math.max(-89.0, Math.min(89.0, pitchMinDeg));
		pitchMaxDeg = Math.max(-89.0, Math.min(89.0, pitchMaxDeg));
		if (pitchMaxDeg < pitchMinDeg) {
			double swap = pitchMinDeg;
			pitchMinDeg = pitchMaxDeg;
			pitchMaxDeg = swap;
		}
	}

	/** True when charge power is a free variable for this gun. */
	public boolean canAdjustCharge() {
		return maxChargePower > minChargePower;
	}

	/** Charge search granularity the solver may use: whole steps for a whole-unit propellant stack. */
	public boolean hasFixedCharge() {
		return !canAdjustCharge();
	}

	public AimEnvelope withPitch(double minDeg, double maxDeg) {
		return new AimEnvelope(minDeg, maxDeg, minChargePower, maxChargePower, chargeStep, arcPreference);
	}

	public AimEnvelope withCharge(double minPower, double maxPower) {
		return new AimEnvelope(pitchMinDeg, pitchMaxDeg, minPower, maxPower, chargeStep, arcPreference);
	}

	public AimEnvelope withPreference(ArcPreference preference) {
		return new AimEnvelope(pitchMinDeg, pitchMaxDeg, minChargePower, maxChargePower, chargeStep, preference);
	}

	/**
	 * Elevation of sample {@code index} out of {@code sampleCount} evenly spaced samples spanning this window,
	 * which is how {@link InverseBallistics} scans a window and how it names the peak sample it found.
	 */
	public double samplePitch(int index, int sampleCount) {
		if (sampleCount <= 0)
			return pitchMinDeg;
		double fraction = Math.max(0.0, Math.min(1.0, (double) index / sampleCount));
		return pitchMinDeg + (pitchMaxDeg - pitchMinDeg) * fraction;
	}
}
