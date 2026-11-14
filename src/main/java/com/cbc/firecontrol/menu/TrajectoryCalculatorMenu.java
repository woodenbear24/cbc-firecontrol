package com.cbc.firecontrol.menu;

import com.cbc.firecontrol.block.entity.TrajectoryCalculatorBlockEntity;
import com.cbc.firecontrol.init.CBCFCMenus;
import com.cbc.firecontrol.init.CBCItems;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * Menu of the Trajectory Calculator: five real inventory slots (one {@code calculator_link} card each) over
 * the player inventory. Slot geometry constants live here so the menu and the screen agree on the same
 * pixels, the same way {@link MountFrequencyMenu} / {@link com.cbc.firecontrol.client.MountFrequencyScreen}
 * share the mount panel geometry.
 *
 * <p>Both sides construct with the calculator's {@link BlockPos}: the server writes it into the menu buffer
 * through {@code Player#openMenu(MenuProvider, BlockPos)}, the client reads it back. Each side then resolves
 * the {@link IItemHandler} from the block entity it has locally - the server BE's handler (authoritative, and
 * the one whose {@code onContentsChanged} arms a recomputation) or the client BE's synced copy. When the BE
 * is unreachable a fixed-size empty handler is substituted so the slot COUNT can never diverge between the
 * two sides, which is the classic source of ghost-slot desyncs.
 */
public class TrajectoryCalculatorMenu extends AbstractContainerMenu {

	public static final int SLOTS = TrajectoryCalculatorBlockEntity.SLOTS;
	public static final int CHANNELS = TrajectoryCalculatorBlockEntity.CHANNELS_PER_SLOT;
	public static final int CHANNEL_ITEM_SLOTS = TrajectoryCalculatorBlockEntity.CHANNEL_ITEM_SLOTS;

	/**
	 * Slot ranges. The player inventory comes FIRST (indices 0..35) so vanilla's Shift+1..9 swap resolves
	 * through getSlot(button) to the hotbar, exactly as it does in every other container - putting the card
	 * slots at 0..4 would have made those keys swap against card slots instead.
	 */
	public static final int PLAYER_SLOT_COUNT = 36;
	public static final int CARD_SLOT_START = PLAYER_SLOT_COUNT;               /* 36..40        */
	public static final int CHANNEL_SLOT_START = CARD_SLOT_START + SLOTS;      /* 41..90        */

	/** Window-local geometry, shared with the screen. Link slots form one column; readouts sit to their right. */
	public static final int LINK_SLOT_X = 14;
	/** Rows start below the target-entry strip, which occupies {@code TARGET_STRIP_Y..TARGET_STRIP_BOTTOM}. */
	public static final int LINK_SLOT_Y = 52;
	public static final int LINK_SLOT_SPACING = 24;
	/** Target-entry strip (absolute world coordinates of the point to engage), shared with the screen. */
	public static final int TARGET_STRIP_Y = 26;
	public static final int TARGET_FIELD_H = 20;
	public static final int TARGET_X_FIELD_X = 18;
	public static final int TARGET_Y_FIELD_X = 88;
	public static final int TARGET_Z_FIELD_X = 158;
	public static final int TARGET_FIELD_W = 52;
	public static final int TARGET_SET_X = 214;
	public static final int TARGET_CLEAR_X = 248;
	/** Aim button, right of CLEAR: slew every mount this calculator records to its row's solved aim. */
	public static final int TARGET_AIM_X = 286;
	/** Live echo of the target currently stored in the block entity. */
	public static final int TARGET_ECHO_X = 312;
	public static final int TARGET_ECHO_W = 98;
	/** The recessed band behind the strip reaches this far above the fields and below them. */
	public static final int TARGET_STRIP_PAD_TOP = 5;
	public static final int TARGET_STRIP_PAD_BOTTOM = 8;
	/**
	 * Window-local y the strip band - and the divider drawn under it - ends at. The readout rows must not start
	 * above this, or their backdrop paints over the divider and the two blocks read as one.
	 */
	public static final int TARGET_STRIP_BOTTOM = TARGET_STRIP_Y - TARGET_STRIP_PAD_TOP + TARGET_FIELD_H
			+ TARGET_STRIP_PAD_BOTTOM;
	/** Each channel is its two filter slots side by side (18 + 18 + 4 gutter), 5 channels per row. */
	public static final int CHANNEL_SLOT_X = 212;
	public static final int CHANNEL_COLUMN_SPACING = 40;
	/**
	 * Vertical offset of a channel pair inside its row strip. Zero puts the filter slots exactly where the card
	 * slot is, which is also what centres their 20 pixel backdrop inside the row band (ROW_BAND_*) instead of
	 * letting it hang 4 pixels below the row it belongs to.
	 */
	public static final int CHANNEL_Y_OFFSET = 0;
	/** A readout row's backdrop starts this far above its card slot and is this tall. */
	public static final int ROW_BAND_INSET = 1;
	public static final int ROW_BAND_H = 20;
	/**
	 * How wide one readout row line may be before it would print over the channel grid: from the text column
	 * (right of the card slot, at {@code LINK_SLOT_X + 34}) to the leftmost filter backdrop, which sits at
	 * {@code CHANNEL_SLOT_X - 16}.
	 */
	public static final int ROW_TEXT_W = CHANNEL_SLOT_X - 16 - (LINK_SLOT_X + 34);

