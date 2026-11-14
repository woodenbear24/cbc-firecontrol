package com.cbc.firecontrol.client.calculator;

import java.util.ArrayList;
import java.util.List;

import com.cbc.firecontrol.ballistics.TrajectoryPrediction;
import com.cbc.firecontrol.block.entity.TrajectoryCalculatorBlockEntity;
import com.cbc.firecontrol.client.TrajectoryCalculatorScreen;
import com.cbc.firecontrol.client.gui.ConsoleTheme;
import com.cbc.firecontrol.menu.TrajectoryCalculatorMenu;
import com.cbc.firecontrol.network.CalculatorChannelSyncPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The channels page: the ghost-slot grid that turns each row into a Create redstone-link frequency set.
 *
 * <p>The column meanings mirror the mount radio's columns one to one, which is what makes a calculator column
 * drive one mount action; the filter items themselves are the menu's ghost slots, and the pair in a column is the
 * frequency pair the linked mount must carry in its own radio channel to receive this console's signal.
 *
 * <p>An armed row gets the lighter backdrop, so at a glance a row that can transmit is distinguishable from a row
 * that is only configured but idle.
 *
 * <p>SYNC pulls the mounts' columns into the grid on request. The console already mirrors them on its own every
 * second, so the button is not new behaviour - it is the same copy made visible and immediate, which is what a
 * gunner who has just retuned a radio wants.
 */
public class CalculatorChannelTab extends CalculatorTab {

	private Button syncButton;

	public CalculatorChannelTab() {
		super(TrajectoryCalculatorMenu.TAB_CHANNELS, "cbc_firecontrol.calculator.tab.channels");
	}

	@Override
	protected List<? extends GuiEventListener> controls(TrajectoryCalculatorScreen screen) {
		List<GuiEventListener> widgets = new ArrayList<>();
		syncButton = Button.builder(Component.translatable("cbc_firecontrol.calculator.channels.sync"),
				button -> PacketDistributor.sendToServer(
						new CalculatorChannelSyncPayload(menu(screen).getCalculatorPos())))
				.bounds(screen.panelLeft() + TrajectoryCalculatorMenu.CHANNEL_SYNC_X,
						screen.panelTop() + TrajectoryCalculatorMenu.CHANNEL_SYNC_Y,
						TrajectoryCalculatorMenu.CHANNEL_SYNC_W, TrajectoryCalculatorMenu.CHANNEL_SYNC_H)
				.tooltip(Tooltip.create(Component.translatable("cbc_firecontrol.calculator.channels.sync_hint")))
				.build();
		syncButton.active = hasLinkedRow(screen);
		widgets.add(syncButton);
		return widgets;
	}

	/**
	 * There is nothing to pull until a row holds a card that records a mount, so the control says so instead of
	 * accepting a click that would copy nothing. Follows the cards live: inserting one at the console, or pulling
	 * one out, changes the answer within a tick.
	 */
	@Override
	protected void ticked(TrajectoryCalculatorScreen screen) {
		if (syncButton != null)
			syncButton.active = hasLinkedRow(screen);
	}

	private static boolean hasLinkedRow(TrajectoryCalculatorScreen screen) {
		TrajectoryCalculatorBlockEntity be = calculator(screen);
		if (be == null)
			return false;
		for (int slot = 0; slot < TrajectoryCalculatorMenu.SLOTS; slot++)
			if (be.isSlotActive(slot))
				return true;
		return false;
	}

	@Override
	protected void background(TrajectoryCalculatorScreen screen, GuiGraphics graphics) {
		int x = screen.panelLeft();
		int w = TrajectoryCalculatorMenu.PANEL_WIDTH;
		TrajectoryCalculatorBlockEntity be = calculator(screen);
		for (int slot = 0; slot < TrajectoryCalculatorMenu.SLOTS; slot++) {
			int rowTop = rowTop(screen, slot);
			graphics.fill(x + 8, rowTop - TrajectoryCalculatorMenu.ROW_BAND_INSET, x + w - 8,
					rowTop + TrajectoryCalculatorMenu.ROW_BAND_H, ConsoleTheme.ROW_BG);
			boolean active = be != null && be.isSlotActive(slot);
			for (int channel = 0; channel < TrajectoryCalculatorMenu.CHANNELS; channel++) {
				int baseX = x + TrajectoryCalculatorMenu.CHANNEL_SLOT_X
						+ channel * TrajectoryCalculatorMenu.CHANNEL_COLUMN_SPACING;
				int baseY = rowTop + TrajectoryCalculatorMenu.CHANNEL_Y_OFFSET;
				graphics.fill(baseX - 1, baseY - 1, baseX + 2 * 18 + 1, baseY + 18 + 1,
						active ? ConsoleTheme.CHANNEL_BG_ACTIVE : ConsoleTheme.CHANNEL_BG_IDLE);
			}
		}
	}

