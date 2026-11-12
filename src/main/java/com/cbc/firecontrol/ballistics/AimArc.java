package com.cbc.firecontrol.ballistics;

import java.util.Locale;

/**
 * Which of the two in-plane solutions a solved aim came from.
 *
 * <p>For a fixed muzzle speed and a fixed target, the plan range measured at the target's altitude is a
 * single-peaked function of elevation (measured with CBC's integrator: gravity -0.05 / drag 0.01 / speed 6
 * peaks near 34.5 degrees, NOT the drag-free 45 degrees). Any target below that peak is therefore reachable
 * by exactly two elevations - one on each side of the peak - and only this enum says which one the solution
 * used. {@link AimArc#FLAT} is the branch before the peak (shorter flight time), {@link AimArc#LOB} the
 * branch after it (steeper, slower to arrive, but more tolerant of elevation error and able to clear high
 * terrain).
 */
public enum AimArc {

	/** Low elevation, the shorter of the two arcs: reaches the target with the least climb. */
	FLAT,
	/** High elevation, the longer arc: the ballistic "mortar" branch. */
	LOB;

	/** Ordinal-indexed table so a stored/sent index decodes back safely instead of throwing. */
	public static final AimArc[] BY_INDEX = values();

	public static AimArc byIndex(int index) {
		return index >= 0 && index < BY_INDEX.length ? BY_INDEX[index] : FLAT;
	}

	public String langKey() {
		return "cbc_firecontrol.aim.arc." + name().toLowerCase(Locale.ROOT);
	}

	/**
	 * One-word form for the readout row, which has only just over 140 pixels before it reaches the channel
	 * grid. The sentence-length {@link #langKey()} stays for tooltips, where the text wraps for free.
	 */
	public String shortLangKey() {
		return "cbc_firecontrol.aim.arc.short." + name().toLowerCase(Locale.ROOT);
	}
}