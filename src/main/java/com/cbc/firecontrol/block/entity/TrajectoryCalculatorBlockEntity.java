package com.cbc.firecontrol.block.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.ballistics.AimArc;
import com.cbc.firecontrol.ballistics.AimEnvelope;
import com.cbc.firecontrol.ballistics.AimOutcome;
import com.cbc.firecontrol.ballistics.AimSolution;
import com.cbc.firecontrol.ballistics.InverseBallistics;
import com.cbc.firecontrol.ballistics.LinkStatus;
import com.cbc.firecontrol.ballistics.MountScanner;
import com.cbc.firecontrol.ballistics.MountSnapshot;
import com.cbc.firecontrol.ballistics.TrajectoryComputer;
import com.cbc.firecontrol.ballistics.RoundState;
import com.cbc.firecontrol.ballistics.TrajectoryPrediction;
import com.cbc.firecontrol.init.CBCItems;
import com.cbc.firecontrol.item.CalculatorLinkData;
import com.cbc.firecontrol.menu.TrajectoryCalculatorMenu;
import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.createmod.catnip.data.Couple;
import net.createmod.catnip.math.AngleHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Trajectory Calculator block entity: five real inventory slots, each holding at most one
 * {@code calculator_link} card, and (on the server) the firing solution computed for the mount each card
 * points at.
 *
 * <p>Server -> client flow: the solution for every slot is recomputed on the server tick (the mounted cannon
 * contraption entity only exists there), and whenever the set of solutions changes it is pushed to clients
 * through the block entity's own data packet ({@code write(..., clientPacket=true)}), so the GUI just reads
 * the client-side copy of this BE - no custom payload is needed for the readouts.
 *
 * <p>Items are real inventory items (NeoForge {@link ItemStackHandler}, exposed through the
 * {@code ItemHandler.BLOCK} capability registered in {@link com.cbc.firecontrol.CBCFireControl}), so cards
 * survive reloads, are dropped when the block breaks, and are moved by the vanilla menu slots rather than a
 * ghost container.
 */
public class TrajectoryCalculatorBlockEntity extends SmartBlockEntity implements MenuProvider {

	/** One link card per slot -> at most 5 mounts in play, matching the 5 GUI readout rows. */
	public static final int SLOTS = 5;
	/** Wireless reach of a link card, mirroring Create's own redstone-link range. */
	public static final int LINK_RANGE = 256;
	/** Server recompute period in ticks (0.25 s): live enough to track aiming, cheap enough per mount. */
	private static final int RECOMPUTE_INTERVAL = 5;

	/**
	* Wireless channels per link slot. Column meaning is IDENTICAL to the mount radio's columns
	* (0 = yaw +, 1 = yaw -, 2 = pitch +, 3 = pitch -, 4 = fire, see AdvancedCannonMountBlockEntity), so a
	* calculator column and a mount column that hold the same two filter items in the same order land on the
	* same Create redstone-link network key and therefore connect.
	*/
	public static final int CHANNELS_PER_SLOT = 5;
	/** Each channel is one ordered Couple&lt;Frequency&gt; = two filter slots, exactly like Create's own links. */
	public static final int FILTERS_PER_CHANNEL = 2;
	public static final int CHANNEL_ITEM_SLOTS = SLOTS * CHANNELS_PER_SLOT * FILTERS_PER_CHANNEL;

	/**
	 * Period of the mount-to-calculator channel copy in ticks (1 s). The copy is one-way on purpose: the mount
	 * radio is where a frequency pair is authored, and a calculator row only mirrors it. One second is plenty -
	 * nothing transmits faster than the aim loop's own 5 tick recompute - and it keeps five 10-element array
	 * copies off the hot path.
	 */
	private static final int MOUNT_CHANNEL_SYNC_INTERVAL = 20;

	/* ------------------------------------------------------------------ fire missions */

	/**
	 * Period of a mount's mission check in ticks. A mount that cannot take the order in front of it - no
	 * assembled cannon, no shootable round, no solvable aim - is refused here, one refusal per period, until
	 * its chances run out. Ten checks at ten ticks is a hundred ticks of grace, which is roughly how long a
	 * breech takes to reload; a mount that is still unable after that loses the order rather than holding the
	 * queue open for a gun nobody can fire.
	 */
	public static final int MISSION_CHECK_INTERVAL = 10;
	/** Refusal chances a mount gets per mission before the console drops that mission from its queue. */
	public static final int MISSION_REJECTIONS = 10;
	/** Orders a console holds waiting for a mount before it starts discarding the oldest. */
	public static final int MAX_PENDING_MISSIONS = 16;
	/** Orders a single mount queue holds, including the one it is working on. */
	public static final int MAX_MISSIONS_PER_MOUNT = 16;

	/**
	 * What a mount's front-of-queue order is doing, mirrored to clients for the tasks board. The phases are
	 * strictly a progression: an order waits its check, turns the gun onto it, fires once on aim, and is then
	 * gone - the next order behind it takes its place.
	 */
	public enum MissionPhase {
		/** No order in this mount's queue. */
		NONE,
		/** Accepted by nothing yet; its refusal chances are being spent while the mount cannot take it. */
		QUEUED,
		/** Accepted: the link is driving this mount onto the order's aim. */
		AIMING,
		/** On aim; the fire channel is open and the shot this order asked for is on its way. */
		FIRING
	}

	/**
	 * One order within one mount's queue. Server-side only: the client sees the phase and the chances left,
	 * not this object.
	 */
	private static final class MissionEntry {

		private final FireMission mission;
		private int rejectionsLeft = MISSION_REJECTIONS;
		/** The mount took this order and the row is driving onto it. */
		private boolean active;
		/** The order's shot went out; the entry is dropped on the next check. */
		private boolean fired;

		private MissionEntry(FireMission mission) {
			this.mission = mission;
		}
	}


	private final ItemStackHandler inventory = new ItemStackHandler(SLOTS) {
		@Override
		public boolean isItemValid(int slot, ItemStack stack) {
			return stack.is(CBCItems.CALCULATOR_LINK.asItem());
		}

		@Override
		protected void onContentsChanged(int slot) {
			setChanged();
			linksChanged = true;
			// Which slots count as "active" (and therefore transmit) depends on the inventory.
			channelsChanged = true;
			/*
			 * Pulling a card - or replacing it with another one - ends that row's orders outright: a queue is
			 * that mount's, and the mount it belonged to is gone. The row is disarmed here rather than on the
			 * next check so a card pulled mid-turn stops the turn on the tick it was pulled.
			 */
			if (!missionQueues[slot].isEmpty()) {
				missionQueues[slot].clear();
				missionsChanged = true;
				Level level = getLevel();
				if (level != null && !level.isClientSide())
					disarmMissionRow(level, slot);
			}
			// A card that just went in may take orders the console was holding for want of any mount.
			missionsChanged = true;
		}
	};

	private final TrajectoryPrediction[] predictions = new TrajectoryPrediction[SLOTS];
	/** The aim each slot's mount would need to hit {@link #targetVec()}; client-mirrored copy. */
	private final AimSolution[] aims = new AimSolution[SLOTS];
	/**
	* What CBC's own firing prerequisites say about each slot's mount, synced alongside the solutions so a row
	* reading NO_ROUND can name WHICH prerequisite is missing in its tooltip: nothing shootable in the bore, a
	* round not assembled to its full length, or a charge that cannot ignite its load.
	*/
	private final RoundState[] rounds = new RoundState[SLOTS];

	/**
	* Signal strength transmitted on each (slot, channel). Channel order is the mount radio's:
	* yaw plus, yaw minus, pitch up, pitch down, fire.
	*/
	private final int[][] channelStrength = new int[SLOTS][CHANNELS_PER_SLOT];

	/** Rows whose solved aim the link is currently driving; set by the Aim button. */
	private final boolean[] aimArmed = new boolean[SLOTS];

	/** Rows whose mount sits inside the aim deadband right now. */
	private final boolean[] aimOnTarget = new boolean[SLOTS];

	/**
	 * Fire missions. The console holds orders it has received but could not hand to any mount yet (no card is
	 * in, or nothing is in range); every active row gets its own copy of each order it was given, and works
	 * through that queue on its own. Queues diverge by design: a mount that refuses an order ten times loses
	 * that order alone and moves on to the one behind it.
	 */
	private final List<FireMission> pendingMissions = new ArrayList<>();
	@SuppressWarnings("unchecked")
	private final ArrayDeque<MissionEntry>[] missionQueues = new ArrayDeque[SLOTS];
	/** Countdown to the next per-mount mission check. */
	private int missionCooldown = MISSION_CHECK_INTERVAL;
	/** Set whenever queues or phases moved, so the client copy is refreshed even if no aim did. */
	private boolean missionsChanged;

	/* Client mirror of the mission state, shipped in the data packet for the tasks board. */
	private final List<FireMission> clientMissions = new ArrayList<>();
	private final int[] missionQueueSizes = new int[SLOTS];
	private final int[] missionPhases = new int[SLOTS];
	private final int[] missionRejectionsLeft = new int[SLOTS];
	private final double[] missionFrontX = new double[SLOTS];
	private final double[] missionFrontY = new double[SLOTS];
	private final double[] missionFrontZ = new double[SLOTS];

	/**
	* Controller memory, one entry per row and axis: accumulated error, the previous tick's error, its
	* smoothed rate of change, and the level last transmitted. Server-side control state only - it is never
	* synced, and it is cleared whenever a row is unarmed, arrives on aim, or is pressed again.
	*/
	private final double[] integral = new double[SLOTS * AXES_PER_ROW];
	private final double[] previousError = new double[SLOTS * AXES_PER_ROW];
	private final double[] smoothedRate = new double[SLOTS * AXES_PER_ROW];
	private final int[] heldLevel = new int[SLOTS * AXES_PER_ROW];

