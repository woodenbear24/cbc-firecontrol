package com.cbc.firecontrol.compat;

import java.lang.reflect.Method;

import com.cbc.firecontrol.CBCFireControl;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * Turns an absolute WORLD coordinate into the coordinate space of the Sable ship that a mount lives in.
 *
 * <p>Why the target moves and the muzzle does not: {@code MountScanner} reads the muzzle through
 * {@code Contraption#toGlobalVector}, which answers in the scale of the level the contraption entity lives in.
 * Inside a ship that is the ship's sub-level, so the muzzle is hull-relative - and the mount's own yaw/pitch are
 * hull-relative too, because a gun bolted to a deck can only turn against the deck. Converting the muzzle into the
 * world would therefore make every solved angle an absolute bearing the mount cannot follow. A target entered by
 * the gunner (typed in, read off a map, or locked with the designator from outside the ship) is world space, so it
 * is the side that has to travel: Sable's sub-level pose maps world space back into the ship, rigidly, so the
 * muzzle-to-target distance is the real distance and the resulting angles stay hull-relative.
 *
 * <p>Sable is an optional dependency and is deliberately NOT on the compile classpath, so every call is resolved
 * reflectively against its runtime API (verified against {@code sable-neoforge-1.21.1-2.0.6}):
 * {@code Sable.HELPER.getContaining(Level, Vec3i)} to find the ship under a world position, and
 * {@code SubLevel#logicalPose()} (a {@code Pose3dc}) with {@code transformPositionInverse(Vec3)} /
 * {@code transformPosition(Vec3)} to move between the two spaces. When Sable is absent, or when no ship covers the
 * position, the coordinate is returned unchanged and the reason is logged.
 */
public final class SableShipCoordinates {

	/**
	 * No cannon of CBC's fires farther than a few thousand blocks, so a target closer than this is already in the
	 * mount's own coordinate space and must be left exactly as it was given. Anything past it is the scale
	 * mismatch this class exists for (measured case: a muzzle at {@code 122, 64, 75} against a target at
	 * {@code 2048xxxx}).
	 */
	private static final double FOREIGN_SCALE_DISTANCE = 100_000.0;
	/** Recomputes run every few ticks; one line per interval is enough to explain a scale decision. */
	private static final long LOG_INTERVAL_MS = 15_000L;

	private static volatile boolean attempted;
	private static boolean available;
	private static Object helper;
	private static Method getContaining;  // (Level, Vec3i) -> SubLevel
	private static Method getContainingEntity;  // (BlockEntity) -> SubLevel, the ship a block is bolted to
	private static Method logicalPose;    // (SubLevel) -> pose
	private static Method toSubLevel;     // (pose, Vec3) -> Vec3, world -> ship-local
	private static Method toWorld;        // (pose, Vec3) -> Vec3, ship-local -> world
	private static long lastLoggedAt;

	private SableShipCoordinates() {
	}

	/**
	 * The target to solve against: {@code target} itself when it is already in the mount's coordinate space, or
	 * that same point expressed in the ship's sub-level coordinates when it is not. Never returns null for a
	 * non-null target - when the ship cannot be resolved the original coordinate is kept, which fails the solve
	 * honestly instead of inventing a target.
	 */
	@Nullable
	public static Vec3 resolveTarget(Level mountLevel, @Nullable BlockEntity mount, Vec3 muzzle,
			@Nullable Vec3 target) {
		if (target == null || !ensureAvailable())
			return target;
		double rawDistance = target.distanceTo(muzzle);
		if (rawDistance <= FOREIGN_SCALE_DISTANCE)
			return target; // same scale as the muzzle already - do not touch a working gun line
		Vec3 hullSpace = worldToShipLocal(mountLevel, mount, target);
		if (hullSpace == null) {
			log("target {} sits {} blocks from muzzle {} and neither the mount's own ship nor a ship under that "
					+ "world position was found (level {}) - the solve keeps the target as given",
					format(target), string(rawDistance), format(muzzle), where(mountLevel));
			return target;
		}
		double hullDistance = hullSpace.distanceTo(muzzle);
		if (hullDistance > rawDistance) {
			log("target {} converted into ship space {} but that is WORSE ({} blocks from muzzle {} vs {} blocks "
					+ "as given, level {}) - the two points are probably not on the same ship",
					format(target), format(hullSpace), string(hullDistance), format(muzzle),
					string(rawDistance), where(mountLevel));
			return target;
		}
		log("target {} is world space, {} blocks from muzzle {}; converted into the ship's own coordinates {} "
				+ "({} blocks away, level {}) - angles stay hull-relative",
				format(target), string(rawDistance), format(muzzle), format(hullSpace), string(hullDistance),
				where(mountLevel));
		return hullSpace;
	}

	/** The ship's sub-level coordinate of a world point, or null when Sable is absent or no ship covers it. */
	@Nullable
	public static Vec3 worldToShipLocal(Level mountLevel, Vec3 worldPos) {
		return worldToShipLocal(mountLevel, null, worldPos);
	}

	/**
	 * The coordinate of a world point inside the ship the {@code mount} is bolted to - which is the ship the
	 * muzzle's own scale belongs to. The mount's ship is asked first: with two ships in play (a duel) the ship
	 * under the target would be the WRONG frame, because the gun can only aim in its own hull's coordinates.
	 * Falls back to whatever ship covers the world point when the mount is not on one at all.
	 */
	@Nullable
	public static Vec3 worldToShipLocal(Level mountLevel, @Nullable BlockEntity mount, Vec3 worldPos) {
		if (!ensureAvailable())
			return null;
		Object pose = poseOfMount(mount);
		if (pose == null)
			pose = poseCovering(mountLevel, worldPos);
		if (pose == null)
			return null;
		try {
			Object result = toSubLevel.invoke(pose, worldPos);
			return result instanceof Vec3 v ? v : null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			log("Sable pose call failed for {}: {}", format(worldPos), e.toString());
			return null;
		}
	}

	/** The pose of the ship a block entity belongs to, or null when it is not on one. */
	@Nullable
	private static Object poseOfMount(@Nullable BlockEntity mount) {
		if (mount == null || getContainingEntity == null)
			return null;
		try {
			Object subLevel = getContainingEntity.invoke(helper, mount);
			return subLevel == null ? null : logicalPose.invoke(subLevel);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** The world coordinate of a ship-local point, or null when Sable is absent or no ship covers it. */
	@Nullable
	public static Vec3 shipLocalToWorld(Level mountLevel, Vec3 hullPos) {
		return transform(mountLevel, hullPos, toWorld);
	}

	/**
	 * The world coordinate of a hull-local point on the ship {@code mount} is bolted to. The mount's own ship is
	 * asked first, because that is the frame the point was measured in; whatever ship happens to cover the point
	 * is the fallback for a caller that has no mount to name. Null only when Sable is absent, the mount is not on
	 * a ship, and no ship covers the point either.
	 */
	@Nullable
	public static Vec3 shipLocalToWorld(Level mountLevel, @Nullable BlockEntity mount, Vec3 hullPos) {
		if (!ensureAvailable())
			return null;
		Object pose = poseOfMount(mount);
		if (pose == null)
			return transform(mountLevel, hullPos, toWorld);
		try {
			Object result = toWorld.invoke(pose, hullPos);
			return result instanceof Vec3 v ? v : null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			log("Sable pose call failed for {}: {}", format(hullPos), e.toString());
			return null;
		}
	}

	@Nullable
	private static Vec3 transform(Level mountLevel, Vec3 pos, Method poseMethod) {
		if (!ensureAvailable())
			return null;
		Object pose = poseCovering(mountLevel, pos);
		if (pose == null)
			return null;
		try {
			Object result = poseMethod.invoke(pose, pos);
			return result instanceof Vec3 v ? v : null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			log("Sable pose call failed for {}: {}", format(pos), e.toString());
			return null;
		}
	}

	/**
	 * The pose of the ship covering {@code pos}. The mount's own level is tried first, because a sub-level answers
	 * for itself; then every other dimension of the server, which is where Sable keeps its sub-level container and
	 * where a footprint-scale position actually lands.
	 */
	@Nullable
	private static Object poseCovering(Level mountLevel, Vec3 pos) {
		BlockPos cell = BlockPos.containing(pos.x, pos.y, pos.z);
		Object pose = poseCoveringIn(mountLevel, cell);
		if (pose != null)
			return pose;
		MinecraftServer server = mountLevel.getServer();
		if (server == null)
			return null;
		for (Level dimension : server.getAllLevels()) {
			if (dimension == mountLevel)
				continue;
			Object found = poseCoveringIn(dimension, cell);
			if (found != null)
				return found;
		}
		return null;
	}

	@Nullable
	private static Object poseCoveringIn(Level level, Vec3i cell) {
		try {
			Object subLevel = getContaining.invoke(helper, level, cell);
			if (subLevel == null)
				return null;
			return logicalPose.invoke(subLevel);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static boolean ensureAvailable() {
		if (attempted)
			return available;
		synchronized (SableShipCoordinates.class) {
			if (attempted)
				return available;
			attempted = true;
			try {
				Class<?> sable = Class.forName("dev.ryanhcode.sable.Sable");
				Object foundHelper = sable.getField("HELPER").get(null);
				Class<?> subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
				Class<?> poseClass = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
				Method foundGetContaining = foundHelper.getClass()
						.getMethod("getContaining", Level.class, Vec3i.class);
				Method foundGetContainingEntity = null;
				try {
					foundGetContainingEntity = foundHelper.getClass().getMethod("getContaining", BlockEntity.class);
					foundGetContainingEntity.setAccessible(true);
				} catch (NoSuchMethodException e) {
					// A Sable build without the BlockEntity overload: the world-position lookup still resolves.
				}
				Method foundLogicalPose = subLevelClass.getMethod("logicalPose");
				Method foundToSubLevel = poseClass.getMethod("transformPositionInverse", Vec3.class);
				Method foundToWorld = poseClass.getMethod("transformPosition", Vec3.class);
				foundGetContaining.setAccessible(true);
				foundLogicalPose.setAccessible(true);
				foundToSubLevel.setAccessible(true);
				foundToWorld.setAccessible(true);
				helper = foundHelper;
				getContaining = foundGetContaining;
				getContainingEntity = foundGetContainingEntity;
				logicalPose = foundLogicalPose;
				toSubLevel = foundToSubLevel;
				toWorld = foundToWorld;
				available = true;
				CBCFireControl.LOGGER.info("[cbc_firecontrol] Sable ship coordinate bridge ready "
						+ "(world <-> sub-level through SubLevel#logicalPose)");
			} catch (ClassNotFoundException e) {
				CBCFireControl.LOGGER.info("[cbc_firecontrol] no Sable present; ship targets stay as given");
			} catch (ReflectiveOperationException | RuntimeException e) {
				CBCFireControl.LOGGER.warn("[cbc_firecontrol] Sable coordinate bridge unavailable: {}", e.toString());
			}
			return available;
		}
	}

	private static void log(String message, Object... args) {
		long now = System.currentTimeMillis();
		if (now - lastLoggedAt < LOG_INTERVAL_MS)
			return;
		lastLoggedAt = now;
		CBCFireControl.LOGGER.info("[cbc_firecontrol] " + message, args);
	}

	private static String where(Level level) {
		return level.getClass().getSimpleName() + " @" + level.dimension().location();
	}

	private static String format(Vec3 v) {
		return String.format("%.1f, %.1f, %.1f", v.x, v.y, v.z);
	}

	private static String string(double value) {
		return String.format("%.0f", value);
	}
}