	public static final int PANEL_WIDTH = 416;

	/*
	 * Tabs. Their ids are part of the menu contract rather than a client detail: the screen shows one page at a
	 * time and tells the server which page it is showing, so a shift-click can never park a card in a row the
	 * gunner is not looking at, and a filter slot that is not drawn can never be clicked by coincidence.
	 */
	public static final int TAB_LINKS = 0;
	public static final int TAB_TARGET = 1;
	public static final int TAB_CHANNELS = 2;
	public static final int TAB_STATUS = 3;
	public static final int TAB_TASKS = 4;
	public static final int TAB_COUNT = 5;

	/* Tab strip across the top of the panel, and the first row a page may use below it. */
	public static final int TAB_BAR_X = 8;
	public static final int TAB_BAR_Y = 2;
	public static final int TAB_BAR_HEIGHT = 17;
	public static final int TAB_GAP = 2;
	/**
	 * Cell width the page strip is built from, sized so that the pages fill the panel's content width:
	 * {@code TAB_COUNT * TAB_WIDTH + (TAB_COUNT - 1) * TAB_GAP} stays within {@code PANEL_WIDTH - 16}, and the
	 * handful of pixels the integer division gives back is left unclaimed at the strip's right edge. A page title
	 * wider than a cell is truncated by {@link com.cbc.firecontrol.client.gui.TabBar}.
	 */
	public static final int TAB_WIDTH = (PANEL_WIDTH - 16 - (TAB_COUNT - 1) * TAB_GAP) / TAB_COUNT;
	/* The strip's own width follows from the page count: TAB_COUNT * TAB_WIDTH + (TAB_COUNT - 1) * TAB_GAP. */
	public static final int CONTENT_TOP = TAB_BAR_Y + TAB_BAR_HEIGHT + 2;
	/**
	 * SYNC control of the channels page, in the header band at the panel's left edge: the grid owns everything
	 * right of {@code CHANNEL_SLOT_X} in that band, where its header and five column labels sit.
	 */
	public static final int CHANNEL_SYNC_X = 8;
	public static final int CHANNEL_SYNC_Y = CONTENT_TOP + 1;
	public static final int CHANNEL_SYNC_W = 96;
	public static final int CHANNEL_SYNC_H = 18;
	/*
	 * The target page's second block: the fire-mission wait window, entered as whole seconds. One field and one
	 * commit button in a recessed strip, the live echo of what the console holds beside them, and three
	 * explanation lines under the strip - the same strip shape the target entry uses above it, so the console
	 * reads as one instrument. The page has no readout rows, so everything under the target strip belongs to
	 * this block; the links page is what uses that band of the panel for its rows.
	 */
	public static final int TARGET_WAIT_HEADER_Y = TARGET_STRIP_BOTTOM + 4;
	public static final int TARGET_WAIT_STRIP_Y = TARGET_WAIT_HEADER_Y + 15;
	public static final int TARGET_WAIT_LABEL_X = 18;
	public static final int TARGET_WAIT_FIELD_X = 48;
	public static final int TARGET_WAIT_FIELD_W = 44;
	public static final int TARGET_WAIT_SET_X = 96;
	public static final int TARGET_WAIT_SET_W = 48;
	public static final int TARGET_WAIT_ECHO_X = 152;
	public static final int TARGET_WAIT_ECHO_W = PANEL_WIDTH - 16 - TARGET_WAIT_ECHO_X;
	/** The recessed band behind the field reaches this far above and below it. */
	public static final int TARGET_WAIT_STRIP_PAD_TOP = 5;
	public static final int TARGET_WAIT_STRIP_PAD_BOTTOM = 8;
	public static final int TARGET_WAIT_STRIP_BOTTOM = TARGET_WAIT_STRIP_Y - TARGET_WAIT_STRIP_PAD_TOP
			+ TARGET_FIELD_H + TARGET_WAIT_STRIP_PAD_BOTTOM;
	/**
	 * First of the explanation lines under the strip; the rest follow one per {@code TARGET_WAIT_NOTE_LINE_H}.
	 * The last of them must stay above {@code PANEL_HEIGHT}, which is what bounds this block.
	 */
	public static final int TARGET_WAIT_NOTE_Y = TARGET_WAIT_STRIP_BOTTOM + 8;
	public static final int TARGET_WAIT_NOTE_LINE_H = 10;
	/*
	 * The tasks page's board: six columns, one queue each - the console's own queue first, then one per mount row,
	 * so a column answers "what is this mount working on" instead of repeating the row readouts. Rows are entry
	 * positions counted inside each column on its own, because a fire order is per-queue and the console and its
	 * mounts do not hold the same number of them.
	 */
	public static final int TASK_COLUMNS = SLOTS + 1;
	public static final int TASK_BOARD_X = 8;
	public static final int TASK_BOARD_W = PANEL_WIDTH - 16;
	public static final int TASK_COLUMN_W = TASK_BOARD_W / TASK_COLUMNS;
	public static final int TASK_HEADER_Y = CONTENT_TOP + 2;
	public static final int TASK_HEADER_H = 12;
	public static final int TASK_ROW_Y = TASK_HEADER_Y + TASK_HEADER_H + 2;
	public static final int TASK_ROW_H = 20;
	public static final int TASK_BOARD_BOTTOM = TASK_ROW_Y + SLOTS * TASK_ROW_H;
	/**
	 * The last row band ends at LINK_SLOT_Y - ROW_BAND_INSET + 4 * LINK_SLOT_SPACING + ROW_BAND_H = 167, and the
	 * 9 pixels left over are the visual gap under the last row.
	 */
	public static final int PANEL_HEIGHT = 176;
	public static final int PLAYER_INV_X = 8;
	public static final int PLAYER_INV_Y = PANEL_HEIGHT + 8;
	public static final int PLAYER_INV_H = 104;
	private static final int PLAYER_SLOT_X = PLAYER_INV_X + 8;
	private static final int PLAYER_SLOT_Y = PLAYER_INV_Y + 14;
	/** 3 inventory rows (3 * 18) + the 4 pixel vanilla gap, then the hotbar row. */
	private static final int HOTBAR_Y = PLAYER_SLOT_Y + 58;