	/**
	* The solution each row was measured against last tick. When the periodic re-solve replaces it the
	* reference moved rather than the barrel, and the derivative must not be read as a jump.
	*/
	private final AimSolution[] drivenSolution = new AimSolution[SLOTS];

	/**
	* Mirror of the drive state, shipped to clients in the solution packet so the GUI can say
	* which row the link is driving. Bit 0 is driving, bit 1 is on aim; the remaining bits pack one
	* nibble per channel of the strength being transmitted, which is what the filter slots display.
	*/
	private final int[] linkFlags = new int[SLOTS];
	private final int[] linkLevels = new int[SLOTS];

	/** Set whenever the aim flags or channel strengths differ from what clients last saw. */
	private boolean aimStateChanged;
	private boolean linksChanged = true;
	private int recomputeCooldown = 0;
	/** Countdown of {@link #MOUNT_CHANNEL_SYNC_INTERVAL}; forced to 0 whenever a card is inserted or re-bound. */
	private int mountChannelCooldown = 0;

	/** Target point the gunner entered, in absolute world coordinates (the GUI fields hold exactly this). */
	private boolean hasTarget;
	private double targetX;
	private double targetY;
	private double targetZ;

	/**
	* Filter items of the per-slot channels. NOT a real inventory: nothing is consumed here, the stacks are
	* pure configuration, stored in the BE (so they persist and reach clients) and edited through the menu's
	* ghost slots. Layout is {@code (slot * CHANNELS_PER_SLOT + channel) * FILTERS_PER_CHANNEL + which}.
	*/
	private final ItemStackHandler slotChannels = new ItemStackHandler(CHANNEL_ITEM_SLOTS);

	private final List<SlotChannelTransmitter> activeTransmitters = new ArrayList<>();
	private boolean channelsChanged = true;
	/**
	* Redstone level of the block feeding this calculator. It no longer drives the yaw/pitch
	* channels, which now carry the closed-loop correction; it gates the FIRE channel, so a
	* gunner who powers the console fires each mount only once that mount is on aim.
	*/
	private int emittedLinkPower = 0;

	/**
	* This calculator's own persistent id. Only the mount cards carry mount ids today; the calculator id is
	* stored so later features (designator-to-calculator binding, multi-calculator fire control) have a stable
	* handle that does not depend on the block having been moved.
	*/
	private UUID calculatorId;

