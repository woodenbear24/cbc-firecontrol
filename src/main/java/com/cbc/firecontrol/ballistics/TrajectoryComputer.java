package com.cbc.firecontrol.ballistics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * Pure-function ballistic integrator: given a {@link MountSnapshot} produced by {@link MountScanner}, step the
 * shot exactly like CBC's projectile motion is stepped - per-tick gravity then drag, position integrated from
 * the resulting velocity - and report where it leaves the simulated flight.
 *
 * <p>The gravity / drag constants below are CBC's own shipped munition values, read out of
 * {@code data/createbigcannons/munition_properties/projectiles/*.json} inside the CBC 5.11.7 jar:
 * big-cannon shells use {@code gravity = -0.05, drag = 0.01, quadratic_drag = false}; autocannon rounds use
 * {@code gravity = -0.025, drag = 0.01, quadratic_drag = false}. They are public data-pack values, not a
 * copy of CBC internals, which is what keeps this whole module mixin-free.
 *
 * <p>No world mutation, no entity spawned, no CBC internals touched - it only queries block states, so it is
 * deterministic and unit-testable.
 */
public final class TrajectoryComputer {

	public static final double BIG_CANNON_GRAVITY = -0.05;
	public static final double BIG_CANNON_DRAG = 0.01;
	public static final double AUTOCANNON_GRAVITY = -0.025;
	public static final double AUTOCANNON_DRAG = 0.01;

	/** Hard cap on simulated ticks (big cannons have no CBC projectile lifetime, so cap the search). */
	public static final int MAX_PREDICT_TICKS = 1200;
	/** Hard cap on simulated travel distance, to bound work when a shot flies off into open sky. */
	public static final double MAX_PREDICT_RANGE = 4096.0;
	/**
	 * CBC spawns the shot with its muzzle INSIDE the last barrel cell and CBC projectiles do not collide with
	 * their own launch structure, so the first {@code CLEARANCE} blocks of flight are not collision-tested -
	 * otherwise every prediction would report a hit inside its own muzzle.
	 */
	private static final double CLEARANCE = 2.0;

	private TrajectoryComputer() {
	}

	/** One simulated flight: where it ended, why, and after how many ticks. */
	public record Flight(LinkStatus status, Vec3 impact, int ticks) {
		public boolean hitTerrain() {
			return status == LinkStatus.BLOCK_HIT;
		}
	}

	/** Direct fire of the mount as it stands right now: current direction, current speed, real terrain. */
	public static TrajectoryPrediction simulate(Level level, MountSnapshot snapshot) {
		if (!snapshot.assembled())
			return TrajectoryPrediction.failure(LinkStatus.NO_CANNON, snapshot.yawDeg(), snapshot.pitchDeg());
		if (snapshot.muzzleSpeed() <= 0.0)
			return TrajectoryPrediction.failure(LinkStatus.NO_CHARGE, snapshot.yawDeg(), snapshot.pitchDeg());
		return build(snapshot, simulateFlight(level, snapshot, snapshot.direction(), snapshot.muzzleSpeed()));
	}

	/** Collision-aware flight along an arbitrary direction (used to VERIFY a solved aim). */
	public static Flight simulateFlight(Level level, MountSnapshot s, Vec3 direction, double speed) {
		return run(s, direction, speed, level, null);
	}

	/**
	 * Where a collision-free flight should be cut short, so the search can measure a flight economically:
	 * either the point where the shot DESCENDS through an altitude plane (only counted once the shot has
	 * actually risen above that plane - a muzzle that sits on the target's own height never "crosses" it on
	 * the way down unless the barrel was elevated) and/or the point where its plan distance reaches a limit.
	 * A flight that never meets either is returned with its terminal status, which callers read as "did not
	 * reach".
	 */
	public record FreeStop(@Nullable Double descendingThroughY, @Nullable Double planDistance) {
		public static final FreeStop NONE = new FreeStop(null, null);
		public static FreeStop atAltitude(double y) {
			return new FreeStop(y, null);
		}
		public static FreeStop atAltitudeAndRange(double y, double planDistance) {
			return new FreeStop(y, planDistance);
		}
	/**
	 * A flight's plan distance is a stop condition on its own: where the shot is at the moment it passes over
	 * the target's own ring, whether or not it has already come back down.
	 */
	public static FreeStop atPlanDistance(double planDistance) {
		return new FreeStop(null, planDistance);
	}

	}

	/** A shot has to clear the observation plane by this much before its descent through it counts. */
	private static final double PLANE_EPSILON = 0.25;

	/** Collision-free flight along an arbitrary direction, cut at the plane and/or plan distance it stops at. */
	public static Flight simulateFree(MountSnapshot s, Vec3 direction, double speed, FreeStop stop) {
		return run(s, direction, speed, null, stop);
	}

	private static Flight run(MountSnapshot s, Vec3 direction, double speed, Level level, FreeStop stop) {
		final Vec3 muzzle = s.muzzle();
		Double planeY = stop == null ? null : stop.descendingThroughY();
		Double stopPlan = stop == null ? null : stop.planDistance();
		boolean abovePlane = planeY == null || muzzle.y > planeY + PLANE_EPSILON;

		Vec3 position = muzzle;
		Vec3 velocity = direction.normalize().scale(speed);

		double gravity = s.gravity();
		double drag = s.drag();
		boolean quadratic = s.quadraticDrag();
		int maxTicks = Math.max(1, Math.min(s.maxFlightTicks(), MAX_PREDICT_TICKS));

		// Sub-step the flight so a fast shot cannot tunnel through a thin wall: at most ~0.5 block per sample.
		int subSteps = Math.max(1, (int) Math.ceil(Math.abs(speed) / 0.5));

		for (int tick = 1; tick <= maxTicks; tick++) {
			// CBC's per-tick order: apply gravity, then drag the velocity, then move.
			velocity = velocity.add(0.0, gravity, 0.0);
			double speedNow = velocity.length();
			double keep = quadratic ? Math.max(0.0, 1.0 - drag * speedNow) : Math.max(0.0, 1.0 - drag);
			velocity = velocity.scale(keep);

			if (velocity.lengthSqr() < 1.0e-8) {
				// The shot has bled off all its speed in air: report where it stalled.
				return new Flight(LinkStatus.LIFETIME_END, position, tick);
			}

			Vec3 step = velocity.scale(1.0 / subSteps);
			for (int subStep = 0; subStep < subSteps; subStep++) {
				Vec3 previous = position;
				Vec3 next = previous.add(step);
				double travelled = next.distanceTo(muzzle);

				if (planeY != null) {
					if (!abovePlane && next.y > planeY + PLANE_EPSILON)
						abovePlane = true;
					if (abovePlane && previous.y > planeY && next.y <= planeY) {
						// Interpolate the crossing so the measured plan range is not quantised by the step size.
						double span = previous.y - next.y;
						double fraction = span <= 1.0e-9 ? 1.0 : (previous.y - planeY) / span;
						return new Flight(LinkStatus.OK, interpolate(previous, next, fraction), tick);
					}
				}
				if (stopPlan != null) {
					double previousPlan = planDistance(muzzle, previous);
					double nextPlan = planDistance(muzzle, next);
					if (previousPlan < stopPlan && nextPlan >= stopPlan) {
						double fraction = nextPlan <= previousPlan ? 1.0 : (stopPlan - previousPlan) / (nextPlan - previousPlan);
						return new Flight(LinkStatus.OK, interpolate(previous, next, fraction), tick);
					}
				}
				if (level != null && travelled > CLEARANCE && blocksFlight(level, next))
					return new Flight(LinkStatus.BLOCK_HIT, previous, tick);

				position = next;
				if (level != null && level.isOutsideBuildHeight(BlockPos.containing(position)))
					return new Flight(LinkStatus.OUT_OF_WORLD, position, tick);
				if (position.distanceTo(muzzle) > MAX_PREDICT_RANGE)
					return new Flight(LinkStatus.TOO_FAR, position, tick);
			}
		}
		return new Flight(LinkStatus.LIFETIME_END, position, maxTicks);
	}

	private static boolean blocksFlight(Level level, Vec3 at) {
		BlockPos blockPos = BlockPos.containing(at);
		BlockState state = level.getBlockState(blockPos);
		return !state.getCollisionShape(level, blockPos).isEmpty();
	}

	private static Vec3 interpolate(Vec3 from, Vec3 to, double fraction) {
		return from.add(to.subtract(from).scale(Math.max(0.0, Math.min(1.0, fraction))));
	}

	/** Distance in the horizontal plane only - the "how far did it throw" a ship's gunner cares about. */
	public static double planDistance(Vec3 from, Vec3 to) {
		return Math.hypot(to.x - from.x, to.z - from.z);
	}

	private static TrajectoryPrediction build(MountSnapshot s, Flight flight) {
		Vec3 impact = flight.impact();
		double horizontal = Math.hypot(impact.x - s.muzzle().x, impact.z - s.muzzle().z);
		double straight = impact.distanceTo(s.muzzle());
		return new TrajectoryPrediction(flight.status(),
				impact.x, impact.y, impact.z,
				horizontal, straight,
				s.muzzleSpeed(), flight.ticks(),
				s.barrelCount(), s.chargePower(),
				s.munitionKey(), s.materialKey(),
				s.yawDeg(), s.pitchDeg());
	}
}
