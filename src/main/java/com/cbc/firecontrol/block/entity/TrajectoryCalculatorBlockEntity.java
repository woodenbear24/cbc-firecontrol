package com.cbc.firecontrol.block.entity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.ballistics.AimArc;
import com.cbc.firecontrol.ballistics.AimApplyResult;
import com.cbc.firecontrol.ballistics.AimEnvelope;
import com.cbc.firecontrol.ballistics.AimOutcome;
import com.cbc.firecontrol.ballistics.AimSolution;
import com.cbc.firecontrol.ballistics.InverseBallistics;
import com.cbc.firecontrol.ballistics.LinkStatus;
import com.cbc.firecontrol.ballistics.MountScanner;
import com.cbc.firecontrol.ballistics.MountSnapshot;
import com.cbc.firecontrol.ballistics.TrajectoryComputer;
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

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
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
		}
	};

	private final TrajectoryPrediction[] predictions = new TrajectoryPrediction[SLOTS];
	/** The aim each slot's mount would need to hit {@link #targetVec()}; client-mirrored copy. */
	private final AimSolution[] aims = new AimSolution[SLOTS];
	private boolean linksChanged = true;
	private int recomputeCooldown = 0;

	/** Target point the gunner entered, in absolute world coordinates (the GUI fields hold exactly this). */
	private boolean hasTarget;
	private double targetX;
	private double targetY;
	private double targetZ;
	/**
	 * Sanity bound on how far from this calculator an entered target may sit. Ballistics cost does not grow
	 * with distance (every flight is capped by the integrator's own range/tick limits), but a wild coordinate
	 * would otherwise sit in the GUI claiming to be a target; anything past this is simply not a shot this
	 * fire control could ever be pointed at.
	 */
	public static final int MAX_TARGET_OFFSET = 2048;

	/**
	 * Filter items of the per-slot channels. NOT a real inventory: nothing is consumed here, the stacks are
	 * pure configuration, stored in the BE (so they persist and reach clients) and edited through the menu's
	 * ghost slots. Layout is {@code (slot * CHANNELS_PER_SLOT + channel) * FILTERS_PER_CHANNEL + which}.
	 */
	private final ItemStackHandler slotChannels = new ItemStackHandler(CHANNEL_ITEM_SLOTS);

	private final List<SlotChannelTransmitter> activeTransmitters = new ArrayList<>();
	private boolean channelsChanged = true;
	/** Redstone level the calculator currently feeds into its channels (0 when unpowered). */
	private int emittedLinkPower = 0;

	/**
	 * This calculator's own persistent id. Only the mount cards carry mount ids today; the calculator id is
	 * stored so later features (telescope-to-calculator binding, multi-calculator fire control) have a stable
	 * handle that does not depend on the block having been moved.
	 */
	private UUID calculatorId;

	public TrajectoryCalculatorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		Arrays.fill(predictions, TrajectoryPrediction.EMPTY);
		Arrays.fill(aims, AimSolution.EMPTY);
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
	 * Set the target point (absolute world coordinates) and arm an immediate recompute. Rejected when a
	 * coordinate is not finite or the point sits further than {@link #MAX_TARGET_OFFSET} from this calculator:
	 * an out-of-reach target is a legitimate answer, a wild coordinate is just noise, and the GUI keeps showing
	 * the previous target rather than silently clearing it.
	 */
	public void setTarget(double x, double y, double z) {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
			return;
		Vec3 centre = getBlockPos().getCenter();
		if (Math.abs(x - centre.x) > MAX_TARGET_OFFSET || Math.abs(y - centre.y) > MAX_TARGET_OFFSET
				|| Math.abs(z - centre.z) > MAX_TARGET_OFFSET)
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
	}

	public void clearTarget() {
		if (!hasTarget)
			return;
		hasTarget = false;
		linksChanged = true;
		setChanged();
		sendData();
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
		}
		if (channelsChanged) {
			channelsChanged = false;
			rebuildLinkTransmitters();
		}
		updateEmittedLinkPower(level);
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
		boolean changed = false;
		for (int slot = 0; slot < SLOTS; slot++) {
			SlotResult result = resolveSlot(level, slot, false);
			fresh[slot] = result.prediction();
			freshAims[slot] = result.aim();
			if (!fresh[slot].equals(predictions[slot]) || !sameAim(freshAims[slot], aims[slot]))
				changed = true;
		}
		if (!changed)
			return;

		System.arraycopy(fresh, 0, predictions, 0, SLOTS);
		System.arraycopy(freshAims, 0, aims, 0, SLOTS);
		setChanged();
		sendData();
	}

	/** Counted outcome of one Aim button press across all five rows. */
	public record AimSummary(int linked, int drove, int alreadyAimed, int clamped, int refused) {

		/** Nothing this calculator records can be aimed: no rows linked, or no target entered. */
		public boolean empty() {
			return linked == 0;
		}
	}

	/**
	 * The Aim button: drive every linked, assembled mount this calculator records to the aim its row solved for
	 * the entered target, in one action.
	 *
	 * <p>Each row is re-resolved here WITH the level, which is where the solver's terrain check runs: a solution
	 * whose path leaves the muzzle through a hill is refused and its mount is left where it is, instead of the
	 * gun being slewed to an angle it cannot fire from. The live readouts skip that check on purpose (they run
	 * every {@code RECOMPUTE_INTERVAL} ticks for up to five mounts), which is why the same row can read as a
	 * solution and still be refused by this method.
	 */
	public AimSummary applyAllAims(ServerPlayer player) {
		Level level = getLevel();
		if (level == null || level.isClientSide() || !hasTarget)
			return new AimSummary(0, 0, 0, 0, 0);

		int linked = 0, drove = 0, alreadyAimed = 0, clamped = 0, refused = 0;
		for (int slot = 0; slot < SLOTS; slot++) {
			SlotResult result = resolveSlot(level, slot, true);
			AdvancedCannonMountBlockEntity mount = result.mount();
			if (mount == null)
				continue;
			linked++;
			AimApplyResult applied = mount.applySolvedAim(result.aim());
			switch (applied.status()) {
			case APPLIED -> {
				drove++;
				if (applied.clamped())
					clamped++;
			}
			case ALREADY_AIMED -> alreadyAimed++;
			default -> refused++;
			}
		}

		if (linked == 0) {
			player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.aim_none"), true);
		} else {
			player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.aim_summary", drove,
					alreadyAimed, refused), true);
			if (clamped > 0)
				player.displayClientMessage(Component.translatable("cbc_firecontrol.calculator.aim_clamped", clamped),
						false);
		}
		return new AimSummary(linked, drove, alreadyAimed, clamped, refused);
	}

	/**
	 * What one slot says: the next shot that mount would fire, the aim it would need for the entered target, and
	 * the mount itself so an apply action does not have to resolve the link a second time.
	 */
	private record SlotResult(TrajectoryPrediction prediction, AimSolution aim,
			AdvancedCannonMountBlockEntity mount) {

		static SlotResult of(LinkStatus status) {
			return new SlotResult(TrajectoryPrediction.failure(status, 0, 0), AimSolution.EMPTY, null);
		}
	}

	/**
	 * Resolve slot -&gt; link card -&gt; mount, then simulate its next shot and solve the entered target.
	 * {@code checkTerrain} adds the solver's flight-through-blocks test, which the Aim path needs before it will
	 * move a gun and the periodic readouts deliberately skip.
	 */
	private SlotResult resolveSlot(Level level, int slot, boolean checkTerrain) {
		ItemStack stack = inventory.getStackInSlot(slot);
		if (stack.isEmpty())
			return new SlotResult(TrajectoryPrediction.EMPTY, AimSolution.EMPTY, null);
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
					snapshot.pitchDeg()), AimSolution.EMPTY, mount);

		TrajectoryPrediction forward = TrajectoryComputer.simulate(level, snapshot);
		if (!hasTarget)
			return new SlotResult(forward, AimSolution.EMPTY, mount);

		/*
		 * The Level is only handed to the solver on the Apply path. Its terrain check flies the SOLVED path
		 * through real blocks for every candidate elevation it verifies, and the readouts recompute every
		 * RECOMPUTE_INTERVAL ticks for up to five mounts at once - affordable to do once when the gunner commits
		 * an aim, not continuously while the rows are just being kept live.
		 */
		AimSolution aim = InverseBallistics.solve(snapshot, targetVec(), aimEnvelopeFor(mount, snapshot),
				checkTerrain ? level : null);
		return new SlotResult(forward, aim, mount);
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
			list.add(entry);
		}
		return list;
	}

	private void readSolutions(ListTag list) {
		for (int slot = 0; slot < SLOTS; slot++) {
			if (slot >= list.size()) {
				predictions[slot] = TrajectoryPrediction.EMPTY;
				continue;
			}
			CompoundTag entry = list.getCompound(slot);
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
	 * The calculator's own redstone level is what its channels carry: powering the block makes every registered
	 * channel transmit that level to mounts listening on the same ordered frequency pair, within Create's own
	 * linkRange (AllConfigs.server().logistics.linkRange) - the same semantics a Create Redstone Link has.
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
			return emittedLinkPower;
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

