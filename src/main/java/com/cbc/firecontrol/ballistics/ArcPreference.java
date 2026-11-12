package com.cbc.firecontrol.ballistics;

/**
 * Which branch of the two possible solutions {@link InverseBallistics} should prefer when both hit, supplied
 * by the caller through {@link AimEnvelope}. It is a preference, not a filter: the solver still measures both
 * branches, and a preferred branch that only produces a residual miss never outranks a clean hit on the other
 * branch (see {@code InverseBallistics#ranksAbove}).
 */
public enum ArcPreference {

	/** Prefer the low arc: shortest time on target, flattest flight. Guns that shoot at moving things. */
	FLAT_FIRST(AimArc.FLAT),
	/** Prefer the high arc: clears terrain, tolerates elevation error better. Mortars, howitzers, plunging fire. */
	LOB_FIRST(AimArc.LOB);

	private final AimArc preferredArc;

	ArcPreference(AimArc preferredArc) {
		this.preferredArc = preferredArc;
	}

	public AimArc preferredArc() {
		return preferredArc;
	}

	/** Rank of one arc under this preference: a lower number ranks better. */
	public int rankOf(AimArc arc) {
		return arc == preferredArc ? 0 : 1;
	}
}