package com.cbc.firecontrol.ballistics;

/**
 * Why an inverse (target -&gt; aim) solution succeeded or could not be found.
 *
 * <p>The failure values are decided by WHERE the two possible solutions (see {@link AimArc}) sit relative to
 * the mount's {@link AimEnvelope}, which is why each one calls for a different answer from the gunner:
 *
 * <ul>
 * <li>{@link #TARGET_TOO_CLOSE} - every elevation the gun is allowed to use lands beyond the target: it is the
 *     mount's minimum range that binds, and the cure is a steeper gun than this one.</li>
 * <li>{@link #ELEVATION_LIMITED} - a solution exists at an elevation OUTSIDE the allowed window: the mount
 *     cannot depress or elevate far enough. The elevation that would work is in
 *     {@link AimSolution#suggestedPitchDeg()}.</li>
 * <li>{@link #CHARGE_INSUFFICIENT} - elevation had headroom, but no charge level inside the envelope throws
 *     far enough. The charge that would work is in {@link AimSolution#suggestedCharge()}.</li>
 * <li>{@link #TARGET_OUT_OF_REACH} - nothing helps: neither more elevation nor more charge covers it.</li>
 * </ul>
 */
public enum AimOutcome {

	/** A solution was found and, when a world was supplied, its flight path is clear to the target. */
	HIT(true),
	/** A solution was found but the residual miss exceeds the tolerances (edge of reach, degenerate arc). */
	RESIDUAL(true),
	/** The solved path is mathematically right but passes through terrain: it cannot actually be fired. */
	BLOCKED(true),
	/** A wider elevation window would hit the target; this mount's elevation limits are what stopped it. */
	ELEVATION_LIMITED(false),
	/** No elevation, inside or outside the window, and no charge inside the envelope covers the distance. */
	TARGET_OUT_OF_REACH(false),
	/** Even the steepest elevation allowed lands beyond the target: it sits inside the gun's minimum range. */
	TARGET_TOO_CLOSE(false),
	/**
	 * Elevation had headroom but no charge level inside the envelope extends the shot far enough (big
	 * cannons): the load, not the mount, is what stopped the solution.
	 */
	CHARGE_INSUFFICIENT(false),
	/** Target is at (or behind) the muzzle - there is nothing to aim at. */
	INVALID_TARGET(false),
	/** Nothing loaded / no charge accumulated: no muzzle speed to solve with. */
	NO_MUZZLE_SPEED(false),
	/** The mount has no assembled cannon. */
	NO_CANNON(false),
	/**
	 * The cannon is assembled but its next shot does not exist: no round in the bore, a round that is not
	 * assembled to its expected length, or a load the breech charge cannot ignite. See {@link RoundState},
	 * which names which of the three it is.
	 */
	NO_ROUND(false);

	/** Ordinal-indexed table so a stored/sent index decodes back safely instead of throwing. */
	public static final AimOutcome[] BY_INDEX = values();

	public static AimOutcome byIndex(int index) {
		return index >= 0 && index < BY_INDEX.length ? BY_INDEX[index] : NO_CANNON;
	}

	private final boolean hasSolution;

	AimOutcome(boolean hasSolution) {
		this.hasSolution = hasSolution;
	}

	public boolean hasSolution() {
		return hasSolution;
	}

	/** True when this failure names something the gunner could actually change: elevation limit or load. */
	public boolean suggestsACure() {
		return this == ELEVATION_LIMITED || this == CHARGE_INSUFFICIENT;
	}

	public String langKey() {
		return "cbc_firecontrol.aim." + name().toLowerCase(java.util.Locale.ROOT);
	}

	/**
	 * Terse label for the readout row, which has only just over 140 pixels before it runs into the channel
	 * grid: the full sentence in {@link #langKey()} ("Elevation limits too narrow for this target") would print
	 * across the filter slots. Tooltips use the long form, where the text wraps for free.
	 */
	public String shortLangKey() {
		return "cbc_firecontrol.aim.short." + name().toLowerCase(java.util.Locale.ROOT);
	}
}
