package com.cbc.firecontrol.block.entity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.cbc.firecontrol.CBCFireControl;
import com.cbc.firecontrol.ballistics.AimApplyResult;
import com.cbc.firecontrol.ballistics.AimEnvelope;
import com.cbc.firecontrol.ballistics.AimSolution;
import com.cbc.firecontrol.ballistics.InverseBallistics;
import com.cbc.firecontrol.ballistics.MountScanner;
import com.cbc.firecontrol.ballistics.MountSnapshot;
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
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;

import com.cbc.firecontrol.init.CBCFCMenus;
import com.cbc.firecontrol.menu.MountFrequencyMenu;
import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import org.jetbrains.annotations.Nullable;
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
 * Advanced Cannon Mount BlockEntity - half-slab mount variant.
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
public class AdvancedCannonMountBlockEntity extends CannonMountBlockEntity implements MenuProvider {

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

	public AdvancedCannonMountBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		// Radio aiming needs a client sync every tick (not every 3) so the cradle/axle render as
		// continuous motion; CBC's default lazy rate of 3 is tuned for idle stress syncs, not aiming.
		this.setLazyTickRate(1);
		if (CBCFCBlocks.ADVANCED_CANNON_MOUNT.has(state)) {
			this.cannonYaw = state.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
		}
	}

	/** Public read access to CBC's protected mountedContraption entity, for the Trajectory Calculator.
	 *  Plain inherited-field accessor (no reflection, no mixin): mountedContraption is protected on CBC's
	 *  CannonMountBlockEntity and this class is that subclass. Null while the mount is disassembled. */
	public rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity mountedEntityOrNull() {
		return this.mountedContraption;
	}

	/** Public read access to the protected CBC cannonYaw / cannonPitch, for the Trajectory Link tooltip. */
	public float getLinkYaw() {
		return Mth.wrapDegrees(this.cannonYaw);
	}

	public float getLinkPitch() {
		return this.cannonPitch;
	}

	/**
	 * The direction the assembled bore points at right now, without walking the bore: the contraption
	 * rotation of this mount applied to the assembly orientation CBC records for it, which is the same
	 * vector {@code MountScanner.scan} derives from. The Trajectory Calculator drives its aim channels
	 * every tick and cannot afford a full scan, so it reads the live angle through here instead.
	 */
	public Vec3 currentBoreDirection() {
		AbstractMountedCannonContraption cannon = mountedCannonOrNull();
		PitchOrientedContraptionEntity entity = mountedEntityOrNull();
		if (cannon == null || entity == null)
			return null;
		Direction orientation = cannon.initialOrientation();
		Vec3 base = entity.toGlobalVector(Vec3.atCenterOf(BlockPos.ZERO), 0.0f);
		Vec3 along = entity.toGlobalVector(Vec3.atCenterOf(orientation.getNormal()), 0.0f);
		Vec3 direction = along.subtract(base);
		return direction.lengthSqr() < 1.0e-6 ? null : direction.normalize();
	}

	@Override
	protected void assemble() throws AssemblyException {
		// CBC's own assemble() is gated on CBCBlocks.CANNON_MOUNT.has(state); ours is gated on our block.
		if (!CBCFCBlocks.ADVANCED_CANNON_MOUNT.has(this.getBlockState())) return;

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

	/** The currently assembled CBC autocannon contraption on this mount, or null if not an autocannon. */
	private MountedAutocannonContraption getMountedAutocannon() {
		if (this.mountedContraption == null)
			return null;
		var contraption = this.mountedContraption.getContraption();
		return contraption instanceof MountedAutocannonContraption autocannon ? autocannon : null;
	}

	/** True when the assembled cannon on this mount is a CBC autocannon (MountedAutocannonContraption). */
	private boolean isMountedAutocannon() {
		return getMountedAutocannon() != null;
	}

	/**
	 * Autocannon firing state machine (server tick). CBC continuous-fires a mounted autocannon only when BOTH
	 * canBeFiredOnController(mount) is true AND the breech canFire()==(fireRate>0 && firingCooldown<=0). A mounted
	 * autocannon that is NOT CBC-auto-firable (manual / manually-ridden "manual mode": right-click to ride, CBC's
	 * ServerboundFiringActionPacket -> PitchOrientedContraptionEntity.tryFiringShot -> fireShot on left-click)
	 * never continuous-fires on its own, so we simply HOLD its breech at the firing cadence while assembled - the
	 * player rides it and left-clicks to fire, with NO wireless redstone link involved at all.
	 *   - Manual/riding autocannon (CBC won't auto-fire): hold fireRate at cadence while running => left-click fires.
	 *   - CBC-auto-firable mounted autocannon: keep idle fireRate 0 (no assembly blast); while the radio Fire channel
	 *     is powered, issue one CBC fireShot per cadence step with fireRate raised only momentarily then reset to 0.
	 */
	private void updateAutocannonFiringState() {
		MountedAutocannonContraption autocannon = getMountedAutocannon();
		if (autocannon == null)
			return;
		if (!autocannon.canBeFiredOnController(this)) {
			// Manual/riding autocannon: CBC will not continuous-fire it, so hold it at the firing cadence while
			// assembled; left-click (CBC packet) fires it. No radio/wireless required.
			setMountedAutocannonFireRate(this.isRunning() ? AUTOCANNON_FIRE_RATE_WHILE_FIRING : 0);
			return;
		}
		if (!(channelPowered[CH_FIRE] && this.isRunning())) {
			setMountedAutocannonFireRate(0);
			return;
		}
		if (fireCooldown > 0)
			return;
		setMountedAutocannonFireRate(AUTOCANNON_FIRE_RATE_WHILE_FIRING);
		consoleFire();
		setMountedAutocannonFireRate(0);
		fireCooldown = AUTOCANNON_FIRE_INTERVAL_TICKS;
	}

	/** Sets the mounted autocannon's breech fireRate (only writes when the value actually changes). */
	private void setMountedAutocannonFireRate(int value) {
		MountedAutocannonContraption autocannon = getMountedAutocannon();
		if (autocannon == null)
			return;
		for (var be : autocannon.presentBlockEntities.values()) {
			if (be instanceof AbstractAutocannonBreechBlockEntity breech && breech.getFireRate() != value)
				breech.setFireRate(value);
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
		if (!this.isRunning() && CBCFCBlocks.ADVANCED_CANNON_MOUNT.has(state)) {
			this.cannonYaw = state.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
			this.cannonPitch = 0;
		}
		super.tick();
		tickWirelessControl();
	}

	@Override
	public ResourceLocation getTypeId() {
		return ResourceLocation.fromNamespaceAndPath(CBCFireControl.MODID, "advanced_cannon_mount");
	}

	/* ---------- Wireless radio-link channels + Create redstone-link receivers + frequency menu ---------- */

	public static final int CH_YAW_PLUS = 0;
	public static final int CH_YAW_MINUS = 1;
	public static final int CH_PITCH_UP = 2;
	public static final int CH_PITCH_DOWN = 3;
	public static final int CH_FIRE = 4;
	public static final int WIRELESS_CHANNELS = 5;

	/**
	 * Highest signal strength a Create redstone link can carry. The received strength throttles the aim rate
	 * in {@link #tickWirelessControl()}: the value-box RPM is the rate at this strength, and a weaker
	 * transmitter turns proportionally slower.
	 */
	private static final int MAX_LINK_SIGNAL = 15;

	private static final int FIRE_COOLDOWN_TICKS = 5;
	/** Breech fireRate index temporarily raised around each autocannon shot so CBC's own fireShot passes its
	 *  canFire() (fireRate>0). FIRE_RATES[14]=4 ticks/round. It is set straight back to 0 after the shot, so CBC's
	 *  contraption-tick continuous auto-fire never runs => no self-firing on assembly / idle. */
	private static final int AUTOCANNON_FIRE_RATE_WHILE_FIRING = 15;
	/** Autocannon shot spacing while the radio Fire channel is powered: 4 ticks/round = 5 rounds/sec. */
	private static final int AUTOCANNON_FIRE_INTERVAL_TICKS = 4;
	/**
	 * Aim speed range, in the RPM a Create Rotation Speed Controller would feed the interface shaft at FULL
	 * (15) signal - {@link #tickWirelessControl()} converts it with CBC's own factor (rpm * 0.0375 deg/tick),
	 * so the 128 ceiling is 4.8 deg/tick, or 96 deg/s: a full 360-degree yaw takes 75 ticks (3.75 s) at maximum
	 * signal instead of the 15 s the previous 32 ceiling allowed.
	 *
	 * <p>At the top of the range one tick turns further than the calculator's derived drive deadband
	 * (0.02-0.5 degrees), so a gunner who sets 128 and leaves the signal at full will see the barrel step over
	 * the solved aim and come back. That is why the link's signal still throttles the rate proportionally: the
	 * calculator drops the strength as the error shrinks, and 128 RPM then only applies to the coarse part of
	 * the slew. Fine work stays on the lower end - the floor of 1 is what the stall-detection thresholds in the
	 * calculator are sized against, so do not raise it.
	 */
	private static final int AIM_RPM_MIN = 1;
	private static final int AIM_RPM_MAX = 128;
	private static final int AIM_RPM_DEFAULT = 8;


	private final ItemStack[] frequencyItems = new ItemStack[WIRELESS_CHANNELS * 2];
	private final int[] channelPower = new int[WIRELESS_CHANNELS];
	private final boolean[] channelPowered = new boolean[WIRELESS_CHANNELS];
	private final List<MountLinkReceiver> activeReceivers = new ArrayList<>();
	private int fireCooldown;
	private boolean linkInitDone;

	/**
	 * Persistent identity of THIS mount. 1.21.1 removed {@code BlockEntity#getUUID()}, so this mod owns its id:
	 * generated once on the server, saved in the mount's own NBT, and mirrored to clients through the block
	 * entity data packet. A calculator link card references mounts by this id (with coordinates cached for a
	 * fast direct lookup) so the card survives rebuilds instead of silently following a reused coordinate.
	 */
	private UUID mountId;
	/** Bookkeeping for {@link MountIdIndex}: which position this id is currently registered at. */
	private UUID indexedId;
	private BlockPos indexedPos;

	/**
	 * Stable per-mount id. Assigned on demand on the SERVER (and persisted); a client copy that has not yet
	 * received the synced value returns null rather than inventing a different id.
	 */
	@Nullable
	public UUID mountId() {
		if (mountId != null)
			return mountId;
		Level level = getLevel();
		if (level == null || level.isClientSide())
			return null;
		mountId = UUID.randomUUID();
		setChanged();
		return mountId;
	}

	/** Server only: keep this mount findable by id at its current position. */
	private void updateMountIndex() {
		Level level = getLevel();
		if (level == null || level.isClientSide())
			return;
		UUID id = mountId();
		if (id == null)
			return;
		if (id.equals(indexedId) && worldPosition.equals(indexedPos))
			return;
		if (indexedId != null)
			MountIdIndex.unregister(level, indexedId);
		MountIdIndex.register(level, id, worldPosition);
		indexedId = id;
		indexedPos = worldPosition.immutable();
	}

	private void removeMountFromIndex() {
		Level level = getLevel();
		if (level == null || level.isClientSide() || indexedId == null)
			return;
		MountIdIndex.unregister(level, indexedId);
		indexedId = null;
		indexedPos = null;
	}

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
		return Component.literal("Advanced Cannon Mount - Radio");
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
			MountedAutocannonContraption autocannon = getMountedAutocannon();
			if (autocannon != null) {
				// Auto-firable mounted autocannon: radio cadence fires it; start promptly on the rising edge.
				// Manual/riding autocannon is fired by CBC left-click and needs no radio here.
				if (autocannon.canBeFiredOnController(this))
					fireCooldown = 0;
			} else if (fireCooldown <= 0) {
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
		updateMountIndex();
		if (fireCooldown > 0)
			fireCooldown--;
		// Autocannon firing (see updateAutocannonFiringState): a CBC-auto-firable mounted autocannon fires via the
		// radio cadence with fireRate raised only momentarily (no idle/assembly blast); a manual/riding autocannon
		// (CBC fires it on left-click) is held at the firing cadence while assembled, so it fires with NO wireless.
		updateAutocannonFiringState();
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

		// Radio steps mirror CBC's kinetic aiming, and are THROTTLED by the signal strength of the channel
		// that asks for the motion. A value-box "RPM" is treated as the RPM that a Create Rotation Speed
		// Controller would feed the interface shaft at FULL (15) signal, then CBC's own reduction applies:
		//   Create convertToAngular(rpm) = rpm * 360 / 60 / 20 = rpm * 0.3  deg/tick  (KineticBlockEntity.java)
		//   CBC  getAngularSpeed(rpm)   = convertToAngular(rpm) * 0.125   = rpm * 0.0375 deg/tick
		// CannonMountBlockEntity uses the SAME 0.0375 factor for yaw and pitch, so both axes turn at the
		// identical rate (the old "pitch at half rate" asymmetry is removed to match CBC).
		//
		// A Create redstone link transmits the neighbour's signal strength (0..15), not just on/off, so the
		// received strength scales that rate linearly: strength 15 turns at the whole set RPM, strength 5 at a
		// third of it. This is what makes a lever output, a comparator decay curve, or a graduated button
		// stack behave as a fine-trim control instead of an all-or-nothing slew, which is how a gunner holds a
		// mount onto a solved aim without overshooting it. Opposite channels cancel as before, and each side
		// contributes its OWN throttled share, so a weak Yaw+ against a strong Yaw- turns backwards at the
		// difference rather than sitting still.
		float degPerTick = aimRpm * 0.0375f;
		float yawStep = 0f;
		float pitchStep = 0f;
		if (channelPowered[CH_YAW_PLUS])
			yawStep += degPerTick * channelPower[CH_YAW_PLUS] / (float) MAX_LINK_SIGNAL;
		if (channelPowered[CH_YAW_MINUS])
			yawStep -= degPerTick * channelPower[CH_YAW_MINUS] / (float) MAX_LINK_SIGNAL;
		if (channelPowered[CH_PITCH_UP])
			pitchStep += degPerTick * channelPower[CH_PITCH_UP] / (float) MAX_LINK_SIGNAL;
		if (channelPowered[CH_PITCH_DOWN])
			pitchStep -= degPerTick * channelPower[CH_PITCH_DOWN] / (float) MAX_LINK_SIGNAL;

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

	/**
	 * Editable aim speed (RPM target): shown on the mount's Create value box and driving the radio step at
	 * FULL signal. The rate actually applied is this scaled by each channel's received signal strength, so the
	 * box reads the fast end of the range rather than the speed the mount is currently turning at.
	 */
	public int aimRpm() {
		return aimRpm;
	}



	@Override
	public void remove() {
		unregisterReceivers();
		removeMountFromIndex();
		super.remove();
	}

	@Override
	public void onChunkUnloaded() {
		unregisterReceivers();
		removeMountFromIndex();
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
		if (mountId != null)
			compound.putString("MountId", mountId.toString());
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
		if (compound.contains("MountId", Tag.TAG_STRING)) {
			String stored = compound.getString("MountId");
			if (!stored.isEmpty()) {
				try {
					this.mountId = UUID.fromString(stored);
				} catch (IllegalArgumentException e) {
					CBCFireControl.LOGGER.warn("[cbc_firecontrol] mount {} has an unreadable MountId '{}'",
							this.worldPosition, stored);
				}
			}
		}
		if (!clientPacket)
			updateMountIndex();
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
			return lvl.getBlockEntity(worldPosition) == AdvancedCannonMountBlockEntity.this;
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

	/**
	 * Public accessor for the currently assembled cannon contraption (null if none / not assembled).
	 * Exposed because CBC's {@code mountedContraption} field and its getContraption() chain are
	 * protected / cross-package, so the DisplaySource cannot read the loaded-round state directly.
	 * This bridges it out without copying any CBC internals.
	 */
	public rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption mountedCannonOrNull() {
		if (this.mountedContraption == null)
			return null;
		var c = this.mountedContraption.getContraption();
		return (c instanceof rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption a) ? a : null;
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

	/**
	 * Elevation window this mount's assembled cannon physically allows, expressed in the solver's convention
	 * (0 = level, positive = barrel up) rather than CBC's console value. CBC clamps its own {@code cannonPitch}
	 * to {@code [-maximumDepression, maximumElevation]} and, for a mount assembled upside-down
	 * ({@code initialOrientation == DOWN}), elevating the barrel DECREASES cannonPitch
	 * ({@link #consoleAdjustPitch} applies {@code sgn * deg}), so for those mounts the window flips.
	 *
	 * <p>Two deliberate fallbacks keep the calculator useful: an idle mount (no contraption assembled yet) and
	 * a mount whose CBC mount-properties entry is missing - CBC then reports elevation 0 / depression 0, which
	 * would otherwise make every solve answer ELEVATION_LIMITED - both report {@link AimEnvelope#DEFAULT}
	 * instead, and the log line below says which one happened.
	 */
	public double aimPitchMinDeg() {
		return aimElevationWindow()[0];
	}

	public double aimPitchMaxDeg() {
		return aimElevationWindow()[1];
	}

	private double[] aimElevationWindow() {
		if (this.mountedContraption == null)
			return new double[] { AimEnvelope.DEFAULT.pitchMinDeg(), AimEnvelope.DEFAULT.pitchMaxDeg() };
		float d = this.mountedContraption.maximumDepression();
		float e = this.mountedContraption.maximumElevation();
		boolean inverted = this.mountedContraption.getInitialOrientation() == Direction.DOWN;
		double min = inverted ? -e : -d;
		double max = inverted ? d : e;
		if (max - min < 1.0) {
			if (this.level != null && !this.level.isClientSide())
				CBCFireControl.LOGGER.warn(
						"[cbc_firecontrol] mount @{} reports elevation={} depression={} - no CBC mount-properties entry"
								+ " for this combination, using the wide default aim window",
						this.worldPosition, e, d);
			return new double[] { AimEnvelope.DEFAULT.pitchMinDeg(), AimEnvelope.DEFAULT.pitchMaxDeg() };
		}
		return new double[] { min, max };
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

	/**
	 * Below this many degrees an axis counts as already on aim. It is also the angular floor of the calculator's
	 * closed-loop refinement (that loop stops when a re-solve asks for less than this), so it sets how precisely
	 * a mount can be parked: 0.02 degrees is about 0.1 blocks of shot placement at 300 blocks, well inside the
	 * 1.5 block hit tolerance the rows report against.
	 */
	public static final double AIM_DEADBAND_DEG = 0.02;

	/**
	 * Drive this mount to a solved aim. Both console APIs already speak the solver's convention -
	 * {@link #consoleAdjustYaw(float)} is clockwise-positive, which is how {@link InverseBallistics#yawOf} counts
	 * too (CBC's cannonYaw runs SOUTH 0 -> WEST 90, i.e. clockwise over the map), and
	 * {@link #consoleAdjustPitch(float)} is elevate-positive like {@link InverseBallistics#pitchOf} - so the
	 * conversion is a signed delta per axis with nothing else to translate.
	 *
	 * <p>The mount's CURRENT angles come from the scanned barrel direction rather than from the stored
	 * cannonPitch, because CBC stores the NEGATIVE of the world elevation for a mount assembled barrel-down
	 * (see {@link #aimElevationWindow()}) while consoleAdjustPitch's own sign flip is what makes its argument
	 * elevate-positive. Reading the barrel instead of the stored number is correct for every orientation without
	 * re-deriving CBC's internal sign, and it also catches a mount that got wrenched or slewed by something
	 * other than this mod.
	 *
	 * <p>Clamping is reported rather than hidden: consoleAdjustPitch clamps to {@code [-maximumDepression,
	 * maximumElevation]} itself and records how much of the request actually landed in {@code appliedPitchStep},
	 * so a mount that cannot elevate far enough says so instead of claiming it is on aim.
	 *
	 * <p><b>Known defect, deliberately not fixed here (v0.0.5).</b> This method applies the whole delta in the
	 * call, so a calculator-driven slew is not throttled by {@code aimRpm}, by the radio link's signal strength,
	 * or by {@link #hasKineticStressInput()}: a 90 degree turn lands in a single tick, and the mount will move
	 * even with the bottom kinetic link unpowered or its network overstressed. The radio path in
	 * {@link #tickWirelessControl()} does respect all three. Fixing this means giving the mount target angles
	 * and slewing toward them per tick, which moves the calculator's convergence check out of the Aim button
	 * and changes what {@link AimSummary} can report - a deliberate semantic change, not a bug fix to slip in.
	 */
	public AimApplyResult applySolvedAim(AimSolution aim) {
		if (this.level == null || this.level.isClientSide())
			return AimApplyResult.of(AimApplyResult.Status.NOT_ASSEMBLED);
		if (aim == null || !aim.outcome().hasSolution())
			return AimApplyResult.of(AimApplyResult.Status.NO_SOLUTION);

		MountSnapshot snapshot = MountScanner.scan(this);
		if (!snapshot.assembled())
			return AimApplyResult.of(AimApplyResult.Status.NOT_ASSEMBLED);

		double yawDelta = AngleHelper.getShortestAngleDiff((float) InverseBallistics.yawOf(snapshot.direction()),
				(float) aim.yawDeg());
		double pitchDelta = aim.pitchDeg() - InverseBallistics.pitchOf(snapshot.direction());
		if (Math.abs(yawDelta) < AIM_DEADBAND_DEG && Math.abs(pitchDelta) < AIM_DEADBAND_DEG)
			return AimApplyResult.of(AimApplyResult.Status.ALREADY_AIMED);

		if (Math.abs(yawDelta) >= AIM_DEADBAND_DEG)
			consoleAdjustYaw((float) yawDelta);
		boolean clamped = false;
		if (Math.abs(pitchDelta) >= AIM_DEADBAND_DEG) {
			/*
			 * appliedPitchStep is the render-lerp record of how far the LAST pitch change actually landed
			 * (see interpolatedPitch). Clearing it first makes the clamp test about THIS request: CBC clamps
			 * cannonPitch inside consoleAdjustPitch, so a shorter step than the one asked for means the mount's
			 * own elevation limits stopped the barrel short of the solved angle.
			 */
			this.appliedPitchStep = 0.0f;
			consoleAdjustPitch((float) pitchDelta);
			clamped = Math.abs(this.appliedPitchStep) + 1.0e-3 < Math.abs(pitchDelta);
		}
		return new AimApplyResult(AimApplyResult.Status.APPLIED, yawDelta, pitchDelta, clamped);
	}
}