	private final BlockPos pos;
	private final Inventory playerInventory;
	private final IItemHandler handler;
	/**
	 * Menu-local copy of the block entity's channel filters (nothing is ever consumed from the player). The
	 * server pushes this into the BE, the BE re-registers its link transmitters and syncs back through the
	 * block entity data packet - the same flow MountFrequencyMenu uses for the mount radio.
	 */
	private final ItemStackHandler ghostHandler = new ItemStackHandler(CHANNEL_ITEM_SLOTS);
	/**
	 * Page currently open. The client sets it through {@link com.cbc.firecontrol.network.CalculatorTabPayload};
	 * it starts on the links page, which is what the screen shows before any packet has arrived.
	 */
	private int activeTab = TAB_LINKS;

	public TrajectoryCalculatorMenu(int containerId, Inventory playerInventory, BlockPos pos) {
		super(CBCFCMenus.TRAJECTORY_CALCULATOR.get(), containerId);
		this.pos = pos;
		this.playerInventory = playerInventory;

		IItemHandler resolved = null;
		Level level = playerInventory.player.level();
		if (level.getBlockEntity(pos) instanceof TrajectoryCalculatorBlockEntity be) {
			resolved = be.getInventory();
			ItemStack[] fromBE = be.getSlotChannelItems();
			for (int i = 0; i < ghostHandler.getSlots() && i < fromBE.length; i++)
				ghostHandler.setStackInSlot(i, fromBE[i].copy());
		}
		// A fixed-size handler on both sides keeps the slot COUNT identical, which is the classic
		// source of ghost-slot desyncs when one side falls back to something else.
		this.handler = resolved != null ? resolved : new ItemStackHandler(SLOTS);

		for (int row = 0; row < 3; row++)
			for (int col = 0; col < 9; col++)
				addSlot(new Slot(playerInventory, col + row * 9 + 9, PLAYER_SLOT_X + col * 18, PLAYER_SLOT_Y + row * 18));
		for (int col = 0; col < 9; col++)
			addSlot(new Slot(playerInventory, col, PLAYER_SLOT_X + col * 18, HOTBAR_Y));

		for (int i = 0; i < SLOTS; i++) {
			final int slotId = CARD_SLOT_START + i;
			addSlot(new SlotItemHandler(handler, i, LINK_SLOT_X, LINK_SLOT_Y + LINK_SLOT_SPACING * i) {
				@Override
				public boolean mayPlace(ItemStack stack) {
					return stack.is(CBCItems.CALCULATOR_LINK.asItem());
				}

				@Override
				public int getMaxStackSize() {
					// Cards already stacksTo(1); restating it keeps the "one card identifies one mount" contract
					// even if a datapack ever raises the item's stack size.
					return 1;
				}

				@Override
				public boolean isActive() {
					// Cards belong to the links page. Vanilla takes this same answer for drawing, hovering and
					// drag targeting, so hiding the page closes the slot on both sides at once.
					return isSlotVisible(slotId);
				}
			});
		}

		for (int slot = 0; slot < SLOTS; slot++) {
			for (int channel = 0; channel < CHANNELS; channel++) {
				int baseX = CHANNEL_SLOT_X + channel * CHANNEL_COLUMN_SPACING;
				for (int filter = 0; filter < TrajectoryCalculatorBlockEntity.FILTERS_PER_CHANNEL; filter++) {
					int index = TrajectoryCalculatorBlockEntity.channelIndex(slot, channel, filter);
					addSlot(new GhostSlot(ghostHandler, index, baseX + filter * 18,
							LINK_SLOT_Y + LINK_SLOT_SPACING * slot + CHANNEL_Y_OFFSET));
				}
			}
		}
	}

