package com.cbc.firecontrol.block.entity;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * One designator sight line that ran out of simulated space before it found anything.
 *
 * <p>The client walks the line through the chunks it has; the moment the line leaves them it can only report
 * "nothing", even though there may well be a hill 400 blocks ahead that the guns could still shoot at. The
 * console answers that by loading the space ahead along the same line, cell by cell, and stopping as soon as
 * either the shot can no longer get there or the line has been covered.
 *
 * <p>The work is deliberately spread over ticks: a chunk per tick is requested and the walk waits for it, so a
 * spotter looking at an unloaded horizon costs the server one ticket and a bounded scan per tick instead of a
 * synchronous generation storm. Tickets are released when the walk ends (found, out of reach, or timed out),
 * and the ticket type carries its own timeout as the safety net for a console that vanishes mid-walk.
 *
 * <p>Only one walk runs per console at a time: a new middle click replaces the old one, because the spotter has
 * moved on and the earlier line is no longer the one they are looking at.
 */
final class DesignatorProbe {

	/**
	 * Cells tested per tick. Reading a block state is cheap; the bound exists so a line that is entirely inside
	 * already-loaded chunks cannot cost an unbounded scan in one tick.
	 */
	private static final int CELLS_PER_TICK = 96;
	/** Upper bound on chunks held open by one walk, so a very long line cannot pin a strip of the map. */
	private static final int MAX_CHUNKS = 32;
	/** Ticks a walk may run before it gives up on terrain that has not arrived. */
	private static final int TIMEOUT_TICKS = 200;
	/** Ticket level that pulls a chunk all the way to FULL, which is what reading its blocks needs. */
	private static final int TICKET_LEVEL = 31;
	/** Seconds a ticket outlives its walk, covering a console removed without a clean release. */
	private static final int TICKET_TIMEOUT_SECONDS = 60;

	private static final TicketType<ChunkPos> TICKET =
			TicketType.create("cbc_firecontrol_designator_probe", Comparator.comparingLong(ChunkPos::toLong),
					TICKET_TIMEOUT_SECONDS);

	/** Crossing-parameter tolerance for boundaries sharing one point, mirroring the client's walk. */
	private static final double TIE_EPS = 1e-12;

	enum Result {
		/** Nothing yet this tick; keep walking next one. */
		SEARCHING,
		/** A block the console can actually shoot at. */
		FOUND,
		/** The line found a block nothing here can reach, or ran past the console's reach first. */
		BEYOND_REACH,
		/** Terrain did not arrive in time, or the chunk budget ran out. */
		TERRAIN_TIMEOUT
	}

	private final ServerLevel level;
	final ServerPlayer player;

	/** Travel cap in blocks from the eye, supplied by the console: its farthest mount's reach. */
	private final double cap;

	private int x;
	private int y;
	private int z;
	private final int stepX;
	private final int stepY;
	private final int stepZ;
	private final double tDeltaX;
	private final double tDeltaY;
	private final double tDeltaZ;
	private double tMaxX;
	private double tMaxY;
	private double tMaxZ;
	/** Distance along the line reached so far, in blocks. */
	private double travelled;

	private int ticksLeft = TIMEOUT_TICKS;
	private final Set<ChunkPos> ticketed = new LinkedHashSet<>();
	private BlockPos found;

	DesignatorProbe(ServerLevel level, ServerPlayer player, Vec3 origin, Vec3 direction, double cap) {
		this.level = level;
		this.player = player;
		this.cap = cap;

		Vec3 unit = direction.normalize();
		double dx = unit.x;
		double dy = unit.y;
		double dz = unit.z;
		/*
		 * Same Amanatides & Woo setup the client walk uses (see TargetDesignatorClientEvents#walkSightLine),
		 * but with t measured in BLOCKS along the line rather than as a fraction of a finite segment: the walk
		 * has no far end of its own here, it ends on the console's reach, on a block, or on the timeout. The
		 * signed divide is what keeps the next boundary on the correct side of the cell when the line runs the
		 * other way down an axis; an axis that does not change has no boundary to cross.
		 */
		this.x = (int) Math.floor(origin.x);
		this.y = (int) Math.floor(origin.y);
		this.z = (int) Math.floor(origin.z);
		this.stepX = dx > 0 ? 1 : -1;
		this.stepY = dy > 0 ? 1 : -1;
		this.stepZ = dz > 0 ? 1 : -1;
		this.tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : stepX / dx;
		this.tDeltaY = dy == 0 ? Double.POSITIVE_INFINITY : stepY / dy;
		this.tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : stepZ / dz;
		this.tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : (this.x + (stepX > 0 ? 1 : 0) - origin.x) / dx;
		this.tMaxY = dy == 0 ? Double.POSITIVE_INFINITY : (this.y + (stepY > 0 ? 1 : 0) - origin.y) / dy;
		this.tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : (this.z + (stepZ > 0 ? 1 : 0) - origin.z) / dz;
	}

