package com.cbc.firecontrol.ballistics;

/**
 * The geometry of a head-aim sight line: where along the spotter's ray the guns are asked to converge.
 *
 * <p>The model is the one aircraft gunnery uses rather than the one a rifle sight uses. A sight line is not a
 * direction, it is a <i>point</i>: the camera's ray is dropped like a laser onto the world, and every bore is
 * driven at the point that laser lands on. Two guns a few blocks apart then bear in slightly on that point, which
 * is what makes their shot paths meet there instead of staying parallel forever. The point therefore has to be
 * finite, and it has to be bounded, or a spotter looking at empty sky asks for a convergence distance of infinity
 * and the difference between the rows' bearings drops below the drive loop's deadband - which is another way of
 * saying the guns go parallel again.
 *
 * <p>Two bounds do that work:
 * <ul>
 * <li>{@link #MAX_RANGE} is the farthest the laser may reach. Past it a spotter is aiming at nothing the fire
 * control can put a shell on anyway, and the rows read as near-parallel, which is the correct answer for a target
 * farther than the guns can reach.</li>
 * <li>{@link #MIN_RANGE} is the nearest. A laser that lands on the shooter's own vehicle - the deck under a
 * gunner's feet, the hull plating beside a turret - would otherwise pull every bore into a steep inward slew
 * pointed at something nobody means to shoot at.</li>
 * </ul>
 *
 * <p>The class carries no Minecraft types on purpose: the maths it does is the part worth testing on its own, and
 * the ray-casting that feeds its inputs stays where the level is.
 */
public final class HeadAimConvergence {

	/**
	 * The longest convergence baseline in blocks. CBC's largest guns reach past this, so it is a stand-in for
	 * "as far as the spotter can meaningfully aim" rather than a range limit: a target beyond it is aimed at with
	 * the rows effectively parallel, which is what a solution for that distance would need anyway.
	 */
	public static final double MAX_RANGE = 640.0;

	/**
	 * The shortest convergence baseline in blocks. Below it the bores of a multi-gun line slew inward hard enough
	 * to cross each other's muzzle arc, so a laser that lands closer than this is pushed out to it.
	 */
	public static final double MIN_RANGE = 32.0;

	private HeadAimConvergence() {
	}

	/**
	 * A range the console may use: anything unusable (absent, NaN, infinite, negative, or absurdly long) becomes
	 * {@link #MAX_RANGE}, and a usable one is clamped into {@code [MIN_RANGE, MAX_RANGE]}.
	 */
	public static double clampRange(double range) {
		if (!(range > 0.0) || Double.isInfinite(range))
			return MAX_RANGE;
		if (range < MIN_RANGE)
			return MIN_RANGE;
		return range > MAX_RANGE ? MAX_RANGE : range;
	}

	/**
	 * How far along a ray the point where it leaves a sphere lies, or NaN when the ray never leaves one ahead of
	 * its start.
	 *
	 * <p>Reading a vehicle's own bounding box as that sphere is how a spotter riding the vehicle keeps the laser
	 * off the vehicle: the sight point is pushed to just outside whatever they are standing on, so a gun line
	 * aimed across its own deck still converges somewhere in front of them rather than inside the deck. The cases
	 * are handled separately because each one means something different:
	 * <ul>
	 * <li>Start outside, ray misses the sphere entirely: NaN. The vehicle is not in the way of this view, so
	 * there is nothing to push the point out past.</li>
	 * <li>Start outside, ray crosses it, and the sphere sits behind the start: NaN, for the same reason - the
	 * exit parameter is negative, so it is not a distance ahead of the camera.</li>
	 * <li>Start inside the sphere (the spotter is standing on the vehicle, which is the normal case): the nearer
	 * crossing is negative and the farther one is the exit, and the exit is what is wanted.</li>
	 * <li>Ray tangent to the sphere: the discriminant collapses to zero and both crossings coincide; that is a
	 * grazing hit and is returned as the exit rather than dropped.</li>
	 * </ul>
	 *
	 * @param ox,oy,oz ray start
	 * @param dx,dy,dz ray direction; any non-zero multiple answers the same distance, so callers may pass a
	 *                normalised direction without worrying about the precision of that normalisation
	 * @param cx,cy,cz sphere centre
	 * @param radius   sphere radius, positive
	 * @return the distance along the ray to the sphere's exit, or NaN when there is none ahead of the start
	 */
	public static double sphereExitDistance(double ox, double oy, double oz, double dx, double dy, double dz,
			double cx, double cy, double cz, double radius) {
		if (!(radius > 0.0))
			return Double.NaN;
		double lx = ox - cx;
		double ly = oy - cy;
		double lz = oz - cz;
		double lenSq = dx * dx + dy * dy + dz * dz;
		if (!(lenSq > 0.0))
			return Double.NaN;
		// |t * d - l|^2 == radius^2 expands to lenSq t^2 + 2(l.d) t + (|l|^2 - r^2). Solving for t and then
		// multiplying by the direction's own length turns the parameter into a distance, which is what this
		// answers in: a caller that hands over a direction scaled to anything at all still gets blocks back.
		double halfB = lx * dx + ly * dy + lz * dz;
		double c = lx * lx + ly * ly + lz * lz - radius * radius;
		double disc = halfB * halfB - lenSq * c;
		if (disc < 0.0)
			return Double.NaN;
		double exit = (-halfB + Math.sqrt(disc)) / Math.sqrt(lenSq);
		if (!(exit > 0.0))
			return Double.NaN;
		return exit;
	}

	/**
	 * The radius a spotter's own vehicle is pushed out to: twice its longest side. Twice, because the vehicle is
	 * the thing being aimed <i>from</i> - a point one longest-side out can still sit over the far end of a long
	 * hull or the muzzle of a gun mounted at it, and the point has to be clear of the whole thing for the bore
	 * bearings to mean anything.
	 */
	public static double vehicleConvergenceRadius(double sizeX, double sizeY, double sizeZ) {
		double longest = Math.max(sizeX, Math.max(sizeY, sizeZ));
		if (!(longest > 0.0))
			return MIN_RANGE;
		return Math.max(MIN_RANGE, longest * 2.0);
	}

	/**
	 * Hold an elevation demand inside the window a carriage can actually give, which is the WT turret's elevation
	 * stop: the sight line keeps traversing, and only the elevation is capped. The demand passes through untouched
	 * when no usable window was reported - a NaN pitch, a NaN limit (CBC knows nothing about this mount's carriage,
	 * e.g. it is not assembled yet), or an inverted window whose maximum sits below its minimum. Capping against a
	 * window nobody measured would invent a stop the gun does not have, which is worse than asking for a bearing the
	 * mount's own clamp will handle.
	 *
	 * @param pitch   the elevation being asked for, degrees, 0 = level and positive = barrel up
	 * @param minDeg  lowest elevation this carriage allows
	 * @param maxDeg  highest elevation this carriage allows
	 * @return {@code pitch} inside [{@code minDeg}, {@code maxDeg}], or {@code pitch} itself when the window is
	 *         unusable
	 */
	public static double clampPitch(double pitch, double minDeg, double maxDeg) {
		if (!Double.isFinite(pitch) || !Double.isFinite(minDeg) || !Double.isFinite(maxDeg) || maxDeg < minDeg)
			return pitch;
		return Math.max(minDeg, Math.min(maxDeg, pitch));
	}
}