	/**
	 * Ghost slot: accepts any item as a channel filter and never consumes it. Consuming would be wrong here -
	 * the item IS the frequency definition, and one filter item is meant to configure several channels.
	 *
	 * <p>Not static: the answer to "is this slot on screen" comes from the menu's open page, and an invisible
	 * filter must not be a click target, a drag target or a shift-click destination.
	 */
	private final class GhostSlot extends SlotItemHandler {

		private final int slotId;

		GhostSlot(IItemHandler handler, int index, int x, int y) {
			super(handler, index, x, y);
			this.slotId = CHANNEL_SLOT_START + index;
		}

		@Override
		public boolean isActive() {
			return isSlotVisible(slotId);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return true;
		}

		@Override
		public int getMaxStackSize() {
			return 1;
		}

		@Override
		public boolean mayPickup(Player player) {
			// Clearing a filter goes through clicked()/quickMoveStack, which copies rather than moves.
			return false;
		}

		@Override
		public void onQuickCraft(ItemStack stack, int amount) {
		}
	}

	/**
	 * Channel clicks follow Create's own ghost-slot rules (GhostItemMenu#clicked): the item held is COPIED
	 * into the filter slot instead of moved, throwing is ignored, and creative middle-click clones the filter
	 * out to the cursor. Real card slots keep vanilla behaviour.
	 */
	@Override
	public void clicked(int slotId, int dragType, ClickType clickType, Player player) {
		// A slot that belongs to another page is not drawn, so it takes no input either: the cursor would
		// otherwise drop onto coordinates that the open page uses for something else. slotId -999 (a click
		// outside the panel) is not a slot at all and stays with vanilla's handling of it.
		if (slotId >= 0 && !isSlotVisible(slotId))
			return;
		if (slotId < CHANNEL_SLOT_START) {
			super.clicked(slotId, dragType, clickType, player);
			return;
		}
		if (clickType == ClickType.THROW || clickType == ClickType.SWAP)
			return;

		int channelIndex = slotId - CHANNEL_SLOT_START;
		if (clickType == ClickType.CLONE) {
			if (player.getAbilities().instabuild && getCarried().isEmpty()) {
				ItemStack cloned = ghostHandler.getStackInSlot(channelIndex).copy();
				cloned.setCount(cloned.getMaxStackSize());
				setCarried(cloned);
			}
			return;
		}

		ItemStack held = getCarried();
		ItemStack filter = held.isEmpty() ? ItemStack.EMPTY : held.copy();
		if (!filter.isEmpty())
			filter.setCount(1);
		ghostHandler.setStackInSlot(channelIndex, filter);
		getSlot(slotId).setChanged();
		pushChannels();
	}

