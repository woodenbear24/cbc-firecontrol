package com.cbc.firecontrol.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.joml.Vector2i;

/**
 * Create-style container screen whose content is split into pages, one visible at a time.
 *
 * <p>The strip is {@link TabBar}, added as a renderable so vanilla paints it after the panel texture and before
 * the slots; each page contributes widgets, a clipped background, panel-local labels and screen-space tooltips
 * through {@link GuiTab}.
 *
 * <p>What a page hides is enforced in the menu, not here - the screen only stops drawing. A menu that kept every
 * slot live would still accept a click landing on the coordinates of an invisible slot, and would still hand a
 * hidden slot to a shift-click. Screens are advisory; the menu is the authority. Vanilla already agrees with the
 * menu on drawing: {@code AbstractContainerScreen} renders and hovers a slot only while {@code Slot#isActive}.
 */
public abstract class TabbedSimiContainerScreen<T extends AbstractContainerMenu> extends AbstractSimiContainerScreen<T> {

	/** Where the strip sits and how wide one cell is, in absolute window space. */
	public record Strip(int x, int y, int height, int tabWidth, int tabGap) {
	}

	private final List<GuiTab> tabs;
	private final List<GuiEventListener> tabWidgets = new ArrayList<>();
	private int activeTab;

	protected TabbedSimiContainerScreen(T menu, Inventory playerInventory, Component title, List<GuiTab> tabs) {
		super(menu, playerInventory, title);
		this.tabs = tabs;
		this.activeTab = tabs.isEmpty() ? 0 : tabs.get(0).id();
	}

	/** Geometry of the strip; its total width follows from the page count, so only the cell size is given. */
	protected abstract Strip tabBarStrip();

	/** Area a page owns, in absolute window space. Page backgrounds are clipped to it, tooltips are not. */
	protected abstract Vector2i contentAreaOrigin();

	protected abstract Vector2i contentAreaSize();

	/** Font for page text. {@code Screen#font} is protected, and pages live outside this hierarchy. */
	public Font consoleFont() {
		return this.font;
	}

	/** The menu every page reads from. {@code AbstractContainerScreen#menu} is protected for the same reason. */
	public T consoleMenu() {
		return this.menu;
	}

	/** Panel origin: subtract these from a window-space point to get {@code renderLabels} space. */
	public int panelLeft() {
		return this.leftPos;
	}

	public int panelTop() {
		return this.topPos;
	}

	public List<GuiTab> tabs() {
		return this.tabs;
	}

	public int activeTab() {
		return this.activeTab;
	}

	public GuiTab currentTab() {
		int index = indexOf(this.activeTab);
		return index < 0 ? null : this.tabs.get(index);
	}

	private int indexOf(int tabId) {
		for (int i = 0; i < this.tabs.size(); i++)
			if (this.tabs.get(i).id() == tabId)
				return i;
		return -1;
	}

	@Override
	protected void init() {
		super.init();
		Strip strip = tabBarStrip();
		addRenderableWidget(new TabBar(this.font, strip.x(), strip.y(), strip.height(), strip.tabWidth(),
				strip.tabGap(), this.tabs, this::activeTab, this::setActiveTab));
		rebuildTabWidgets();
	}

	/** Switch pages locally. Subclasses report the change to the server through {@link #onTabChanged}. */
	public void setActiveTab(int tabId) {
		if (tabId == this.activeTab || indexOf(tabId) < 0)
			return;
		this.activeTab = tabId;
		rebuildTabWidgets();
		onTabChanged(tabId);
	}

	/** Tell the menu's server side which page is open, so its slot gating matches what is drawn. */
	protected void onTabChanged(int tabId) {
	}

	/**
	 * Swapping pages means swapping controls: a text field left in the widget list would still take keyboard focus
	 * after its page went away, and a button would still be clickable at coordinates the next page uses for
	 * something else.
	 */
	private void rebuildTabWidgets() {
		removeWidgets(this.tabWidgets);
		this.tabWidgets.clear();
		setFocused(null);
		GuiTab tab = currentTab();
		if (tab == null)
			return;
		for (GuiEventListener widget : tab.widgets(this)) {
			/*
			 * The screen registers a control only when it carries all three roles. Every widget class - vanilla's
			 * AbstractWidget and Create's AbstractSimiWidget alike - does, so anything else is a page that tried
			 * to register something that cannot be drawn or narrated.
			 */
			if (widget instanceof AbstractWidget concrete) {
				addRenderableWidget(concrete);
				this.tabWidgets.add(widget);
				continue;
			}
			throw new IllegalStateException(
					"A page control must be a widget: " + widget.getClass().getSimpleName());
		}
	}

	/**
	 * The page background, clipped to the content area. Subclasses draw their panel first and then call
	 * {@code super.renderBg}: that order is what keeps a page's fills from bleeding over the strip.
	 */
	@Override
	protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
		GuiTab tab = currentTab();
		if (tab == null)
			return;
		Vector2i origin = contentAreaOrigin();
		Vector2i size = contentAreaSize();
		graphics.enableScissor(origin.x, origin.y, origin.x + size.x, origin.y + size.y);
		tab.renderBackground(this, graphics);
		graphics.disableScissor();
	}

	@Override
	protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
		// Create's base leaves this empty on purpose; pages draw their own text here, in panel space.
		GuiTab tab = currentTab();
		if (tab != null)
			tab.renderLabels(this, graphics, mouseX, mouseY);
	}

	@Override
	protected void renderForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		super.renderForeground(graphics, mouseX, mouseY, partialTicks);
		GuiTab tab = currentTab();
		if (tab != null)
			tab.renderForeground(this, graphics, mouseX, mouseY, partialTicks);
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		GuiTab tab = currentTab();
		if (tab != null)
			tab.tick(this);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		GuiTab tab = currentTab();
		if (tab != null && tab.keyPressed(this, keyCode, scanCode, modifiers))
			return true;
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
}
