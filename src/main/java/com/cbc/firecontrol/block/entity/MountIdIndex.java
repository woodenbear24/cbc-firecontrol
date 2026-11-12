package com.cbc.firecontrol.block.entity;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;

/**
 * Server-side table mapping an Advanced Cannon Mount's persistent id to the position its block entity
 * currently occupies.
 *
 * <p>Why this exists: a link card stores (mount id + last known coordinates). Coordinates alone go stale -
 * the mount can be broken and rebuilt elsewhere, picked up by a Create contraption, or the exact cell can be
 * reused by a DIFFERENT mount. With this table the calculator can follow the real mount by id and rewrite the
 * card's cached coordinates instead of mis-aiming at an unrelated cannon.
 *
 * <p>Entries are added when a server mount block entity loads (or first ticks) and removed on
 * {@code remove()} / {@code onChunkUnloaded()}. Every lookup re-verifies the position against the live block
 * entity, so a missed removal self-cleans on the next query instead of permanently pointing at a ghost.
 */
public final class MountIdIndex {

	private static final Map<LevelAccessor, ConcurrentHashMap<UUID, BlockPos>> BY_LEVEL = new IdentityHashMap<>();

	private MountIdIndex() {
	}

	public static synchronized void register(LevelAccessor level, UUID id, BlockPos pos) {
		if (level == null || id == null)
			return;
		BY_LEVEL.computeIfAbsent(level, l -> new ConcurrentHashMap<>()).put(id, pos.immutable());
	}

	public static synchronized void unregister(LevelAccessor level, UUID id) {
		if (level == null || id == null)
			return;
		ConcurrentHashMap<UUID, BlockPos> map = BY_LEVEL.get(level);
		if (map != null)
			map.remove(id);
	}

	/** The position of the mount holding this id, or null when it is unknown or no longer alive. */
	@Nullable
	public static synchronized BlockPos find(LevelAccessor level, UUID id) {
		if (level == null || id == null)
			return null;
		ConcurrentHashMap<UUID, BlockPos> map = BY_LEVEL.get(level);
		if (map == null)
			return null;
		BlockPos pos = map.get(id);
		if (pos == null)
			return null;
		if (!isMountAt(level, pos, id)) {
			map.remove(id);
			return null;
		}
		return pos;
	}

	/** Same as {@link #find} but rejects mounts further than {@code maxDistance} from {@code from}. */
	@Nullable
	public static synchronized BlockPos find(LevelAccessor level, UUID id, BlockPos from, double maxDistance) {
		BlockPos pos = find(level, id);
		if (pos == null || from == null)
			return null;
		if (pos.getCenter().distanceTo(from.getCenter()) > maxDistance)
			return null;
		return pos;
	}

	private static boolean isMountAt(LevelAccessor level, BlockPos pos, UUID id) {
		return level.getBlockEntity(pos) instanceof AdvancedCannonMountBlockEntity mount
				&& id.equals(mount.mountId());
	}
}