	/**
	 * Shift-click semantics, restricted to the page that is open: a player card moves into an empty card slot on
	 * the links page, any item copies into the first free filter on the channels page, a card moves back to the
	 * player inventory on the links page, and shift-clicking a filter clears it. On the pages that own no slot
	 * range nothing moves, so a hidden range can never be edited by accident.
	 */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		if (index >= CHANNEL_SLOT_START) {
			if (!isSlotVisible(index))
				return ItemStack.EMPTY;
			ghostHandler.setStackInSlot(index - CHANNEL_SLOT_START, ItemStack.EMPTY);
			getSlot(index).setChanged();
			pushChannels();
			return ItemStack.EMPTY;
		}

		Slot slot = this.slots.get(index);
		if (!slot.hasItem())
			return ItemStack.EMPTY;

		ItemStack slotStack = slot.getItem();
		ItemStack copy = slotStack.copy();

		if (index >= CARD_SLOT_START) {
			if (activeTab != TAB_LINKS)
				return ItemStack.EMPTY;
			// Out of a card slot into the player inventory, hotbar first (fromLast walks the range backwards).
			if (!moveItemStackTo(slotStack, 0, CARD_SLOT_START, true))
				return ItemStack.EMPTY;
		} else if (activeTab == TAB_LINKS) {
			/*
			 * The links page moves cards only. Copying an unrelated item into a filter the gunner cannot see
			 * would edit the console silently, and a card that finds no free row must stay in the inventory
			 * rather than end up as a filter.
			 */
			if (!slotStack.is(CBCItems.CALCULATOR_LINK.asItem())
					|| !moveItemStackTo(slotStack, CARD_SLOT_START, CHANNEL_SLOT_START, false))
				return ItemStack.EMPTY;
		} else if (activeTab == TAB_CHANNELS) {
			// Configuring a channel is a copy, never a move; a link card is a legitimate filter item too.
			boolean filled = false;
			for (int i = 0; i < ghostHandler.getSlots(); i++) {
				if (!ghostHandler.getStackInSlot(i).isEmpty())
					continue;
				ItemStack filter = copy.copy();
				filter.setCount(1);
				ghostHandler.setStackInSlot(i, filter);
				getSlot(CHANNEL_SLOT_START + i).setChanged();
				pushChannels();
				filled = true;
				break;
			}
			if (!filled)
				return ItemStack.EMPTY;
		} else {
			return ItemStack.EMPTY;
		}

