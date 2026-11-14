package com.cbc.firecontrol.client;

import java.util.List;

import com.cbc.firecontrol.block.entity.TrajectoryCalculatorBlockEntity;
import com.cbc.firecontrol.client.calculator.CalculatorChannelTab;
import com.cbc.firecontrol.client.calculator.CalculatorLinkTab;
import com.cbc.firecontrol.client.calculator.CalculatorStatusTab;
import com.cbc.firecontrol.client.calculator.CalculatorTargetTab;
import com.cbc.firecontrol.client.calculator.CalculatorTaskTab;
import com.cbc.firecontrol.client.gui.ConsoleTheme;
import com.cbc.firecontrol.client.gui.GuiTab;
import com.cbc.firecontrol.client.gui.TabbedSimiContainerScreen;
import com.cbc.firecontrol.menu.TrajectoryCalculatorMenu;
import com.cbc.firecontrol.network.CalculatorTabPayload;

import org.joml.Vector2i;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Create-style console of the Trajectory Calculator, split into pages (see {@link TabbedSimiContainerScreen}):
 * the link cards and their readouts, the target entry with the mission wait window under it, the channel grid, a
 * status page that quotes the same numbers without the grid crowding them, and the fire-mission board.
 *
 * <p>Drawn procedurally with {@link GuiGraphics#fill} instead of a bespoke panel texture: the readout column is
 * wide (mount coordinates, status, impact point, speed, flight time) and text-driven, so a flat dark panel with
 * row strips reads better than art and adds no new asset.
 *
 * <p>Numbers are read from the CLIENT-side calculator block entity, whose solutions the server pushes through the
 * block-entity data packet ({@code TrajectoryCalculatorBlockEntity#write(..., clientPacket=true)}) - no menu
 * payload round-trip, so the readouts remain correct for any player viewing the GUI. The open page travels the
 * other way as one byte ({@link CalculatorTabPayload}), because the menu has to refuse input for slots this
 * screen is not showing.
 *
 * <p>What is left here is the panel chrome and the geometry every page agrees on. Every control, fill, label and
 * tooltip that used to sit in this class now belongs to the page that draws it.
 */
public class TrajectoryCalculatorScreen extends TabbedSimiContainerScreen<TrajectoryCalculatorMenu> {

	public TrajectoryCalculatorScreen(TrajectoryCalculatorMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, List.<GuiTab>of(new CalculatorLinkTab(), new CalculatorTargetTab(),
				new CalculatorChannelTab(), new CalculatorStatusTab(), new CalculatorTaskTab()));
	}

	@Override
	protected void init() {
		// Must be set before super.init(), which is what computes the panel position from them.
		setWindowSize(TrajectoryCalculatorMenu.PANEL_WIDTH,
				TrajectoryCalculatorMenu.PLAYER_INV_Y + TrajectoryCalculatorMenu.PLAYER_INV_H);
		setWindowOffset(0, 0);
		super.init();
		/*
		 * A menu is rebuilt when the server re-opens it (a dimension change, for example) and the fresh instance
		 * defaults to the links page, while this screen keeps the page it was showing. Announce the page on every
		 * init so both menus match the drawing even then: one byte, only when the screen is (re)built.
		 */
		setActiveTabOnBothSides();
	}

	@Override
	protected Strip tabBarStrip() {
		return new Strip(panelLeft() + TrajectoryCalculatorMenu.TAB_BAR_X, panelTop() + TrajectoryCalculatorMenu.TAB_BAR_Y,
				TrajectoryCalculatorMenu.TAB_BAR_HEIGHT, TrajectoryCalculatorMenu.TAB_WIDTH,
				TrajectoryCalculatorMenu.TAB_GAP);
	}

	@Override
	protected Vector2i contentAreaOrigin() {
		return new Vector2i(panelLeft() + 8, panelTop() + TrajectoryCalculatorMenu.CONTENT_TOP);
	}

	@Override
	protected Vector2i contentAreaSize() {
		return new Vector2i(TrajectoryCalculatorMenu.PANEL_WIDTH - 16,
				TrajectoryCalculatorMenu.PANEL_HEIGHT - TrajectoryCalculatorMenu.CONTENT_TOP);
	}

	/**
	 * Tell the menu which page is open - both instances of it. The screen's own menu object is the client's copy,
	 * and vanilla asks <em>that</em> one whether a slot is live when it decides what to draw: {@code
	 * AbstractContainerScreen#renderSlots} skips a slot whose {@code Slot#isActive} is false, so a client menu left
	 * on the default page would frame the card slots on every page and would never paint the filter items on the
	 * channels page. The packet then carries the same answer to the server's copy, which is what refuses the click.
	 */
	@Override
	protected void onTabChanged(int tabId) {
		setActiveTabOnBothSides();
	}

	private void setActiveTabOnBothSides() {
		consoleMenu().setActiveTab(activeTab());
		PacketDistributor.sendToServer(new CalculatorTabPayload(consoleMenu().getCalculatorPos(), activeTab()));
	}

	@Override
	protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
		int x = panelLeft();
		int y = panelTop();
		int w = TrajectoryCalculatorMenu.PANEL_WIDTH;
		int h = TrajectoryCalculatorMenu.PANEL_HEIGHT;

		graphics.fill(x, y, x + w, y + h, ConsoleTheme.PANEL_BG);
		graphics.fill(x, y, x + w, y + 1, ConsoleTheme.PANEL_EDGE);
		graphics.fill(x, y + h - 1, x + w, y + h, ConsoleTheme.PANEL_EDGE);
		graphics.fill(x, y, x + 1, y + h, ConsoleTheme.PANEL_EDGE);
		graphics.fill(x + w - 1, y, x + w, y + h, ConsoleTheme.PANEL_EDGE);

		/*
		 * The player inventory is chrome rather than page content: it is outside the clip, it belongs to no page,
		 * and it stays live while every page is open - which is what lets a card be dragged in from the hotbar on
		 * the links page and a filter item copied in on the channels page.
		 */
		int invY = y + TrajectoryCalculatorMenu.PLAYER_INV_Y;
		graphics.fill(x + TrajectoryCalculatorMenu.PLAYER_INV_X, invY,
				x + TrajectoryCalculatorMenu.PLAYER_INV_X + 178,
				invY + TrajectoryCalculatorMenu.PLAYER_INV_H, ConsoleTheme.INVENTORY_BG);

		// The page draws its own fills inside the clip; this call is what applies the clip.
		super.renderBg(graphics, partialTicks, mouseX, mouseY);
	}

	@Override
	protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
		graphics.drawString(this.font,
				Component.translatable("container.cbc_firecontrol.trajectory_calculator.inventory"),
				TrajectoryCalculatorMenu.PLAYER_INV_X + 8, TrajectoryCalculatorMenu.PLAYER_INV_Y + 3,
				ConsoleTheme.HEADER_TEXT, false);
		super.renderLabels(graphics, mouseX, mouseY);
	}

	/**
	 * The client-side block entity, or null when its chunk is gone. Pages read live values through this rather
	 * than through the menu: solutions are pushed by the block entity to every viewer, not only to whoever has
	 * the menu open.
	 */
	public TrajectoryCalculatorBlockEntity clientCalculator() {
		if (Minecraft.getInstance().level == null)
			return null;
		if (Minecraft.getInstance().level.getBlockEntity(consoleMenu().getCalculatorPos())
				instanceof TrajectoryCalculatorBlockEntity be)
			return be;
		return null;
	}
}