	/** Where the walk got to, in blocks from the spotter's eye - what a "nothing found" answer quotes. */
	double travelled() {
		return travelled;
	}

	/** The cell the walk locked, once {@link #advance} returned {@link Result#FOUND}; null before that. */
	BlockPos foundCell() {
		return found;
	}

	/** Drops every chunk this walk was holding open. Idempotent, and needed however the walk ends. */
	void release() {
		for (ChunkPos pos : ticketed)
			level.getChunkSource().removeRegionTicket(TICKET, pos, TICKET_LEVEL, pos);
		ticketed.clear();
	}

	/**
	 * Walk as far as this tick's budget allows. {@link Result#SEARCHING} means the line is simply not covered
	 * yet - including when the next cell sits in a chunk that has been asked for but has not arrived, which is
	 * the normal state for the first few ticks of a walk onto an unloaded horizon.
	 */
	Result advance(TrajectoryCalculatorBlockEntity console) {
		if (--ticksLeft <= 0)
			return Result.TERRAIN_TIMEOUT;
		for (int i = 0; i < CELLS_PER_TICK; i++) {
			double next = Math.min(Math.min(tMaxX, tMaxY), tMaxZ);
			if (!Double.isFinite(next) || next > cap)
				return Result.BEYOND_REACH;
			travelled = next;
			/*
			 * Boundaries that share one crossing parameter are crossed together, so the cell entered is the one
			 * the line actually spends length inside rather than a side cell it only touches at a corner -
			 * locking the latter would name a block the crosshair is not on.
			 */
			if (tMaxX <= next + TIE_EPS) {
				x += stepX;
				tMaxX += tDeltaX;
			}
			if (tMaxY <= next + TIE_EPS) {
				y += stepY;
				tMaxY += tDeltaY;
			}
			if (tMaxZ <= next + TIE_EPS) {
				z += stepZ;
				tMaxZ += tDeltaZ;
			}

			BlockPos cell = new BlockPos(x, y, z);
			if (!level.isLoaded(cell)) {
				// Wait for this cell rather than stepping over it: a walk that skipped unloaded cells would
				// report the first block it happened to find rather than the first block on the line.
				if (!requestChunk(cell))
					return Result.TERRAIN_TIMEOUT;
				return Result.SEARCHING;
			}
			if (!holdsBlock(level.getBlockState(cell)))
				continue;
			if (console.probeCovers(cell)) {
				found = cell;
				return Result.FOUND;
			}
			return Result.BEYOND_REACH;
		}
		return Result.SEARCHING;
	}

	/** Asks for the chunk ahead of the walk, or reports that this walk has no chunk budget left. */
	private boolean requestChunk(BlockPos cell) {
		ChunkPos pos = new ChunkPos(cell);
		if (ticketed.contains(pos))
			return true;
		if (ticketed.size() >= MAX_CHUNKS)
			return false;
		level.getChunkSource().addRegionTicket(TICKET, pos, TICKET_LEVEL, pos);
		ticketed.add(pos);
		return true;
	}

	/**
	 * The client's rule, kept identical so both sides answer the same question about the same line: air is not a
	 * target, a cell holding only fluid is not either, and everything the player could see counts.
	 */
	private static boolean holdsBlock(BlockState state) {
		if (state.isAir())
			return false;
		return !(state.getBlock() instanceof LiquidBlock);
	}
}
