package com.cbc.firecontrol.menu;

import com.cbc.firecontrol.block.entity.AdvancedCannonMountBlockEntity;
import com.cbc.firecontrol.init.CBCFCMenus;
import com.simibubi.create.foundation.gui.menu.GhostItemMenu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * Create-style "remote controller" container for the Advanced Cannon Mount, modeled on Create's
 * LinkedControllerMenu. The top panel shows {@value #CHANNELS} columns of 2 ghost slots each
 * ("一个下面两个格子"); each column is one wireless control channel and its two items become a
 * Create redstone-link Couple&lt;Frequency&gt; (see {@link AdvancedCannonMountBlockEntity.MountLinkReceiver}).
 *
 * Ghost slots accept ANY item and do not consume it (allowRepeats). The player places any item(s)
 * into a column's two slots to define that channel's Create-link frequency; then a matching Create
 * Redstone Link transmitter (or a Create LinkedController column) set to the SAME two items drives
 * this mount on that channel within Create's linkRange.
 *
 * <p>The last column is the mount's own assemble/disassemble channel (see
 * {@link AdvancedCannonMountBlockEntity#CH_ASSEMBLE}). It behaves like any other column - a powered
 * Create link on its two items assembles or unloads the cannon - but the trajectory calculator does
 * not mirror it, because the console's channel grid is five columns wide, so a SYNC can never change
 * this column's items.
 *
 * contentHolder is the mount BlockPos (authoritative on the server; reconstructed on the client from
 * the openMenu extraData buffer). The menu itself is stateless - it mirrors the block entity's stored
 * frequency items and pushes edits back to the block entity (server side only).
 */
public class MountFrequencyMenu extends GhostItemMenu<BlockPos> {

	/**
	 * Columns on the panel, mirrored from the block entity that owns the stored items so the ghost
	 * inventory can never end up shorter than the grid addSlots() lays out.
	 */
	public static final int CHANNELS = AdvancedCannonMountBlockEntity.RADIO_COLUMNS;
	private static final int SLOT_COUNT = CHANNELS * 2;

	/** Server path: contentHolder is the real mount position (built by the MenuProvider). */
	public MountFrequencyMenu(MenuType<?> type, int id, Inventory inv, BlockPos pos) {
		super(type, id, inv, pos);
	}

	/** Client path: MenuBase decodes the mount position from the openMenu extra-data buffer. */
	public MountFrequencyMenu(MenuType<?> type, int id, Inventory inv, RegistryFriendlyByteBuf buf) {
		super(type, id, inv, buf);
	}

	/** Registered as the MenuType IContainerFactory so the client can rebuild the menu from extraData. */
	public static MountFrequencyMenu newClient(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
		return new MountFrequencyMenu(CBCFCMenus.MOUNT_FREQUENCY.get(), id, inv, buf);
	}

	@Override
	@OnlyIn(Dist.CLIENT)
	protected BlockPos createOnClient(RegistryFriendlyByteBuf extraData) {
		return extraData.readBlockPos();
	}

	@Override
	protected boolean allowRepeats() {
		return true;
	}

	@Override
	protected ItemStackHandler createGhostInventory() {
		ItemStackHandler inv = new ItemStackHandler(SLOT_COUNT);
		if (player != null && player.level().getBlockEntity(contentHolder) instanceof AdvancedCannonMountBlockEntity mount) {
			ItemStack[] freq = mount.getFrequencyItems();
			for (int i = 0; i < inv.getSlots() && i < freq.length; i++)
				inv.setStackInSlot(i, freq[i]);
		}
		return inv;
	}

	@Override
	protected void addSlots() {
		addPlayerSlots(8, 131);

		int x = 12;
		int y = 34;
		int slot = 0;
		for (int column = 0; column < CHANNELS; column++) {
			for (int row = 0; row < 2; ++row)
				addSlot(new SlotItemHandler(ghostInventory, slot++, x, y + row * 18));
			x += 24;
		}
	}

	/** Ghost slots are the last SLOT_COUNT slots (indices 36..36+SLOT_COUNT-1 in vanilla numbering). */
	private boolean isGhostSlot(int slotId) {
		return slotId >= 36 && slotId < 36 + SLOT_COUNT;
	}

	@Override
	protected void saveData(BlockPos pos) {
		pushToBlockEntity();
	}

	/**
	 * Copy the ghost inventory back into the block entity (server side only). The block entity
	 * (re)registers its Create redstone-link receivers so the frequency edits take effect live.
	 */
	public void pushToBlockEntity() {
		if (player == null || player.level().isClientSide())
			return;
		AdvancedCannonMountBlockEntity mount = resolveMount(player);
		if (mount != null) {
			ItemStack[] arr = new ItemStack[ghostInventory.getSlots()];
			for (int i = 0; i < arr.length; i++)
				arr[i] = ghostInventory.getStackInSlot(i);
			mount.setFrequencyItems(arr);
		}
	}

	@Override
	public void clicked(int slotId, int dragType, ClickType clickType, Player playerIn) {
		super.clicked(slotId, dragType, clickType, playerIn);
		if (isGhostSlot(slotId))
			pushToBlockEntity();
	}

	@Override
	public ItemStack quickMoveStack(Player playerIn, int index) {
		ItemStack result = super.quickMoveStack(playerIn, index);
		if (isGhostSlot(index))
			pushToBlockEntity();
		return result;
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack stack, Slot slotIn) {
		// Never let shift-double-click (pick-all) touch the frequency ghost slots.
		return slotIn.container == playerInventory;
	}

	/** The mount position this panel controls, readable outside this class (see CBCFCNetwork). */
	public BlockPos mountPos() {
		return contentHolder == null ? null : (BlockPos) contentHolder;
	}

	/**
	 * Resolves the mount this menu controls. On a Sable ship the player's own level and the panel's position can
	 * sit on different coordinate scales (the player reports sub-level local coordinates while the raycast that
	 * opened the panel reports the ship's footprint coordinate), so the player's level alone is not enough: fall
	 * back to the ship's world dimension before giving up.
	 */
	private AdvancedCannonMountBlockEntity resolveMount(Player playerIn) {
		if (contentHolder == null)
			return null;
		BlockPos pos = (BlockPos) contentHolder;
		if (playerIn.level().getBlockEntity(pos) instanceof AdvancedCannonMountBlockEntity mount)
			return mount;
		if (playerIn.getServer() == null)
			return null;
		ServerLevel world = playerIn.getServer().overworld();
		return world != playerIn.level() && world.getBlockEntity(pos) instanceof AdvancedCannonMountBlockEntity mount
				? mount
				: null;
	}

	@Override
	public boolean stillValid(Player playerIn) {
		// Presence of the mount is the whole test. A distance check here would close the panel instantly for
		// ship users: on a Sable ship the player's position is in the sub-level's local scale while this menu's
		// position is in the ship's footprint scale, so the arithmetic yields nonsense. The panel is a fixed
		// radio terminal, exactly like Create's controller, so it stays open until the player closes it or the
		// mount is gone.
		return resolveMount(playerIn) != null;
	}
}
