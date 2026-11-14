package com.cbc.firecontrol.ballistics;

/**
 * Why a Trajectory Calculator link slot does (or does not) have a firing solution. Kept as a dense int id
 * because the calculator block entity syncs these to clients through its block-entity data packet.
 */
public enum LinkStatus {
	/** Slot holds nothing. */
	EMPTY_SLOT(false),
	/** Slot holds an item that is not a Calculator Link. */
	NOT_A_LINK(false),
	/** The link card is unbound (no mount recorded). */
	UNBOUND(false),
	/** Resolved, computed, solution valid. */
	OK(true),
	/** Solution ended by hitting a block. */
	BLOCK_HIT(true),
	/** Solution ended when the shot's flight time ran out (CBC autocannon lifetime, or the tick cap). */
	LIFETIME_END(true),
	/** Solution fell below the world. */
	OUT_OF_WORLD(true),
	/** Linked mount position is not an Advanced Cannon Mount. */
	NOT_A_MOUNT(false),
	/** Linked mount's chunk is not loaded, so its assembled cannon cannot be inspected. */
	MOUNT_UNLOADED(false),
	/** Linked mount is out of the calculator's link range. */
	OUT_OF_RANGE(false),
	/** Mount found but no cannon is assembled on it (or the muzzle could not be resolved). */
	NO_CANNON(false),
	/** Cannon assembled but nothing pushable is loaded (charge power 0). */
	NO_CHARGE(false),
	/**
	 * Assembled and loaded with powder, but CBC would not fire a shot: the bore holds no complete round, or
	 * the charge cannot ignite it. NO_CHARGE says there is no powder; this says there is powder and nothing
	 * to throw with it.
	 */
	NO_ROUND(false);

	private final boolean hasSolution;

	LinkStatus(boolean hasSolution) {
		this.hasSolution = hasSolution;
	}

	public boolean hasSolution() {
		return hasSolution;
	}

	/** Lang key used by the calculator GUI / tooltip for this status. */
	public String langKey() {
		return "cbc_firecontrol.calculator.status." + name().toLowerCase();
	}

	/**
	 * One-word form for the readout row header. The row has 148 pixels between the card slot and the channel
	 * grid, and that is not enough for a coordinate triple AND a sentence-length status - so the header carries
	 * only the terse state, and the full sentence plus the mount coordinate live in the row tooltip.
	 */
	public String shortLangKey() {
		return "cbc_firecontrol.calculator.status.short." + name().toLowerCase();
	}

	public static LinkStatus byId(int id) {
		LinkStatus[] all = values();
		if (id < 0 || id >= all.length)
			return EMPTY_SLOT;
		return all[id];
	}
}
