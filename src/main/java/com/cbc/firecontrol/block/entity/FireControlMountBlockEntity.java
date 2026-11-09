package com.cbc.firecontrol.block.entity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.init.CBCFCBlocks;

import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;


import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.ItemCannon;
import rbasamoyai.createbigcannons.remix.GetItemStorage;
import net.neoforged.neoforge.items.IItemHandler;
import rbasamoyai.createbigcannons.cannons.CannonContraptionProviderBlock;

import com.cbc.firecontrol.init.CBCFCMenus;
import com.cbc.firecontrol.menu.MountFrequencyMenu;
import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Fire Control Mount BlockEntity - half-slab mount variant.
 *
 * Inherits almost everything from CBC's CannonMountBlockEntity:
 *   - Kinetic pitch/yaw interfaces (pitchInterface, yawInterface)
 *   - applyRotation() logic that syncs cannonYaw/cannonPitch -> mountedContraption
 *   - disassemble(), calculateCannonStressApplied()
 *   - Goggle tooltip via {@link #addToGoggleTooltip} (inherited, calls ExtendsCannonMount.addCannonInfoToTooltip)
 *   - Assembly exception display via getLastAssemblyException() (inherited)
 *   - Item handler / capabilities for the mounted contraption (inherited)
 *
 * Overridden for half-slab layout differences:
 *   - assemble(): assemblyPos = 1 grid in cannon direction (was 2 in CBC).
 *   - resetContraptionToOffset(): CBC's body hardcodes relative(vertical, -2); ours uses -1 so the
 *     mounted cannon entity ends at the correct world position matching our new assemblyPos.
 *   - createRenderBoundingBox(): CBC expanded +/-2 blocks (sized for CBC's ROTATING_MOUNT disc at
 *     mount-pos +/-1 plus carriage axle at mount-pos +/-2). Ours expands +/-1 (only interface shaft
 *     visuals at mount cell plus mount model's guard structure reaching mount cell top boundary;
 *     assembled cannon entity has its own AABB covering the whole cannon structure).
 *
 * Overridden for CBC-vs-our-block identity handling:
 *   - Constructor: initializes cannonYaw for our block (CBC only does so for CBCBlocks.CANNON_MOUNT)
 *   - tick(): CBC's early-return "reset from state" branch only fires for CBCBlocks.CANNON_MOUNT;
 *             our mount needs the same reset so cannonYaw visually follows wrench rotations.
 *   - getTypeId(): returns a cbc_firecontrol-namespaced id so CBC code paths that key
 *                  off mount-type identity don't mistake our mount for a stock CBC mount.
 */
public class FireControlMountBlockEntity extends CannonMountBlockEntity implements MenuProvider {

	/**
	 * Reflection handle to CBC's private boolean 'running' field.
	 * ModDevGradle's compile-time AT application on third-party mod jars is unreliable,
	 * so we access via reflection instead. NeoForge 1.21 loads mods into an unnamed module,
	 * so Field.setAccessible(true) on another mod's private field works without --add-opens.
	 */
	private static final Field RUNNING_FIELD;
	private static final Field PREV_YAW_FIELD;
	private static final Field PREV_PITCH_FIELD;
	static {
		try {
			RUNNING_FIELD = CannonMountBlockEntity.class.getDeclaredField("running");
			RUNNING_FIELD.setAccessible(true);
			PREV_YAW_FIELD = CannonMountBlockEntity.class.getDeclaredField("prevYaw");
			PREV_YAW_FIELD.setAccessible(true);
			PREV_PITCH_FIELD = CannonMountBlockEntity.class.getDeclaredField("prevPitch");
			PREV_PITCH_FIELD.setAccessible(true);
		} catch (NoSuchFieldException e) {
			throw new IllegalStateException(
					"CBC field layout changed: 'running'/'prevYaw'/'prevPitch' missing on CannonMountBlockEntity; "
							+ "CBC Fire Control needs a matching update.", e);
		}
	}

	private static void setRunning(CannonMountBlockEntity target, boolean value) {
		try {
			RUNNING_FIELD.setBoolean(target, value);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(
					"Could not write CBC CannonMountBlockEntity.running (CBC version mismatch?).", e);
		}
	}

	private static void setPrevYaw(CannonMountBlockEntity target, float value) {
		try {
			PREV_YAW_FIELD.setFloat(target, value);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(
					"Could not write CBC CannonMountBlockEntity.prevYaw (CBC version mismatch?).", e);
		}
	}

	private static void setPrevPitch(CannonMountBlockEntity target, float value) {
		try {
			PREV_PITCH_FIELD.setFloat(target, value);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(
					"Could not write CBC CannonMountBlockEntity.prevPitch (CBC version mismatch?).", e);
		}
	}

	public FireControlMountBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		// Radio aiming needs a client sync every tick (not every 3) so the cradle/axle render as
		// continuous motion; CBC's default lazy rate of 3 is tuned for idle stress syncs, not aiming.
		this.setLazyTickRate(1);
		if (CBCFCBlocks.FIRE_CONTROL_MOUNT.has(state)) {
			this.cannonYaw = state.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
		}
	}

	@Override
	protected void assemble() throws AssemblyException {
		// CBC's own assemble() is gated on CBCBlocks.CANNON_MOUNT.has(state); ours is gated on our block.
		if (!CBCFCBlocks.FIRE_CONTROL_MOUNT.has(this.getBlockState())) return;

		Direction vertical = this.getBlockState().getValue(BlockStateProperties.VERTICAL_DIRECTION);
		// Half-slab layout: assemblyPos = 1 grid in cannon direction (was 2 in CBC's full-block mount).
		//   - vertical=DOWN (upright mount, cannon above): assemblyPos = mount.above(1) = mountPos+1 (adjacent cell)
		//   - vertical=UP   (inverted mount, cannon below): assemblyPos = mount.below(1) = mountPos-1 (adjacent cell)
		BlockPos assemblyPos = this.worldPosition.relative(vertical, -1);
		if (this.getLevel().isOutsideBuildHeight(assemblyPos)) {
			throw cannonBlockOutsideOfWorld(assemblyPos);
		}

		// Inline CBC's private helper getContraption(assemblyPos):
		BlockState assemblyState = this.getLevel().getBlockState(assemblyPos);
		if (!(assemblyState.getBlock() instanceof CannonContraptionProviderBlock providerBlock)) return;
		AbstractMountedCannonContraption mountedCannon = providerBlock.getCannonContraption();
		if (mountedCannon == null) return;
		if (!mountedCannon.assemble(this.getLevel(), assemblyPos)) return;

		Direction facing = this.getBlockState().getValue(CannonMountBlock.HORIZONTAL_FACING);
		Direction facing1 = mountedCannon.initialOrientation();
		if (facing.getAxis() != facing1.getAxis() && facing1.getAxis().isHorizontal()) return;

		// Activates CBC's tick rotation branch. Written via reflection (see RUNNING_FIELD).
		setRunning(this, true);

		mountedCannon.removeBlocksFromWorld(this.getLevel(), BlockPos.ZERO);
		PitchOrientedContraptionEntity contraptionEntity = PitchOrientedContraptionEntity.create(
				this.getLevel(), mountedCannon, facing1, this);
		this.mountedContraption = contraptionEntity;
		this.resetContraptionToOffset();
		this.getLevel().addFreshEntity(contraptionEntity);

		this.sendData();
		AllSoundEvents.CONTRAPTION_ASSEMBLE.playOnServer(this.getLevel(), this.worldPosition);

		// Assembly-time diagnostic: reveals whether THIS assembled cannon exposes an item handler
		// that Create can feed/extract (Mechanical Arm via Funnel/Depot, Funnel, hopper).
		// Autocannon -> true; big cannon / drop mortar -> false (auto-load via CannonLoader + pole).
		if (this.getLevel() instanceof ServerLevel slevel) {
			var c = this.mountedContraption != null ? this.mountedContraption.getContraption() : null;
			boolean storage = c instanceof GetItemStorage;
			CBCFireControl.LOGGER.info(
					"[cbc_firecontrol] assembled mount {} cannon={} GetItemStorage={} ItemCannon={} -> {}",
					this.worldPosition,
					(c == null ? "null" : c.getClass().getSimpleName()),
					storage,
					(c instanceof ItemCannon),
					storage
							? "item-handler ACTIVE: deposit via Funnel/Depot works (autocannon: ammo->slot1, spent case<-slot0)"
							: "still auto-loadable: Mechanical Arm feeds this mount via the CBC CannonMountPoint arm interaction point (added CBCFCArmInteractionPointTypes)");
		}
	}

	/**
	 * Override CBC's protected resetContraptionToOffset() which hardcodes relative(vertical, -2)
	 * when positioning the mounted cannon contraption entity. Our half-slab layout moved
	 * assemblyPos to relative(vertical, -1); entity position MUST match assemblyPos formula, otherwise
	 * the assembled cannon ends up one block away from where the assembly scan actually placed it.
	 */
	@Override
	protected void resetContraptionToOffset() {
		if (this.mountedContraption == null) return;
		this.cannonPitch = 0;
		this.cannonYaw = this.getContraptionDirection().toYRot();
		// prevYaw/prevPitch are private in CBC; write them via reflection helpers above.
		// They back the client-side lerp interpolation used by getPitchOffset/getYawOffset each frame;
		// setting both to the freshly-reset cannonYaw/cannonPitch prevents a first-frame visual snap
		// from a stale prior value.
		setPrevPitch(this, this.cannonPitch);
		setPrevYaw(this, this.cannonYaw);

		this.mountedContraption.pitch = this.cannonPitch;
		this.mountedContraption.yaw = this.cannonYaw;
		this.mountedContraption.prevPitch = this.mountedContraption.pitch;
		this.mountedContraption.prevYaw = this.mountedContraption.yaw;

		this.mountedContraption.setXRot(this.cannonPitch);
		this.mountedContraption.setYRot(this.cannonYaw);
		this.mountedContraption.xRotO = this.mountedContraption.getXRot();
		this.mountedContraption.yRotO = this.mountedContraption.getYRot();

		Direction vertical = this.getBlockState().getValue(BlockStateProperties.VERTICAL_DIRECTION);
		// Formula MUST match assemble()'s assemblyPos line (both use relative(vertical, -1)).
		Vec3 vec = Vec3.atBottomCenterOf((this.worldPosition.relative(vertical, -1)));
		this.mountedContraption.setPos(vec);
	}

	@Override
	public AABB createRenderBoundingBox() {
		// Half-slab mount:
		//   - plinth + cap model geometry lives inside AABB(pos) (plinth 0..8 model units, cap 9..11,
		//     guards up to 16 = mount cell top boundary; all within AABB(getBlockPos()) 0..1 range).
		//   - Interface shaft visuals (SHAFT, SHAFT_HALF) render at mount cell center inside AABB(pos).
		//   - Expand +/-1 in cannon direction for margin (mount cell top boundary at y=1.0 world;
		//     +1 covers the adjacent cannon-assembly cell in case mount-side visuals bleed a few units
		//     past the boundary when the mount's SHAFT partial pivots slightly during wrench rotation).
		// The assembled mounted cannon entity has its OWN AABB covering the entire cannon structure,
		// so mount BE does not need to extend into the cannon cell interior.
		boolean upsideDown = this.getBlockState().getValue(BlockStateProperties.VERTICAL_DIRECTION) == Direction.UP;
		return new AABB(this.getBlockPos()).expandTowards(0, upsideDown ? -1 : 1, 0);
	}

	@Override
	public void tick() {
		// CBC's inherited tick() does the "reset from state" dance ONLY for CBCBlocks.CANNON_MOUNT.
		// Our mount block bypasses that reset, leaving cannonYaw stale after wrench rotations.
		// Do the equivalent reset ourselves for our block, then hand off to CBC's tick() which will
		// (correctly) not touch it again and will proceed into the rotation-update block once
		// running=true is set by a successful assemble.
		BlockState state = this.getBlockState();
		if (!this.isRunning() && CBCFCBlocks.FIRE_CONTROL_MOUNT.has(state)) {
			this.cannonYaw = state.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
			this.cannonPitch = 0;
		}
		super.tick();
		tickWirelessControl();
	}

	@Override
	public ResourceLocation getTypeId() {
		return ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "fire_control_mount");
	}

	/* ---------- Wireless radio-link channels + Create redstone-link receivers + frequency menu ---------- */

	public static final int CH_YAW_PLUS = 0;
	public static final int CH_YAW_MINUS = 1;
	public static final int CH_PITCH_UP = 2;
	public static final int CH_PITCH_DOWN = 3;
	public static final int CH_FIRE = 4;
	public static final int WIRELESS_CHANNELS = 5;

	private static final float WIRELESS_YAW_STEP = 2.0F;
	private static final float WIRELESS_PITCH_STEP = 1.0F;
	private static final int FIRE_COOLDOWN_TICKS = 5;
	private static final int AIM_RPM_MIN = 1;
	private static final int AIM_RPM_MAX = 32;
	private static final int AIM_RPM_DEFAULT = 8;


	private final ItemStack[] frequencyItems = new ItemStack[WIRELESS_CHANNELS * 2];
	private final int[] channelPower = new int[WIRELESS_CHANNELS];
	private final boolean[] channelPowered = new boolean[WIRELESS_CHANNELS];
	private final List<MountLinkReceiver> activeReceivers = new ArrayList<>();
	private int fireCooldown;
	private boolean linkInitDone;
	private ScrollValueBehaviour aimSpeedBox;
	private int aimRpm = AIM_RPM_DEFAULT;
	/** Signed deg/tick actually applied to cannonYaw / cannonPitch on the last server tick; synced to
	 *  clients so the mount cradle/axle renders as continuous motion instead of per-sync jumps. */
	private float appliedYawStep;
	private float appliedPitchStep;



	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		super.addBehaviours(behaviours);
		aimSpeedBox = new ScrollValueBehaviour(Component.literal("Aim Speed"), this,
				new AimValueBoxTransform())
				.withFormatter(v -> Integer.toString(v))
				.between(AIM_RPM_MIN, AIM_RPM_MAX)
				.withCallback(this::onAimSpeedChanged);
		aimSpeedBox.value = AIM_RPM_DEFAULT;
		behaviours.add(aimSpeedBox);
	}

	/** Value-box edit (Create-native, on the server) mirrors the editable target into aimRpm for the radio step. */
	private void onAimSpeedChanged(int v) {
		this.aimRpm = Mth.clamp(v, AIM_RPM_MIN, AIM_RPM_MAX);
	}

	/** MenuProvider: empty-hand right-click on the mount opens the radio frequency menu (server side). */
	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
		if (getLevel() == null)
			return null;
		return new MountFrequencyMenu(CBCFCMenus.MOUNT_FREQUENCY.get(), id, inv, this.getBlockPos());
	}

	@Override
	public Component getDisplayName() {
		return Component.literal("Fire Control Mount - Radio");
	}

	/**
	 * Cancel CBC's default redstone behaviors on the mount: a redstone signal on the FRONT face no longer
	 * assembles the cannon, and a signal on the BACK face no longer fires it (CannonMountBlock detects
	 * ASSEMBLY_POWERED / FIRE_POWERED and calls this). No-op override = only the radio controls this mount:
	 * assemble/disassemble/fire run through the frequency GUI (consoleAssemble/Disassemble/Fire) and the
	 * Create redstone-link channels. Kinetic yaw/pitch shaft interfaces are unaffected.
	 */
	@Override
	public void onRedstoneUpdate(boolean assemblyPowered, boolean prevAssemblyPowered, boolean firePowered, boolean prevFirePowered, int firePower) {
		// Intentionally disabled: do not assemble on front redstone, do not fire on back redstone.
	}

	public ItemStack[] getFrequencyItems() {
		ItemStack[] out = new ItemStack[frequencyItems.length];
		for (int i = 0; i < out.length; i++)
			out[i] = (frequencyItems[i] == null) ? ItemStack.EMPTY : frequencyItems[i];
		return out;
	}

	/** Server-side: store frequency items, (re)register Create link receivers, persist + sync to clients. */
	public void setFrequencyItems(ItemStack[] items) {
		if (items == null)
			return;
		for (int i = 0; i < frequencyItems.length; i++) {
			ItemStack st = (i < items.length) ? items[i] : null;
			frequencyItems[i] = (st == null || st.isEmpty()) ? ItemStack.EMPTY : st.copy();
		}
		if (getLevel() != null && !getLevel().isClientSide()) {
			rebuildLinkReceivers();
			setChanged();
			sendData();
		}
	}

	private void rebuildLinkReceivers() {
		Level level = getLevel();
		if (level == null)
			return;
		if (!level.isClientSide())
			for (MountLinkReceiver r : activeReceivers)
				Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, r);
		activeReceivers.clear();
		linkInitDone = true;
		if (level.isClientSide())
			return;
		MountLinkReceiver[] primary = new MountLinkReceiver[WIRELESS_CHANNELS];
		for (int col = 0; col < WIRELESS_CHANNELS; col++) {
			ItemStack a = frequencyItems[col * 2];
			ItemStack b = frequencyItems[col * 2 + 1];
			if ((a == null || a.isEmpty()) && (b == null || b.isEmpty())) {
				channelPowered[col] = false;
				channelPower[col] = 0;
				continue;
			}
			// Register the channel as ONE ORDERED Couple (slot0, slot1). Do NOT also register the reverse.
			// The radio GUI encodes DIRECTION by which ghost slot holds the item (Yaw+ = item in slot0,
			// Yaw- = item in slot1; same for Pitch). Registering both orders made Yaw+ and Yaw- (and Pitch+
			// and Pitch-) share an identical key set, so a single powered Create link drove BOTH opposite
			// channels and their per-tick +step/-step deltas cancelled -> the "rotation/pitch not received"
			// symptom. The player sets the Create link's two filter slots to the SAME order as the column.
			primary[col] = addReceiverFor(col, a, b);
		}
		for (int col = 0; col < WIRELESS_CHANNELS; col++) {
			if (primary[col] == null)
				continue;
			// Seed current power. addToNetwork->updateNetworkOf(self) only pushes to OTHER members (self
			// is not a Create LinkBehaviour), so a transmitter that is ALREADY powered when we (re)join is
			// otherwise only heard on its next on/off toggle -- that is the "rotation/pitch not received"
			// symptom. Read the live network state directly (range-aware) and apply it now.
			int p = currentChannelPower(primary[col]);
			channelPower[col] = p;
			channelPowered[col] = p > 0;
			CBCFireControl.LOGGER.info("Fire control radio @{}: channel {} items [{} | {}] initial power {} (0=Yaw+ 1=Yaw- 2=Pitch+ 3=Pitch- 4=Fire)",
					this.worldPosition, col, frequencyItems[col * 2], frequencyItems[col * 2 + 1], p);
		}
	}

	private MountLinkReceiver addReceiverFor(int col, ItemStack first, ItemStack second) {
		Frequency f1 = Frequency.of(first == null ? ItemStack.EMPTY : first);
		Frequency f2 = Frequency.of(second == null ? ItemStack.EMPTY : second);
		MountLinkReceiver r = new MountLinkReceiver(col, Couple.create(f1, f2));
		Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(getLevel(), r);
		activeReceivers.add(r);
		return r;
	}

	/** Range-aware live power this channel would currently receive from its Create redstone-link network. */
	private int currentChannelPower(MountLinkReceiver self) {
		Level level = getLevel();
		if (level == null)
			return 0;
		Set<IRedstoneLinkable> network = Create.REDSTONE_LINK_NETWORK_HANDLER.getNetworkOf(level, self);
		int power = 0;
		for (IRedstoneLinkable other : network) {
			if (other == self)
				continue;
			if (!other.isAlive())
				continue;
			if (!RedstoneLinkNetworkHandler.withinRange(self, other))
				continue;
			power = Math.max(power, other.getTransmittedStrength());
			if (power >= 15)
				break;
		}
		return power;
	}

	private void onChannelPower(int col, int power) {
		if (col < 0 || col >= WIRELESS_CHANNELS)
			return;
		int prev = channelPower[col];
		channelPower[col] = power;
		channelPowered[col] = power > 0;
		if (prev != power) {
			// Diagnostic: makes a "bind frequency -> drive" in-game test observable in the log.
			// col: 0=Yaw+ 1=Yaw- 2=Pitch+ 3=Pitch- 4=Fire ; power 0 = unpowered, >0 = a matching Create link/controller is powered.
			CBCFireControl.LOGGER.info("Fire control radio @{}: channel {} power {} -> {}",
					this.worldPosition, col, prev, power);
		}
		if (power > 0 && prev == 0 && col == CH_FIRE) {
			if (fireCooldown <= 0) {
				consoleFire();
				fireCooldown = FIRE_COOLDOWN_TICKS;
			}
		}
	}

	private void tickWirelessControl() {
		if (getLevel() == null || getLevel().isClientSide())
			return;
		if (!linkInitDone)
			rebuildLinkReceivers();
		if (fireCooldown > 0)
			fireCooldown--;
		// Rotation is radio-controlled ONLY (both yaw and pitch). CBC's kinetic->rotation coupling is
		// disabled outright by overriding getAngularSpeed() to 0 (see below), so the mount can never be
		// turned by the bottom shaft's speed - only the powered radio channels move it, and only while the
		// bottom face is drawing kinetic stress (hasKineticStressInput()).
		//
		// The interface speed is intentionally LEFT at its natural network value here (NO per-tick
		// setSpeed(0)). Forcing the yaw/pitch interface speed to 0 every tick fought the KineticNetwork's
		// own speed re-assignment (0 <-> network speed flip-flop), which destabilised the bottom gear's
		// network: after a chunk/world reload the interface restores its speed from NBT and was then
		// knocked back to 0 each tick, so a gear/shaft linked to the base face dropped its connection.
		// Leaving the interface speed undisturbed (matching vanilla CBC mounts) keeps that base link
		// stable across reloads, while getAngularSpeed()==0 still suppresses any kinetic-driven aiming.

		// Steps default to "no motion this tick"; consoleAdjustYaw/consoleAdjustPitch overwrite them with the
		// signed delta actually applied. They are synced to clients for continuous cradle/axle rendering.
		this.appliedYawStep = 0.0f;
		this.appliedPitchStep = 0.0f;

		// Radio steps mirror CBC's kinetic aiming exactly. A value-box "RPM" is treated as the RPM that a
		// Create Rotation Speed Controller would feed the interface shaft, then CBC's own reduction applies:
		//   Create convertToAngular(rpm) = rpm * 360 / 60 / 20 = rpm * 0.3  deg/tick  (KineticBlockEntity.java)
		//   CBC  getAngularSpeed(rpm)   = convertToAngular(rpm) * 0.125   = rpm * 0.0375 deg/tick
		// CannonMountBlockEntity uses the SAME 0.0375 factor for yaw and pitch, so both axes turn at the
		// identical rate (the old "pitch at half rate" asymmetry is removed to match CBC).
		float degPerTick = aimRpm * 0.0375f;
		float yawStep = 0f;
		float pitchStep = 0f;
		if (channelPowered[CH_YAW_PLUS])
			yawStep += degPerTick;
		if (channelPowered[CH_YAW_MINUS])
			yawStep -= degPerTick;
		if (channelPowered[CH_PITCH_UP])
			pitchStep += degPerTick;
		if (channelPowered[CH_PITCH_DOWN])
			pitchStep -= degPerTick;

		boolean aimEnabled = (yawStep != 0f || pitchStep != 0f) && hasKineticStressInput();
		if (aimEnabled) {
			if (yawStep != 0f)
				consoleAdjustYaw(yawStep);
			if (pitchStep != 0f)
				consoleAdjustPitch(pitchStep);
		}

	}

	private boolean hasKineticStressInput() {
		// The mount may rotate/pitch only while its single bottom kinetic link is connected to a
		// powered stress network that still has spare capacity. No bottom link, no source, or an
		// overstressed (fully-loaded) network => no stress input => the mount cannot turn or pitch.
		KineticBlockEntity link = getYawInterface();
		if (link == null || !link.hasNetwork())
			return false;
		KineticNetwork network = link.getOrCreateNetwork();
		float capacity = network.calculateCapacity();
		float stress = network.calculateStress();
		return capacity > 0f && (capacity - stress) > 0.5f && !link.isOverStressed();
	}

	/** Editable aim speed (RPM target): shown on the mount's Create value box and driving the radio step. */
	public int aimRpm() {
		return aimRpm;
	}



	@Override
	public void remove() {
		unregisterReceivers();
		super.remove();
	}

	@Override
	public void onChunkUnloaded() {
		unregisterReceivers();
		super.onChunkUnloaded();
	}

	private void unregisterReceivers() {
		if (getLevel() != null && !getLevel().isClientSide()) {
			for (MountLinkReceiver r : activeReceivers)
				Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(getLevel(), r);
			activeReceivers.clear();
			linkInitDone = false;
		}
	}

	@Override
	public void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(compound, registries, clientPacket);
		ListTag list = new ListTag();
		for (int i = 0; i < frequencyItems.length; i++) {
			ItemStack st = frequencyItems[i];
			if (st == null || st.isEmpty())
				list.add(new CompoundTag());
			else
				list.add(st.saveOptional(registries));
		}
		compound.put("FrequencyItems", list);
		compound.putFloat("AppliedYawStep", appliedYawStep);
		compound.putFloat("AppliedPitchStep", appliedPitchStep);


	}

	@Override
	protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(compound, registries, clientPacket);
		this.appliedYawStep = compound.getFloat("AppliedYawStep");
		this.appliedPitchStep = compound.getFloat("AppliedPitchStep");
		if (aimSpeedBox != null) {
			int stored = aimSpeedBox.getValue();
			if (stored >= AIM_RPM_MIN && stored <= AIM_RPM_MAX) {
				aimRpm = stored;
			} else {
				aimRpm = Mth.clamp(aimRpm, AIM_RPM_MIN, AIM_RPM_MAX);
				aimSpeedBox.value = aimRpm;
			}
		}


		if (compound.contains("FrequencyItems", Tag.TAG_LIST)) {

			ListTag list = compound.getList("FrequencyItems", Tag.TAG_COMPOUND);
			for (int i = 0; i < frequencyItems.length && i < list.size(); i++) {
				Tag t = list.get(i);
				frequencyItems[i] = (t instanceof CompoundTag ct) ? ItemStack.parseOptional(registries, ct) : ItemStack.EMPTY;
			}
		}
	}

	/** Create-native aim-speed value box: side faces only; the box is lowered 3 voxels (8->5) toward the plinth. */
	private long lastHandlerNullLog = -1000L;

	/**
	 * Diagnostic override. When Create (Mechanical Arm / Funnel / hopper / depot) queries our
	 * mount's ItemHandler.BLOCK capability and it resolves to null, log WHY server-side
	 * (throttled ~every 5s). Root facts: only a mounted AUTOCANNON exposes a usable handler
	 * (breech item handler: ammo insert slot 1 / spent-case extract slot 0). A mounted BIG
	 * CANNON (quickfiring breech) has NO item handler at all, so an arm/funnel/hopper cannot
	 * push shells into it -- auto-load big cannons with a CannonLoader + piston extension pole.
	 * Also: Create Mechanical Arm deploy targets must be an ArmInteractionPoint (Funnel/Depot),
	 * never the raw mount block.
	 */
	@Override
	public IItemHandler getItemHandler(Direction side) {
		IItemHandler handler = super.getItemHandler(side);
		Level level = this.getLevel();
		if (handler == null && level != null && !level.isClientSide
				&& level.getGameTime() - this.lastHandlerNullLog >= 100L && false) {
			this.lastHandlerNullLog = level.getGameTime();
			String reason;
			if (this.mountedContraption == null) {
				reason = "no assembled contraption (assemble the cannon on the mount first)";
			} else {
				var c = this.mountedContraption.getContraption();
				reason = "contraption=" + (c == null ? "null" : c.getClass().getSimpleName())
						+ " GetItemStorage?=" + (c instanceof GetItemStorage)
						+ " ItemCannon?=" + (c instanceof ItemCannon);
			}
			CBCFireControl.LOGGER.warn(
					"[cbc_firecontrol] mount {} side {} -> ItemHandler NULL: {} | "
					+ "only a mounted AUTOCANNON has an insertable/extractable handler "
					+ "(slot1 ammo in / slot0 spent case out); a BIG CANNON mount has NONE -> "
					+ "auto-load big cannons with CannonLoader+extension pole, not arm/funnel. "
					+ "Arm deploy target must be a Funnel/Depot interaction point, not the mount block.",
					this.getBlockPos(), side, reason);
		}
		return handler;
	}

	private static final class AimValueBoxTransform extends CenteredSideValueBoxTransform {
		AimValueBoxTransform() {
			super((state, dir) -> !dir.getAxis().isVertical());
		}

		@Override
		public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
			// Bind in block-local voxel coordinates, but nudge 4 voxels toward the plinth for BOTH
			// orientations so the number always hugs the plinth: DOWN-slab plinth is the lower half
			// (Y 0..8) -> Y=4; UP-slab plinth is the upper half (Y 8..16) -> Y=12 (mirror about centre 8).
			Direction vertical = state.getValue(BlockStateProperties.VERTICAL_DIRECTION);
			float y = vertical == Direction.UP ? 12.0f : 4.0f;
			Vec3 location = VecHelper.voxelSpace(8, y, 15.5);
			Direction side = getSide();
			location = VecHelper.rotateCentered(location, AngleHelper.horizontalAngle(side), Direction.Axis.Y);
			location = VecHelper.rotateCentered(location, AngleHelper.verticalAngle(side), Direction.Axis.X);
			return location;
		}
	}

	/** One Create redstone-link listener per (channel, slot-order). Drives that channel's action on power. */
	private final class MountLinkReceiver implements IRedstoneLinkable {

		private final int col;
		private final Couple<Frequency> key;

		MountLinkReceiver(int col, Couple<Frequency> key) {
			this.col = col;
			this.key = key;
		}

		@Override
		public Couple<Frequency> getNetworkKey() {
			return key;
		}

		@Override
		public boolean isListening() {
			return true;
		}

		@Override
		public int getTransmittedStrength() {
			return 0;
		}

		@Override
		public void setReceivedStrength(int power) {
			onChannelPower(col, power);
		}

		@Override
		public boolean isAlive() {
			Level lvl = getLevel();
			if (lvl == null || lvl.isClientSide() || isRemoved() || !lvl.isLoaded(worldPosition))
				return false;
			return lvl.getBlockEntity(worldPosition) == FireControlMountBlockEntity.this;
		}

		@Override
		public BlockPos getLocation() {
			return worldPosition;
		}
	}

	/* ---------- Fire Control console (server-side actions, invoked from CBCFCNetwork.handleControl) ---------- */

	/** Authoritative readouts shipped to the GUI via MountStatePayload. */
	public float consoleYaw() {
		return this.cannonYaw;
	}

	public float consolePitch() {
		return this.cannonPitch;
	}

	public void consoleAssemble() {
		if (this.level == null || this.level.isClientSide() || this.isRunning())
			return;
		try {
			this.assemble();
		} catch (AssemblyException e) {
			CBCFireControl.LOGGER.warn("console assemble failed at {}: {}", this.worldPosition, e.getMessage());
		}
	}

	public void consoleDisassemble() {
		if (this.level == null || this.level.isClientSide() || !this.isRunning())
			return;
		this.disassemble();
	}

	public void consoleFire() {
		if (this.level == null || this.level.isClientSide() || !this.isRunning())
			return;
		if (!(this.level instanceof ServerLevel slevel) || this.mountedContraption == null)
			return;
		var contraption = this.mountedContraption.getContraption();
		if (contraption instanceof AbstractMountedCannonContraption cannon
				&& cannon.canBeFiredOnController(this)) {
			cannon.fireShot(slevel, this.mountedContraption);
		}
	}

	@Override
	public float getYawOffset(float partialTicks) {
		if (getLevel() != null && getLevel().isClientSide()) {
			// Radio-driven smooth client render: the interface shaft speed is intentionally held at 0 (radio
			// supplies the aim rate; the bottom shaft only supplies stress/capacity), so CBC's own
			// aSpeed-from-interface-speed smoothing collapses. Interpolate from the previous tick's angle
			// (cannonYaw - appliedYawStep) to the authoritative cannonYaw across the render tick instead.
			float p = Mth.clamp(partialTicks + 1.0f, 0.0f, 1.0f);
			return Mth.lerp(p, this.cannonYaw - appliedYawStep, this.cannonYaw);
		}
		return super.getYawOffset(partialTicks);
	}

	@Override
	public float getPitchOffset(float partialTicks) {
		if (getLevel() != null && getLevel().isClientSide()) {
			float p = Mth.clamp(partialTicks + 1.0f, 0.0f, 1.0f);
			return Mth.lerp(p, this.cannonPitch - appliedPitchStep, this.cannonPitch);
		}
		return super.getPitchOffset(partialTicks);
	}

	/**
	 * Kinetic->rotation coupling disabled for this mount. Cannon aiming is driven solely by the radio
	 * channels ({@link #consoleAdjustYaw} / {@link #consoleAdjustPitch}); the single bottom kinetic link
	 * only supplies stress/capacity (see {@link #hasKineticStressInput()}). Returning 0 makes CBC's
	 * tick() compute {@code yawSpeed = pitchSpeed = 0} from {@code getAngularSpeed(-interfaceSpeed, ...)},
	 * so a bottom shaft's speed can never spin the cannon. This also removes the one-tick angle spike
	 * ("abnormal yaw twitch") that the inherited implementation produced the moment a power network was
	 * reconnected and the interface speed jumped from 0 to the shaft's speed. The client-side cradle /
	 * axle smoothing is unaffected: it is handled by the getYawOffset / getPitchOffset overrides above,
	 * which never call this method.
	 */
	@Override
	public float getAngularSpeed(float interfaceSpeed, float clientDiff) {
		return 0f;
	}

	/** Positive deg = clockwise. While assembled this turns the cannon; while idle it spins the cradle continuously (no 90-degree block-facing step). */
	public void consoleAdjustYaw(float deg) {
		if (this.level == null || this.level.isClientSide())
			return;
		float prev = this.cannonYaw;
		if (this.isRunning()) {
			this.cannonYaw = Mth.wrapDegrees(this.cannonYaw + deg);
			this.appliedYawStep = AngleHelper.getShortestAngleDiff(prev, this.cannonYaw);
			this.applyRotation();
			this.sendData();
		} else {
			this.cannonYaw = Mth.wrapDegrees(this.cannonYaw + deg);
			this.appliedYawStep = AngleHelper.getShortestAngleDiff(prev, this.cannonYaw);
			this.sendData();
			
			
			
		}
	}

	/** Positive deg = elevate. Only while assembled; clamped to the cannon's elevation/depression limits. */
	public void consoleAdjustPitch(float deg) {
		if (this.level == null || this.level.isClientSide() || !this.isRunning() || this.mountedContraption == null)
			return;
		float d = this.mountedContraption.maximumDepression();
		float e = this.mountedContraption.maximumElevation();
		float sgn = this.mountedContraption.getInitialOrientation() == Direction.DOWN ? -1.0f : 1.0f;
		float prev = this.cannonPitch;
		this.cannonPitch = Mth.clamp(this.cannonPitch + sgn * deg, -d, e);
		this.appliedPitchStep = this.cannonPitch - prev;
		this.applyRotation();
		this.sendData();
	}
}


