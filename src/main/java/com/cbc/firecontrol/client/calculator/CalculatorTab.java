package com.cbc.firecontrol.client.calculator;

import java.util.List;

import com.cbc.firecontrol.block.entity.TrajectoryCalculatorBlockEntity;
import com.cbc.firecontrol.client.TrajectoryCalculatorScreen;
import com.cbc.firecontrol.client.gui.GuiTab;
import com.cbc.firecontrol.client.gui.TabbedSimiContainerScreen;
import com.cbc.firecontrol.menu.TrajectoryCalculatorMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;

/**
 * Page of the calculator console.
 *
 * <p>The generic framework hands its pages a {@code TabbedSimiContainerScreen<?>}; every page here is built for
 * one concrete console, so the cast happens once, in this adapter, and the pages read the menu, the client block
 * entity and the panel origin through typed methods instead of repeating the cast in each hook.
 */
public abstract class CalculatorTab extends GuiTab {

	protected CalculatorTab(int id, String titleKey) {
		super(id, titleKey);
	}

	private static TrajectoryCalculatorScreen console(TabbedSimiContainerScreen<?> screen) {
		if (screen instanceof TrajectoryCalculatorScreen calculatorScreen)
			return calculatorScreen;
		throw new IllegalStateException("A calculator page was mounted on a screen that is not the calculator console");
	}

	protected static TrajectoryCalculatorMenu menu(TrajectoryCalculatorScreen screen) {
		return screen.consoleMenu();
	}

	/** The client-side calculator block entity, or null when its chunk is gone. */
	protected static TrajectoryCalculatorBlockEntity calculator(TrajectoryCalculatorScreen screen) {
		return screen.clientCalculator();
	}

	/** Absolute window-space y of a row's slot line, which every row of the panel shares. */
	protected static int rowTop(TrajectoryCalculatorScreen screen, int slot) {
		return screen.panelTop() + TrajectoryCalculatorMenu.LINK_SLOT_Y
				+ slot * TrajectoryCalculatorMenu.LINK_SLOT_SPACING;
	}

	/** Panel-local x of a row's text column, right of its card slot. */
	protected static int textLeft() {
		return TrajectoryCalculatorMenu.LINK_SLOT_X + 34;
	}

	@Override
	public final List<? extends GuiEventListener> widgets(TabbedSimiContainerScreen<?> screen) {
		return controls(console(screen));
	}

	@Override
	public final void renderBackground(TabbedSimiContainerScreen<?> screen, GuiGraphics graphics) {
		background(console(screen), graphics);
	}

	@Override
	public final void renderLabels(TabbedSimiContainerScreen<?> screen, GuiGraphics graphics, int mouseX, int mouseY) {
		labels(console(screen), graphics, mouseX, mouseY);
	}

	@Override
	public final void renderForeground(TabbedSimiContainerScreen<?> screen, GuiGraphics graphics, int mouseX,
			int mouseY, float partialTicks) {
		foreground(console(screen), graphics, mouseX, mouseY, partialTicks);
	}

	@Override
	public final void tick(TabbedSimiContainerScreen<?> screen) {
		ticked(console(screen));
	}

	@Override
	public final boolean keyPressed(TabbedSimiContainerScreen<?> screen, int keyCode, int scanCode, int modifiers) {
		return keyTyped(console(screen), keyCode, scanCode, modifiers);
	}

	protected List<? extends GuiEventListener> controls(TrajectoryCalculatorScreen screen) {
		return List.of();
	}

	protected void background(TrajectoryCalculatorScreen screen, GuiGraphics graphics) {
	}

	protected void labels(TrajectoryCalculatorScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
	}

	protected void foreground(TrajectoryCalculatorScreen screen, GuiGraphics graphics, int mouseX, int mouseY,
			float partialTicks) {
	}

	protected void ticked(TrajectoryCalculatorScreen screen) {
	}

	protected boolean keyTyped(TrajectoryCalculatorScreen screen, int keyCode, int scanCode, int modifiers) {
		return false;
	}
}
