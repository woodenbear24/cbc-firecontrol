package com.cbc.firecontrol.ballistics;

/**
 * Whether the assembled cannon on a mount can actually fire its NEXT shot, and if not, which of CBC's own
 * {@code fireShot} prerequisites is missing.
 *
 * <p>{@link MountScanner} derives this from the assembled structure rather than assuming a cannon is loaded,
 * because CBC only spawns a projectile when all of these hold (see {@code MountedBigCannonContraption#fireShot}):
 *
 * <ul>
 * <li><b>a round is in the bore</b> - a cannon loaded with powder but no shell reaches the spawn code with a
 *     null projectile and fires nothing, yet it used to report a firing solution;</li>
 * <li><b>the round is complete</b> - CBC asks {@code ProjectileBlock#isComplete} for the collected blocks and
 *     fails the shot when the assembled length does not match the shell's expected size;</li>
 * <li><b>the breech charge can be ignited</b> - {@code BigCannonPropellantBlock#canBeIgnited} on the first
 *     propellant cell gates the whole shot, so a charge that needs a detonator does not fire on its own;</li>
 * <li><b>an autocannon has a shell in its breech</b> - autocannon ammunition is an item stack held by the
 *     breech block entity, so it is read from there rather than from the bore.</li>
 * </ul>
 *
 * <p>Two things CBC applies to the launch speed are deliberately NOT mirrored, because neither is reachable
 * through CBC's public API: the shell's own {@code AbstractBigCannonProjectile#addedChargePower()} (it lives on
 * the projectile ENTITY, which fireShot creates at the moment of firing) and the material's
 * {@code minimumSpread()} floor, which clamps the accumulated charge before it becomes the shot's speed
 * ({@code MountedBigCannonContraption#cannonMaterial} is private). A weak load is therefore predicted slightly
 * short, never slightly long, so a solved aim errs on the conservative side.
 */
public enum RoundState {

	/** All of CBC's firing prerequisites hold: the next shot exists. */
	READY,
	/** Nothing shootable is in the bore (no shell, or no autocannon round in the breech). */
	NO_ROUND,
	/** A shell is in the bore but not assembled to its expected length. */
	INCOMPLETE_ROUND,
	/** The first propellant cell refuses to ignite the load. */
	NOT_IGNITABLE;

	/** Ordinal-indexed table so a synced index decodes back safely instead of throwing. */
	public static final RoundState[] BY_INDEX = values();

	public static RoundState byIndex(int index) {
		return index >= 0 && index < BY_INDEX.length ? BY_INDEX[index] : READY;
	}

	public boolean ready() {
		return this == READY;
	}

	/** Lang key naming the missing prerequisite, for the calculator row tooltip. */
	public String langKey() {
		return "cbc_firecontrol.round." + name().toLowerCase(java.util.Locale.ROOT);
	}
}