	public TrajectoryCalculatorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		Arrays.fill(predictions, TrajectoryPrediction.EMPTY);
		Arrays.fill(aims, AimSolution.EMPTY);
		Arrays.fill(rounds, RoundState.READY);
		Arrays.fill(previousError, Double.NaN);
		Arrays.fill(smoothedRate, Double.NaN);
		for (int slot = 0; slot < SLOTS; slot++)
			missionQueues[slot] = new ArrayDeque<>();
		// lazyTick is the safety net: a mount finishing assembly or its chunk reloading changes a solution
		// without any inventory change reaching onContentsChanged.
		setLazyTickRate(2);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
	}

	public ItemStackHandler getInventory() {
		return inventory;
	}

	public TrajectoryPrediction getPrediction(int slot) {
		if (slot < 0 || slot >= SLOTS)
			return TrajectoryPrediction.EMPTY;
		return predictions[slot];
	}

	/** The solution computed for this slot's mount against the entered target, or EMPTY when there is none. */
	/** The CBC firing prerequisites this slot's mount currently fails (READY when its next shot exists). */
	public RoundState roundState(int slot) {
		if (slot < 0 || slot >= SLOTS)
			return RoundState.READY;
		return rounds[slot] == null ? RoundState.READY : rounds[slot];
	}

	public AimSolution getAim(int slot) {
		if (slot < 0 || slot >= SLOTS)
			return AimSolution.EMPTY;
		return aims[slot];
	}

	public boolean hasTarget() {
		return hasTarget;
	}

	public double targetX() {
		return targetX;
	}

	public double targetY() {
		return targetY;
	}

	public double targetZ() {
		return targetZ;
	}

	public Vec3 targetVec() {
		return new Vec3(targetX, targetY, targetZ);
	}

	/**
	* Set the target point (absolute world coordinates) and arm an immediate recompute. Only non-finite
	* coordinates are refused - a wild number is noise - and there is no distance bound: how far a target sits
	* from this calculator is the ballistics' answer (out of reach is a legitimate one), not a rule here. The
	* GUI keeps showing the previous target rather than silently clearing it when a write is refused.
	*/
	public void setTarget(double x, double y, double z) {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
			return;
		if (hasTarget && targetX == x && targetY == y && targetZ == z)
			return;
		hasTarget = true;
		targetX = x;
		targetY = y;
		targetZ = z;
		linksChanged = true;
		setChanged();
		sendData();
		aimStateChanged = false;
	}

	public void clearTarget() {
		if (!hasTarget)
			return;
		hasTarget = false;
		linksChanged = true;
		setChanged();
		sendData();
		aimStateChanged = false;
	}

	@Override
	public void tick() {
		super.tick();
		Level level = getLevel();
		if (level == null || level.isClientSide())
			return;
		if (linksChanged) {
			linksChanged = false;
			recomputeCooldown = 0;
			channelsChanged = true;
			mountChannelCooldown = 0;
		}
		if (channelsChanged) {
			channelsChanged = false;
			rebuildLinkTransmitters();
		}
		/*
		 * Mirror the linked mounts' radio columns into this row's filters. A card that was just inserted or
		 * re-pointed asks for it immediately (mountChannelCooldown was forced to 0 above), so the row transmits
		 * the frequencies its mount actually listens for instead of whatever was typed in here last. The copy
		 * flags channelsChanged, so the transmitters are rebuilt on the next tick.
		 */
		if (mountChannelCooldown-- <= 0) {
			mountChannelCooldown = MOUNT_CHANNEL_SYNC_INTERVAL;
			syncChannelsFromMounts(level);
		}
		updateEmittedLinkPower(level);
		driveAimChannels(level);
		releaseFiredMissions(level);
		if (missionCooldown-- <= 0) {
			missionCooldown = MISSION_CHECK_INTERVAL;
			tickMissions(level);
		}
		if (recomputeCooldown-- <= 0) {
			recomputeCooldown = RECOMPUTE_INTERVAL;
			recomputeAndSync();
		}
	}

	@Override
	public void lazyTick() {
		super.lazyTick();
		Level level = getLevel();
		if (level != null && !level.isClientSide())
			recomputeAndSync();
	}

	/** Server only: recompute all five solutions and sync only if something actually changed. */
	private void recomputeAndSync() {
		Level level = getLevel();
		if (level == null || level.isClientSide() || !level.isLoaded(worldPosition))
			return;

		TrajectoryPrediction[] fresh = new TrajectoryPrediction[SLOTS];
		AimSolution[] freshAims = new AimSolution[SLOTS];
		RoundState[] freshRounds = new RoundState[SLOTS];
		boolean changed = false;
		for (int slot = 0; slot < SLOTS; slot++) {
			SlotResult result = resolveSlot(level, slot, false, missionAimTarget(slot));
			fresh[slot] = result.prediction();
			freshAims[slot] = result.aim();
			freshRounds[slot] = result.snapshot() == null ? RoundState.READY : result.snapshot().round();
			if (!fresh[slot].equals(predictions[slot]) || !sameAim(freshAims[slot], aims[slot])
					|| freshRounds[slot] != rounds[slot])
				changed = true;
		}
		if (!changed && !aimStateChanged && !missionsChanged)
			return;

		System.arraycopy(fresh, 0, predictions, 0, SLOTS);
		System.arraycopy(freshAims, 0, aims, 0, SLOTS);
		System.arraycopy(freshRounds, 0, rounds, 0, SLOTS);
		setChanged();
		sendData();
		aimStateChanged = false;
		missionsChanged = false;
	}

	/** Counted outcome of one Aim button press across all five rows. */
	public record AimSummary(int linked, int armed, int refused, double worstMiss) {

		/** Nothing this calculator records can be aimed: no rows linked, or no target entered. */
		public boolean isEmpty() {
			return linked == 0;
		}
	}

	/**
	* The Aim button: arm every linked mount this calculator records, so Create's wireless link
	* drives each one to the aim its row solved for the entered target.
	* *
	* <p>Nothing here moves a gun directly. The press stores the solved angles and switches the
	* row's channels on; from then on {@link #driveAimChannels} compares the mount's own bore angle
	* against them every tick and sends the difference as signal strength, which the mount turns at
	* a rate proportional to it. The row stays armed and keeps correcting until the gunner pulls the
	* card, so a gun slewed off aim by hand is brought back without pressing anything again.
	* *
	* <p>A row is armed only when its solve succeeded WITH the terrain check and its breech can
	* actually fire the next shot: a path that leaves the muzzle through a hill is refused rather than
	* slewing the gun to an angle it cannot shoot from, and an empty or incomplete breech has no
	* point being driven at all. Both refusals say so in the chat readout.
	*/

	public AimSummary applyAllAims(ServerPlayer player) {
		Level level = getLevel();
		if (level == null || level.isClientSide() || !hasTarget)
			return new AimSummary(0, 0, 0, Double.NaN);
		int linked = 0, armed = 0, refused = 0;
		double worstMiss = Double.NaN;
		for (int slot = 0; slot < SLOTS; slot++) {
			if (!isSlotActive(slot))
				continue;
			/*
			 * A row working through its mission queue is already driven, onto the order in front of it; the
			 * manual Aim button neither re-aims it at the console's typed target nor counts it as a refusal.
			 */
			if (missionActive(slot)) {
				linked++;
				armed++;
				continue;
			}
			SlotResult result = resolveSlot(level, slot, true, targetVec());
			if (result.mount() == null)
				continue;
			linked++;
			MountSnapshot snap = result.snapshot();
			AimSolution aim = result.aim();
			if (snap == null || !snap.canFireNext() || aim == null || !aim.hasSolution()) {
				refused++;
				continue;
			}
			resetPidRow(slot);
			aimOnTarget[slot] = false;
			aims[slot] = aim;
			aimArmed[slot] = true;
			aimStateChanged = true;
			armed++;
			double miss = solvedMiss(result);
			if (Double.isFinite(miss))
				worstMiss = Double.isNaN(worstMiss) ? miss : Math.max(worstMiss, miss);
		}
		if (linked == 0) {
			player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.aim_none"), true);
		} else {
			player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.aim_summary",
				armed, refused), true);
			driveAimChannels(level);
			if (!Double.isNaN(worstMiss))
				player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.aim_accuracy",
					String.format(Locale.ROOT, "%.2f", worstMiss)), false);
		}
		return new AimSummary(linked, armed, refused, worstMiss);
	}

	/**
	* How far the shot this row solves to lands from the target, measured from the pose the mount stands in
	* right now - so after a turn it doubles as how far off the mount still is. NaN when there is no solution
	* to measure.
	*/
	private static double solvedMiss(SlotResult result) {
		return result.aim().hasSolution() ? result.aim().missDistance() : Double.NaN;
	}

	/**
	* What one slot says: the next shot that mount would fire, the aim it would need for the entered target,
	* the mount itself so an apply action does not have to resolve the link a second time, and the live scan
	* that both were computed from (the closed-loop aim refinement compares the scan before a turn with the
	* scan after it).
	*/
	private record SlotResult(TrajectoryPrediction prediction, AimSolution aim,
			AdvancedCannonMountBlockEntity mount, MountSnapshot snapshot) {

		static SlotResult of(LinkStatus status) {
			return new SlotResult(TrajectoryPrediction.failure(status, 0, 0), AimSolution.EMPTY, null, null);
		}
	}

	/**
	* Resolve slot -&gt; link card -&gt; mount, then simulate its next shot and solve {@code aimTarget}
	* (null when there is nothing for this row to be aimed at - neither an order in front of it nor a typed
	* console target). {@code checkTerrain} adds the solver's flight-through-blocks test, which the Aim path
	* needs before it will move a gun and the periodic readouts deliberately skip.
	*/
	private SlotResult resolveSlot(Level level, int slot, boolean checkTerrain, Vec3 aimTarget) {
		ItemStack stack = inventory.getStackInSlot(slot);
		if (stack.isEmpty())
			return new SlotResult(TrajectoryPrediction.EMPTY, AimSolution.EMPTY, null, null);
		if (!stack.is(CBCItems.CALCULATOR_LINK.asItem()))
			return SlotResult.of(LinkStatus.NOT_A_LINK);

		CalculatorLinkData link = stack.get(CBCItems.CALCULATOR_LINK_DATA.get());
		if (link == null)
			return SlotResult.of(LinkStatus.UNBOUND);

		UUID wantedId = link.mountIdOrNull();
		BlockPos target = link.mount();

		/*
		* Two ways a card can be pointing at a mount:
		*   - the cached coordinates still hold THAT mount (the fast, normal case), or
		*   - the coordinates are stale (the mount was rebuilt elsewhere / moved, or its old cell was reused by
		*     another block) and the mount must be followed by id through MountIdIndex.
		* Cards with no id recorded (bound before ids existed) keep the old coordinates-only behaviour.
		*/
		AdvancedCannonMountBlockEntity mount = asMount(level.getBlockEntity(target), wantedId);
		if (mount == null && wantedId != null) {
			BlockPos found = MountIdIndex.find(level, wantedId);
			if (found == null)
				return SlotResult.of(LinkStatus.NOT_A_MOUNT);
			if (found.getCenter().distanceTo(getBlockPos().getCenter()) > LINK_RANGE)
				return SlotResult.of(LinkStatus.OUT_OF_RANGE);
			// Self-heal the card so the next recompute takes the fast path, and persist the corrected link.
			stack.set(CBCItems.CALCULATOR_LINK_DATA.get(), link.withMount(found));
			setChanged();
			target = found;
			mount = asMount(level.getBlockEntity(found), wantedId);
			if (mount == null)
				return SlotResult.of(LinkStatus.NOT_A_MOUNT);
		}
		if (mount == null)
			return SlotResult.of(LinkStatus.NOT_A_MOUNT);

		if (target.getCenter().distanceTo(getBlockPos().getCenter()) > LINK_RANGE)
			return SlotResult.of(LinkStatus.OUT_OF_RANGE);
		// The scan needs the mount's assembled contraption, which only exists while its chunk is loaded.
		if (!level.isLoaded(target))
			return SlotResult.of(LinkStatus.MOUNT_UNLOADED);

		MountSnapshot snapshot = MountScanner.scan(mount);
		if (!snapshot.assembled())
			return new SlotResult(TrajectoryPrediction.failure(LinkStatus.NO_CANNON, snapshot.yawDeg(),
					snapshot.pitchDeg()), AimSolution.EMPTY, mount, snapshot);

		TrajectoryPrediction forward = TrajectoryComputer.simulate(level, snapshot);
		if (aimTarget == null)
			return new SlotResult(forward, AimSolution.EMPTY, mount, snapshot);

		/*
		* The Level is only handed to the solver on the Apply path. Its terrain check flies the SOLVED path
		* through real blocks for every candidate elevation it verifies, and the readouts recompute every
		* RECOMPUTE_INTERVAL ticks for up to five mounts at once - affordable to do once when the gunner commits
		* an aim, not continuously while the rows are just being kept live.
		*/
		AimSolution aim = InverseBallistics.solve(snapshot, aimTarget, aimEnvelopeFor(mount, snapshot),
				checkTerrain ? level : null);
		return new SlotResult(forward, aim, mount, snapshot);
	}

	/**
	* The aim window this mount's own cannon physically allows (read off CBC's elevation/depression limits,
	* in the solver's world-elevation convention), with the propellant CBC currently has loaded FIXED: a
	* calculator cannot add powder, so when that load falls short the solver answers CHARGE_INSUFFICIENT and
	* names the charge that WOULD have reached. Autocannons have no charge at all - their speed comes from the
	* barrel count and material, which {@link MountScanner} already resolved into {@code muzzleSpeed}.
	*/
	private static AimEnvelope aimEnvelopeFor(AdvancedCannonMountBlockEntity mount, MountSnapshot snapshot) {
		double minDeg = mount.aimPitchMinDeg();
		double maxDeg = mount.aimPitchMaxDeg();
		if (snapshot.autocannon())
			return new AimEnvelope(minDeg, maxDeg, 0.0, 0.0, 1.0);
		double charge = Math.max(snapshot.chargePower(), snapshot.muzzleSpeed());
		return new AimEnvelope(minDeg, maxDeg, charge, charge, 1.0);
	}

	/** The block entity at the cached coordinates, but only when it is the mount the card actually means. */
	private static AdvancedCannonMountBlockEntity asMount(BlockEntity candidate, UUID wantedId) {
		if (!(candidate instanceof AdvancedCannonMountBlockEntity mount))
			return null;
		if (wantedId == null)
			return mount; // legacy card: coordinates decide, as before mount ids existed
		return wantedId.equals(mount.mountId()) ? mount : null;
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		tag.put("Links", inventory.serializeNBT(registries));
		tag.put("SlotChannels", slotChannels.serializeNBT(registries));
		if (calculatorId != null)
			tag.putString("CalculatorId", calculatorId.toString());
		tag.putInt("EmittedLinkPower", emittedLinkPower);
		tag.putBoolean("HasTarget", hasTarget);
		tag.putDouble("TargetX", targetX);
		tag.putDouble("TargetY", targetY);
		tag.putDouble("TargetZ", targetZ);
		if (clientPacket) {
			tag.put("Solutions", writeSolutions());
			tag.put("Aims", writeAims());
			tag.put("MissionView", writeMissionView());
		} else {
			tag.put("Missions", writePendingMissions());
			tag.put("SlotMissions", writeSlotMissions());
		}
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		if (tag.contains("Links"))
			inventory.deserializeNBT(registries, tag.getCompound("Links"));
		if (tag.contains("SlotChannels"))
			slotChannels.deserializeNBT(registries, tag.getCompound("SlotChannels"));
		if (tag.contains("CalculatorId", Tag.TAG_STRING)) {
			String stored = tag.getString("CalculatorId");
			if (!stored.isEmpty()) {
				try {
					calculatorId = UUID.fromString(stored);
				} catch (IllegalArgumentException e) {
					CBCFireControl.LOGGER.warn("[cbc_firecontrol] calculator {} has an unreadable CalculatorId '{}'",
							worldPosition, stored);
				}
			}
		}
		emittedLinkPower = tag.getInt("EmittedLinkPower");
		hasTarget = tag.getBoolean("HasTarget");
		targetX = tag.getDouble("TargetX");
		targetY = tag.getDouble("TargetY");
		targetZ = tag.getDouble("TargetZ");
		// Both sides re-register on read: the server builds its transmitters, the client refreshes its view.
		channelsChanged = true;
		if (tag.contains("Solutions"))
			readSolutions(tag.getList("Solutions", Tag.TAG_COMPOUND));
		if (tag.contains("Aims"))
			readAims(tag.getList("Aims", Tag.TAG_COMPOUND));
		/*
		 * Missions are read last: deserializing the inventory fires onContentsChanged, which deletes the
		 * queue of any row whose card changed, and the stored queues have to survive that. The client only
		 * ever sees the view; the queues themselves live on the server.
		 */
		if (clientPacket && tag.contains("MissionView"))
			readMissionView(tag.getCompound("MissionView"));
		if (!clientPacket) {
			if (tag.contains("Missions")) {
				pendingMissions.clear();
				for (Tag entry : tag.getList("Missions", Tag.TAG_COMPOUND))
					pendingMissions.add(FireMission.read((CompoundTag) entry));
			}
			readSlotMissions(tag.getList("SlotMissions", Tag.TAG_COMPOUND));
		}
	}

	private ListTag writeSolutions() {
		ListTag list = new ListTag();
		for (int slot = 0; slot < SLOTS; slot++) {
			TrajectoryPrediction p = predictions[slot];
			CompoundTag entry = new CompoundTag();
			entry.putInt("Status", p.status().ordinal());
			entry.putDouble("ImpactX", p.impactX());
			entry.putDouble("ImpactY", p.impactY());
			entry.putDouble("ImpactZ", p.impactZ());
			entry.putDouble("RangeH", p.horizontalRange());
			entry.putDouble("Range3D", p.straightRange());
			entry.putDouble("Speed", p.muzzleSpeed());
			entry.putInt("Ticks", p.flightTicks());
			entry.putInt("Barrels", p.barrelCount());
			entry.putDouble("Charge", p.chargePower());
			entry.putString("Munition", p.munitionKey());
			entry.putString("Material", p.materialKey());
			entry.putDouble("Yaw", p.yawDeg());
			entry.putDouble("Pitch", p.pitchDeg());
			entry.putInt("Round", rounds[slot].ordinal());
			int flags = (aimArmed[slot] ? 1 : 0) | (aimOnTarget[slot] ? 2 : 0);
			entry.putInt("Link", flags);
			entry.putInt("Levels", packedChannelLevels(slot));
			list.add(entry);
		}
		return list;
	}

	private void readSolutions(ListTag list) {
		for (int slot = 0; slot < SLOTS; slot++) {
			if (slot >= list.size()) {
				rounds[slot] = RoundState.READY;
				linkFlags[slot] = 0;
				linkLevels[slot] = 0;
				predictions[slot] = TrajectoryPrediction.EMPTY;
				continue;
			}
			CompoundTag entry = list.getCompound(slot);
			rounds[slot] = RoundState.byIndex(entry.getInt("Round"));
			linkFlags[slot] = entry.getInt("Link");
			linkLevels[slot] = entry.getInt("Levels");
			predictions[slot] = new TrajectoryPrediction(
					LinkStatus.byId(entry.getInt("Status")),
					entry.getDouble("ImpactX"), entry.getDouble("ImpactY"), entry.getDouble("ImpactZ"),
					entry.getDouble("RangeH"), entry.getDouble("Range3D"),
					entry.getDouble("Speed"), entry.getInt("Ticks"),
					entry.getInt("Barrels"), entry.getDouble("Charge"),
					entry.getString("Munition"), entry.getString("Material"),
					entry.getDouble("Yaw"), entry.getDouble("Pitch"));
		}
	}

	/**
	* NaN-safe comparison of two solutions, and deliberately blind to {@code evaluations}. A failed solve carries
	* NaN in its suggestion fields, and record equality treats NaN as unequal to NaN - which would make every
	* recompute look like a change and push the whole solution packet to every watching client every five ticks.
	* The evaluation count is telemetry that also drifts between identical-looking solves, so it is excluded too.
	*/
	private static boolean sameAim(AimSolution a, AimSolution b) {
		if (a == b)
			return true;
		if (a == AimSolution.EMPTY || b == AimSolution.EMPTY)
			return false;
		if (a.outcome() != b.outcome() || a.arc() != b.arc() || a.alternativeArc() != b.alternativeArc()
				|| a.flightTicks() != b.flightTicks())
			return false;
		return sameNumber(a.yawDeg(), b.yawDeg()) && sameNumber(a.pitchDeg(), b.pitchDeg())
				&& sameNumber(a.chargePower(), b.chargePower()) && sameNumber(a.muzzleSpeed(), b.muzzleSpeed())
				&& sameNumber(a.impactX(), b.impactX()) && sameNumber(a.impactY(), b.impactY())
				&& sameNumber(a.impactZ(), b.impactZ()) && sameNumber(a.targetPlanRange(), b.targetPlanRange())
				&& sameNumber(a.impactPlanRange(), b.impactPlanRange())
				&& sameNumber(a.altitudeError(), b.altitudeError())
				&& sameNumber(a.suggestedPitchDeg(), b.suggestedPitchDeg())
				&& sameNumber(a.suggestedCharge(), b.suggestedCharge());
	}

	private static boolean sameNumber(double a, double b) {
		return a == b || (Double.isNaN(a) && Double.isNaN(b));
	}

	/**
	* The aim solutions, pushed with the rest of the client packet. Suggestion fields are written behind a
	* presence flag rather than as NaN, because NBT doubles round-trip through text in some paths and NaN is
	* not representable there.
	*/
	private ListTag writeAims() {
		ListTag list = new ListTag();
		for (int slot = 0; slot < SLOTS; slot++) {
			AimSolution aim = aims[slot];
			CompoundTag entry = new CompoundTag();
			/*
			* The aim array starts as AimSolution.EMPTY (no slot has been solved yet). That is a distinct state
			* from a solve that legitimately failed, and record identity would not survive the round trip, so
			* presence travels as its own flag.
			*/
			entry.putBoolean("Computed", aim != AimSolution.EMPTY);
			entry.putInt("Outcome", aim.outcome().ordinal());
			entry.putInt("Arc", aim.arc().ordinal());
			entry.putBoolean("AltArc", aim.alternativeArc());
			entry.putDouble("Yaw", aim.yawDeg());
			entry.putDouble("Pitch", aim.pitchDeg());
			entry.putDouble("Charge", aim.chargePower());
			entry.putDouble("Speed", aim.muzzleSpeed());
			entry.putDouble("ImpactX", aim.impactX());
			entry.putDouble("ImpactY", aim.impactY());
			entry.putDouble("ImpactZ", aim.impactZ());
			entry.putDouble("TargetPlan", aim.targetPlanRange());
			entry.putDouble("ImpactPlan", aim.impactPlanRange());
			entry.putDouble("AltErr", aim.altitudeError());
			entry.putInt("Ticks", aim.flightTicks());
			boolean hasPitchAdvice = aim.hasSuggestedPitch();
			boolean hasChargeAdvice = aim.hasSuggestedCharge();
			entry.putBoolean("HasSugPitch", hasPitchAdvice);
			entry.putBoolean("HasSugCharge", hasChargeAdvice);
			if (hasPitchAdvice)
				entry.putDouble("SugPitch", aim.suggestedPitchDeg());
			if (hasChargeAdvice)
				entry.putDouble("SugCharge", aim.suggestedCharge());
			list.add(entry);
		}
		return list;
	}

	private void readAims(ListTag list) {
		for (int slot = 0; slot < SLOTS; slot++) {
			if (slot >= list.size()) {
				aims[slot] = AimSolution.EMPTY;
				continue;
			}
			CompoundTag entry = list.getCompound(slot);
			if (!entry.getBoolean("Computed")) {
				aims[slot] = AimSolution.EMPTY;
				continue;
			}
			aims[slot] = new AimSolution(
					AimOutcome.byIndex(entry.getInt("Outcome")),
					entry.getDouble("Yaw"), entry.getDouble("Pitch"),
					entry.getDouble("Charge"), entry.getDouble("Speed"),
					entry.getDouble("ImpactX"), entry.getDouble("ImpactY"), entry.getDouble("ImpactZ"),
					entry.getDouble("TargetPlan"), entry.getDouble("ImpactPlan"), entry.getDouble("AltErr"),
					entry.getInt("Ticks"), 0,
					AimArc.byIndex(entry.getInt("Arc")), entry.getBoolean("AltArc"),
					entry.getBoolean("HasSugPitch") ? entry.getDouble("SugPitch") : Double.NaN,
					entry.getBoolean("HasSugCharge") ? entry.getDouble("SugCharge") : Double.NaN);
		}
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("container.cbc_firecontrol.trajectory_calculator");
	}

	@Override
	public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
		return new TrajectoryCalculatorMenu(containerId, playerInventory, getBlockPos());
	}

	/* ------------------------------------------------------------------ fire missions */

	/** Highest level a Create redstone link can carry; a mission opens the fire channel to this. */
	private static final int MISSION_FIRE_SIGNAL = 15;

	/**
	 * Server side entry point of a fire mission: record the order, then hand a copy of it to every mount
	 * this console can reach right now. An order no mount could take is kept pending in the console, and a
	 * card inserted later collects it. Returns how many mount queues took the order.
	 */
	public int addMission(double x, double y, double z) {
		Level level = getLevel();
		if (level == null || level.isClientSide())
			return 0;
		FireMission mission = new FireMission(x, y, z);
		if (!mission.isFinite())
			return 0;
		if (pendingMissions.size() >= MAX_PENDING_MISSIONS)
			pendingMissions.remove(0);
		pendingMissions.add(mission);
		missionsChanged = true;
		setChanged();
		int handed = dispatchPendingMissions();
		sendData();
		return handed;
	}

	/**
	 * Hand every pending order to every active row that has room for it. An order leaves the console's
	 * pending list as soon as at least one mount took it: from then on its fate belongs to those per-mount
	 * queues, which may diverge - one mount can drop it on exhausted refusals while another fires it.
	 */
	private int dispatchPendingMissions() {
		if (pendingMissions.isEmpty())
			return 0;
		int handed = 0;
		Iterator<FireMission> it = pendingMissions.iterator();
		while (it.hasNext()) {
			FireMission mission = it.next();
			boolean delivered = false;
			for (int slot = 0; slot < SLOTS; slot++) {
				if (!isSlotActive(slot) || missionQueues[slot].size() >= MAX_MISSIONS_PER_MOUNT)
					continue;
				missionQueues[slot].addLast(new MissionEntry(mission));
				delivered = true;
				handed++;
			}
			if (delivered) {
				it.remove();
				missionsChanged = true;
			}
		}
		return handed;
	}
	/**
	 * The mission cadence: one check per mount queue every {@link #MISSION_CHECK_INTERVAL} ticks.
	 *
	 * <p>The front order of a queue is re-tested from scratch every check - the mount is there, its cannon
	 * assembled, its breech able to fire the next shot, the order solvable from where the gun stands. A
	 * mount that fails spends one of its {@link #MISSION_REJECTIONS} chances; at zero the order is deleted
	 * from that queue only, and the queue moves on. A mount that passes gets the row armed onto the order's
	 * aim, and once the drive loop reports the row inside the deadband the order's shot goes out over the
	 * wireless fire channel; the order is then dropped on the following tick and the order behind it is tested at the next check.
	 *
	 * <p>Acceptance is deliberately re-checked rather than trusted once: a gun can be taken apart, disarmed
	 * or reloaded out from under an order that was accepted a second ago, and refusing it again is what the
	 * chances are for.
	 */
	private void tickMissions(Level level) {
		dispatchPendingMissions();
		boolean changed = false;
		for (int slot = 0; slot < SLOTS; slot++) {
			ArrayDeque<MissionEntry> queue = missionQueues[slot];
			if (queue.isEmpty())
				continue;
			if (!isSlotActive(slot)) {
				queue.clear();
				disarmMissionRow(level, slot);
				changed = true;
				continue;
			}
			MissionEntry entry = queue.peekFirst();
			if (entry.fired) {
				queue.pollFirst();
				disarmMissionRow(level, slot);
				changed = true;
				continue;
			}
			SlotResult result = resolveSlot(level, slot, true, entry.mission.asVec());
			boolean able = result.mount() != null && result.snapshot() != null
					&& result.snapshot().canFireNext() && result.aim() != null && result.aim().hasSolution();
			if (!able) {
				entry.rejectionsLeft--;
				if (entry.active)
					disarmMissionRow(level, slot);
				entry.active = false;
				if (entry.rejectionsLeft <= 0)
					queue.pollFirst();
				changed = true;
				continue;
			}
			if (!entry.active) {
				entry.active = true;
				aims[slot] = result.aim();
				drivenSolution[slot] = null;
				resetPidRow(slot);
				aimOnTarget[slot] = false;
				aimArmed[slot] = true;
				aimStateChanged = true;
				changed = true;
				continue;
			}
			if (aimOnTarget[slot] && !entry.fired) {
				entry.fired = true;
				setChannelStrength(level, slot, AdvancedCannonMountBlockEntity.CH_FIRE, MISSION_FIRE_SIGNAL);
				changed = true;
			}
		}
		if (changed) {
			missionsChanged = true;
			setChanged();
		}
	}

	/**
	 * A fired order leaves its mount's queue on the tick after the shot rather than at the next check: the
	 * fire channel is then held open for exactly one tick, so a mount hears one rising edge per
	 * order and an autocannon - which fires on every tick the channel is powered - still gives one
	 * shot per order.
	 */
	private void releaseFiredMissions(Level level) {
		for (int slot = 0; slot < SLOTS; slot++) {
			MissionEntry entry = missionQueues[slot].peekFirst();
			if (entry == null || !entry.fired)
				continue;
			missionQueues[slot].pollFirst();
			disarmMissionRow(level, slot);
			missionsChanged = true;
			setChanged();
		}
	}

	/** Release a row from mission duty: it drives nothing and its fire channel is shut. */
	private void disarmMissionRow(Level level, int slot) {
		if (aimArmed[slot] || aimOnTarget[slot])
			aimStateChanged = true;
		aimArmed[slot] = false;
		aimOnTarget[slot] = false;
		resetPidRow(slot);
		quietAxes(level, slot);
		setChannelStrength(level, slot, AdvancedCannonMountBlockEntity.CH_FIRE, 0);
	}

	/** Server side: whether this row's front order is the one its mount is working on. */
	public boolean missionActive(int slot) {
		if (slot < 0 || slot >= SLOTS)
			return false;
		MissionEntry entry = missionQueues[slot].peekFirst();
		return entry != null && entry.active;
	}

	/**
	 * The point this row is aimed at right now: the order in front of its queue while the mount works on it,
	 * otherwise the console's own typed target. Null when the row has neither, which leaves its readouts on
	 * the forward prediction only.
	 */
	private Vec3 missionAimTarget(int slot) {
		if (missionActive(slot))
			return missionQueues[slot].peekFirst().mission.asVec();
		return hasTarget ? targetVec() : null;
	}

	/** Client view: orders the console still holds because no mount could take them. */
	public List<FireMission> pendingMissionView() {
		return clientMissions;
	}

	/** Client view: how many orders this mount's queue holds, the front one included. */
	public int missionQueueSize(int slot) {
		return slot >= 0 && slot < SLOTS ? missionQueueSizes[slot] : 0;
	}

	/** Client view: what this mount's front order is doing. */
	public MissionPhase missionPhase(int slot) {
		if (slot < 0 || slot >= SLOTS)
			return MissionPhase.NONE;
		MissionPhase[] phases = MissionPhase.values();
		int index = missionPhases[slot];
		return index >= 0 && index < phases.length ? phases[index] : MissionPhase.NONE;
	}

	/** Client view: refusal chances left on this mount's front order. */
	public int missionRejectionsLeft(int slot) {
		return slot >= 0 && slot < SLOTS ? missionRejectionsLeft[slot] : 0;
	}

	/** Client view: the impact point of this mount's front order. */
	public Vec3 missionFrontTarget(int slot) {
		if (slot < 0 || slot >= SLOTS || missionQueueSizes[slot] == 0)
			return null;
		return new Vec3(missionFrontX[slot], missionFrontY[slot], missionFrontZ[slot]);
	}

	private ListTag writePendingMissions() {
		ListTag list = new ListTag();
		for (FireMission mission : pendingMissions)
			list.add(mission.write(new CompoundTag()));
		return list;
	}

	private ListTag writeSlotMissions() {
		ListTag list = new ListTag();
		for (int slot = 0; slot < SLOTS; slot++) {
			CompoundTag entry = new CompoundTag();
			ListTag orders = new ListTag();
			for (MissionEntry mission : missionQueues[slot]) {
				CompoundTag order = mission.mission.write(new CompoundTag());
				order.putInt("R", mission.rejectionsLeft);
				orders.add(order);
			}
			entry.put("Orders", orders);
			list.add(entry);
		}
		return list;
	}

	private void readSlotMissions(ListTag list) {
		for (int slot = 0; slot < SLOTS; slot++) {
			missionQueues[slot].clear();
			if (slot >= list.size())
				continue;
			ListTag orders = list.getCompound(slot).getList("Orders", Tag.TAG_COMPOUND);
			for (Tag entry : orders) {
				CompoundTag order = (CompoundTag) entry;
				MissionEntry mission = new MissionEntry(FireMission.read(order));
				mission.rejectionsLeft = order.getInt("R");
				missionQueues[slot].addLast(mission);
			}
		}
		missionsChanged = true;
	}

	/**
	 * The client-facing view of the mission system: what the tasks board draws. It says nothing about PID
	 * state or solved aims - the board shows order phases, and the aims themselves travel in the solution
	 * packet this view sits beside.
	 */
	private CompoundTag writeMissionView() {
		CompoundTag view = new CompoundTag();
		ListTag pending = new ListTag();
		for (FireMission mission : pendingMissions)
			pending.add(mission.write(new CompoundTag()));
		view.put("Pending", pending);
		ListTag slots = new ListTag();
		for (int slot = 0; slot < SLOTS; slot++) {
			CompoundTag entry = new CompoundTag();
			MissionEntry front = missionQueues[slot].peekFirst();
			entry.putInt("Size", missionQueues[slot].size());
			if (front != null) {
				MissionPhase phase = front.fired ? MissionPhase.FIRING
						: front.active ? MissionPhase.AIMING : MissionPhase.QUEUED;
				entry.putInt("Phase", phase.ordinal());
				entry.putInt("Rejections", front.rejectionsLeft);
				front.mission.write(entry);
			} else {
				entry.putInt("Phase", MissionPhase.NONE.ordinal());
			}
			slots.add(entry);
		}
		view.put("Slots", slots);
		return view;
	}

	private void readMissionView(CompoundTag view) {
		clientMissions.clear();
		for (Tag entry : view.getList("Pending", Tag.TAG_COMPOUND))
			clientMissions.add(FireMission.read((CompoundTag) entry));
		ListTag slots = view.getList("Slots", Tag.TAG_COMPOUND);
		for (int slot = 0; slot < SLOTS; slot++) {
			if (slot >= slots.size()) {
				missionQueueSizes[slot] = 0;
				missionPhases[slot] = MissionPhase.NONE.ordinal();
				missionRejectionsLeft[slot] = 0;
				continue;
			}
			CompoundTag entry = slots.getCompound(slot);
			missionQueueSizes[slot] = entry.getInt("Size");
			missionPhases[slot] = entry.getInt("Phase");
			missionRejectionsLeft[slot] = entry.getInt("Rejections");
			missionFrontX[slot] = entry.getDouble("X");
			missionFrontY[slot] = entry.getDouble("Y");
			missionFrontZ[slot] = entry.getDouble("Z");
		}
	}

	/* ------------------------------------------------------------------ channel configuration */

	/** Flat index into the channel filter list for one slot/channel/filter, shared with the menu and screen. */
	public static int channelIndex(int slot, int channel, int filter) {
		return (slot * CHANNELS_PER_SLOT + channel) * FILTERS_PER_CHANNEL + filter;
	}

	/** A slot transmits only while it holds a card that actually records a mount. */
	public boolean isSlotActive(int slot) {
		if (slot < 0 || slot >= SLOTS)
			return false;
		ItemStack stack = inventory.getStackInSlot(slot);
		return !stack.isEmpty()
				&& stack.is(CBCItems.CALCULATOR_LINK.asItem())
				&& stack.get(CBCItems.CALCULATOR_LINK_DATA.get()) != null;
	}

	/** Strength this calculator is transmitting for one row on one of the mount's channels. */
	public int linkStrength(int slot, int channel) {
		if (slot < 0 || slot >= SLOTS || channel < 0 || channel >= CHANNELS_PER_SLOT)
			return 0;
		return channelStrength[slot][channel];
	}

	/** Whether the link is driving this row's mount toward its solved aim. */
	public boolean isDriving(int slot) {
		return slot >= 0 && slot < SLOTS && aimArmed[slot];
	}

	/** Whether this row's mount currently sits inside the aim deadband. */
	public boolean isOnTarget(int slot) {
		return slot >= 0 && slot < SLOTS && aimOnTarget[slot];
	}

	/** Packs the five strengths of a row into one int, four bits per channel. */
	private int packedChannelLevels(int slot) {
		int packed = 0;
		for (int channel = 0; channel < CHANNELS_PER_SLOT; channel++)
			packed |= (channelStrength[slot][channel] & 15) << (channel * 4);
		return packed;
	}

	/** Strength the client believes a row is transmitting, read from the mirrored solution packet. */
	public int displayedLinkLevel(int slot, int channel) {
		if (slot < 0 || slot >= SLOTS || channel < 0 || channel >= CHANNELS_PER_SLOT)
			return 0;
		return (linkLevels[slot] >> (channel * 4)) & 15;
	}

	/** Client-side view of {@link #isDriving}: whether the link is driving this row. */
	public boolean linkDriving(int slot) {
		return slot >= 0 && slot < SLOTS && (linkFlags[slot] & 1) != 0;
	}

	/** Client-side view of {@link #isOnTarget}: whether this row is inside the deadband. */
	public boolean linkOnAim(int slot) {
		return slot >= 0 && slot < SLOTS && (linkFlags[slot] & 2) != 0;
	}

	/**
	* The deadband the drive stops at. It is a single fixed angle now, so the slot index no longer
	* changes the answer; the tooltip still asks it per row and quotes it in loop terms, so a gunner
	* can see what "on aim" means without knowing the constant.
	*/
	public double aimDeadband(int slot) {
		return AIM_DEADBAND_DEG;
	}

	/**
	* The wider angle a row has to drift past before it stops counting as on aim. Quoted alongside the entry
	* deadband so the tooltip describes the loop as it behaves: a gun parked on the entry angle holds still
	* instead of being released and re-driven.
	*/
	public double aimExitBand(int slot) {
		return AIM_HYSTERESIS_EXIT_DEG;
	}

	public ItemStack getChannelFilter(int slot, int channel, int filter) {
		return slotChannels.getStackInSlot(channelIndex(slot, channel, filter));
	}

	public ItemStack[] getSlotChannelItems() {
		ItemStack[] out = new ItemStack[slotChannels.getSlots()];
		for (int i = 0; i < out.length; i++)
			out[i] = slotChannels.getStackInSlot(i);
		return out;
	}

	/**
	* Server side entry point of the menu: store the filter copies (count forced to 1, exactly like Create's
	* own LinkBehaviour#setFrequency), re-register this calculator's link transmitters so the edit takes
	* effect live, and sync the new configuration to clients.
	*/
	public void setSlotChannelItems(ItemStack[] items) {
		if (items == null)
			return;
		for (int i = 0; i < slotChannels.getSlots(); i++) {
			ItemStack stack = i < items.length ? items[i] : null;
			if (stack == null || stack.isEmpty()) {
				slotChannels.setStackInSlot(i, ItemStack.EMPTY);
				continue;
			}
			ItemStack copy = stack.copy();
			copy.setCount(1);
			slotChannels.setStackInSlot(i, copy);
		}
		if (getLevel() == null || getLevel().isClientSide())
			return;
		channelsChanged = true;
		setChanged();
		sendData();
		aimStateChanged = false;
	}

	/**
	 * Server side: run the mount-to-console channel mirror now, on request, instead of waiting for the next
	 * periodic pass. The channels page's SYNC button calls this, so a gunner who has just retuned a mount's radio
	 * sees the console follow on the click rather than up to a second later.
	 *
	 * <p>The countdown is re-armed to the full interval rather than to zero: this call just did the mirror's work,
	 * so running it again on the next tick would copy the same filters twice. The semantics are the periodic
	 * mirror's exactly - only non-empty mount columns are copied, and nothing a row holds by hand is blanked.
	 */
	public void pullChannelsFromMounts() {
		Level level = getLevel();
		if (level == null || level.isClientSide())
			return;
		mountChannelCooldown = MOUNT_CHANNEL_SYNC_INTERVAL;
		syncChannelsFromMounts(level);
	}

	/**
	 * Server side: mirror each linked mount's radio columns into that row's channel filters.
	 *
	 * <p>One-way, and only where the mount actually says something. The mount radio is the authority for which
	 * frequency pair a channel uses, and {@link AdvancedCannonMountBlockEntity#getFrequencyItems()} is laid out
	 * column-major as {@code channel * FILTERS_PER_CHANNEL + filter} - identical to this calculator's
	 * {@link #channelIndex(int, int, int)} within one row - so a column copies across without remapping.
	 *
	 * <p>An empty mount slot leaves the local filter alone: a gunner who configured a row by hand (a mount with
	 * no radio yet, or a channel the mount does not use) keeps that setup instead of watching it blank out every
	 * second. Filters that already match are not rewritten, so a steady link produces no sync traffic.
	 */
	private void syncChannelsFromMounts(Level level) {
		boolean changed = false;
		for (int slot = 0; slot < SLOTS; slot++) {
			if (!isSlotActive(slot))
				continue;
			AdvancedCannonMountBlockEntity mount = mountForRow(level, slot);
			if (mount == null)
				continue;
			ItemStack[] frequencies = mount.getFrequencyItems();
			for (int channel = 0; channel < CHANNELS_PER_SLOT; channel++) {
				for (int filter = 0; filter < FILTERS_PER_CHANNEL; filter++) {
					int from = channel * FILTERS_PER_CHANNEL + filter;
					ItemStack mountFilter = from < frequencies.length ? frequencies[from] : ItemStack.EMPTY;
					if (mountFilter == null || mountFilter.isEmpty())
						continue;
					int index = channelIndex(slot, channel, filter);
					ItemStack local = slotChannels.getStackInSlot(index);
					if (ItemStack.isSameItemSameComponents(local, mountFilter))
						continue;
					ItemStack copy = mountFilter.copy();
					copy.setCount(1);
					slotChannels.setStackInSlot(index, copy);
					changed = true;
				}
			}
		}
		if (!changed)
			return;
		channelsChanged = true;
		setChanged();
		sendData();
	}

	/** Stable id of this calculator, assigned on the server and persisted; null on an unsynced client copy. */
	public UUID calculatorId() {
		if (calculatorId != null)
			return calculatorId;
		Level level = getLevel();
		if (level == null || level.isClientSide())
			return null;
		calculatorId = UUID.randomUUID();
		setChanged();
		return calculatorId;
	}

	public int getEmittedLinkPower() {
		return emittedLinkPower;
	}

	/**
	* Re-register this calculator's Create redstone-link transmitters: one per (active slot, channel) that has
	* at least one filter item. Inactive slots register nothing, so an empty or unbound slot can never drive
	* a mount.
	*/
	private void rebuildLinkTransmitters() {
		Level level = getLevel();
		if (level == null)
			return;
		if (!level.isClientSide()) {
			for (SlotChannelTransmitter transmitter : activeTransmitters)
				Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, transmitter);
		}
		activeTransmitters.clear();
		if (level.isClientSide())
			return;
		for (int slot = 0; slot < SLOTS; slot++) {
			if (!isSlotActive(slot))
				continue;
			for (int channel = 0; channel < CHANNELS_PER_SLOT; channel++) {
				ItemStack first = getChannelFilter(slot, channel, 0);
				ItemStack second = getChannelFilter(slot, channel, 1);
				if (first.isEmpty() && second.isEmpty())
					continue;
				SlotChannelTransmitter transmitter = new SlotChannelTransmitter(slot, channel,
						Couple.create(Frequency.of(first), Frequency.of(second)));
				Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, transmitter);
				activeTransmitters.add(transmitter);
			}
		}
		if (!activeTransmitters.isEmpty())
			CBCFireControl.LOGGER.info("[cbc_firecontrol] calculator @{} registered {} channel(s), signal {}",
					worldPosition, activeTransmitters.size(), emittedLinkPower);
	}

	/**
	* The console signal gates the FIRE channel only: each mount decides for itself whether it may
	* fire, and the drive loop opens that channel once a row sits inside the deadband, so powering the
	* console fires the guns that are on aim and leaves the rest alone.
	* Aiming channels carry a closed-loop correction instead - see {@link #driveAimChannels}. Registered
	* transmitters are refreshed whenever the console level changes, which keeps Create's own linkRange
	* (AllConfigs.server().logistics.linkRange) semantics: a mount beyond it still hears nothing.
	*/
	private void updateEmittedLinkPower(Level level) {
		int power = level.getBestNeighborSignal(worldPosition);
		if (power == emittedLinkPower)
			return;
		int previous = emittedLinkPower;
		emittedLinkPower = power;
		for (SlotChannelTransmitter transmitter : activeTransmitters)
			Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(level, transmitter);
		CBCFireControl.LOGGER.info("[cbc_firecontrol] calculator @{} link signal {} -> {} ({} channel(s) live)",
				worldPosition, previous, power, activeTransmitters.size());
		setChanged();
	}
	/**
	* Degrees of angular error one point of signal strength stands for. A mount turns a packet at
	* {@code aimRpm * 0.0375 * power / 15} degrees per tick, so this also sets how fast a large error
	* closes: 30 degrees off asks for the full fifteen levels, the mount's own maximum slew rate, and
	* the correction falls off in proportion as the gun approaches the solution.
	*/
	private static final double DEGREES_PER_LINK_LEVEL = 2.0;

	/**
	* Angular error at which a row counts as on aim, and the point below which the closed loop stops
	* transmitting. Deliberately a fixed coarse angle rather than one derived from the plan range: 0.5
	* degrees is about 0.87 blocks of cross-track error at 100, 1.75 at 200 and 2.62 at 300, so a long
	* shot reads "on aim" with more miss than the 1.5-block hit tolerance the readouts quote. That is
	* the accepted trade-off. Deriving the angle from the range instead put the stop point under the
	* solver's own residual bias (0.06-0.22 blocks), which a barrel turning in whole radio steps cannot
	* resolve - it showed up as the aim hunting around the solution instead of settling on it.
	*/
	private static final double AIM_DEADBAND_DEG = 0.5;

	/**
	* Angle a row has to be pushed past before it stops counting as on aim, wider than the entry deadband.
	* The drive is quantised in whole radio levels and the solution is re-derived every
	* {@code RECOMPUTE_INTERVAL} ticks, so a row parked exactly on the entry angle flips in and out of
	* "on aim" every couple of ticks, opens and closes its fire channel with it, and the barrel jerks along
	* with that. One entry angle and one wider exit angle is the cheapest anti-chatter there is: a row
	* leaves its aim only once the error has genuinely moved.
	*/
	private static final double AIM_HYSTERESIS_EXIT_DEG = 0.9;

	/**
	* Integral gain, in degrees of drive authority per accumulated degree-tick. Sized together with
	* {@link #AIM_I_MAX_DEG_TICK}: a full integral is worth just under one link level, because one extra
	* level is exactly what the integral has to be able to buy. The term exists to push through a mount that
	* refuses a one-level ask - Create's radio applies the whole step or none at all, so friction can hold a
	* gun that is being asked politely - and at this rate a 1.2 degree error that will not move reaches that
	* level in about thirty ticks, half a second. That is slow enough to never be the term steering a normal
	* slew and fast enough to unstick a gun before the gunner gives up on the row.
	*/
	private static final double AIM_KI = 0.06;

	/**
	* Cap on the accumulated error, so a mount that will not move cannot wind the integral up forever. Times
	* {@link #AIM_KI} it is worth 1.8 degrees, just under one level: all the authority the loop needs to buy
	* and no more, because a bigger budget would let old debt outvote an error the gunner has since slewed
	* away from.
	*/
	private static final double AIM_I_MAX_DEG_TICK = 30.0;

	/**
	* Derivative damping. It is applied as a REDUCTION of drive authority, never as a command: the mount has
	* no brake, so driving the other channel does not slow the gun, it turns it the other way - which is the
	* very oscillation being removed. The gain sits near one tick of look-ahead because a tick is what the
	* mount commits to: at 128 RPM and full signal one tick moves 4.8 degrees, and this term is what stops a
	* gun that fast from crossing the solved angle and coming back for it.
	*/
	private static final double AIM_KD = 1.6;

	/**
	* Smoothing of the derivative. The periodic re-solve shifts the answer the row is measured against, and
	* an unfiltered derivative reads that shift as movement of the barrel.
	*/
	private static final double AIM_D_ALPHA = 0.5;

	/**
	* How far below a level's own boundary the authority must fall before that level is dropped. Without it
	* an error sitting on a multiple of {@link #DEGREES_PER_LINK_LEVEL} alternates between two strengths each
	* tick, which the gunner sees as the muzzle twitching even though the aim itself is steady.
	*/
	private static final double AIM_LEVEL_HYSTERESIS_DEG = 0.6;

	/** Axes per row: controller memory is indexed {@code slot * AXES_PER_ROW + axis}, 0 yaw then 1 pitch. */
	private static final int AXES_PER_ROW = 2;

	/**
	* The mount a row's card points at, followed by id when its cached coordinates have gone
	* stale. Deliberately lighter than {@link #resolveSlot}: no scan and no solve, since the drive
	* loop only needs the block entity to read the bore angles off.
	*/
	private AdvancedCannonMountBlockEntity mountForRow(Level level, int slot) {
		ItemStack stack = inventory.getStackInSlot(slot);
		if (stack.isEmpty() || !stack.is(CBCItems.CALCULATOR_LINK.asItem()))
			return null;
		CalculatorLinkData link = stack.get(CBCItems.CALCULATOR_LINK_DATA.get());
		if (link == null)
			return null;
		UUID wantedId = link.mountIdOrNull();
		AdvancedCannonMountBlockEntity mount = asMount(level.getBlockEntity(link.mount()), wantedId);
		if (mount != null)
			return mount;
		if (wantedId == null)
			return null;
		BlockPos found = MountIdIndex.find(level, wantedId);
		if (found == null || found.getCenter().distanceTo(getBlockPos().getCenter()) > LINK_RANGE)
			return null;
		stack.set(CBCItems.CALCULATOR_LINK_DATA.get(), link.withMount(found));
		setChanged();
		return asMount(level.getBlockEntity(found), wantedId);
	}

	/**
	 * The closed loop. For every armed row, compare the mount's present bore angle with the aim its
	 * row solved for and put the difference on the matching channel as signal strength.
	 *
	 * <p>A mount turns whatever it receives at a rate proportional to that strength, so the
	 * correction shrinks on its own as the gun approaches the solution and the channel falls quiet
	 * at the deadband: the console never reads the mount's angle through a packet, and nothing is
	 * transmitted once a row is on aim. An unarmed row keeps its channels at zero, which is also how
	 * a gunner stops a drive - pulling the card cuts the link.
	 *
	 * <p>Angles are compared to the stored solution rather than to a step count, so a mount slewed by
	 * hand or rebuilt under the same card is picked up again on the next tick. That stored solution is
	 * itself re-derived from the mount's live pose every {@code RECOMPUTE_INTERVAL} ticks, which makes
	 * the whole loop a fixed-point iteration: every turn moves the muzzle, and the next solve answers
	 * the pose the gun actually holds rather than the one it held when Aim was pressed.
	 *
	 * <p>The deadband is a fixed angle ({@link #AIM_DEADBAND_DEG}) rather than derived from the row's plan
	 * range. At long range that means more miss than the readouts' hit tolerance quotes, and that is the
	 * accepted trade-off: a stop point sized in blocks fell under the solver's own residual bias, which the
	 * barrel cannot reach in whole radio steps, so the loop chased it instead of settling. A row therefore
	 * keeps driving for as long as it is armed and its mount is reachable - nothing releases it short of aim.
	 *
	 * <p>Each axis runs a PID whose actuator is the link level: proportionality on the remaining error, an
	 * integral that grows only while the gun refuses to move, and derivative damping that takes authority
	 * away while the error is closing. The D term never reverses the direction, because reversing it would
	 * mean driving the opposite channel, and the mount has no brake - the opposite channel turns the gun
	 * through the aim instead of stopping it. Entry into the deadband and exit from it use different angles
	 * and level drops carry hysteresis, since a whole-level actuator and a re-solve that moves the answer
	 * would otherwise make the muzzle twitch around the solution instead of resting on it.
	 */
	private void driveAimChannels(Level level) {
		if (level == null || level.isClientSide)
			return;
		for (int slot = 0; slot < SLOTS; slot++) {
			AimSolution solution = aimArmed[slot] ? aims[slot] : null;
			// An armed row whose stored solution has stopped solving - the target moved out of reach, the
			// cannon was taken apart - transmits nothing and simply resumes on the next Aim press.
			if (solution != null && !solution.hasSolution())
				solution = null;
			Vec3 bore;
			AdvancedCannonMountBlockEntity mount = solution == null ? null : mountForRow(level, slot);
			if (mount == null || (bore = mount.currentBoreDirection()) == null) {
				if (aimArmed[slot] || aimOnTarget[slot])
					aimStateChanged = true;
				aimArmed[slot] = false;
				aimOnTarget[slot] = false;
				resetPidRow(slot);
				quietAxes(level, slot);
				setChannelStrength(level, slot, AdvancedCannonMountBlockEntity.CH_FIRE, 0);
				continue;
			}
			double yawError = Mth.wrapDegrees(solution.yawDeg() - InverseBallistics.yawOf(bore));
			double pitchError = solution.pitchDeg() - InverseBallistics.pitchOf(bore);
			/*
			* The periodic re-solve replaces the answer this row is measured against. That moved the target,
			* not the barrel, so the derivative is dropped and the integral kept: the gun is still working off
			* the same error, and reading the swap as motion would brake a turn that has not slowed at all.
			*/
			if (drivenSolution[slot] != solution) {
				forgetRates(slot);
				drivenSolution[slot] = solution;
			}
			boolean wasOnTarget = aimOnTarget[slot];
			// Wider angle to leave the aim than to reach it, so a row parked on the threshold stays put.
			double limit = aimOnTarget[slot] ? AIM_HYSTERESIS_EXIT_DEG : AIM_DEADBAND_DEG;
			aimOnTarget[slot] = Math.abs(yawError) <= limit && Math.abs(pitchError) <= limit;
			if (wasOnTarget != aimOnTarget[slot])
				aimStateChanged = true;
			if (aimOnTarget[slot])
				resetPidRow(slot);
			setAxisChannels(level, slot, yawError, aimOnTarget[slot] ? 0 : pidAxisLevel(slot, 0, yawError),
					pitchError, aimOnTarget[slot] ? 0 : pidAxisLevel(slot, 1, pitchError));
			/*
			 * An order owns its row's fire channel while it is being worked: the console's redstone
			 * gate is a manual trigger, whereas a mission asks for its shot the moment the gun is on
			 * aim, powered console or not. The mission check opens that channel once per order and holds
			 * it open until the order is dropped, so the mount hears exactly one rising edge per order.
			 */
			MissionEntry order = missionQueues[slot].peek();
			if (order != null && order.active)
				continue;
			setChannelStrength(level, slot, AdvancedCannonMountBlockEntity.CH_FIRE,
				aimOnTarget[slot] && emittedLinkPower > 0 ? emittedLinkPower : 0);
		}
	}

	/** Zeroes a row's four axis channels: it is not asking the mount to turn. */
	private void quietAxes(Level level, int slot) {
		setChannelStrength(level, slot, AdvancedCannonMountBlockEntity.CH_YAW_PLUS, 0);
		setChannelStrength(level, slot, AdvancedCannonMountBlockEntity.CH_YAW_MINUS, 0);
		setChannelStrength(level, slot, AdvancedCannonMountBlockEntity.CH_PITCH_UP, 0);
		setChannelStrength(level, slot, AdvancedCannonMountBlockEntity.CH_PITCH_DOWN, 0);
	}

	/**
	 * Sends a row's four axis channels. The sign of each error chooses the direction, the controller's level
	 * chooses the rate, and an axis with nothing to send holds both of its channels at zero.
	 */
	private void setAxisChannels(Level level, int slot, double yawError, int yawLevel, double pitchError,
			int pitchLevel) {
		setChannelStrength(level, slot, AdvancedCannonMountBlockEntity.CH_YAW_PLUS, yawError > 0 ? yawLevel : 0);
		setChannelStrength(level, slot, AdvancedCannonMountBlockEntity.CH_YAW_MINUS, yawError < 0 ? yawLevel : 0);
		setChannelStrength(level, slot, AdvancedCannonMountBlockEntity.CH_PITCH_UP,
				pitchError > 0 ? pitchLevel : 0);
		setChannelStrength(level, slot, AdvancedCannonMountBlockEntity.CH_PITCH_DOWN,
				pitchError < 0 ? pitchLevel : 0);
	}

	/**
	 * The controller for one axis of one row, returning the link level to transmit. The actuator is a
	 * strength rather than an angle, so the three terms are summed as degrees of authority and converted to
	 * levels at the end: the proportional term asks for what is left, the integral adds what the mount has
	 * been refusing to give up, and the derivative removes what the closing error is about to overshoot.
	 *
	 * <p>Nothing here can send a gun the wrong way. The direction still comes from the sign of the error in
	 * {@link #setAxisChannels}, so damping can only ever make the loop push less gently, which is what a
	 * mount without a brake can actually do with it.
	 */
	private int pidAxisLevel(int slot, int axis, double error) {
		int i = slot * AXES_PER_ROW + axis;
		double absError = Math.abs(error);
		double rate = Double.isFinite(previousError[i]) ? error - previousError[i] : 0.0;
		previousError[i] = error;
		double smoothed = Double.isFinite(smoothedRate[i])
				? smoothedRate[i] + AIM_D_ALPHA * (rate - smoothedRate[i]) : rate;
		smoothedRate[i] = smoothed;
		/*
		 * Anti-windup in two parts: an integral that has ended up on the other side of the error is a record
		 * of an aim already crossed, so it is discarded rather than carried, and the accumulation stops while
		 * the row already asks for everything a link packet can carry, because extra error there is only
		 * debt waiting to unwind.
		 */
		if (Math.signum(integral[i]) != Math.signum(error))
			integral[i] = 0.0;
		if (heldLevel[i] < 15)
			integral[i] = Mth.clamp(integral[i] + error, -AIM_I_MAX_DEG_TICK, AIM_I_MAX_DEG_TICK);
		/*
		 * Damping only while the error is closing. A derivative that widens it is the re-solve moving the
		 * answer or a gunner slewing by hand, and holding back against that would fight the correction
		 * instead of smoothing it.
		 */
		double damping = smoothed * error < 0.0 ? Math.abs(smoothed) : 0.0;
		double authority = absError + AIM_KI * Math.abs(integral[i]) - AIM_KD * damping;
		if (authority <= 0.0) {
			heldLevel[i] = 0;
			return 0;
		}
		int wanted = Math.min(15, Math.max(1, (int) Math.ceil(authority / DEGREES_PER_LINK_LEVEL)));
		/*
		 * Drop a level only once the authority is a good fraction below that level's own boundary. A single
		 * tick of the smoothed rate must not be able to flip the transmitted strength back and forth.
		 */
		if (wanted == heldLevel[i] - 1
				&& authority > (heldLevel[i] - 1) * DEGREES_PER_LINK_LEVEL - AIM_LEVEL_HYSTERESIS_DEG)
			wanted = heldLevel[i];
		heldLevel[i] = wanted;
		return wanted;
	}

	/** Drops a row's rate memory and keeps its integral: the aim moved under it, the barrel did not. */
	private void forgetRates(int slot) {
		for (int axis = 0; axis < AXES_PER_ROW; axis++) {
			int i = slot * AXES_PER_ROW + axis;
			previousError[i] = Double.NaN;
			smoothedRate[i] = Double.NaN;
		}
	}

	/** Clears a row's whole controller memory, so the next drive starts from nothing. */
	private void resetPidRow(int slot) {
		for (int axis = 0; axis < AXES_PER_ROW; axis++) {
			int i = slot * AXES_PER_ROW + axis;
			integral[i] = 0.0;
			previousError[i] = Double.NaN;
			smoothedRate[i] = Double.NaN;
			heldLevel[i] = 0;
		}
		drivenSolution[slot] = null;
	}