		if (slotStack.isEmpty())
			slot.set(ItemStack.EMPTY);
		else
			slot.setChanged();
		return copy;
	}

	@Override
	public boolean stillValid(Player player) {
		if (!(player.level().getBlockEntity(pos) instanceof TrajectoryCalculatorBlockEntity be))
			return false;
		return be.canPlayerUse(player);
	}

	public BlockPos getCalculatorPos() {
		return pos;
	}

	public IItemHandler getHandler() {
		return handler;
	}

	public ItemStackHandler getGhostHandler() {
		return ghostHandler;
	}

	public int getActiveTab() {
		return activeTab;
	}

	/** Both sides: the client reports the page it shows, the server stores it so its gating matches the screen. */
	public void setActiveTab(int tab) {
		this.activeTab = (tab >= 0 && tab < TAB_COUNT) ? tab : TAB_LINKS;
	}

	/**
	 * Which slot ranges the open page owns. Card slots belong to the links page and filter slots to the channels
	 * page; the player inventory is always live, because it is drawn as chrome below the panel and belongs to no
	 * page. One answer drives three vanilla mechanisms at once: {@code Slot#isActive} for rendering and hover,
	 * {@link #clicked} for direct input, and {@link #quickMoveStack} for shift-click destinations.
	 */
	public boolean isSlotVisible(int slotId) {
		if (slotId >= CHANNEL_SLOT_START)
			return activeTab == TAB_CHANNELS;
		if (slotId >= CARD_SLOT_START)
			return activeTab == TAB_LINKS;
		return true;
	}

	/**
	 * Pull filters the gunner never touched. A mount console publishes its own frequency column set whenever it
	 * is edited, and the calculator's copy of those filters only follows while this menu is rebuilt - which a
	 * long-lived open console never does. The block entity re-reads the mounts every few seconds, so polling it
	 * here is what makes an open console follow a change made at the gun instead of at the console.
	 *
	 * <p>The pull is deliberately cheap: the block entity's array is compared against the menu's copy and
	 * nothing is written unless a filter actually differs, so an untouched console produces no sync traffic.
	 */
	@Override
	public void broadcastChanges() {
		super.broadcastChanges();
		if (playerInventory.player.level().isClientSide())
			return;
		if (!(playerInventory.player.level().getBlockEntity(pos) instanceof TrajectoryCalculatorBlockEntity be))
			return;
		ItemStack[] fromBE = be.getSlotChannelItems();
		for (int i = 0; i < ghostHandler.getSlots() && i < fromBE.length; i++) {
			ItemStack local = ghostHandler.getStackInSlot(i);
			ItemStack remote = fromBE[i];
			if (local.isEmpty() == remote.isEmpty() && ItemStack.isSameItemSameComponents(local, remote))
				continue;
			ghostHandler.setStackInSlot(i, remote.copy());
			getSlot(CHANNEL_SLOT_START + i).setChanged();
		}
	}

	/** Server side only: hand the menu-local filter copies to the block entity, which re-registers and syncs. */
	private void pushChannels() {
		Level level = playerInventory.player.level();
		if (level.isClientSide())
			return;
		if (!(level.getBlockEntity(pos) instanceof TrajectoryCalculatorBlockEntity be))
			return;
		ItemStack[] snapshot = new ItemStack[ghostHandler.getSlots()];
		for (int i = 0; i < snapshot.length; i++)
			snapshot[i] = ghostHandler.getStackInSlot(i);
		be.setSlotChannelItems(snapshot);
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack stack, Slot slotIn) {
		// Double-click pick-out / pick-into must never touch the channel filters.
		return slotIn.container == playerInventory;
	}

	@Override
	public boolean canDragTo(Slot slotIn) {
		// Vanilla drag-distribute MOVES items; channel filters must only ever be copies, so dragging is limited
		// to the player inventory and the real card slots.
		return slotIn.container == playerInventory || slotIn.index < CHANNEL_SLOT_START;
	}
}