	@Override
	protected void labels(TrajectoryCalculatorScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
		int headerY = TrajectoryCalculatorMenu.CONTENT_TOP + 1;
		graphics.drawString(screen.consoleFont(), Component.translatable("cbc_firecontrol.calculator.channels"),
				TrajectoryCalculatorMenu.CHANNEL_SLOT_X, headerY, ConsoleTheme.HEADER_TEXT, false);
		int columnY = headerY + 11;
		for (int channel = 0; channel < TrajectoryCalculatorMenu.CHANNELS; channel++)
			graphics.drawString(screen.consoleFont(),
					Component.translatable("cbc_firecontrol.calculator.channel." + channel),
					TrajectoryCalculatorMenu.CHANNEL_SLOT_X + channel * TrajectoryCalculatorMenu.CHANNEL_COLUMN_SPACING,
					columnY, ConsoleTheme.CHANNEL_LABEL, false);

		TrajectoryCalculatorBlockEntity be = calculator(screen);
		for (int slot = 0; slot < TrajectoryCalculatorMenu.SLOTS; slot++) {
			int rowTop = rowTop(screen, slot) - screen.panelTop();
			TrajectoryPrediction prediction = be != null ? be.getPrediction(slot) : TrajectoryPrediction.EMPTY;
			graphics.drawString(screen.consoleFont(), fit(screen.consoleFont(),
					CalculatorTooltips.firstLine(slot, prediction.status()),
					(TrajectoryCalculatorMenu.CHANNEL_SLOT_X - 16) - textLeft()),
					textLeft(), rowTop + 1,
					CalculatorTooltips.colorFor(prediction.status()), false);
			graphics.drawString(screen.consoleFont(),
					Component.translatable("cbc_firecontrol.calculator.channel.filled", filtersSet(screen, slot),
							TrajectoryCalculatorMenu.CHANNELS * TrajectoryCalculatorBlockEntity.FILTERS_PER_CHANNEL),
					textLeft(), rowTop + 10, ConsoleTheme.TEXT_SECONDARY, false);
		}
	}

	@Override
	protected void foreground(TrajectoryCalculatorScreen screen, GuiGraphics graphics, int mouseX, int mouseY,
			float partialTicks) {
		int[] hovered = hoveredChannel(screen, mouseX, mouseY);
		if (hovered != null)
			graphics.renderComponentTooltip(screen.consoleFont(),
					CalculatorTooltips.channelLines(screen, hovered[0], hovered[1]), mouseX, mouseY);
	}

	/** How many filter slots of a row are configured, for the line under the row's status. */
	private static int filtersSet(TrajectoryCalculatorScreen screen, int slot) {
		int filled = 0;
		for (int channel = 0; channel < TrajectoryCalculatorMenu.CHANNELS; channel++)
			for (int filter = 0; filter < TrajectoryCalculatorBlockEntity.FILTERS_PER_CHANNEL; filter++)
				if (!screen.consoleMenu().getGhostHandler()
						.getStackInSlot(TrajectoryCalculatorBlockEntity.channelIndex(slot, channel, filter))
						.isEmpty())
					filled++;
		return filled;
	}

	/** Which (slot, channel) filter pair the mouse sits over, or null when it is over neither. */
	private int[] hoveredChannel(TrajectoryCalculatorScreen screen, int mouseX, int mouseY) {
		for (int slot = 0; slot < TrajectoryCalculatorMenu.SLOTS; slot++) {
			int top = rowTop(screen, slot) + TrajectoryCalculatorMenu.CHANNEL_Y_OFFSET - 1;
			for (int channel = 0; channel < TrajectoryCalculatorMenu.CHANNELS; channel++) {
				int left = screen.panelLeft() + TrajectoryCalculatorMenu.CHANNEL_SLOT_X
						+ channel * TrajectoryCalculatorMenu.CHANNEL_COLUMN_SPACING - 1;
				if (isInside(mouseX, mouseY, left, top, 2 * 18 + 2, 18 + 2))
					return new int[] { slot, channel };
			}
		}
		return null;
	}
}