/**
	* Publishes one channel's strength and refreshes that row's link network, so Create re-reads what
	* each of its channels carries. Only rows whose transmitters are registered need a refresh;
	* the strength itself is stored either way, so the value is correct the moment the card is put back.
	*/
	private void setChannelStrength(Level level, int slot, int channel, int strength) {
		if (channelStrength[slot][channel] == strength)
			return;
		channelStrength[slot][channel] = strength;
		aimStateChanged = true;
		for (SlotChannelTransmitter transmitter : activeTransmitters)
			if (transmitter.slot == slot)
				Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(level, transmitter);
	}

	@Override
	public void remove() {
		unregisterTransmitters();
		super.remove();
	}

	@Override
	public void onChunkUnloaded() {
		unregisterTransmitters();
		super.onChunkUnloaded();
	}

	private void unregisterTransmitters() {
		Level level = getLevel();
		if (level == null || level.isClientSide())
			return;
		for (SlotChannelTransmitter transmitter : activeTransmitters)
			Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, transmitter);
		activeTransmitters.clear();
	}

	/**
	* One transmitter of one active slot. Create keys its link networks by an ORDERED Couple&lt;Frequency&gt;,
	* so slot S channel K reaches exactly the mount channel K whose filter pair is the same items in the same
	* order - which is what makes one calculator column drive one mount action.
	*/
	private final class SlotChannelTransmitter implements IRedstoneLinkable {

		private final int slot;
		private final int channel;
		private final Couple<Frequency> key;

		SlotChannelTransmitter(int slot, int channel, Couple<Frequency> key) {
			this.slot = slot;
			this.channel = channel;
			this.key = key;
		}

		@Override
		public Couple<Frequency> getNetworkKey() {
			return key;
		}

		@Override
		public int getTransmittedStrength() {
			return channelStrength[slot][channel];
		}

		@Override
		public void setReceivedStrength(int power) {
			// Transmit-only member: a calculator never listens on its own channels.
		}

		@Override
		public boolean isListening() {
			return false;
		}

		@Override
		public boolean isAlive() {
			Level level = getLevel();
			if (level == null || isRemoved() || isChunkUnloaded())
				return false;
			return level.getBlockEntity(worldPosition) == TrajectoryCalculatorBlockEntity.this;
		}

		@Override
		public BlockPos getLocation() {
			return worldPosition;
		}
	}
}

