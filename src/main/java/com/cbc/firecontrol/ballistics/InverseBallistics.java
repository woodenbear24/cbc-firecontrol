package com.cbc.firecontrol.ballistics;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Inverse ballistics: given a mount and a TARGET, find the aim (and charge) that would hit it. The mirror of
 * {@link TrajectoryComputer}, and it shares that class's integrator, so a solution produced here is validated
 * by the same physics the calculator uses to predict a shot - no second, simpler model.
 *
 * <p><b>Two answers, not one.</b> For a fixed muzzle speed, the plan range measured where the shot descends
 * through the target's altitude plane rises with elevation, peaks, then falls again: with CBC's shipped
 * numbers (gravity -0.05, drag 0.01 per tick, muzzle speed 6) that peak sits near 33-34 degrees at roughly
 * 330 blocks, NOT at the drag-free 45 degrees. A target closer than the peak is therefore reachable at two
 * elevations - one before the peak ({@link AimArc#FLAT}) and one after it ({@link AimArc#LOB}) - and both are
 * searched here. Searching only for the flattest crossing reports a mount whose window starts above the peak
 * (a mortar, or a gun that cannot depress enough) as unable to hit when its lob solution does exist, and
 * labels it TARGET_TOO_CLOSE: that was the defect this rewrite exists to fix.
 *
 * <p>Search order: yaw comes from the target directly (with no wind or spread term the lateral error is zero
 * by construction, so searching it would only cost time); for big cannons whose charge is adjustable, charge
 * is bisected first for the cheapest load that can over-reach (maximum reachable range is monotonic in
 * charge), then every candidate charge has its elevation window scanned for the range peak and bisected on
 * BOTH sides of it; candidates are ranked by {@link ArcPreference}, with a clean hit on the branch this gun
 * does not prefer ranked above a merely acceptable hit on the branch it does. The winner is finally flown
 * collision-free to measure its miss and - when a {@link Level} is passed - through real terrain, which
 * reports {@link AimOutcome#BLOCKED} when a hill sits in the way.
 *
 * <p>When nothing hits, {@link #classifyFailure} works out WHICH limit was binding by re-measuring inside a
 * deliberately wider elevation window and, for charge-adjustable guns, at charges above the envelope cap.
 * {@link AimOutcome} states what each verdict means; {@link AimSolution#suggestedPitchDeg()} and
 * {@link AimSolution#suggestedCharge()} carry the number the gunner could actually change.
 *
 * Angles are the FIRED DIRECTION's own: {@code yaw 0 = +Z increasing toward -X, pitch 0 = level positive up},
 * i.e. {@code dir = (-sin(yaw), sin(pitch), cos(yaw))}. They are deliberately not the mount's console values;
 * mapping a solution onto the mount's console yaw/pitch is the wiring step, and this solver is intentionally
 * attached to no block or item yet.
 */
public final class InverseBallistics {

	/** Plan-range tolerance for calling a solution a hit, in blocks. */
	public static final double HIT_TOLERANCE = 1.5;
	/** Altitude tolerance: the target is a block-scale object, so less than this is still a hit. */
	public static final double ALTITUDE_TOLERANCE = 1.5;
	/** Targets nearer the muzzle than this cannot be aimed at at all. */
	public static final double MIN_PLAN_RANGE = 2.0;

	/** Elevation samples per window scan: coarse, then bisection narrows. 16 keeps the peak bracket tight. */
	private static final int PITCH_SCAN_SAMPLES = 16;
	private static final int PITCH_BISECTION_STEPS = 18;
	private static final int CHARGE_BISECTION_STEPS = 8;
	/** How many geometrically growing probe charges the failure diagnostic may try above the envelope cap. */
	private static final int CHARGE_PROBE_STEPS = 4;
	/** How far past the requested elevation limits the failure diagnostic may look, in degrees. */
	private static final double WIDE_PROBE_DEG = 45.0;
	/** Never probe past this elevation: a shot aimed at the zenith has no measurable solution. */
	private static final double STEEPEST_PROBE_DEG = 89.0;
	/** Ceiling on simulated flights per solve, so an unreachable target cannot spin the search. */
	public static final int MAX_EVALUATIONS = 1200;

	private InverseBallistics() {
	}

	public static Vec3 directionFromAngles(double yawDeg, double pitchDeg) {
		double yaw = Math.toRadians(yawDeg);
		double pitch = Math.toRadians(pitchDeg);
		double horizontal = Math.cos(pitch);
		return new Vec3(-Math.sin(yaw) * horizontal, Math.sin(pitch), Math.cos(yaw) * horizontal).normalize();
	}

	public static double yawOf(Vec3 direction) {
		return Math.toDegrees(Math.atan2(-direction.x, direction.z));
	}

	public static double pitchOf(Vec3 direction) {
		return Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
	}

	/** No terrain checks: preview what an aim would need, usable without a world. */
	public static AimSolution solve(MountSnapshot snapshot, Vec3 target, AimEnvelope envelope) {
		return solve(snapshot, target, envelope, null);
	}

	/**
	 * @param level when non-null the winning trajectory is also flown through this world, which is what reports
	 *              {@link AimOutcome#BLOCKED} for a solution whose path runs into terrain
	 */
	public static AimSolution solve(MountSnapshot snapshot, Vec3 target, AimEnvelope envelope,
			@Nullable Level level) {
		if (!snapshot.assembled())
			return AimSolution.failure(AimOutcome.NO_CANNON, snapshot, 0, 0);

		Vec3 muzzle = snapshot.muzzle();
		Vec3 toTarget = target.subtract(muzzle);
		double planRange = Math.hypot(toTarget.x, toTarget.z);
		if (planRange < MIN_PLAN_RANGE)
			return AimSolution.failure(AimOutcome.INVALID_TARGET, snapshot, planRange, 0);
		/*
		 * Both gun types need a launch speed, and for a big cannon that speed IS the charge CBC has
		 * accumulated (MountScanner mirrors CBC passing the accumulated charge power straight to
		 * Projectile#shoot), so a cannon with an empty powder charge has nothing to solve with.
		 */
		if (snapshot.muzzleSpeed() <= 0.0)
			return AimSolution.failure(AimOutcome.NO_MUZZLE_SPEED, snapshot, planRange, 0);

		double yaw = yawOf(toTarget);
		double targetY = target.y;
		int[] evaluations = new int[] { 0 };

		List<Double> charges = chargeCandidates(snapshot, envelope, evaluations, planRange, yaw, targetY);
		if (charges.isEmpty())
			return AimSolution.failure(AimOutcome.CHARGE_INSUFFICIENT, snapshot, planRange, evaluations[0]);

		AimSolution best = null;
		boolean sawFlat = false;
		boolean sawLob = false;

		for (double charge : charges) {
			double speed = snapshot.autocannon() ? snapshot.muzzleSpeed() : charge;
			if (speed <= 0.0)
				continue;

			RangePeak peak = scanMaxRange(snapshot, yaw, speed, envelope, targetY, evaluations);
			if (peak.peakRange() + HIT_TOLERANCE < planRange)
				continue; // this load cannot cover the target at any elevation inside the window

			Branches roots = branchRoots(snapshot, yaw, speed, planRange, envelope, targetY, peak, evaluations);
			sawFlat = sawFlat || !Double.isNaN(roots.flatPitch());
			sawLob = sawLob || !Double.isNaN(roots.lobPitch());

			best = consider(verify(snapshot, target, yaw, roots.flatPitch(), charge, speed, level, evaluations,
					AimArc.FLAT), best, envelope, snapshot.chargePower());
			best = consider(verify(snapshot, target, yaw, roots.lobPitch(), charge, speed, level, evaluations,
					AimArc.LOB), best, envelope, snapshot.chargePower());

			if (best != null && best.outcome() == AimOutcome.HIT
					&& best.arc() == envelope.arcPreference().preferredArc())
				break; // a clean hit on the branch this gun prefers: there is nothing better to look for
		}

		if (best != null)
			return best.withAlternativeArc(sawFlat && sawLob);
		return classifyFailure(snapshot, envelope, yaw, targetY, planRange, evaluations);
	}

	/* ------------------------------------------------------------------ candidate ranking */

	/** Pick the better of two verified candidates, keeping the incumbent when the challenger is null. */
	@Nullable
	private static AimSolution consider(@Nullable AimSolution candidate, @Nullable AimSolution best,
			AimEnvelope env, double loadedCharge) {
		if (candidate == null)
			return best;
		return ranksAbove(candidate, best, env, loadedCharge) ? candidate : best;
	}

	/**
	 * Ranking order: a path that can actually be fired beats a {@link AimOutcome#BLOCKED} one; inside that a
	 * clean hit beats a merely acceptable residual <b>even when the acceptable one sits on the preferred
	 * branch</b>, because an arc preference may not buy a worse shot; inside that {@link ArcPreference}
	 * decides; inside that the smaller miss, then the shorter flight, then the charge closest to what is
	 * already loaded.
	 */
	private static boolean ranksAbove(AimSolution candidate, @Nullable AimSolution best, AimEnvelope env,
			double loadedCharge) {
		if (best == null)
			return true;
		int blockedCand = candidate.outcome() == AimOutcome.BLOCKED ? 1 : 0;
		int blockedBest = best.outcome() == AimOutcome.BLOCKED ? 1 : 0;
		if (blockedCand != blockedBest)
			return blockedCand < blockedBest;
		double clean = Math.min(HIT_TOLERANCE, ALTITUDE_TOLERANCE);
		int bandCand = candidate.missDistance() <= clean ? 0 : 1;
		int bandBest = best.missDistance() <= clean ? 0 : 1;
		if (bandCand != bandBest)
			return bandCand < bandBest;
		int arcCand = env.arcPreference().rankOf(candidate.arc());
		int arcBest = env.arcPreference().rankOf(best.arc());
		if (arcCand != arcBest)
			return arcCand < arcBest;
		if (Math.abs(candidate.missDistance() - best.missDistance()) > 1.0e-6)
			return candidate.missDistance() < best.missDistance();
		if (candidate.flightTicks() != best.flightTicks())
			return candidate.flightTicks() < best.flightTicks();
		return Math.abs(candidate.chargePower() - loadedCharge) < Math.abs(best.chargePower() - loadedCharge);
	}

	/* ------------------------------------------------------------------ charge search */

	/**
	 * Charge levels worth trying. When charge is a free variable this is a bisection for the CHEAPEST charge
	 * that can still over-reach the target (reachable range is monotonic in charge), plus its neighbours,
	 * because charge and elevation trade off: the flattest shot at the minimum sufficient charge can still
	 * overshoot in altitude, and one charge step up fired flatter often misses less.
	 */
	private static List<Double> chargeCandidates(MountSnapshot s, AimEnvelope env, int[] evaluations,
			double planRange, double yaw, double targetY) {
		List<Double> out = new ArrayList<>();
		if (s.autocannon() || !env.canAdjustCharge()) {
			out.add(s.autocannon() ? 0.0 : Math.max(env.minChargePower(), s.chargePower()));
			return out;
		}
		double step = env.chargeStep();
		double lo = Math.max(env.minChargePower(), step);
		double hi = env.maxChargePower();
		if (hi < lo)
			return out;
		if (scanMaxRange(s, yaw, hi, env, targetY, evaluations).peakRange() < planRange) {
			// Even the strongest charge cannot reach: report it anyway so the failure carries real numbers.
			out.add(hi);
			return out;
		}
		for (int i = 0; i < CHARGE_BISECTION_STEPS && hi - lo > step * 0.5; i++) {
			double mid = (lo + hi) * 0.5;
			if (scanMaxRange(s, yaw, mid, env, targetY, evaluations).peakRange() >= planRange)
				hi = mid;
			else
				lo = mid;
		}
		for (double charge : new double[] { hi - step, hi, hi + step }) {
			if (charge < lo - 1.0e-9 || charge > env.maxChargePower() + 1.0e-9)
				continue;
			if (!out.contains(charge))
				out.add(charge);
		}
		return out;
	}

	/* ------------------------------------------------------------------ range peak */

	/**
	 * Peak plan range over the elevation window, which sample produced it, and the range measured at each
	 * edge of the window. The peak sample is what splits the window into the flat and the lob branch; the two
	 * edge values are what says whether a branch has a root at all.
	 */
	private record RangePeak(double peakRange, int bestIndex, int sampleCount, double flatEdgeRange,
			double steepEdgeRange) {
	}

	/** Scan the elevation window: what is this gun's reach at this speed, and where does that peak sit. */
	private static RangePeak scanMaxRange(MountSnapshot s, double yaw, double speed, AimEnvelope env,
			double targetY, int[] evaluations) {
		double best = 0.0;
		int bestIndex = 0;
		double flatEdge = 0.0;
		double steepEdge = 0.0;
		for (int i = 0; i <= PITCH_SCAN_SAMPLES; i++) {
			double range = planRangeAt(s, yaw, env.samplePitch(i, PITCH_SCAN_SAMPLES), speed, targetY,
					evaluations);
			if (i == 0)
				flatEdge = range;
			if (i == PITCH_SCAN_SAMPLES)
				steepEdge = range;
			if (range > best) {
				best = range;
				bestIndex = i;
			}
		}
		return new RangePeak(best, bestIndex, PITCH_SCAN_SAMPLES, flatEdge, steepEdge);
	}

	/* ------------------------------------------------------------------ pitch search */

	/** The at most two elevation solutions for one charge, each tagged with the arc it sits on (NaN = none). */
	private record Branches(double flatPitch, double lobPitch) {
	}

	/**
	 * Bisect each side of the range peak. A bracket is only valid when the target is not already overshot at
	 * that bracket's outer edge and is within reach at the peak - that is what makes each root unique, because
	 * the range function is single-peaked and so crosses any attainable range at most once per side. The
	 * tolerance is deliberate: when the crossing sits exactly on an edge of the window, that edge IS the
	 * solution and the gun can legally fire it. A bracket whose outer edge lands clear past the target has no
	 * root on that side - for a window sitting entirely above the peak that is the flat side, and the old
	 * solver reported exactly that situation as TARGET_TOO_CLOSE even though the lob root existed.
	 */
	private static Branches branchRoots(MountSnapshot s, double yaw, double speed, double required,
			AimEnvelope env, double targetY, RangePeak peak, int[] evaluations) {
		double lo = env.pitchMinDeg();
		double hi = env.pitchMaxDeg();
		double peakPitch = env.samplePitch(peak.bestIndex(), peak.sampleCount());
		double flat = Double.NaN;
		double lob = Double.NaN;
		boolean peakCovers = peak.peakRange() + HIT_TOLERANCE >= required;
		if (peak.bestIndex() > 0 && peakCovers && peak.flatEdgeRange() < required + HIT_TOLERANCE)
			flat = bisectElevation(s, yaw, speed, required, env, targetY, lo, peakPitch, true, evaluations);
		if (peak.bestIndex() < peak.sampleCount() && peakCovers && peak.steepEdgeRange() < required + HIT_TOLERANCE)
			lob = bisectElevation(s, yaw, speed, required, env, targetY, Math.max(lo, peakPitch), hi, false,
					evaluations);
		return new Branches(flat, lob);
	}

	/**
	 * Narrow one bracket between {@code from} and {@code to}. {@code ascending} says which way the range runs
	 * across it: rising on the flat side (return the lowest elevation that covers) and falling on the lob
	 * side (return the highest elevation that still covers). Both bounds must move on every step - a bracket
	 * where only the covering side is replaced keeps its midpoint identical forever, which is how this solver
	 * used to return a scan sample pitch (15.0 / 40.0 degrees) instead of the real crossing. When the flight
	 * budget runs out before the bracket closes the answer degrades to the best end it reached instead of
	 * failing - the caller always verifies with real physics afterwards.
	 */
	private static double bisectElevation(MountSnapshot s, double yaw, double speed, double required,
			AimEnvelope env, double targetY, double from, double to, boolean ascending, int[] evaluations) {
		if (to - from < 1.0e-3)
			return Double.NaN;
		double low = from;
		double high = to;
		for (int i = 0; i < PITCH_BISECTION_STEPS && high - low > 0.01 && evaluations[0] < MAX_EVALUATIONS; i++) {
			double mid = (low + high) * 0.5;
			boolean covers = planRangeAt(s, yaw, mid, speed, targetY, evaluations) >= required;
			if (ascending) {
				/*
				 * Range rises across this bracket, so its non-covering end is the flat one: a midpoint that
				 * already covers rules out everything steeper than it, and one that does not cover rules out
				 * everything flatter. Moving only the covering side is what left the midpoint unchanged.
				 */
				if (covers)
					high = mid;
				else
					low = mid;
			} else if (covers) {
				low = mid;
			} else {
				high = mid;
			}
		}
		return ascending ? high : low;
	}

	/** Plan range of one candidate flight: horizontal distance to where it drops through the target's height. */
	private static double planRangeAt(MountSnapshot s, double yaw, double pitchDeg, double speed,
			double targetY, int[] evaluations) {
		if (evaluations[0] >= MAX_EVALUATIONS)
			return 0.0;
		evaluations[0]++;
		TrajectoryComputer.Flight flight = TrajectoryComputer.simulateFree(s,
				directionFromAngles(yaw, pitchDeg), speed, TrajectoryComputer.FreeStop.atAltitude(targetY));
		// A shot that never falls through the target's altitude has no reach AT that altitude: zero, not its
		// terminal point, otherwise an under-powered flat shot would read as an enormous overshoot.
		if (flight.status() != LinkStatus.OK)
			return 0.0;
		return planDistance(s.muzzle(), flight.impact());
	}

	/* ------------------------------------------------------------------ verification */

	/** Fly the solved aim for real (collision-free, then through terrain when a world was given). */
	@Nullable
	private static AimSolution verify(MountSnapshot s, Vec3 target, double yaw, double pitch, double charge,
			double speed, @Nullable Level level, int[] evaluations, AimArc arc) {
		if (Double.isNaN(pitch) || evaluations[0] >= MAX_EVALUATIONS)
			return null;

		Vec3 muzzle = s.muzzle();
		Vec3 direction = directionFromAngles(yaw, pitch);
		double targetPlan = planDistance(muzzle, target);

		/*
		 * Measure the solved shot twice and judge the better reading. The first is by the SAME quantity the
		 * search converged on - where it descends through the target's altitude plane, i.e. where it actually
		 * lands relative to the target - because that is what "how far did it throw" means for a ship's gunner.
		 * The second is how far above or below the target the shell sits at the instant it crosses the target's
		 * own ring, which is what "did it hit the thing" means. A shell that flies 0.2 blocks over the target and
		 * lands four blocks long has hit the target and is only called a miss by the first reading; a
		 * near-vertical shell that lands beside the target is only called a 120 block height error by the
		 * second, because it is still climbing as it passes over the target column. Whichever flight comes
		 * closer decides.
		 */
		evaluations[0]++;
		TrajectoryComputer.Flight landing = TrajectoryComputer.simulateFree(s, direction, speed,
				TrajectoryComputer.FreeStop.atAltitude(target.y));
		double landingError = landing.status() == LinkStatus.OK
				? Math.hypot(planDistance(muzzle, landing.impact()) - targetPlan, landing.impact().y - target.y)
				: Double.POSITIVE_INFINITY;
		evaluations[0]++;
		TrajectoryComputer.Flight passing = TrajectoryComputer.simulateFree(s, direction, speed,
				TrajectoryComputer.FreeStop.atPlanDistance(targetPlan));
		double passingError = passing.status() == LinkStatus.OK
				? passing.impact().y - target.y
				: Double.POSITIVE_INFINITY;

		TrajectoryComputer.Flight measured = Math.abs(passingError) < landingError ? passing : landing;
		Vec3 impact = measured.impact();
		int ticks = measured.ticks();
		double impactPlan = planDistance(muzzle, impact);
		double altitudeError = impact.y - target.y;
		AimOutcome outcome = isCloseEnough(targetPlan, impactPlan, altitudeError) ? AimOutcome.HIT : AimOutcome.RESIDUAL;

		if (level != null && evaluations[0] < MAX_EVALUATIONS) {
			evaluations[0]++;
			TrajectoryComputer.Flight terrain = TrajectoryComputer.simulateFlight(level, s, direction, speed);
			if (terrain.hitTerrain()) {
				// The aim is mathematically right but the hill is in the way: a solution that cannot be fired.
				outcome = AimOutcome.BLOCKED;
				impact = terrain.impact();
				ticks = terrain.ticks();
				impactPlan = planDistance(muzzle, impact);
				altitudeError = impact.y - target.y;
			}
		}
		return new AimSolution(outcome, yaw, pitch, charge, speed,
				impact.x, impact.y, impact.z,
				targetPlan, impactPlan, altitudeError, ticks, evaluations[0],
				arc, false, Double.NaN, Double.NaN);
	}

	private static boolean isCloseEnough(double targetPlan, double impactPlan, double altitudeError) {
		return Math.hypot(impactPlan - targetPlan, altitudeError) <= Math.min(HIT_TOLERANCE, ALTITUDE_TOLERANCE);
	}

	private static double planDistance(Vec3 from, Vec3 to) {
		return Math.hypot(to.x - from.x, to.z - from.z);
	}

	/* ------------------------------------------------------------------ failure diagnosis */

	/**
	 * Nothing hit: work out WHICH limit was binding, so the answer the gunner receives is one they can act
	 * on. Every probe here is a handful of simulated flights and only ever runs on the failure path.
	 */
	private static AimSolution classifyFailure(MountSnapshot s, AimEnvelope env, double yaw, double targetY,
			double planRange, int[] evaluations) {
		double speed = s.autocannon() ? s.muzzleSpeed() : Math.max(env.maxChargePower(), s.chargePower());
		RangePeak peak = scanMaxRange(s, yaw, speed, env, targetY, evaluations);

		/*
		 * The range-at-altitude function is single-peaked, so its smallest value across the window sits on an
		 * edge. When BOTH edges land past the target, every elevation the gun may use does: no elevation
		 * choice can put a shell under the target, which is TARGET_TOO_CLOSE and the only failure whose cure
		 * is "use a steeper gun than this one".
		 */
		if (peak.flatEdgeRange() > planRange + HIT_TOLERANCE && peak.steepEdgeRange() > planRange + HIT_TOLERANCE)
			return AimSolution.failure(AimOutcome.TARGET_TOO_CLOSE, s, planRange, evaluations[0]);

		/*
		 * Would a wider elevation window have hit it? Look WIDE_PROBE_DEG past each elevation limit (clamped
		 * short of vertical) and measure again. If coverage appears there, the mount's elevation limits are
		 * what stopped the solution and the elevation that WOULD have worked is named: ELEVATION_LIMITED.
		 * That verdict is what a window like -5..2 degrees used to report as TARGET_OUT_OF_REACH.
		 */
		double wideMin = Math.max(-STEEPEST_PROBE_DEG, env.pitchMinDeg() - WIDE_PROBE_DEG);
		double wideMax = Math.min(STEEPEST_PROBE_DEG, env.pitchMaxDeg() + WIDE_PROBE_DEG);
		if (wideMin < env.pitchMinDeg() || wideMax > env.pitchMaxDeg()) {
			AimEnvelope wide = env.withPitch(wideMin, wideMax);
			RangePeak widePeak = scanMaxRange(s, yaw, speed, wide, targetY, evaluations);
			if (widePeak.peakRange() + HIT_TOLERANCE >= planRange) {
				Branches wideRoots = branchRoots(s, yaw, speed, planRange, wide, targetY, widePeak, evaluations);
				double suggested = measuredSuggestion(s, yaw, speed, planRange, targetY, wideRoots,
						env.arcPreference(), evaluations);
				if (!Double.isNaN(suggested))
					return AimSolution.failure(AimOutcome.ELEVATION_LIMITED, s, planRange, suggested, Double.NaN,
							evaluations[0]);
			}
		}

		/*
		 * More elevation would not have helped either, so for a gun whose power comes from propellant the last
		 * question is how much charge WOULD have covered it. This probe is deliberately not bounded by the
		 * envelope - it answers "what would this take", which is exactly the case of a mount sitting at its one
		 * loaded charge (min == max), where the shortfall is powder, not aim limits. Autocannons are excluded:
		 * their power is fixed by barrel count and material, so naming a charge there would advise something
		 * nobody can do.
		 */
		if (!s.autocannon()) {
			double suggested = suggestedCharge(s, yaw, env, speed, planRange, targetY, evaluations);
			if (!Double.isNaN(suggested))
				return AimSolution.failure(AimOutcome.CHARGE_INSUFFICIENT, s, planRange, Double.NaN, suggested,
						evaluations[0]);
		}
		return AimSolution.failure(AimOutcome.TARGET_OUT_OF_REACH, s, planRange, evaluations[0]);
	}

	/**
	 * Turn the roots found in the widened window into a number worth telling the gunner: measure each one and
	 * keep only a suggestion that actually lands near the target. A root produced by a discontinuity (the
	 * elevation where a shot first stops clearing the target's altitude plane, so its measured range jumps
	 * from nothing to a long way past the target) measures far off and is dropped, which stops the diagnosis
	 * from recommending an angle that would not hit either.
	 */
	private static double measuredSuggestion(MountSnapshot s, double yaw, double speed, double required,
			double targetY, Branches roots, ArcPreference preference, int[] evaluations) {
		double[] ordered = preference.preferredArc() == AimArc.FLAT
				? new double[] { roots.flatPitch(), roots.lobPitch() }
				: new double[] { roots.lobPitch(), roots.flatPitch() };
		double bestPitch = Double.NaN;
		double bestDeviation = 4.0 * HIT_TOLERANCE;
		for (double pitch : ordered) {
			if (Double.isNaN(pitch))
				continue;
			double deviation = Math.abs(planRangeAt(s, yaw, pitch, speed, targetY, evaluations) - required);
			if (deviation < bestDeviation) {
				bestDeviation = deviation;
				bestPitch = pitch;
			}
		}
		return bestPitch;
	}

	/** Smallest charge above the envelope cap whose best range would cover the target, NaN when none is found. */
	private static double suggestedCharge(MountSnapshot s, double yaw, AimEnvelope env, double capCharge,
			double required, double targetY, int[] evaluations) {
		double step = env.chargeStep();
		double uncovered = capCharge;
		double covered = Double.NaN;
		double probe = capCharge;
		for (int i = 0; i < CHARGE_PROBE_STEPS && evaluations[0] < MAX_EVALUATIONS; i++) {
			probe = probe * 1.5 + step;
			if (scanMaxRange(s, yaw, probe, env, targetY, evaluations).peakRange() + HIT_TOLERANCE >= required) {
				covered = probe;
				break;
			}
			uncovered = probe;
		}
		if (Double.isNaN(covered))
			return Double.NaN;
		for (int i = 0; i < CHARGE_BISECTION_STEPS / 2 && covered - uncovered > step * 0.5
				&& evaluations[0] < MAX_EVALUATIONS; i++) {
			double mid = (uncovered + covered) * 0.5;
			if (scanMaxRange(s, yaw, mid, env, targetY, evaluations).peakRange() + HIT_TOLERANCE >= required)
				covered = mid;
			else
				uncovered = mid;
		}
		return covered;
	}
}

