package com.cbc.firecontrol.client.gui;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * The strip of page buttons across the top of a tabbed console.
 *
 * <p>One widget rather than one button per page: the pages share a background band, the selection has to be drawn
 * against its neighbours (the active cell joins the content area below it, inactive cells sit behind it), and the
 * whole strip is a single hit-test with the selected index derived from the x position. It is added as a
 * renderable, so vanilla's pass paints it after {@code renderBg} and before the slots - the layer a strip needs.
 */
public class TabBar extends AbstractWidget {

	private final Font font;
	private final List<GuiTab> tabs;
	private final int tabWidth;
	private final int tabGap;
	private final IntSupplier activeTab;
	private final IntConsumer onSelect;

	public TabBar(Font font, int x, int y, int height, int tabWidth, int tabGap, List<GuiTab> tabs,
			IntSupplier activeTab, IntConsumer onSelect) {
		super(x, y, tabs.size() * tabWidth + (tabs.size() - 1) * tabGap, height, Component.empty());
		this.font = font;
		this.tabs = tabs;
		this.tabWidth = tabWidth;
		this.tabGap = tabGap;
		this.activeTab = activeTab;
		this.onSelect = onSelect;
	}

	/** Index of the page under the given x, or -1 when the point sits in a gutter or outside the strip. */
	private int tabAt(int mouseX) {
		if (mouseX < getX() || mouseX >= getX() + getWidth())
			return -1;
		int step = tabWidth + tabGap;
		int offset = mouseX - getX();
		int index = offset / step;
		if (index < 0 || index >= tabs.size() || offset - index * step >= tabWidth)
			return -1;
		return index;
	}

	/** Window-space left edge of one cell, so a page can align its own header with the tab that opened it. */
	public int tabLeft(int index) {
		return getX() + index * (tabWidth + tabGap);
	}

	@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		int y = getY();
		int h = getHeight();
		graphics.fill(getX(), y, getX() + getWidth(), y + h, ConsoleTheme.STRIP_BG);
		graphics.fill(getX(), y, getX() + getWidth(), y + 1, ConsoleTheme.PANEL_EDGE);
		graphics.fill(getX(), y + h - 1, getX() + getWidth(), y + h, ConsoleTheme.PANEL_EDGE);

		int active = activeTab.getAsInt();
		int hovered = isMouseOver(mouseX, mouseY) ? tabAt(mouseX) : -1;
		for (int i = 0; i < tabs.size(); i++) {
			int left = tabLeft(i);
			boolean selected = tabs.get(i).id() == active;
			graphics.fill(left, y + 1, left + tabWidth, y + h - 1, selected ? ConsoleTheme.TAB_BG_ACTIVE
					: (i == hovered ? ConsoleTheme.TAB_BG_HOVER : ConsoleTheme.TAB_BG));
			if (selected)
				graphics.fill(left, y + 1, left + tabWidth, y + 2, ConsoleTheme.TAB_TEXT_ACTIVE);

			Component title = tabs.get(i).title();
			graphics.drawString(font, truncate(title, tabWidth - 6),
					left + (tabWidth - font.width(title)) / 2, y + (h - font.lineHeight) / 2 + 1,
					selected ? ConsoleTheme.TAB_TEXT_ACTIVE
							: (i == hovered ? ConsoleTheme.TAB_TEXT_HOVER : ConsoleTheme.TAB_TEXT),
					false);
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!active || button != 0 || !isMouseOver(mouseX, mouseY))
			return false;
		int index = tabAt((int) mouseX);
		if (index < 0)
			return false;
		onSelect.accept(tabs.get(index).id());
		return true;
	}

	private Component truncate(Component title, int width) {
		if (font.width(title) <= width)
			return title;
		String full = title.getString();
		int budget = width - font.width(ConsoleTheme.TRUNCATION_MARK);
		int keep = 0;
		while (keep < full.length() && font.width(full.substring(0, keep + 1)) <= budget)
			keep++;
		return Component.literal(full.substring(0, Math.max(1, keep)) + ConsoleTheme.TRUNCATION_MARK);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		int active = activeTab.getAsInt();
		for (GuiTab tab : tabs)
			if (tab.id() == active)
				output.add(NarratedElementType.TITLE, tab.title());
	}
}
